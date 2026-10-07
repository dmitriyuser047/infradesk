package ru.bitec.app.ops
package integration.ssh

import application.port.{RemoteConfigurationTransport,ServerProfileRemoteObservation}
import cats.effect.IO
import cats.syntax.all._
import domain.connection.Connection
import domain.provisioning.{ServerProfileContent,ServerProfileDiff,PackageProbeFinding}
import io.circe.Json
import java.util.UUID
import scala.concurrent.duration._

/** Only selected desired-state facts leave this collector. GET endpoints never call it. */
private[ssh] final class SshProfileObserver(transport: RemoteConfigurationTransport[IO]) {
  import SshProfileObserver._
  def observe(connection: Connection,resourceId: UUID,desired: Option[ServerProfileContent]): IO[ServerProfileRemoteObservation] =
    transport.withSessionBounded(connection,65536) { session =>
      for {
        identity <- new ProfileCommands(session,root=true).capture("id",List("-u"),5.seconds,privileged=false)
        _ <- IO.raiseUnless(identity.exitCode==0 && identity.stdout.trim.matches("[0-9]{1,8}"))(
          ProfileRemoteFailure("PROVISIONING_SSH_REJECTED"))
        commands=new ProfileCommands(session,root=identity.stdout.trim=="0")
        privilege <- commands.capture("true",Nil,5.seconds)
        _ <- IO.raiseUnless(privilege.exitCode==0)(ProfileRemoteFailure("PROVISIONING_PERMISSION_DENIED"))
        ssh <- commands.shell("printf '%s' \"$SSH_CONNECTION\"",privileged=false)
        endpoint <- parseEndpoint(ssh.stdout).liftTo[IO](ProfileRemoteFailure("PROVISIONING_SSH_SOURCE_UNAVAILABLE"))
        packageProbe <- collectPackages(commands,desired)
        packages=packageProbe._1
        packageFindings=packageProbe._2
        networkFile <- file(commands,NetworkPath,NetworkMarker,desired.exists(_.network.enabled),content=true)
        effectiveNetwork=desired.filter(_.network.enabled).map(d => ServerProfileDiff.effectiveSysctl(d.network)).getOrElse(Map.empty)
        liveNetwork <- effectiveNetwork.keys.toList.sorted.traverse { key =>
          commands.capture("sysctl",List("-n",key)).flatMap { r =>
            if(r.exitCode!=0) IO.raiseError[(String,Json)](ProfileRemoteFailure("PROVISIONING_NETWORK_PROBE_FAILED"))
            else normalizeValue(r.stdout).map(v => key -> Json.fromString(v)).liftTo[IO](ProfileRemoteFailure("PROVISIONING_NETWORK_PROBE_FAILED"))
          }
        }
        limitsFile <- file(commands,LimitsPath,LimitsMarker,desired.exists(_.limits.enabled),content=true)
        managerFile <- file(commands,ManagerPath,ManagerMarker,desired.exists(_.limits.enabled),content=false)
        managerLimit <- if(desired.exists(_.limits.enabled)) commands.shell(
          "set -eu; hard=$(systemctl show --property=DefaultLimitNOFILE --value); soft=$(systemctl show --property=DefaultLimitNOFILESoft --value); printf '%s:%s' \"$soft\" \"$hard\"")
          .flatMap(r => normalizeLimit(r.stdout).filter(_ => r.exitCode==0).liftTo[IO](ProfileRemoteFailure("PROVISIONING_SYSTEMD_PROBE_FAILED")))
          .map(Some(_)) else IO.pure(None)
        failFile <- file(commands,FailPath,FailMarker,desired.exists(_.fail2ban.enabled),content=false)
        failService <- service(commands,"fail2ban",desired.exists(_.fail2ban.enabled),packages.contains("fail2ban"))
        dockerBinary <- binary(commands,"docker",desired.exists(_.docker.enabled))
        dockerService <- service(commands,"docker",desired.exists(_.docker.enabled),dockerBinary)
        compose <- if(desired.exists(_.docker.enabled) && dockerBinary) commands.shell(
          "if docker compose version --short >/dev/null 2>&1; then echo YES; elif command -v docker-compose >/dev/null 2>&1 && docker-compose version --short >/dev/null 2>&1; then echo YES; else echo NO; fi")
          .map(r => r.exitCode==0 && r.stdout.trim=="YES") else IO.pure(false)
        caddyBinary <- binary(commands,"caddy",desired.exists(_.caddy.enabled))
        caddyService <- service(commands,"caddy",desired.exists(_.caddy.enabled),caddyBinary)
        caddyFile <- file(commands,CaddyPath,CaddyMarker,desired.exists(_.caddy.enabled),content=false)
        caddyPort <- if(desired.exists(_.caddy.enabled) && caddyFile.managed) commands.shell(
          "awk '$1==\"https_port\" && $2 ~ /^[0-9]+$/ {print $2}' /etc/caddy/Caddyfile")
          .map(r => Option.when(r.exitCode==0)(r.stdout.trim).flatMap(_.toIntOption).filter(p => p>=1 && p<=65535)) else IO.pure(None)
        listeners <- collectListeners(commands,desired,caddyService._2)
        siteRoot=desired.map(_.site.root).getOrElse("/var/www/infradesk/default")
        siteFile <- file(commands,s"$siteRoot/index.html",SiteMarker,desired.exists(_.site.enabled),content=false)
        firewall <- collectFirewall(commands,resourceId,desired,packages.contains("ufw"),endpoint)
        dnsWarnings <- desired.filter(_.caddy.enabled).flatMap(_.caddy.domain).traverse { domain =>
          commands.capture("getent",List("ahosts",domain),8.seconds).map { result =>
            val addresses=result.stdout.linesIterator.flatMap(_.trim.split("\\s+").headOption)
              .flatMap(a => ServerProfileContent.canonicalFirewallSource(a).toOption).toSet
            val server=ServerProfileContent.canonicalFirewallSource(endpoint._2).toOption
            if(result.exitCode!=0 || addresses.isEmpty) List("CADDY_DNS_UNRESOLVED")
            else if(!server.exists(addresses)) List("CADDY_DNS_TARGET_MISMATCH") else Nil
          }.handleErrorWith {
            case e: ProfileRemoteFailure if e.truncated => IO.raiseError(e)
            case _ => IO.pure(List("CADDY_DNS_UNRESOLVED"))
          }
        }.map(_.getOrElse(Nil))
        networkValues=networkFile.content.linesIterator.flatMap { line => line.trim.split("\\s*=\\s*",2) match {
          case Array(k,v) if effectiveNetwork.contains(k) => normalizeValue(v).map(k -> Json.fromString(_))
          case _ => None
        }}.toMap
        facts=Json.obj(
          "packages" -> Json.obj("installed" -> Json.fromValues(packages.sorted.map(Json.fromString))).deepMerge(
            if(packageFindings.isEmpty) Json.obj() else Json.obj("findings" -> Json.fromValues(packageFindings.map(_.json)))),
          "network" -> Json.obj("sysctl" -> Json.fromFields(liveNetwork),"managedSysctl" -> Json.fromFields(networkValues),
            "managedFile" -> Json.fromBoolean(networkFile.managed),"managedFileHash" -> hashJson(networkFile),
            "bbr" -> Json.fromBoolean(liveNetwork.toMap.get("net.ipv4.tcp_congestion_control").contains(Json.fromString("bbr"))),
            "managedBbr" -> Json.fromBoolean(networkValues.get("net.ipv4.tcp_congestion_control").contains(Json.fromString("bbr")))),
          "limits" -> Json.obj("managedNofileSoft" -> limitJson(limitsFile,"soft"),"managedNofileHard" -> limitJson(limitsFile,"hard"),
            "systemdDefaultLimitNofile" -> managerLimit.fold(Json.Null)(Json.fromString),
            "managedFile" -> Json.fromBoolean(limitsFile.managed),"managedFileHash" -> hashJson(limitsFile),
            "managedSystemdDropin" -> Json.fromBoolean(managerFile.managed),"managedSystemdDropinHash" -> hashJson(managerFile)),
          "firewall" -> firewall._1,
          "fail2ban" -> Json.obj("installed" -> Json.fromBoolean(packages.contains("fail2ban")),
            "enabled" -> Json.fromBoolean(failService._1),"active" -> Json.fromBoolean(failService._2),
            "managedFile" -> Json.fromBoolean(failFile.managed),"managedFileHash" -> hashJson(failFile)),
          "docker" -> Json.obj("installed" -> Json.fromBoolean(dockerBinary),"enabled" -> Json.fromBoolean(dockerService._1),
            "active" -> Json.fromBoolean(dockerService._2),"composeAvailable" -> Json.fromBoolean(compose)),
          "site" -> Json.obj("managedContentHash" -> hashJson(siteFile),"rootSafe" -> Json.fromBoolean(siteFile.state!="UNSAFE"),"managed" -> Json.fromBoolean(siteFile.managed)),
          "caddy" -> Json.obj("installed" -> Json.fromBoolean(caddyBinary),"enabled" -> Json.fromBoolean(caddyService._1),
            "active" -> Json.fromBoolean(caddyService._2),"managedConfigHash" -> hashJson(caddyFile),
            "managed" -> Json.fromBoolean(caddyFile.managed),"httpsPort" -> caddyPort.fold(Json.Null)(Json.fromInt),
            "listeningPorts" -> Json.fromValues(listeners._1.map(Json.fromInt)))
        )
        blockers=List("NETWORK_CONFIG_UNMANAGED" -> networkFile,"LIMITS_CONFIG_UNMANAGED" -> limitsFile,
          "LIMITS_CONFIG_UNMANAGED" -> managerFile,"FAIL2BAN_CONFIG_UNMANAGED" -> failFile,
          "CADDY_CONFIG_UNMANAGED" -> caddyFile,"SITE_CONTENT_UNMANAGED" -> siteFile)
          .collect { case (code,f) if Set("UNSAFE","UNMANAGED")(f.state) => code } ++ firewall._3 ++ listeners._2 ++ Option.when(packageFindings.nonEmpty)("PROVISIONING_PACKAGE_PROBE_FAILED")
      } yield ServerProfileRemoteObservation(facts,ServerProfileDiff.hashObservation(facts),warnings=(firewall._2 ++ dnsWarnings).distinct.sorted,
        blockingProblems=blockers.distinct.sorted)
    }.timeoutTo(180.seconds,IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_TIMEOUT",uncertain=true)))
      .handleErrorWith {
        case e: ProfileRemoteFailure => IO.pure(ServerProfileRemoteObservation(Json.obj(),"",Some(e.code)))
        case application.port.RemoteConfigurationFailure.HostKeyMismatch |
          application.port.RemoteConfigurationFailure.HostKeyNotTrusted |
          application.port.RemoteConfigurationFailure.AuthenticationFailed =>
          IO.pure(ServerProfileRemoteObservation(Json.obj(),"",Some("PROVISIONING_SSH_REJECTED")))
        case _ => IO.pure(ServerProfileRemoteObservation(Json.obj(),"",Some("PROVISIONING_REMOTE_UNAVAILABLE")))
      }

  private def collectPackages(commands: ProfileCommands,desired: Option[ServerProfileContent]): IO[(List[String],List[PackageProbeFinding])] = {
    val names=desired.toList.flatMap(d => (if(d.packages.enabled) d.packages.packages else Nil) ++
      (if(d.firewall.enabled) List("ufw") else Nil) ++ (if(d.fail2ban.enabled) List("fail2ban") else Nil) ++
      (if(d.docker.enabled) List("docker.io","docker-ce","docker-ce-cli","containerd.io","docker-compose","docker-compose-plugin") else Nil) ++
      (if(d.caddy.enabled) List("caddy","ca-certificates") else Nil)).distinct.sorted
    if(names.isEmpty) IO.pure(Nil -> Nil) else commands.shell("""set -eu
      command -v dpkg-query >/dev/null || exit 2
      for package in "$@"; do
        query_exit=0
        status=$(dpkg-query -W -f='${db:Status-Abbrev}' "$package" 2>/dev/null) || query_exit=$?
        if [ "$query_exit" = 0 ]; then
          case "$status" in
            'ii ') printf 'I|%s\n' "$package"; continue ;;
            'un ') printf 'A|%s\n' "$package"; continue ;;
          esac
        elif [ "$query_exit" = 1 ] && [ -z "$status" ]; then
          printf 'A|%s\n' "$package"; continue
        fi
        safe_status=INVALID; classification=UNKNOWN; action=MANUAL
        case "$status" in [uihrp][ncHUFWti][\ R]) safe_status=$status ;; esac
        if [ "$query_exit" = 0 ]; then
          case "$safe_status" in ?[HUFWt]?|??R) classification=BROKEN; action=REPAIR ;; esac
        fi
        printf 'F|%s|%s|%s|%s\n' "$package" "$safe_status" "$classification" "$action"
      done""",names).map { r =>
      def unavailable = Nil -> names.map(name => PackageProbeFinding(name,None,"UNKNOWN","MANUAL"))
      val records=r.stdout.linesIterator.map(_.split("\\|",-1).toList).toList
      val valid=r.exitCode==0 && records.size==names.size && records.flatMap(_.lift(1)).sorted==names && records.forall {
        case List(kind,name) => Set("I","A")(kind) && names.contains(name)
        case List("F",name,state,classification,action) => names.contains(name) &&
          scala.util.Try(PackageProbeFinding(name,Option.when(state!="INVALID")(state),classification,action)).isSuccess
        case _ => false
      }
      if(!valid) unavailable else records.collect { case List("I",name) => name }.sorted -> records.collect {
        case List("F",name,state,classification,action) => PackageProbeFinding(name,Option.when(state!="INVALID")(state),classification,action)
      }
    }
  }

  private def binary(commands: ProfileCommands,name: String,enabled: Boolean): IO[Boolean] =
    if(!enabled) IO.pure(false) else commands.shell("if command -v \"$1\" >/dev/null 2>&1; then \"$1\" \"$2\" >/dev/null && echo YES; else echo NO; fi",
      List(name,if(name=="caddy") "version" else "--version"))
      .flatMap(r => if(r.exitCode==0 && Set("YES","NO")(r.stdout.trim)) IO.pure(r.stdout.trim=="YES")
        else IO.raiseError(ProfileRemoteFailure("PROVISIONING_BINARY_PROBE_FAILED")))
  private def service(commands: ProfileCommands,name: String,enabled: Boolean,installed: Boolean): IO[(Boolean,Boolean)] =
    if(!enabled || !installed) IO.pure(false -> false) else commands.shell("""set -eu
      load=$(systemctl show --property=LoadState --value "$1"); [ "$load" = loaded ] || exit 1
      enabled=$(systemctl is-enabled "$1" 2>/dev/null || true); active=$(systemctl is-active "$1" 2>/dev/null || true)
      case "$enabled" in enabled|disabled|static|indirect|masked|enabled-runtime|linked|linked-runtime|alias) :;; *) exit 1;; esac
      case "$active" in active|inactive|failed|activating|deactivating) :;; *) exit 1;; esac
      printf '%s\n%s' "$enabled" "$active"
    """,List(name)).flatMap(r => r.stdout.trim.split("\\s+").toList match {
      case e :: a :: Nil if r.exitCode==0 => IO.pure((e=="enabled") -> (a=="active"))
      case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_SERVICE_PROBE_FAILED"))
    })
  private def file(commands: ProfileCommands,path: String,marker: String,enabled: Boolean,content: Boolean): IO[FileFact] =
    if(!enabled) IO.pure(FileFact("MISSING",None,"")) else commands.shell(FileProbe,List(path,marker,content.toString)).flatMap { r =>
      val lines=r.stdout.linesIterator.toList
      lines.headOption match {
        case Some(state) if r.exitCode==0 && Set("MISSING","UNMANAGED","UNSAFE")(state) => IO.pure(FileFact(state,None,""))
        case Some("MANAGED") if r.exitCode==0 && lines.lift(1).exists(_.matches("[0-9a-f]{64}")) =>
          IO.pure(FileFact("MANAGED",lines.lift(1),lines.drop(2).mkString("\n")))
        case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_FILE_PROBE_FAILED"))
      }
    }

  private def collectListeners(commands: ProfileCommands,desired: Option[ServerProfileContent],active: Boolean): IO[(List[Int],List[String])] =
    if(!desired.exists(_.caddy.enabled)) IO.pure(Nil -> Nil) else for {
      pid <- commands.capture("systemctl",List("show","--property=MainPID","--value","caddy"))
      sockets <- commands.capture("ss",List("-ltnp"))
      _ <- IO.raiseUnless(pid.exitCode==0 && sockets.exitCode==0 && pid.stdout.trim.matches("[0-9]+"))(
        ProfileRemoteFailure("PROVISIONING_LISTENER_PROBE_FAILED"))
      relevant=Set(80,443,8080,2019) ++ desired.map(_.caddy.localHttpsPort)
      records=sockets.stdout.linesIterator.drop(1).flatMap { line =>
        val fields=line.trim.split("\\s+")
        fields.lift(3).flatMap(_.split(":").lastOption).flatMap(_.toIntOption).filter(relevant).map { port =>
          port -> (active && pid.stdout.trim!="0" && line.contains(s"pid=${pid.stdout.trim},") && line.contains("\"caddy\""))
        }
      }.toList
    } yield records.collect { case (p,true) => p }.distinct.sorted ->
      Option.when(records.exists(!_._2))("CADDY_PORT_IN_USE").toList

  private def collectFirewall(commands: ProfileCommands,resourceId: UUID,desired: Option[ServerProfileContent],installed: Boolean,
    endpoint: (String,String,Int)): IO[(Json,List[String],List[String])] = {
    val enabled=desired.exists(_.firewall.enabled)
    val empty=Json.obj("enabled" -> Json.False,"managedRules" -> Json.arr(),"foreignEquivalentAllows" -> Json.arr(),
      "sshSource" -> (if(enabled) Json.fromString(endpoint._1) else Json.Null),
      "sshServer" -> (if(enabled) Json.fromString(endpoint._2) else Json.Null),
      "sshPort" -> (if(enabled) Json.fromInt(endpoint._3) else Json.Null))
    val desiredRules=desired.filter(_.firewall.enabled).toList.flatMap(_.firewall.rules)
    val access=desiredRules.exists(r => r.protocol=="tcp" && r.port==endpoint._3 && r.sources.exists(ServerProfileContent.sourceCovers(_,endpoint._1)))
    if(!enabled || !installed) IO.pure((empty,Nil,Option.when(enabled && !access)("FIREWALL_SSH_ACCESS_UNPROVEN").toList))
    else for {
      status <- commands.capture("ufw",List("status"))
      added <- commands.capture("ufw",List("show","added"))
      _ <- IO.raiseUnless(status.exitCode==0 && added.exitCode==0)(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
      _ <- IO.raiseUnless(status.stdout.linesIterator.map(_.trim).exists(s => Set("Status: active","Status: inactive")(s)))(
        ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
      parsed=ProfileFirewall.parse(added.stdout,resourceId)
      rules=parsed.getOrElse(Nil)
      own=ProfileFirewall.aggregate(rules.filter(r => r.owned && r.action=="allow").map(_.rule))
      foreign=desiredRules.filter(ProfileFirewall.coveredByForeign(_,rules))
      blocks=Option.when(parsed.isLeft)("FIREWALL_RULE_UNSUPPORTED").toList ++
        Option.when(rules.exists(r => r.owned && r.action!="allow"))("FIREWALL_RULE_UNSUPPORTED").toList ++
        Option.when(desiredRules.exists(d => d.sources.exists(s => rules.exists(r => r.owned && r.rule.id!=d.id &&
          ProfileFirewall.equivalent(r.rule,d.copy(sources=List(s)))))))("FIREWALL_OWNERSHIP_COLLISION").toList ++
        Option.when(!access || ProfileFirewall.sshBlocked(rules,endpoint._1,endpoint._3))("FIREWALL_SSH_ACCESS_UNPROVEN").toList
    } yield (empty.deepMerge(Json.obj("enabled" -> Json.fromBoolean(status.stdout.linesIterator.exists(_.trim=="Status: active")),
      "managedRules" -> Json.fromValues(own.map(ProfileFirewall.json)),"foreignEquivalentAllows" -> Json.fromValues(foreign.map(ProfileFirewall.json)))),
      Option.when(foreign.nonEmpty)("FIREWALL_FOREIGN_ALLOW_REUSED").toList,blocks)
  }
}

private[ssh] object SshProfileObserver {
  val NetworkPath="/etc/sysctl.d/99-infradesk.conf"; val NetworkMarker="# InfraDesk managed: SERVER_PROFILE NETWORK v1"
  val LimitsPath="/etc/security/limits.d/99-infradesk.conf"; val LimitsMarker="# InfraDesk managed: SERVER_PROFILE LIMITS v1"
  val ManagerPath="/etc/systemd/system.conf.d/99-infradesk.conf"; val ManagerMarker="# InfraDesk managed: SERVER_PROFILE SYSTEMD LIMITS v1"
  val FailPath="/etc/fail2ban/jail.d/99-infradesk.conf"; val FailMarker="# InfraDesk managed: SERVER_PROFILE FAIL2BAN v1"
  val CaddyPath="/etc/caddy/Caddyfile"; val CaddyMarker="# InfraDesk managed: SERVER_PROFILE CADDY v1"
  val SiteMarker="<!-- InfraDesk managed: SERVER_PROFILE SITE v1 -->"
  final case class FileFact(state: String,hash: Option[String],content: String) { def managed: Boolean = state=="MANAGED" }
  def hashJson(f: FileFact): Json=f.hash.fold(Json.Null)(Json.fromString)
  def normalizeValue(s: String): Option[String] = {
    val n=s.trim.replaceAll("\\s+"," "); Option.when(n.length<=128 && n.matches("[A-Za-z0-9_.:+ -]+"))(n)
  }
  def normalizeLimit(s: String): Option[String] = s.trim.split(":",-1).toList match {
    case List(a,b) if List(a,b).forall(_.matches("[0-9]{1,8}")) => Some(if(a==b) a else s.trim)
    case List(a) if a.matches("[0-9]{1,8}") => Some(a)
    case _ => None
  }
  def limitJson(file: FileFact,kind: String): Json = file.content.linesIterator.map(_.trim.split("\\s+").toList).collectFirst {
    case List("*",k,"nofile",value) if k==kind && value.matches("[0-9]{1,8}") => Json.fromString(value)
  }.getOrElse(Json.Null)
  def parseEndpoint(raw: String): Option[(String,String,Int)] = raw.trim.split("\\s+").toList match {
    case source :: client :: server :: port :: Nil if !source.contains("/") && !server.contains("/") && ServerProfileContent.canonicalFirewallSource(source).isRight &&
      ServerProfileContent.canonicalFirewallSource(server).isRight && client.toIntOption.exists(p => p>=1 && p<=65535) =>
      port.toIntOption.filter(p => p>=1 && p<=65535).map(p => (source,server,p))
    case _ => None
  }
  private[ssh] val FileProbe="""set -eu
    dest=$1; marker=$2; content=$3; p=$(dirname "$dest")
    while [ "$p" != / ]; do
      [ ! -L "$p" ] || { echo UNSAFE; exit 0; }
      if [ -e "$p" ]; then
        [ -d "$p" ] && [ "$(stat -c '%u' "$p")" = 0 ] || { echo UNSAFE; exit 0; }
        mode=$(stat -c '%a' "$p"); [ $((0$mode & 022)) -eq 0 ] || { echo UNSAFE; exit 0; }
      fi
      p=$(dirname "$p")
    done
    [ ! -L "$dest" ] || { echo UNSAFE; exit 0; }
    if [ ! -e "$dest" ]; then echo MISSING; exit 0; fi
    [ -f "$dest" ] && [ "$(stat -c '%u:%h' "$dest")" = '0:1' ] || { echo UNSAFE; exit 0; }
    mode=$(stat -c '%a' "$dest"); [ $((0$mode & 022)) -eq 0 ] || { echo UNSAFE; exit 0; }
    IFS= read -r first < "$dest" || true
    [ "$first" = "$marker" ] || { echo UNMANAGED; exit 0; }
    echo MANAGED; sha256sum "$dest" | cut -d' ' -f1
    if [ "$content" = true ]; then cat "$dest"; fi
  """
}

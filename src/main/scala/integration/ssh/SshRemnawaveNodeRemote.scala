package ru.bitec.app.ops
package integration.ssh

import application.port.{ProvisioningStepResult, RemoteConfigurationFailure, RemoteConfigurationSession, RemoteConfigurationTransport, RemnawaveNodeLocalEvidence, RemnawaveNodeRemote, RemnawaveNodeRemoteSpec}
import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all._
import domain.connection.Connection
import domain.integration.NodeInstallationData
import domain.provisioning.ServerProfileContent
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Typed, pinned-SSH implementation of the per-node host installation contract. */
final class SshRemnawaveNodeRemote(transport: RemoteConfigurationTransport[IO]) extends RemnawaveNodeRemote[IO] {
  import SshRemnawaveNodeRemote._

  override def installationPrerequisites(connection: Connection): IO[ProvisioningStepResult] = step(withSession(connection) { (_, c, _) =>
    for {
      docker <- c.capture("docker", List("info", "--format", "{{.ServerVersion}}"), 15.seconds)
      _ <- IO.raiseUnless(docker.exitCode == 0 && docker.stdout.trim.nonEmpty)(ProfileRemoteFailure("PROVISIONING_NODE_DOCKER_UNAVAILABLE"))
      compose <- c.capture("docker", List("compose", "version", "--short"), 10.seconds)
      _ <- IO.raiseUnless(compose.exitCode == 0 && compose.stdout.trim.nonEmpty)(ProfileRemoteFailure("PROVISIONING_NODE_COMPOSE_UNAVAILABLE"))
      firewall <- c.capture("ufw", List("status"), 10.seconds)
      _ <- IO.raiseUnless(firewall.exitCode == 0 && firewall.stdout.linesIterator.exists(_.trim == "Status: active"))(
        ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
    } yield success("installationPrerequisites" -> "ready")
  })

  override def preflight(connection: Connection, resourceId: UUID, nodePort: Int): IO[ProvisioningStepResult] =
    step(withSession(connection) { (_, c, _) =>
      validatePort(nodePort) *> runPreflight(c, nodePort, allowDockerMissing = true).flatMap {
        case "CLEAR" => IO.pure(success("preflight" -> "clear"))
        case "PORT" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PORT_OCCUPIED"))
        case "UNKNOWN" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE"))
        case "DOCKER" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_DOCKER_UNAVAILABLE"))
        case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
      }
    })

  override def configureFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] =
    Ref.of[IO, Boolean](false).flatMap { changed => stepTracked(changed)(withSession(connection) { (_, c, _) =>
      validate(spec) *> firewallRules(spec).flatMap { desired =>
        for {
          ssh <- c.shell("printf '%s' \"$SSH_CONNECTION\"", privileged = false)
          endpoint <- parseSsh(ssh.stdout).liftTo[IO]
          status <- c.capture("ufw", List("status"), 15.seconds)
          _ <- IO.raiseUnless(status.exitCode == 0)(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
          active = status.stdout.linesIterator.exists(_.trim == "Status: active")
          inactive = status.stdout.linesIterator.exists(_.trim == "Status: inactive")
          _ <- IO.raiseUnless(active || inactive)(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
          _ <- IO.raiseUnless(active)(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
          old <- nodeRules(c, spec)
          _ <- IO.raiseWhen(broadManagementAllow(old, spec))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_BROAD_RULE_PRESENT"))
          _ <- IO.raiseWhen(old.exists(r => r.owned && (r.action != "allow" || r.rule.id != "node" ||
            r.rule.protocol != "tcp" || r.rule.port != spec.nodePort)))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_RULE_UNSUPPORTED"))
          _ <- IO.raiseWhen(old.exists(r => !r.owned && r.action != "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_RULE_UNSUPPORTED"))
          _ <- IO.raiseWhen(ProfileFirewall.sshBlocked(old, endpoint._1, endpoint._2))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
          _ <- desired.traverse_ { case (source, comment) =>
            nodeRules(c, spec).flatMap { fresh =>
              if (fresh.exists(r => r.owned && r.action == "allow" && r.rule.port == spec.nodePort && r.rule.protocol == "tcp" && r.rule.sources.contains(source))) IO.unit
              else changed.set(true) *> checked(c, "ufw", List("allow", "from", source,
                "to", "any", "port", spec.nodePort.toString, "proto", "tcp", "comment", comment))
            }
          }
          _ <- canary(connection)
          _ <- old.filter(r => r.owned && r.action == "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort)
            .flatMap(_.rule.sources).distinct.filterNot(s => desired.exists(_._1 == s)).traverse_ { source =>
              nodeRules(c, spec).flatMap { fresh =>
                if (fresh.exists(r => r.owned && r.action == "allow" && r.rule.port == spec.nodePort && r.rule.sources.contains(source)))
                  changed.set(true) *> checked(c, "ufw", List("--force", "delete", "allow", "from", source,
                    "to", "any", "port", spec.nodePort.toString, "proto", "tcp", "comment", s"infradesk:remnawave:${spec.resourceId}:${spec.externalNodeId}:node"))
                else IO.unit
              }
            }
          _ <- canary(connection)
        } yield success("firewallRulesApplied" -> desired.size.toString)
      }
    })}

  override def install(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    credential: NodeInstallationData): IO[ProvisioningStepResult] = Ref.of[IO, Boolean](false).flatMap { changed =>
    stepTracked(changed)(withSession(connection) { (s, c, sshUid) =>
    val files = new ProfileManagedFiles(s, c, sshUid, spec.onboardingId)
    val dir = directory(spec)
    val marker = markerLine(spec)
    val compose = renderCompose(spec)
    val env = s"SECRET_KEY=${credential.secretKey}\nNODE_PORT=${spec.nodePort}\n"
    val envHash = ProfileManagedFiles.sha256(env.getBytes(StandardCharsets.UTF_8))
    val managed = renderManaged(spec, envHash)
    validate(spec) *> validateSecret(credential.secretKey) *> preflightInSession(c, spec.nodePort) *>
      (for {
        _ <- files.replace(s"$dir/.env", marker, env.getBytes(StandardCharsets.UTF_8), None,
          installOwned = true, targetMode = 384).flatTap(_ => changed.set(true))
        _ <- files.replace(s"$dir/compose.yml", marker, compose.getBytes(StandardCharsets.UTF_8), None,
          installOwned = true, validate = Some(List("docker", "compose", "-f", "{candidate}", "--env-file", s"$dir/.env", "config", "-q")),
          validationFailureCode = "PROVISIONING_NODE_COMPOSE_INVALID").flatTap(_ => changed.set(true))
        _ <- files.replace(s"$dir/managed.json", marker, managed.getBytes(StandardCharsets.UTF_8), None,
          installOwned = true).flatTap(_ => changed.set(true))
        _ <- checked(c, "docker", List("pull", spec.imageReference), 5.minutes).flatTap(_ => changed.set(true))
      } yield success("installed" -> "true")).handleErrorWith(sanitize)
    })}

  override def start(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] =
    step(withSession(connection) { (_, c, _) => validate(spec) *> startNode(c, spec) })

  override def observe(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[RemnawaveNodeLocalEvidence] =
    withSession(connection) { (_, c, _) => validate(spec) *> c.capture("sh", List("-c", Observe, "infradesk",
      directory(spec), markerLine(spec), spec.imageReference, containerName(spec), spec.nodePort.toString,
      ProfileManagedFiles.sha256(renderCompose(spec).getBytes(StandardCharsets.UTF_8)), managedPrefix(spec)), 25.seconds).flatMap { r =>
      val parts = if (r.exitCode == 0 && !r.stdoutTruncated && !r.stderrTruncated) r.stdout.trim.split(":", -1).toList else Nil
      parts match {
        case List(files, image, running, port, stable) if parts.forall(x => x == "1" || x == "0") =>
          firewallProof(c, spec).map { fw =>
            RemnawaveNodeLocalEvidence(files == "1", image == "1", running == "1", port == "1", stable == "1", fw)
          }
        case _ => IO.pure(RemnawaveNodeLocalEvidence(false, false, false, false, false, false))
      }
    }}

  override def installationPresent(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[Boolean] = withSession(connection) { (_, c, _) =>
    validate(spec) *> c.capture("sh", List("-c", InstallationProof, "infradesk", directory(spec), markerLine(spec),
      spec.imageReference, spec.nodePort.toString, ProfileManagedFiles.sha256(renderCompose(spec).getBytes(StandardCharsets.UTF_8)),
      managedPrefix(spec)), 20.seconds).map(r => r.exitCode == 0 && r.stdout.trim == "1:1" && !r.stdoutTruncated && !r.stderrTruncated)
  }

  override def firewallPresent(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[Boolean] = withSession(connection) { (_, c, _) =>
    validate(spec) *> firewallRules(spec).flatMap(wanted => if (wanted.isEmpty) IO.pure(false) else firewallProof(c, spec))
  }

  private def withSession[A](connection: Connection)(use: (RemoteConfigurationSession[IO], ProfileCommands, Int) => IO[A]): IO[A] =
    transport.withSessionBounded(connection, 65536) { s =>
      new ProfileCommands(s, root = false).capture("id", List("-u"), 5.seconds, privileged = false).flatMap { r =>
        if (r.exitCode != 0 || r.stdoutTruncated || !r.stdout.trim.matches("[0-9]{1,8}"))
          IO.raiseError(ProfileRemoteFailure("PROVISIONING_SSH_REJECTED"))
        else use(s, new ProfileCommands(s, r.stdout.trim == "0"), r.stdout.trim.toInt)
      }
    }.handleErrorWith(sanitize)

  private def runPreflight(c: ProfileCommands, port: Int, allowDockerMissing: Boolean): IO[String] =
    c.capture("sh", List("-c", Preflight, "infradesk", port.toString,
      if (allowDockerMissing) "1" else "0"), 20.seconds).map { r =>
      if (r.exitCode == 0 && Set("CLEAR", "PORT", "FOREIGN", "UNKNOWN", "DOCKER")(r.stdout.trim)) r.stdout.trim
      else "UNKNOWN"
    }

  private def preflightInSession(c: ProfileCommands, port: Int): IO[Unit] =
    runPreflight(c, port, allowDockerMissing = false).flatMap {
      case "CLEAR" => IO.unit
      case "PORT" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PORT_OCCUPIED"))
      case "DOCKER" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_DOCKER_UNAVAILABLE"))
      case "UNKNOWN" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE"))
      case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
    }

  private def startNode(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] = {
    val dir = directory(spec)
    val composeHash = ProfileManagedFiles.sha256(renderCompose(spec).getBytes(StandardCharsets.UTF_8))
    c.capture("sh", List("-c", Start, "infradesk", dir, markerLine(spec), spec.imageReference,
      spec.nodePort.toString, composeHash, managedPrefix(spec), containerName(spec)), 3.minutes).flatMap { r =>
      if (r.exitCode != 0 || r.stdoutTruncated || r.stderrTruncated)
        IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_UNAVAILABLE", uncertain = true, truncated = r.stdoutTruncated || r.stderrTruncated))
      else r.stdout.trim match {
        case "STARTED" => IO.pure(success("startRequested" -> "true"))
        case "FILES" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_MANAGED_FILES_CHANGED"))
        case "CONFIG" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_COMPOSE_INVALID"))
        case "START_FAILED" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_START_FAILED"))
        case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_UNAVAILABLE", uncertain = true))
      }
    }
  }

  private def checked(c: ProfileCommands, executable: String, args: List[String], timeout: FiniteDuration = 30.seconds): IO[Unit] =
    c.capture(executable, args, timeout).flatMap(r => if (r.exitCode == 0) IO.unit else IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_REMOTE_COMMAND_FAILED")))

  private def nodeRules(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec): IO[List[ProfileFirewallRule]] =
    c.capture("ufw", List("show", "added"), 15.seconds).flatMap { r =>
      if (r.exitCode != 0 || r.stdoutTruncated || r.stderrTruncated) IO.raiseError(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
      else SshRemnawaveNodeRemote.parseNodeRules(r.stdout, spec.resourceId, spec.externalNodeId).leftMap(ProfileRemoteFailure(_)).liftTo[IO]
    }

  private def firewallProof(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec): IO[Boolean] =
    c.capture("ufw", List("status"), 15.seconds).flatMap { status =>
      if (status.exitCode != 0 || status.stdoutTruncated || !status.stdout.linesIterator.exists(_.trim == "Status: active")) IO.pure(false)
      else nodeRules(c, spec).map(rules => firewallExact(rules, spec))
    }

  private def canary(connection: Connection): IO[Unit] = transport.withSessionBounded(connection, 128) { s =>
    new ProfileCommands(s, root = true).capture("id", List("-u"), 5.seconds, privileged = false).flatMap(r =>
      if (r.exitCode == 0 && r.stdout.trim.matches("[0-9]{1,8}")) IO.unit
      else IO.raiseError(ProfileRemoteFailure("PROVISIONING_FIREWALL_CANARY_FAILED", uncertain = true)))
  }.timeoutTo(15.seconds, IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_TIMEOUT", uncertain = true)))

  private def sanitize[A](e: Throwable): IO[A] = e match {
    case p: ProfileRemoteFailure => IO.raiseError(p)
    case RemoteConfigurationFailure.HostKeyMismatch => IO.raiseError(ProfileRemoteFailure("PROVISIONING_HOST_KEY_MISMATCH"))
    case RemoteConfigurationFailure.HostKeyNotTrusted => IO.raiseError(ProfileRemoteFailure("PROVISIONING_HOST_KEY_NOT_TRUSTED"))
    case RemoteConfigurationFailure.AuthenticationFailed => IO.raiseError(ProfileRemoteFailure("PROVISIONING_SSH_AUTHENTICATION_FAILED"))
    case _: RemoteConfigurationFailure => IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_UNAVAILABLE", uncertain = true))
    case NonFatal(_) => IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_UNAVAILABLE", uncertain = true))
  }
  private def success(pair: (String, String)) = ProvisioningStepResult(Map(pair), None, None)

  private def step(action: IO[ProvisioningStepResult]): IO[ProvisioningStepResult] =
    action.handleError(e => failure(e))

  private def stepTracked(changed: Ref[IO, Boolean])(action: IO[ProvisioningStepResult]): IO[ProvisioningStepResult] =
    action.handleErrorWith(e => changed.get.map(mutated => failure(e, uncertainAfterMutation = mutated)))

  private def failure(e: Throwable, uncertainAfterMutation: Boolean = false): ProvisioningStepResult = e match {
    case p: ProfileRemoteFailure => ProvisioningStepResult(Map.empty, Some(p.code), None,
      uncertain = p.uncertain || uncertainAfterMutation, outputTruncated = p.truncated)
    case RemoteConfigurationFailure.HostKeyMismatch => ProvisioningStepResult(Map.empty, Some("PROVISIONING_HOST_KEY_MISMATCH"), None)
    case RemoteConfigurationFailure.HostKeyNotTrusted => ProvisioningStepResult(Map.empty, Some("PROVISIONING_HOST_KEY_NOT_TRUSTED"), None)
    case RemoteConfigurationFailure.AuthenticationFailed => ProvisioningStepResult(Map.empty, Some("PROVISIONING_SSH_AUTHENTICATION_FAILED"), None)
    case _: RemoteConfigurationFailure => ProvisioningStepResult(Map.empty, Some("PROVISIONING_REMOTE_UNAVAILABLE"), None, uncertain = true)
    case NonFatal(_) => ProvisioningStepResult(Map.empty, Some("PROVISIONING_REMOTE_UNAVAILABLE"), None, uncertain = true)
  }
}

private[ssh] object SshRemnawaveNodeRemote {
  private val reviewedTags = Set("remnawave/node:2.8.0", "remnawave/node:3.4.1")
  private val imageDigest = "^remnawave/node@sha256:[0-9a-f]{64}$".r
  private def validatePort(port: Int): IO[Unit] = IO.raiseUnless(port >= 1 && port <= 65535)(ProfileRemoteFailure("PROVISIONING_NODE_INVALID_SPEC"))
  private def validateSecret(data: String): IO[Unit] = IO {
    val valid = data != null && data.length <= 65536 && data.matches("[A-Za-z0-9+/]+={0,2}") &&
      scala.util.Try(java.util.Base64.getDecoder.decode(data)).isSuccess
    if (!valid) throw ProfileRemoteFailure("PROVISIONING_NODE_CREDENTIAL_INVALID")
  }
  private def validate(spec: RemnawaveNodeRemoteSpec): IO[Unit] = for {
    _ <- validatePort(spec.nodePort)
    _ <- IO.raiseUnless(reviewedTags(spec.imageReference) || imageDigest.matches(spec.imageReference))(
      ProfileRemoteFailure("PROVISIONING_NODE_IMAGE_UNPINNED"))
    _ <- IO.raiseUnless(spec.panelCidrs.nonEmpty && spec.panelCidrs.forall(c => c.contains("/") &&
      ServerProfileContent.canonicalFirewallSource(c).exists(x => x == c && x != "ANY" && !x.endsWith("/0"))))(
      ProfileRemoteFailure("PROVISIONING_NODE_INVALID_CIDR"))
  } yield ()
  private def directory(s: RemnawaveNodeRemoteSpec) = s"/opt/infradesk/remnawave/${s.externalNodeId}"
  private def containerName(s: RemnawaveNodeRemoteSpec) = s"infradesk-remnawave-${s.externalNodeId}"
  private def markerLine(s: RemnawaveNodeRemoteSpec) = s"# infradesk-managed run=${s.onboardingId} node=${s.externalNodeId} resource=${s.resourceId} image=${s.imageReference}"
  private def managedPrefix(s: RemnawaveNodeRemoteSpec) =
    s"{\"managedBy\":\"infradesk\",\"runId\":\"${s.onboardingId}\",\"nodeId\":\"${s.externalNodeId}\",\"resourceId\":\"${s.resourceId}\",\"image\":\"${s.imageReference}\",\"envSha256\":\""
  private def renderCompose(s: RemnawaveNodeRemoteSpec): String =
    s"""${markerLine(s)}
       |services:
       |  node:
       |    image: ${s.imageReference}
       |    container_name: ${containerName(s)}
       |    network_mode: host
       |    cap_add: ["NET_ADMIN"]
       |    ulimits:
       |      nofile:
       |        soft: 1048576
       |        hard: 1048576
       |    env_file: .env
       |    restart: unless-stopped
       |""".stripMargin
  private def renderManaged(s: RemnawaveNodeRemoteSpec, envHash: String): String = s"${managedPrefix(s)}$envHash\"}\n"
  private def firewallRules(s: RemnawaveNodeRemoteSpec): IO[List[(String, String)]] = IO.pure(s.panelCidrs.distinct.sorted.map(c =>
    c -> s"infradesk:remnawave:${s.resourceId}:${s.externalNodeId}:node"))
  private def parseSsh(raw: String): Either[ProfileRemoteFailure, (String, Int)] = raw.trim.split("\\s+").toList match {
    case source :: clientPort :: server :: serverPort :: Nil if ServerProfileContent.canonicalFirewallSource(source).isRight &&
      ServerProfileContent.canonicalFirewallSource(server).isRight && !source.contains("/") && !server.contains("/") =>
      serverPort.toIntOption.filter(p => p >= 1 && p <= 65535).map(source -> _).toRight(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
    case _ => Left(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
  }
  private def broadManagementAllow(rules: List[ProfileFirewallRule], spec: RemnawaveNodeRemoteSpec): Boolean =
    rules.exists(r => !r.owned && r.action == "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort &&
      r.rule.sources.exists(source => source == "ANY" || source.endsWith("/0") || !spec.panelCidrs.contains(source))) ||
      rules.exists(r => !r.owned && r.action != "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort)

  private def firewallExact(rules: List[ProfileFirewallRule], spec: RemnawaveNodeRemoteSpec): Boolean = {
    val expected = spec.panelCidrs.distinct.toSet
    val ownedNamespace = rules.filter(_.owned)
    val ownedNode = rules.filter(r => r.owned && r.rule.id == "node")
    val managedSources = ownedNode.filter(r => r.action == "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort)
      .flatMap(_.rule.sources).toSet
    expected.nonEmpty && ownedNamespace.forall(_.rule.id == "node") && managedSources == expected &&
      !ownedNode.exists(r => r.action != "allow" || r.rule.protocol != "tcp" || r.rule.port != spec.nodePort) &&
      !broadManagementAllow(rules, spec)
  }
  private val Preflight = """set -u
    |port=$1; allowDockerMissing=$2; root=/opt/infradesk/remnawave
    |echo_result() { printf '%s' "$1"; exit 0; }
    |command -v ss >/dev/null 2>&1 || echo_result UNKNOWN
    |listeners=$(ss -H -ltn "sport = :$port" 2>/dev/null) || echo_result UNKNOWN
    |[ -z "$listeners" ] || echo_result PORT
    |command -v grep >/dev/null 2>&1 || echo_result UNKNOWN
    |for p in /opt /opt/infradesk "$root"; do
    |  [ ! -L "$p" ] || echo_result FOREIGN
    |  if [ -e "$p" ]; then
    |    [ -d "$p" ] && [ "$(stat -c '%u' "$p" 2>/dev/null)" = 0 ] || echo_result FOREIGN
    |    mode=$(stat -c '%a' "$p" 2>/dev/null) || echo_result UNKNOWN
    |    [ $((0$mode & 022)) -eq 0 ] || echo_result FOREIGN
    |  fi
    |done
    |for p in /opt/remnawave /opt/remnawave-node; do [ ! -e "$p" ] && [ ! -L "$p" ] || echo_result FOREIGN; done
    |if [ -d "$root" ]; then
    |  for p in "$root"/* "$root"/.[!.]*; do
    |    [ -e "$p" ] || [ -L "$p" ] || continue
    |    echo_result FOREIGN
    |  done
    |fi
    |if ! command -v docker >/dev/null 2>&1; then [ "$allowDockerMissing" = 1 ] && echo_result CLEAR || echo_result DOCKER; fi
    |containers=$(docker ps -a --format '{{.Image}} {{.Names}}') || echo_result UNKNOWN
    |printf '%s\n' "$containers" | grep -Eiq 'remnawave|node.*remna|remna.*node' && echo_result FOREIGN
    |echo_result CLEAR
    |""".stripMargin
  private val ManagedFileProof = """managed_files() {
    |  d=$1; marker=$2; port=$3; composeHash=$4; managedPrefix=$5
    |  for p in /opt /opt/infradesk /opt/infradesk/remnawave; do
    |    [ ! -L "$p" ] || return 1
    |    if [ -e "$p" ]; then [ -d "$p" ] && [ "$(stat -c '%u' "$p" 2>/dev/null)" = 0 ] || return 1; mode=$(stat -c '%a' "$p" 2>/dev/null) || return 1; [ $((0$mode & 022)) -eq 0 ] || return 1; fi
    |  done
    |  [ ! -L "$d" ] && [ -d "$d" ] && [ "$(stat -c '%u:%a' "$d" 2>/dev/null)" = '0:755' ] &&
    |  [ ! -L "$d/compose.yml" ] && [ ! -L "$d/.env" ] && [ ! -L "$d/managed.json" ] &&
    |  [ "$(stat -c '%u:%a:%h' "$d/compose.yml" 2>/dev/null)" = '0:644:1' ] &&
    |  [ "$(stat -c '%u:%a:%h' "$d/.env" 2>/dev/null)" = '0:600:1' ] &&
    |  [ "$(stat -c '%u:%a:%h' "$d/managed.json" 2>/dev/null)" = '0:644:1' ] &&
    |  [ "$(head -n1 "$d/compose.yml")" = "$marker" ] &&
    |  [ "$(sha256sum "$d/compose.yml" | cut -d' ' -f1)" = "$composeHash" ] || return 1
    |  envHash=$(sha256sum "$d/.env" | cut -d' ' -f1) || return 1
    |  [ "$(cat "$d/managed.json")" = "$managedPrefix$envHash\"}" ] || return 1
    |  [ "$(grep -Ec '^SECRET_KEY=[A-Za-z0-9+/]+={0,2}$' "$d/.env")" = 1 ] &&
    |  [ "$(grep -Fxc "NODE_PORT=$port" "$d/.env")" = 1 ] && [ "$(wc -l < "$d/.env")" = 2 ]
    |}
    |""".stripMargin
  private val InstallationProof = "set -u; " + ManagedFileProof + """
    |files=0; image=0
    |managed_files "$1" "$2" "$4" "$5" "$6" && files=1 || true
    |imageId=$(docker image inspect -f '{{.Id}}' "$3" 2>/dev/null) && [ -n "$imageId" ] && image=1 || true
    |printf '%s:%s' "$files" "$image"
    |""".stripMargin
  private val Observe = "set -u; " + ManagedFileProof + """
    |d=$1; marker=$2; image=$3; name=$4; port=$5; composeHash=$6; managedPrefix=$7
    |files=0; images=0; running=0; listener=0; stable=0
    |managed_files "$d" "$marker" "$port" "$composeHash" "$managedPrefix" && files=1 || true
    |imageId=$(docker image inspect -f '{{.Id}}' "$image" 2>/dev/null) && [ -n "$imageId" ] && images=1 || true
    |configImage=$(docker inspect -f '{{.Config.Image}}' "$name" 2>/dev/null || true)
    |containerImageId=$(docker inspect -f '{{.Image}}' "$name" 2>/dev/null || true)
    |[ "$containerImageId" = "$imageId" ] && [ -n "$imageId" ] || images=0
    |containerImageId=$(docker inspect -f '{{.Image}}' "$name" 2>/dev/null || true)
    |network=$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$name" 2>/dev/null || true)
    |state=$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || true)
    |[ "$configImage" = "$image" ] && [ "$containerImageId" = "$imageId" ] && [ "$network" = host ] && [ "$state" = true ] && running=1 || true
    |listenerOutput=$(ss -H -ltn "sport = :$port" 2>/dev/null); listenerStatus=$?
    |[ "$listenerStatus" = 0 ] && [ -n "$listenerOutput" ] && listener=1 || true
    |restarts=$(docker inspect -f '{{.RestartCount}}' "$name" 2>/dev/null || echo x)
    |started=$(docker inspect -f '{{.State.StartedAt}}' "$name" 2>/dev/null || true)
    |startedSeconds=$(date -d "$started" +%s 2>/dev/null || echo 0); nowSeconds=$(date +%s)
    |[ "$restarts" = 0 ] && [ "$startedSeconds" -gt 0 ] && [ $((nowSeconds-startedSeconds)) -ge 10 ] && stable=1 || true
    |printf '%s:%s:%s:%s:%s' "$files" "$images" "$running" "$listener" "$stable"
    |""".stripMargin
  private val Start = "set -u; " + ManagedFileProof + """
    |d=$1; marker=$2; image=$3; port=$4; composeHash=$5; managedPrefix=$6; name=$7
    |managed_files "$d" "$marker" "$port" "$composeHash" "$managedPrefix" || { printf FILES; exit 0; }
    |docker compose -f "$d/compose.yml" --env-file "$d/.env" config -q >/dev/null 2>&1 || { printf CONFIG; exit 0; }
    |docker compose -f "$d/compose.yml" --env-file "$d/.env" up -d >/dev/null 2>&1 || { printf START_FAILED; exit 0; }
    |printf STARTED
    |""".stripMargin

  private[ssh] def parseNodeRules(raw: String, resource: UUID, node: UUID): Either[String, List[ProfileFirewallRule]] = {
    val prefix = s"infradesk:remnawave:$resource:$node:"
    val nonempty = raw.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    val header = "Added user rules (see 'ufw status' for running firewall)"
    if (nonempty.isEmpty || nonempty.exists(l => l != header && !l.startsWith("ufw "))) return Left("FIREWALL_RULE_UNSUPPORTED")
    nonempty.filter(_.startsWith("ufw ")).traverse { line =>
      val ix = line.indexOf(" comment "); val body = if (ix < 0) line else line.substring(0, ix)
      val comment = if (ix < 0) "" else line.substring(ix + 9).stripPrefix("'").stripSuffix("'")
      val own = comment.startsWith(prefix) && comment.stripPrefix(prefix).matches("[a-z0-9][a-z0-9_-]{0,39}")
      val pattern = "^ufw (allow|deny|reject) from ([0-9a-fA-F.:/]+|any) to any port ([0-9]{1,5}) proto (tcp|udp)$".r
      val short = "^ufw (allow|deny|reject) ([0-9]{1,5})/(tcp|udp)$".r
      body match {
        case pattern(action, source, port, protocol) => for {
          p <- port.toIntOption.filter(x => x >= 1 && x <= 65535).toRight("FIREWALL_RULE_UNSUPPORTED")
          canonical <- ServerProfileContent.canonicalFirewallSource(if (source == "any") "ANY" else source)
        } yield ProfileFirewallRule(action, domain.provisioning.FirewallRule(comment.stripPrefix(prefix), protocol, p, List(canonical)), own)
        case short(action, port, protocol) => for {
          p <- port.toIntOption.filter(x => x >= 1 && x <= 65535).toRight("FIREWALL_RULE_UNSUPPORTED")
        } yield ProfileFirewallRule(action, domain.provisioning.FirewallRule("foreign", protocol, p, List("ANY")), owned = false)
        case _ => Left("FIREWALL_RULE_UNSUPPORTED")
      }
    }
  }
}

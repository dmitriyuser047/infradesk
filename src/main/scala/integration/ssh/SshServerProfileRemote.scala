package ru.bitec.app.ops
package integration.ssh

import application.port.{ProfileExecutionContext,RemoteConfigurationFailure, RemoteConfigurationTransport, RemoteConfigurationSession, ServerProfileRemote, ServerProfileRemoteObservation, ProvisioningStepResult}
import cats.effect.IO
import cats.syntax.all._
import domain.connection.Connection
import domain.provisioning.ServerProfileContent
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration._

/** Typed observation and execution through the application's shared, pinned SSH/SFTP transport. */
final class SshServerProfileRemote(transport: RemoteConfigurationTransport[IO]) extends ServerProfileRemote[IO] {
  private val Limit = 64 * 1024

  override def observe(connection: Connection, resourceId: UUID, desired: Option[ServerProfileContent]): IO[ServerProfileRemoteObservation] =
    new SshProfileObserver(transport).observe(connection,resourceId,desired)

  override def applyModule(connection: Connection, resourceId: UUID, snapshot: domain.provisioning.ServerProfileApplySnapshot,
    kind: domain.provisioning.ProvisioningStepKind, context: ProfileExecutionContext): IO[ProvisioningStepResult] = kind match {
    case domain.provisioning.ProvisioningStepKind.Verify =>
      observe(connection,resourceId,Some(snapshot.content)).map { observed =>
        observed.failureCode match {
          case Some(code) => ProvisioningStepResult(Map.empty,Some(code),None,uncertain=code.startsWith("PROVISIONING_REMOTE_"),
            outputTruncated=code=="PROVISIONING_REMOTE_OUTPUT_LIMIT")
          case None =>
            val diff=domain.provisioning.ServerProfileDiff.assess(snapshot.content,observed.content)
            val compliant=diff.compliant && observed.blockingProblems.isEmpty &&
              domain.provisioning.ServerProfileObservationCodec.validate(observed.content).isRight &&
              domain.provisioning.ServerProfileDiff.hashObservation(observed.content)==observed.contentHash
            ProvisioningStepResult(Map("moduleCount" -> diff.modules.size.toString,"compliantModules" -> diff.modules.count(_._2).toString),
              if(compliant) None else Some("PROVISIONING_VERIFICATION_FAILED"),Some(compliant),
              profileObservation=Some(observed))
        }
      }
    case _ =>
      val enabled = kind match {
        case domain.provisioning.ProvisioningStepKind.InstallPackages => snapshot.content.packages.enabled || snapshot.content.firewall.enabled ||
          snapshot.content.fail2ban.enabled || snapshot.content.docker.enabled || snapshot.content.caddy.enabled
        case domain.provisioning.ProvisioningStepKind.ConfigureNetwork => snapshot.content.network.enabled
        case domain.provisioning.ProvisioningStepKind.ConfigureLimits => snapshot.content.limits.enabled
        case domain.provisioning.ProvisioningStepKind.ConfigureFirewall => snapshot.content.firewall.enabled
        case domain.provisioning.ProvisioningStepKind.ConfigureFail2ban => snapshot.content.fail2ban.enabled
        case domain.provisioning.ProvisioningStepKind.ConfigureDocker => snapshot.content.docker.enabled
        case domain.provisioning.ProvisioningStepKind.DeploySite => snapshot.content.site.enabled
        case domain.provisioning.ProvisioningStepKind.ConfigureCaddy => snapshot.content.caddy.enabled
        case _ => false
      }
      if (!enabled) IO.pure(ProvisioningStepResult(Map("skipReason" -> "NOT_MANAGED"),None,None,skipped=true))
      else if (moduleName(kind).exists(name => domain.provisioning.ServerProfileDiff.assess(snapshot.content,
        context.reviewedObservation).modules.contains(name -> true)))
        IO.pure(ProvisioningStepResult(Map("skipReason" -> "ALREADY_COMPLIANT"),None,None,skipped=true))
      else applyEnabledModule(connection,resourceId,snapshot,kind,context)
  }

  private def applyEnabledModule(connection: Connection, resourceId: UUID,
    snapshot: domain.provisioning.ServerProfileApplySnapshot, kind: domain.provisioning.ProvisioningStepKind,
    context: ProfileExecutionContext): IO[ProvisioningStepResult] = {
    import domain.provisioning.ProvisioningStepKind._
    val content = snapshot.content
    transport.withSessionBounded(connection,Limit) { session =>
      session.executeCaptured("id",List("-u"),5.seconds,32).flatMap { identity =>
        if (identity.exitCode != 0 || identity.stdoutTruncated || !identity.stdout.trim.matches("[0-9]{1,8}"))
          IO.pure(failed("PROVISIONING_SSH_REJECTED"))
        else {
          val root = identity.stdout.trim == "0"
          val files = new ProfileManagedFiles(session,new ProfileCommands(session,root),identity.stdout.trim.toInt,context.runId)
          val privileged = (script: String) => runPrivileged(session,root,script,30.seconds)
          def install(path:String,marker:String,body:String,mode:String="0644",safeChain:String="",expected:Option[String]=None,validate:Boolean=false): IO[ProvisioningStepResult] =
            files.replace(path,marker,body.getBytes(StandardCharsets.UTF_8),expected,
              validate=Option.when(validate)(List("caddy","validate","--adapter","caddyfile","--config")))
              .as(succeeded("managedFile" -> "true"))
          kind match {
            case InstallPackages => new ProfilePackageInstaller(session,new ProfileCommands(session,root),files).install(content,context)
            case ConfigureNetwork =>
              val network = content.network
              val body = domain.provisioning.ServerProfileDiff.renderNetwork(network)
              install("/etc/sysctl.d/99-infradesk.conf","# InfraDesk managed: SERVER_PROFILE NETWORK v1",body,"0644","/etc:/etc/sysctl.d",reviewedHash(context,"network","managedFileHash")).flatMap {
                case r if r.failureCode.nonEmpty => IO.pure(r)
                case _ => privileged("sysctl -p /etc/sysctl.d/99-infradesk.conf").map(code => if(code==0) succeeded("reloaded" -> "true") else failed("PROVISIONING_REMOTE_MUTATION_UNCERTAIN",uncertain=true))
              }
            case ConfigureLimits =>
              val limits = domain.provisioning.ServerProfileDiff.renderLimits(content.limits)
              val systemd = domain.provisioning.ServerProfileDiff.renderManagerLimits(content.limits)
              install("/etc/security/limits.d/99-infradesk.conf","# InfraDesk managed: SERVER_PROFILE LIMITS v1",limits,"0644","/etc:/etc/security:/etc/security/limits.d",reviewedHash(context,"limits","managedFileHash")).flatMap {
                case r if r.failureCode.nonEmpty => IO.pure(r)
                case _ => install("/etc/systemd/system.conf.d/99-infradesk.conf","# InfraDesk managed: SERVER_PROFILE SYSTEMD LIMITS v1",systemd,"0644","/etc:/etc/systemd:/etc/systemd/system.conf.d",reviewedHash(context,"limits","managedSystemdDropinHash"))
              }.flatMap {
                case r if r.failureCode.nonEmpty => IO.pure(r)
                case _ => privileged("systemctl daemon-reexec && systemctl daemon-reload").map(code => if(code==0) succeeded("futureProcessesOnly" -> "true") else failed("PROVISIONING_REMOTE_MUTATION_UNCERTAIN",uncertain=true))
              }
            case DeploySite =>
              val site=content.site
              val html=domain.provisioning.ServerProfileDiff.placeholderHtml(site)
              install(s"${site.root}/index.html","<!-- InfraDesk managed: SERVER_PROFILE SITE v1 -->",html,"0644",s"/var:/var/www:/var/www/infradesk:${site.root}",reviewedHash(context,"site","managedContentHash"))
            case ConfigureCaddy =>
              val caddy=domain.provisioning.ServerProfileDiff.renderCaddy(content.caddy)
              val installProof=context.installFacts.flatMap(_.get("installOwnedCaddyConfigHash")).filter(_.matches("[0-9a-f]{64}"))
              files.replace("/etc/caddy/Caddyfile","# InfraDesk managed: SERVER_PROFILE CADDY v1",
                caddy.getBytes(StandardCharsets.UTF_8),reviewedHash(context,"caddy","managedConfigHash").orElse(installProof),
                installOwned=installProof.nonEmpty,
                validate=Some(List("caddy","validate","--adapter","caddyfile","--config")),
                activate=Some(List("sh","-c","systemctl enable --now caddy && systemctl reload caddy")))
                .as(succeeded("reloaded" -> "true"))
            case ConfigureFail2ban =>
              val body=domain.provisioning.ServerProfileDiff.Fail2banContent
              install("/etc/fail2ban/jail.d/99-infradesk.conf","# InfraDesk managed: SERVER_PROFILE FAIL2BAN v1",body,"0644","/etc:/etc/fail2ban:/etc/fail2ban/jail.d",reviewedHash(context,"fail2ban","managedFileHash")).flatMap {
                case r if r.failureCode.nonEmpty => IO.pure(r)
                case _ => privileged("systemctl enable --now fail2ban && systemctl restart fail2ban")
                  .map(code => if(code==0) succeeded("restarted" -> "true") else failed("PROVISIONING_REMOTE_MUTATION_UNCERTAIN",uncertain=true))
              }
            case ConfigureDocker =>
              privileged("command -v docker >/dev/null 2>&1 && systemctl enable --now docker && (docker compose version >/dev/null 2>&1 || docker-compose version >/dev/null 2>&1)")
                .map(code => if(code==0) succeeded("enabled" -> "true") else failed("PROVISIONING_DOCKER_NOT_READY"))
            case ConfigureFirewall => new ProfileFirewall(transport,connection,new ProfileCommands(session,root),resourceId)
              .apply(content.firewall,context.reviewedObservation.hcursor.downField("firewall").focus)
            case _ => IO.pure(failed("PROVISIONING_STEP_UNSUPPORTED"))
          }
        }
      }
    }.handleErrorWith {
      case e: ProfileRemoteFailure => IO.pure(failed(e.code,uncertain=e.uncertain,truncated=e.truncated))
      case RemoteOutputTruncated => IO.pure(failed("PROVISIONING_REMOTE_OUTPUT_LIMIT",uncertain=true,truncated=true))
      case RemoteConfigurationFailure.HostKeyMismatch | RemoteConfigurationFailure.HostKeyNotTrusted |
        RemoteConfigurationFailure.AuthenticationFailed => IO.pure(failed("PROVISIONING_SSH_REJECTED"))
      case _ => IO.pure(failed("PROVISIONING_REMOTE_UNAVAILABLE",uncertain=true))
    }
  }

  private def runPrivileged(session:RemoteConfigurationSession[IO],root:Boolean,script:String,timeout:FiniteDuration,args:List[String]=Nil):IO[Int] =
    executeCaptured(session,root,"sh",List("-c",script,"sh")++args,timeout)
  private def executeCaptured(session:RemoteConfigurationSession[IO],root:Boolean,executable:String,args:List[String],timeout:FiniteDuration):IO[Int] = {
    val command=if(root) executable -> args else "sudo" -> (List("-n",executable)++args)
    session.executeCaptured(command._1,command._2,timeout,65536).flatMap(r =>
      if(r.stdoutTruncated || r.stderrTruncated) IO.raiseError(RemoteOutputTruncated) else IO.pure(r.exitCode))
  }
  private def reviewedHash(context:ProfileExecutionContext,module:String,key:String):Option[String] =
    context.reviewedObservation.hcursor.downField(module).get[Option[String]](key).toOption.flatten.filter(_.matches("[0-9a-f]{64}"))
  private def succeeded(fact:(String,String)):ProvisioningStepResult=ProvisioningStepResult(Map(fact),None,Some(true))
  private case object RemoteOutputTruncated extends RuntimeException
  private def failed(code:String,uncertain:Boolean=false,truncated:Boolean=false):ProvisioningStepResult=ProvisioningStepResult(Map.empty,Some(code),None,uncertain=uncertain,outputTruncated=truncated)

  private def moduleName(kind: domain.provisioning.ProvisioningStepKind): Option[String] = {
    import domain.provisioning.ProvisioningStepKind._
    kind match {
      case ConfigureNetwork => Some("network")
      case ConfigureLimits => Some("limits")
      case ConfigureFirewall => Some("firewall")
      case ConfigureFail2ban => Some("fail2ban")
      case ConfigureDocker => Some("docker")
      case DeploySite => Some("site")
      case ConfigureCaddy => Some("caddy")
      case _ => None
    }
  }
}

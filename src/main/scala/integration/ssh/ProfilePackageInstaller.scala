package ru.bitec.app.ops
package integration.ssh

import application.port.{ProfileExecutionContext, ProvisioningStepResult, RemoteConfigurationSession}
import cats.effect.IO
import cats.syntax.all._
import domain.provisioning.{ServerProfileContent, ServerProfileDiff}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

private[ssh] final class ProfilePackageInstaller(session: RemoteConfigurationSession[IO], commands: ProfileCommands,
  files: ProfileManagedFiles) {
  import ProfilePackageInstaller._
  private def checked(script: String, args: List[String] = Nil, timeout: FiniteDuration = 30.seconds,
    failure: String = "PROVISIONING_PACKAGE_CHANGE_UNSAFE"): IO[application.port.RemoteCommandOutput] =
    commands.shell(script,args,timeout).flatMap(r => if (r.exitCode == 0) IO.pure(r)
      else IO.raiseError(ProfileRemoteFailure(failure)))

  def install(desired: ServerProfileContent, context: ProfileExecutionContext): IO[ProvisioningStepResult] = {
    val missing = ServerProfileDiff.requiredPackages(desired,context.reviewedObservation)
    if (missing.isEmpty) IO.pure(ProvisioningStepResult(Map("skipReason" -> "ALREADY_COMPLIANT"),None,None,skipped=true))
    else for {
      _ <- IO.raiseUnless(missing.forall(_.matches("[a-z0-9][a-z0-9+.-]{0,127}")))(
        ProfileRemoteFailure("PROVISIONING_PROFILE_SNAPSHOT_INVALID"))
      _ <- checked(BinaryOwnership,(if(missing.exists(p => p.startsWith("docker"))) List("docker") else Nil) ++
        (if(missing.contains("caddy")) List("caddy") else Nil))
      _ <- checked("apt-get update -qq",timeout=120.seconds)
      _ <- if (missing.contains("caddy")) prepareCaddy(context) else IO.unit
      _ <- checked(Simulate,missing,timeout=60.seconds)
      // apt simulations do not lock the package database. Repeat immediately before installation;
      // this is a bounded best-effort guard, never a claim of remote transactional atomicity.
      _ <- checked(Simulate + "\nDEBIAN_FRONTEND=noninteractive apt-get install -y --no-upgrade --no-remove -- \"$@\"",
        missing,300.seconds,failure="PROVISIONING_PACKAGE_INSTALL_FAILED")
      proof <- if (missing.contains("caddy")) checked(CaddyDefaultProof,
        failure="PROVISIONING_CADDY_INSTALL_OWNERSHIP_UNPROVEN").map(_.stdout.trim).flatMap { hash =>
          if (hash.matches("[0-9a-f]{64}")) IO.pure(Map("installOwnedCaddyConfigHash" -> hash))
          else IO.raiseError[Map[String,String]](ProfileRemoteFailure("PROVISIONING_CADDY_INSTALL_OWNERSHIP_UNPROVEN"))
        } else IO.pure(Map.empty[String,String])
    } yield ProvisioningStepResult(proof ++ Map("packagesAdded" -> missing.size.toString),None,None)
  }

  private def prepareCaddy(context: ProfileExecutionContext): IO[Unit] = for {
    candidate <- checked("apt-cache policy caddy",failure="PROVISIONING_PACKAGE_CANDIDATE_UNAVAILABLE")
    _ <- if (candidate.stdout.linesIterator.exists(l => l.trim.startsWith("Candidate:") && !l.contains("(none)"))) IO.unit
      else for {
        // Minimal Ubuntu images may not trust HTTPS until the distro CA package is installed.
        ca <- checked("dpkg-query -W -f='${db:Status-Abbrev}' ca-certificates 2>/dev/null || exit 0")
        _ <- if (ca.stdout.startsWith("ii")) IO.unit else checked(Simulate +
          "\nDEBIAN_FRONTEND=noninteractive apt-get install -y --no-upgrade --no-remove -- \"$@\"",
          List("ca-certificates"),120.seconds).void
        key <- IO.blocking {
          val in=Option(getClass.getResourceAsStream("/keys/caddy-stable.asc")).getOrElse(
            throw ProfileRemoteFailure("PROVISIONING_CADDY_REPOSITORY_UNSAFE"))
          try in.readAllBytes() finally in.close()
        }
        _ <- IO.raiseUnless(ProfileManagedFiles.sha256(key)==KeyHash)(ProfileRemoteFailure("PROVISIONING_CADDY_REPOSITORY_UNSAFE"))
        _ <- putFixed("/etc/apt/keyrings/infradesk-caddy.asc","-----BEGIN PGP PUBLIC KEY BLOCK-----",key,rawPinned=true)
        _ <- putFixed("/etc/apt/sources.list.d/infradesk-caddy.list",RepositoryMarker,
          Repository.getBytes(StandardCharsets.UTF_8),rawPinned=false)
        _ <- checked("apt-get update -qq",timeout=120.seconds,failure="PROVISIONING_CADDY_REPOSITORY_UNSAFE")
      } yield ()
  } yield ()

  private def putFixed(path: String, marker: String, bytes: Array[Byte], rawPinned: Boolean): IO[Unit] = {
    val hash=ProfileManagedFiles.sha256(bytes)
    checked(SshProfileObserver.FileProbe,List(path,marker,"false"),
      failure="PROVISIONING_CADDY_REPOSITORY_UNSAFE").flatMap { current =>
      current.stdout.linesIterator.toList match {
        case List("MANAGED",value) if value==hash => IO.unit
        case List("MISSING") => files.replace(path,marker,bytes,None,installOwned=rawPinned)
        case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_CADDY_REPOSITORY_UNSAFE"))
      }
    }
  }
}

private[ssh] object ProfilePackageInstaller {
  val KeyHash="783dfee04b19e851a928cd87b34710213ebbe7628f98d9f34595ab83be578c00"
  val RepositoryMarker="# InfraDesk managed: SERVER_PROFILE CADDY REPOSITORY v1"
  val Repository=RepositoryMarker+"\ndeb [signed-by=/etc/apt/keyrings/infradesk-caddy.asc] https://dl.cloudsmith.io/public/caddy/stable/deb/debian any-version main\n"
  private[ssh] val Simulate="""set -eu
    simulation=$(apt-get -s --no-upgrade --no-remove install -- "$@") || exit 53
    if printf '%s\n' "$simulation" | grep -q '^Remv '; then exit 54; fi
    for package in $(printf '%s\n' "$simulation" | awk '$1=="Inst" {print $2}'); do
      status=$(dpkg-query -W -f='${db:Status-Abbrev}' "$package" 2>/dev/null || true)
      case "$status" in ii*) exit 54;; '') :;; *) exit 54;; esac
    done
  """
  private[ssh] val BinaryOwnership="""set -eu
    for binary in "$@"; do
      if command -v "$binary" >/dev/null 2>&1; then
        path=$(command -v "$binary")
        [ ! -L "$path" ] && [ -f "$path" ] || exit 55
        dpkg-query -S "$path" >/dev/null 2>&1 || exit 55
      fi
    done
  """
  private[ssh] val CaddyDefaultProof="""set -eu
    [ ! -L /etc/caddy ] && [ ! -L /etc/caddy/Caddyfile ] && [ -f /etc/caddy/Caddyfile ] || exit 56
    [ "$(stat -c '%u:%h' /etc/caddy/Caddyfile)" = '0:1' ] || exit 56
    expected=$(dpkg-query -W -f='${Conffiles}\n' caddy | awk '$1=="/etc/caddy/Caddyfile" && $2 ~ /^[0-9a-f]+$/ {print $2}')
    [ ${#expected} -eq 32 ] && [ "$(md5sum /etc/caddy/Caddyfile | cut -d' ' -f1)" = "$expected" ] || exit 56
    sha256sum /etc/caddy/Caddyfile | cut -d' ' -f1
  """
}

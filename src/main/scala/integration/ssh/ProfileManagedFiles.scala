package ru.bitec.app.ops
package integration.ssh

import application.port.{RemoteConfigurationSession, RemoteFileCreation}
import cats.effect.IO
import cats.syntax.all._
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import scala.concurrent.duration._

/** Content-free failures: output is never attached to a persisted step or application log. */
private[ssh] final case class ProfileRemoteFailure(code: String, uncertain: Boolean = false,
  truncated: Boolean = false) extends RuntimeException(code)

private[ssh] final class ProfileCommands(session: RemoteConfigurationSession[IO], root: Boolean) {
  def capture(executable: String, args: List[String], timeout: FiniteDuration = 30.seconds,
    privileged: Boolean = true): IO[application.port.RemoteCommandOutput] = {
    val argv = if (privileged && !root) "sudo" -> (List("-n", executable) ++ args) else executable -> args
    session.executeCaptured(argv._1, argv._2, timeout, 65536).flatMap { result =>
      if (result.stdoutTruncated || result.stderrTruncated)
        IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_OUTPUT_LIMIT", uncertain = true, truncated = true))
      else IO.pure(result)
    }
  }
  def shell(script: String, args: List[String] = Nil, timeout: FiniteDuration = 30.seconds,
    privileged: Boolean = true): IO[application.port.RemoteCommandOutput] =
    capture("sh", List("-c", script, "infradesk") ++ args, timeout, privileged)
}

/** Exclusive private staging, immutable input hashes and same-filesystem replacement.
  * Only backend-selected paths, renderers and validation/activation commands reach this helper.
  */
private[ssh] final class ProfileManagedFiles(session: RemoteConfigurationSession[IO], commands: ProfileCommands,
  sshUid: Int, runId: UUID) {
  import ProfileManagedFiles._

  def replace(path: String, marker: String, bytes: Array[Byte], expectedHash: Option[String],
    installOwned: Boolean = false, validate: Option[List[String]] = None,
    activate: Option[List[String]] = None, targetMode: Int = 420,
    validationFailureCode: String = "PROVISIONING_CADDY_VALIDATION_FAILED"): IO[Unit] = {
    require(allowedPath(path) && marker.nonEmpty && !marker.contains('\n') && Set(384,420)(targetMode))
    require(expectedHash.forall(_.matches("[0-9a-f]{64}")))
    val nonce = UUID.randomUUID().toString
    val userDir = s"/tmp/infradesk-$runId-$nonce"
    val upload = s"$userDir/input"
    val parent = path.substring(0, path.lastIndexOf('/'))
    val rootDir = s"$parent/.infradesk-$runId-$nonce"
    val candidate = s"$rootDir/candidate"
    val backup = s"$rootDir/previous"
    val hash = sha256(bytes)
    val expected = expectedHash.getOrElse("MISSING")
    def checked(action: IO[application.port.RemoteCommandOutput], code: String): IO[Unit] =
      action.flatMap(r => if (r.exitCode == 0) IO.unit else IO.raiseError(ProfileRemoteFailure(code)))
    val prepareUser = commands.shell("umask 077; mkdir -m 0700 -- \"$1\"", List(userDir), privileged = false)
    val prepareRoot = commands.shell(Prepare, List(path, upload, rootDir, sshUid.toString, hash, expected,
      marker, installOwned.toString, if (targetMode == 384) "0600" else "0644"))
    val cleanupUser = commands.shell("[ ! -L \"$1\" ] && [ -d \"$1\" ] && [ \"$(stat -c '%u:%a' \"$1\")\" = \"$2:700\" ] || exit 1; [ ! -L \"$1/input\" ] || exit 1; rm -f -- \"$1/input\"; rmdir -- \"$1\"",
      List(userDir, sshUid.toString), privileged = false).attempt.void
    val cleanupRoot = commands.shell("[ ! -L \"$1\" ] && [ -d \"$1\" ] && [ \"$(stat -c '%u:%a' \"$1\")\" = '0:700' ] || exit 1; for f in candidate previous; do [ ! -L \"$1/$f\" ] || exit 1; done; rm -f -- \"$1/candidate\" \"$1/previous\"; rmdir -- \"$1\"",
      List(rootDir)).attempt.void
    checked(prepareUser, "PROVISIONING_MANAGED_FILE_UNSAFE") *> (
      session.create(upload, bytes, RemoteFileCreation(Integer.parseInt("600", 8), None)) *>
      checked(prepareRoot, "PROVISIONING_MANAGED_FILE_UNSAFE") *>
      validate.traverse_(argv => checked(commands.capture(argv.head,
        if (argv.exists(_ == "{candidate}")) argv.tail.map(a => if (a == "{candidate}") candidate else a) else argv.tail :+ candidate),
        validationFailureCode)) *>
      checked(commands.shell(Commit, List(path, candidate, backup, expected, hash)),
        "PROVISIONING_MANAGED_FILE_CHANGED") *>
      activate.traverse_ { argv => commands.capture(argv.head, argv.tail).flatMap { activation =>
        if (activation.exitCode == 0) IO.unit
        else if (expectedHash.isEmpty) IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_MUTATION_UNCERTAIN", uncertain = true))
        else commands.shell(Rollback, List(path, backup, expected, hash)).flatMap { restored =>
          if (restored.exitCode != 0) IO.raiseError(ProfileRemoteFailure("PROVISIONING_CADDY_ROLLBACK_UNCERTAIN", uncertain = true))
          else commands.capture(argv.head, argv.tail).flatMap { reactivated =>
            IO.raiseError(ProfileRemoteFailure(if (reactivated.exitCode == 0) "PROVISIONING_CADDY_RELOAD_FAILED"
              else "PROVISIONING_CADDY_ROLLBACK_UNCERTAIN", uncertain = reactivated.exitCode != 0))
          }
        }
      }}
    ).guarantee(cleanupRoot *> cleanupUser)
  }
}

private[ssh] object ProfileManagedFiles {
  def sha256(bytes: Array[Byte]): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .map(b => f"${b & 0xff}%02x").mkString
  def allowedPath(path: String): Boolean = Set("/etc/sysctl.d/99-infradesk.conf",
    "/etc/security/limits.d/99-infradesk.conf", "/etc/systemd/system.conf.d/99-infradesk.conf",
    "/etc/fail2ban/jail.d/99-infradesk.conf", "/etc/caddy/Caddyfile",
    "/etc/apt/keyrings/infradesk-caddy.asc", "/etc/apt/sources.list.d/infradesk-caddy.list")(path) ||
    path.matches("/opt/infradesk/remnawave/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(compose\\.yml|\\.env|managed\\.json)") ||
    path.matches("/var/www/infradesk/[a-z0-9][a-z0-9-]{0,62}/index.html")
  private val SafeParents = """
    safe_parents() {
      p=$(dirname "$1"); chain='';
      while [ "$p" != / ]; do chain="$p $chain"; p=$(dirname "$p"); done
      for p in $chain; do
        [ ! -L "$p" ] || return 1
        if [ ! -e "$p" ]; then mkdir -m 0755 -- "$p" || return 1; fi
        [ -d "$p" ] && [ "$(stat -c '%u' "$p")" = 0 ] || return 1
        mode=$(stat -c '%a' "$p"); [ $((0$mode & 022)) -eq 0 ] || return 1
      done
    }
    safe_file() {
      [ ! -L "$1" ] && [ -f "$1" ] && [ "$(stat -c '%u:%h' "$1")" = '0:1' ] || return 1
      mode=$(stat -c '%a' "$1"); [ $((0$mode & 022)) -eq 0 ]
    }
    hash_file() { sha256sum "$1" | cut -d' ' -f1; }
  """
  private[ssh] val Prepare = "set -eu; " + SafeParents + """
    dest=$1; source=$2; stage=$3; uid=$4; newhash=$5; expected=$6; marker=$7; installowned=$8; target_mode=$9
    safe_parents "$dest" || exit 42
    [ ! -L "$(dirname "$source")" ] && [ "$(stat -c '%u:%a' "$(dirname "$source")")" = "$uid:700" ] || exit 42
    [ ! -L "$source" ] && [ -f "$source" ] && [ "$(stat -c '%u:%a:%h' "$source")" = "$uid:600:1" ] || exit 42
    [ "$(hash_file "$source")" = "$newhash" ] || exit 42
    if [ -e "$dest" ] || [ -L "$dest" ]; then
      safe_file "$dest" && [ "$expected" != MISSING ] && [ "$(hash_file "$dest")" = "$expected" ] || exit 43
      if [ "$installowned" != true ]; then IFS= read -r first < "$dest" || true; [ "$first" = "$marker" ] || exit 43; fi
    else [ "$expected" = MISSING ] || exit 43; fi
    umask 077; mkdir -m 0700 -- "$stage" || exit 42
    [ "$(stat -c '%u:%a' "$stage")" = '0:700' ] || exit 42
    install -o root -g root -m "$target_mode" -- "$source" "$stage/candidate"
    safe_file "$stage/candidate" && [ "$(hash_file "$stage/candidate")" = "$newhash" ] || exit 42
  """
  private[ssh] val Commit = "set -eu; " + SafeParents + """
    dest=$1; candidate=$2; backup=$3; expected=$4; newhash=$5
    safe_parents "$dest" && safe_file "$candidate" && [ "$(hash_file "$candidate")" = "$newhash" ] || exit 42
    [ ! -L "$(dirname "$candidate")" ] && [ "$(stat -c '%u:%a' "$(dirname "$candidate")")" = '0:700' ] || exit 42
    if [ "$expected" = MISSING ]; then [ ! -e "$dest" ] && [ ! -L "$dest" ] || exit 43
    else safe_file "$dest" && [ "$(hash_file "$dest")" = "$expected" ] || exit 43; cp -p -- "$dest" "$backup"; safe_file "$backup" && [ "$(hash_file "$backup")" = "$expected" ] || exit 43;
      safe_file "$dest" && [ "$(hash_file "$dest")" = "$expected" ] || exit 43; fi
    mv -fT -- "$candidate" "$dest"
  """
  private[ssh] val Rollback = "set -eu; " + SafeParents + """
    dest=$1; backup=$2; expected=$3; newhash=$4
    safe_parents "$dest" && safe_file "$dest" && safe_file "$backup" || exit 42
    [ ! -L "$(dirname "$backup")" ] && [ "$(stat -c '%u:%a' "$(dirname "$backup")")" = '0:700' ] || exit 42
    [ "$(hash_file "$dest")" = "$newhash" ] && [ "$(hash_file "$backup")" = "$expected" ] || exit 43
    mv -fT -- "$backup" "$dest"
  """
}

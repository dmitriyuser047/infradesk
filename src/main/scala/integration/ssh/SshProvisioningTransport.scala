package ru.bitec.app.ops
package integration.ssh

import application.port.{ProvisioningStepResult, ProvisioningTransport, SshCredentialResolver}
import cats.effect.IO
import cats.syntax.all._
import domain.connection.{Connection, SshConnectionSettings}
import scala.concurrent.duration._

/** Fixed, read-only readiness probes. Outputs are parsed immediately into an allowlisted schema. */
final class SshProvisioningTransport(client: SshClient[IO], credentials: SshCredentialResolver[IO],
  stepTimeoutSeconds: Int) extends ProvisioningTransport[IO] {
  private val timeout = stepTimeoutSeconds.seconds
  private val probes = List(
    "grep -E '^(ID|VERSION_ID)=' /etc/os-release",
    "uname -m",
    "id -u",
    "sudo -n true",
    "awk '/^MemTotal:/ {print $2}' /proc/meminfo",
    "df -Pk / | awk 'NR==2 {print $4}'",
    "for x in sh apt-get systemctl awk df grep; do command -v \"$x\" >/dev/null 2>&1 && echo \"$x=1\" || echo \"$x=0\"; done",
    "command -v docker >/dev/null 2>&1",
    "systemctl is-system-running"
  )
  private val expectedCommands = Set("sh", "apt-get", "systemctl", "awk", "df", "grep")

  override def preflight(connection: Connection): IO[ProvisioningStepResult] = observe(connection).map(assess)

  private[ssh] def assess(observed: ProvisioningStepResult): ProvisioningStepResult = {
    val facts = observed.facts
    val supported = (facts.get("os") == Some("debian") && facts.get("version") == Some("12")) ||
      (facts.get("os") == Some("ubuntu") && Set("22.04", "24.04")(facts.getOrElse("version", "")))
    val architecture = facts.get("architecture").exists(Set("amd64", "x86_64"))
    val privileged = facts.get("root") == Some("true") || facts.get("sudoAvailable") == Some("true")
    val checks = List(
      supported -> "PROVISIONING_UNSUPPORTED_PLATFORM",
      architecture -> "PROVISIONING_UNSUPPORTED_ARCHITECTURE",
      privileged -> "PROVISIONING_PRIVILEGE_REQUIRED",
      (facts.get("memoryAvailable") == Some("true")) -> "PROVISIONING_MEMORY_INSUFFICIENT",
      (facts.get("diskAvailable") == Some("true")) -> "PROVISIONING_DISK_INSUFFICIENT",
      (facts.get("commandsAvailable") == Some("true")) -> "PROVISIONING_COMMANDS_MISSING",
      (facts.get("systemdAvailable") == Some("true")) -> "PROVISIONING_SYSTEMD_UNAVAILABLE"
    )
    observed.failureCode.fold(checks.collectFirst { case (false, code) => code }) { Some(_) }.orElse(None) match {
      case Some(code) => observed.copy(failureCode = Some(code), verificationResult = Some(false))
      case None => observed.copy(failureCode = None, verificationResult = Some(true))
    }
  }

  override def verify(connection: Connection): IO[ProvisioningStepResult] = preflight(connection).map { result =>
    result.copy(failureCode = result.failureCode.orElse(Option.when(result.verificationResult != Some(true))(
      "PROVISIONING_VERIFICATION_FAILED")), verificationResult = Some(result.failureCode.isEmpty))
  }

  private def observe(connection: Connection): IO[ProvisioningStepResult] = {
    val action = for {
      settings <- IO.fromEither(SshConnectionSettings.from(connection.config))
      credential <- credentials.resolveCredential(connection)
      results <- client.withSession(SshConnectionConfig.fromSettings(settings), SshAuthentication.from(credential))(
        session => probes.traverse(command => session.executeBounded(command, stepTimeoutSeconds, 4096, 1024)))
      result <- IO.pure(parseResults(results))
    } yield result
    action.timeoutTo(timeout, IO.pure(ProvisioningStepResult(Map.empty,
      Some("PROVISIONING_REMOTE_TIMEOUT"), None, uncertain = true))).handleErrorWith {
      case _: SshTransportFailure.CommandTimeout => IO.pure(ProvisioningStepResult(Map.empty,
        Some("PROVISIONING_REMOTE_TIMEOUT"), None, uncertain = true))
      case _: SshTransportFailure.CommandOutputLimitExceeded => IO.pure(ProvisioningStepResult(Map.empty,
        Some("PROVISIONING_REMOTE_OUTPUT_LIMIT"), None, uncertain = true))
      case _: SshTransportFailure.HostKeyMismatch => IO.pure(failed("PROVISIONING_HOST_KEY_MISMATCH"))
      case _: SshTransportFailure.HostKeyNotTrusted => IO.pure(failed("PROVISIONING_HOST_KEY_NOT_TRUSTED"))
      case _: SshTransportFailure.AuthenticationFailed => IO.pure(failed("PROVISIONING_AUTHENTICATION_FAILED"))
      case _ => IO.pure(ProvisioningStepResult(Map.empty, Some("PROVISIONING_REMOTE_UNAVAILABLE"), None, uncertain = true))
    }
  }

  private[ssh] def parseResults(results: List[SshCommandResult]): ProvisioningStepResult = {
    if (results.size != probes.size || results.exists(r => r.stdoutTruncated || r.stderrTruncated))
      ProvisioningStepResult(Map.empty, Some("PROVISIONING_REMOTE_OUTPUT_LIMIT"), None, uncertain = true,
        outputTruncated = true)
    else {
      val output = results.head.stdout.linesIterator.map(_.trim).toList
      val osPairs = output.flatMap(_.split("=", 2).toList match {
        case key :: raw :: Nil if Set("ID", "VERSION_ID")(key) => Some(key -> raw.stripPrefix("\"").stripSuffix("\""))
        case _ => None
      })
      val osValues = osPairs.toMap
      def value(key: String): Option[String] = osValues.get(key)
      def safeOs(raw: Option[String]): String = raw.filter(Set("debian", "ubuntu")).getOrElse("unsupported")
      def safeVersion(os: String, raw: Option[String]): String =
        if (os == "debian" && raw.contains("12")) "12"
        else if (os == "ubuntu" && Set("22.04", "24.04")(raw.getOrElse(""))) raw.get
        else "unsupported"
      val uid = results(2).stdout.trim.toIntOption
      val memory = results(4).stdout.trim.toLongOption
      val disk = results(5).stdout.trim.toLongOption
      val commandLines = results(6).stdout.linesIterator.toList.flatMap(_.split("=", 2).toList match {
        case key :: flag :: Nil if expectedCommands(key) && (flag == "0" || flag == "1") => Some(key -> flag)
        case _ => None
      })
      val commandMap = commandLines.toMap
      val commandSetValid = commandLines.size == expectedCommands.size && commandMap.keySet == expectedCommands && commandMap.values.forall(_ == "1")
      val systemd = Set("running", "degraded")(results(8).stdout.trim)
      val os = safeOs(value("ID"))
      val version = safeVersion(os, value("VERSION_ID"))
      val osProbeValid = osPairs.size == 2 && osPairs.map(_._1).toSet == Set("ID", "VERSION_ID") &&
        value("ID").exists(_.matches("[a-z][a-z0-9_-]{0,31}")) &&
        value("VERSION_ID").exists(_.matches("[0-9][0-9.]{0,11}"))
      val requiredProbeExitCodesValid = List(0, 1, 2, 4, 5, 6).forall(index => results(index).exitCode == 0)
      def availableMiB(kib: Option[Long]): String = kib.filter(_ >= 0)
        .map(value => math.min(value / 1024L, 1024L * 1024L).toString).getOrElse("unknown")
      val facts = Map("os" -> os, "version" -> version,
        "architecture" -> (if (Set("amd64", "x86_64")(results(1).stdout.trim)) results(1).stdout.trim else "unsupported"),
        "root" -> uid.contains(0).toString,
        "sudoAvailable" -> ((uid.contains(0) || results(3).exitCode == 0).toString),
        "memoryAvailable" -> memory.exists(_ >= 512L * 1024L).toString,
        "memoryMiB" -> availableMiB(memory),
        "diskAvailable" -> disk.exists(_ >= 1024L * 1024L).toString,
        "diskFreeMiB" -> availableMiB(disk),
        "commandsAvailable" -> commandSetValid.toString,
        "dockerInstalled" -> (results(7).exitCode == 0).toString,
        "systemdAvailable" -> systemd.toString)
      if (!requiredProbeExitCodesValid || uid.forall(_ < 0) || memory.forall(_ < 0) || disk.forall(_ < 0) || !osProbeValid)
        ProvisioningStepResult(facts, Some("PROVISIONING_REMOTE_PROBE_INVALID"), None, uncertain = false)
      else ProvisioningStepResult(facts, None, None)
    }
  }

  private def failed(code: String) = ProvisioningStepResult(Map.empty, Some(code), None, uncertain = false)
}

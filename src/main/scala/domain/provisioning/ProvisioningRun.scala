package ru.bitec.app.ops
package domain.provisioning

import java.time.Instant
import java.util.UUID

sealed trait ProvisioningRunState { def code: String; def terminal: Boolean }
sealed trait ProvisioningRunKind { def code: String }
object ProvisioningRunKind {
  case object ServerBaselineCheck extends ProvisioningRunKind { val code = "SERVER_BASELINE_CHECK" }
  case object ServerProfileApply extends ProvisioningRunKind { val code = "SERVER_PROFILE_APPLY" }
  val all = List(ServerBaselineCheck, ServerProfileApply)
  def fromCode(code: String): ProvisioningRunKind = all.find(_.code == code).getOrElse(throw new IllegalArgumentException("Unknown provisioning run kind"))
}
object ProvisioningRunState {
  case object Planned extends ProvisioningRunState { val code = "PLANNED"; val terminal = false }
  case object Queued extends ProvisioningRunState { val code = "QUEUED"; val terminal = false }
  case object Running extends ProvisioningRunState { val code = "RUNNING"; val terminal = false }
  case object Succeeded extends ProvisioningRunState { val code = "SUCCEEDED"; val terminal = true }
  case object Failed extends ProvisioningRunState { val code = "FAILED"; val terminal = true }
  case object Unknown extends ProvisioningRunState { val code = "UNKNOWN"; val terminal = true }
  val all = List(Planned, Queued, Running, Succeeded, Failed, Unknown)
  def fromCode(code: String): ProvisioningRunState = all.find(_.code == code).getOrElse(
    throw new IllegalArgumentException(s"Unknown provisioning state: $code"))
}

sealed trait ProvisioningStepKind { def code: String; def order: Int }
object ProvisioningStepKind {
  case object Preflight extends ProvisioningStepKind { val code = "PREFLIGHT"; val order = 0 }
  case object InstallPackages extends ProvisioningStepKind { val code = "INSTALL_PACKAGES"; val order = 1 }
  case object ConfigureNetwork extends ProvisioningStepKind { val code = "CONFIGURE_NETWORK"; val order = 2 }
  case object ConfigureLimits extends ProvisioningStepKind { val code = "CONFIGURE_LIMITS"; val order = 3 }
  case object ConfigureFirewall extends ProvisioningStepKind { val code = "CONFIGURE_FIREWALL"; val order = 4 }
  case object ConfigureFail2ban extends ProvisioningStepKind { val code = "CONFIGURE_FAIL2BAN"; val order = 5 }
  case object ConfigureDocker extends ProvisioningStepKind { val code = "CONFIGURE_DOCKER"; val order = 6 }
  case object DeploySite extends ProvisioningStepKind { val code = "DEPLOY_SITE"; val order = 7 }
  case object ConfigureCaddy extends ProvisioningStepKind { val code = "CONFIGURE_CADDY"; val order = 8 }
  case object Verify extends ProvisioningStepKind { val code = "VERIFY"; val order = 1 }
  val Baseline = List(Preflight, Verify)
  val ProfileApply = List(Preflight, InstallPackages, ConfigureNetwork, ConfigureLimits, ConfigureFirewall,
    ConfigureFail2ban, ConfigureDocker, DeploySite, ConfigureCaddy, Verify)
  val all = List(Preflight, InstallPackages, ConfigureNetwork, ConfigureLimits, ConfigureFirewall,
    ConfigureFail2ban, ConfigureDocker, DeploySite, ConfigureCaddy, Verify)
  def fromCode(code: String): ProvisioningStepKind = all.find(_.code == code).getOrElse(
    throw new IllegalArgumentException(s"Unknown provisioning step: $code"))
}

sealed trait ProvisioningStepState { def code: String }
object ProvisioningStepState {
  case object Pending extends ProvisioningStepState { val code = "PENDING" }
  case object Running extends ProvisioningStepState { val code = "RUNNING" }
  case object Succeeded extends ProvisioningStepState { val code = "SUCCEEDED" }
  case object Failed extends ProvisioningStepState { val code = "FAILED" }
  case object Skipped extends ProvisioningStepState { val code = "SKIPPED" }
  case object Unknown extends ProvisioningStepState { val code = "UNKNOWN" }
  val all = List(Pending, Running, Succeeded, Failed, Skipped, Unknown)
  def fromCode(code: String): ProvisioningStepState = all.find(_.code == code).getOrElse(
    throw new IllegalArgumentException(s"Unknown provisioning step state: $code"))
}

object ProvisioningSafeMessage {
  def forCode(code: Option[String]): Option[String] = code.map {
    case "PROVISIONING_UNSUPPORTED_PLATFORM" | "PROVISIONING_UNSUPPORTED_ARCHITECTURE" =>
      "This server does not meet the supported platform requirements."
    case "PROVISIONING_PRIVILEGE_REQUIRED" => "Root access or non-interactive sudo is required."
    case "PROVISIONING_MEMORY_INSUFFICIENT" => "The server needs at least 512 MiB of memory."
    case "PROVISIONING_DISK_INSUFFICIENT" => "The server needs at least 1 GiB of free disk space."
    case "PROVISIONING_COMMANDS_MISSING" | "PROVISIONING_SYSTEMD_UNAVAILABLE" =>
      "A required system command or service manager is unavailable."
    case "PROVISIONING_VERIFICATION_FAILED" => "The independent readiness check did not pass."
    case "PROVISIONING_SOURCE_CHANGED" | "PROVISIONING_TARGET_NOT_FOUND" => "The selected SSH source changed after planning."
    case "PROVISIONING_HOST_KEY_MISMATCH" | "PROVISIONING_HOST_KEY_NOT_TRUSTED" =>
      "The SSH host identity is not trusted."
    case "PROVISIONING_AUTHENTICATION_FAILED" => "SSH authentication failed."
    case "PROVISIONING_REMOTE_TIMEOUT" | "PROVISIONING_REMOTE_UNAVAILABLE" | "PROVISIONING_REMOTE_OUTPUT_LIMIT" =>
      "The remote check ended without a reliable result. Confirm the server state before continuing."
    case _ => "The readiness check could not be completed."
  }
}

/** Immutable approval snapshot. It intentionally contains no credentials or remote output. */
final case class ProvisioningInputSnapshot(schemaVersion: Int, runKind: ProvisioningRunKind,
  organizationId: UUID, resourceId: UUID,
  resourceType: String, resourceKind: String, connectionId: UUID, connectionUpdatedAt: Instant,
  steps: List[ProvisioningStepKind], profileApply: Option[ServerProfileApplySnapshot] = None)

/** Everything approved for one explicit profile apply. No SSH secret or remote output is stored. */
final case class ServerProfileApplySnapshot(assignmentId: UUID, assignmentVersion: Long, profileId: UUID,
  revisionId: UUID, revisionNumber: Int, revisionHash: String, content: ServerProfileContent,
  observationId: UUID, observationHash: String, reviewedDiffHash: String,
  blockingProblems: List[String] = Nil)

final case class ProvisioningRun(id: UUID, organizationId: UUID, resourceId: UUID,
  requestId: Option[UUID], requestedBy: Option[UUID], input: ProvisioningInputSnapshot, state: ProvisioningRunState,
  createdAt: Instant, updatedAt: Instant, failureCode: Option[String] = None,
  claimToken: Option[UUID] = None, leaseUntil: Option[Instant] = None,
  currentStep: Option[ProvisioningStepKind] = None, safeMessage: Option[String] = None,
  startedAt: Option[Instant] = None, finishedAt: Option[Instant] = None)

final case class ProvisioningStep(runId: UUID, kind: ProvisioningStepKind, state: ProvisioningStepState,
  startedAt: Option[Instant] = None, finishedAt: Option[Instant] = None,
  facts: Map[String, String] = Map.empty, failureCode: Option[String] = None,
  attempt: Int = 0, safeMessage: Option[String] = None, outputSummary: Option[String] = None,
  verificationResult: Option[Boolean] = None, outputTruncated: Boolean = false,
  storedPosition: Option[Int] = None) {
  def id: UUID = UUID.nameUUIDFromBytes((runId.toString + ":" + kind.code).getBytes(java.nio.charset.StandardCharsets.UTF_8))
  def position: Int = storedPosition.getOrElse(kind.order)
  def displayName: String = kind.code
}

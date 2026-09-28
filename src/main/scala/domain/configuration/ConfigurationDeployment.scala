package ru.bitec.app.ops
package domain.configuration

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

sealed trait ConfigurationDeploymentState { def code: String; def terminal: Boolean }
object ConfigurationDeploymentState {
  case object Queued extends ConfigurationDeploymentState { val code = "QUEUED"; val terminal = false }
  case object Running extends ConfigurationDeploymentState { val code = "RUNNING"; val terminal = false }
  case object Succeeded extends ConfigurationDeploymentState { val code = "SUCCEEDED"; val terminal = true }
  case object Failed extends ConfigurationDeploymentState { val code = "FAILED"; val terminal = true }
  case object RolledBack extends ConfigurationDeploymentState { val code = "ROLLED_BACK"; val terminal = true }
  case object RollbackFailed extends ConfigurationDeploymentState { val code = "ROLLBACK_FAILED"; val terminal = true }
  case object Cancelled extends ConfigurationDeploymentState { val code = "CANCELLED"; val terminal = true }
  val all: List[ConfigurationDeploymentState] =
    List(Queued, Running, Succeeded, Failed, RolledBack, RollbackFailed, Cancelled)
  def fromCode(code: String): Either[IllegalArgumentException, ConfigurationDeploymentState] =
    all.find(_.code == code).toRight(new IllegalArgumentException("Invalid deployment state"))
}

sealed trait ConfigurationDeploymentPhase { def code: String }
object ConfigurationDeploymentPhase {
  case object Precheck extends ConfigurationDeploymentPhase { val code = "PRECHECK" }
  case object Upload extends ConfigurationDeploymentPhase { val code = "UPLOAD" }
  case object Validate extends ConfigurationDeploymentPhase { val code = "VALIDATE" }
  case object Replace extends ConfigurationDeploymentPhase { val code = "REPLACE" }
  case object Activate extends ConfigurationDeploymentPhase { val code = "ACTIVATE" }
  case object Verify extends ConfigurationDeploymentPhase { val code = "VERIFY" }
  case object Cleanup extends ConfigurationDeploymentPhase { val code = "CLEANUP" }
  case object Rollback extends ConfigurationDeploymentPhase { val code = "ROLLBACK" }
  val all: List[ConfigurationDeploymentPhase] =
    List(Precheck, Upload, Validate, Replace, Activate, Verify, Cleanup, Rollback)
  def fromCode(code: String): Either[IllegalArgumentException, ConfigurationDeploymentPhase] =
    all.find(_.code == code).toRight(new IllegalArgumentException("Invalid deployment phase"))
}

sealed trait ConfigurationActivation { def code: String }
object ConfigurationActivation {
  case object None extends ConfigurationActivation { val code = "NONE" }
  case object SystemdReload extends ConfigurationActivation { val code = "SYSTEMD_RELOAD" }
  case object SystemdRestart extends ConfigurationActivation { val code = "SYSTEMD_RESTART" }
  val all: List[ConfigurationActivation] = List(None, SystemdReload, SystemdRestart)
  def fromCode(code: String): Either[IllegalArgumentException, ConfigurationActivation] =
    all.find(_.code == code).toRight(new IllegalArgumentException("Invalid activation"))
}

final case class ConfigurationValidator(executable: String, args: List[String])
final case class ConfigurationExecutionPolicy(
  activation: ConfigurationActivation,
  unitName: Option[String],
  validator: Option[ConfigurationValidator],
  newFileMode: Int
)
object ConfigurationExecutionPolicy {
  val Default: ConfigurationExecutionPolicy = ConfigurationExecutionPolicy(ConfigurationActivation.None, None, None, 420)
  private val UnitPattern = "[A-Za-z0-9_.@:-]+\\.service"
  private val AbsoluteExecutable = "/[A-Za-z0-9_./+-]+"

  def validate(policy: ConfigurationExecutionPolicy): Either[IllegalArgumentException, ConfigurationExecutionPolicy] = {
    val unitValid = policy.activation match {
      case ConfigurationActivation.None => policy.unitName.isEmpty
      case _ => policy.unitName.exists(name => name.length <= 255 && name.matches(UnitPattern))
    }
    val validatorValid = policy.validator.forall(v =>
      v.executable.length <= 4096 && v.executable.matches(AbsoluteExecutable) &&
        !v.executable.split('/').contains("..") && v.args.length <= 32 &&
        v.args.forall(arg => arg.length <= 4096 && !arg.exists(_ < ' ') &&
          !arg.contains("$") && !arg.contains('`') && !arg.contains("{{")))
    if (unitValid && validatorValid && policy.newFileMode >= 0 && policy.newFileMode <= 511) Right(policy)
    else Left(new IllegalArgumentException("Invalid configuration execution policy"))
  }
}

sealed trait ExpectedRemoteState
object ExpectedRemoteState {
  case object Missing extends ExpectedRemoteState
  final case class Sha256(value: String) extends ExpectedRemoteState
  def validate(value: ExpectedRemoteState): Either[IllegalArgumentException, ExpectedRemoteState] = value match {
    case Missing => Right(Missing)
    case hash @ Sha256(text) if text.matches("[0-9a-f]{64}") => Right(hash)
    case _ => Left(new IllegalArgumentException("Invalid remote hash"))
  }
}

final case class ConfigurationDeployment(
  id: UUID,
  organizationId: UUID,
  requestId: UUID,
  assignmentId: UUID,
  assignmentVersion: Int,
  resourceId: UUID,
  profileId: UUID,
  profileRevisionNumber: Int,
  targetPath: String,
  values: List[ConfigurationVariableValue],
  desiredSha256: String,
  connectionId: UUID,
  connectionUpdatedAt: Instant,
  expectedRemoteState: ExpectedRemoteState,
  policy: ConfigurationExecutionPolicy,
  actorUserId: UUID,
  createdAt: Instant,
  state: ConfigurationDeploymentState,
  phase: ConfigurationDeploymentPhase,
  leaseOwner: Option[UUID],
  leaseToken: Option[UUID],
  leaseExpiresAt: Option[Instant],
  startedAt: Option[Instant],
  finishedAt: Option[Instant],
  failureCode: Option[String],
  cancelRequested: Boolean,
  rolloutId: Option[UUID],
  rolloutItemId: Option[UUID]
)

object ConfigurationDeployment {
  def sha256(text: String): String =
    sha256Bytes(text.getBytes(StandardCharsets.UTF_8))

  def sha256Bytes(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
      .map(byte => f"${byte & 0xff}%02x").mkString

  /** Paths are derived from the server-approved target and generated deployment ID only. */
  def artifactPaths(targetPath: String, deploymentId: UUID): (String, String) = {
    val parent = targetPath.substring(0, targetPath.lastIndexOf('/') + 1)
    (s"${parent}.infradesk-$deploymentId.tmp", s"${parent}.infradesk-$deploymentId.bak")
  }
}

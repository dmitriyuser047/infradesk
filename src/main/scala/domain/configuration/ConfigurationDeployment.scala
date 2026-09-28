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

/** Where a deployment is. Phases up to and including REPLACE happen before the target can have
  * changed; from REPLACE on, the target may hold the desired file and only a rollback or a
  * successful verification may end the deployment.
  */
sealed trait ConfigurationDeploymentPhase { def code: String; def order: Int }
object ConfigurationDeploymentPhase {
  case object Precheck extends ConfigurationDeploymentPhase { val code = "PRECHECK"; val order = 0 }
  case object Upload extends ConfigurationDeploymentPhase { val code = "UPLOAD"; val order = 1 }
  case object Validate extends ConfigurationDeploymentPhase { val code = "VALIDATE"; val order = 2 }
  case object Replace extends ConfigurationDeploymentPhase { val code = "REPLACE"; val order = 3 }
  case object Activate extends ConfigurationDeploymentPhase { val code = "ACTIVATE"; val order = 4 }
  case object Verify extends ConfigurationDeploymentPhase { val code = "VERIFY"; val order = 5 }
  case object Cleanup extends ConfigurationDeploymentPhase { val code = "CLEANUP"; val order = 6 }
  case object Rollback extends ConfigurationDeploymentPhase { val code = "ROLLBACK"; val order = 7 }
  val all: List[ConfigurationDeploymentPhase] =
    List(Precheck, Upload, Validate, Replace, Activate, Verify, Cleanup, Rollback)
  def fromCode(code: String): Either[IllegalArgumentException, ConfigurationDeploymentPhase] =
    all.find(_.code == code).toRight(new IllegalArgumentException("Invalid deployment phase"))

  /** Whether the service may already have been told to use the new file. */
  def mayHaveActivated(phase: ConfigurationDeploymentPhase): Boolean = phase.order >= Activate.order
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

/** A structured validator: an absolute executable and literal arguments. The only substitutions are
  * the whole-token-free placeholders `{candidate}` and `{target}`, replaced by server-generated paths.
  */
final case class ConfigurationValidator(executable: String, args: List[String]) {
  def argv(candidate: String, target: String): List[String] =
    args.map(_.replace(ConfigurationValidator.Candidate, candidate).replace(ConfigurationValidator.Target, target))
}
object ConfigurationValidator {
  val Candidate = "{candidate}"
  val Target = "{target}"
  val MaxArgs = 32
  val MaxArgLength = 1024
  val MaxExecutableLength = 1024
  private val Executable = "/[A-Za-z0-9_.+-]+(/[A-Za-z0-9_.+-]+)*"

  def valid(validator: ConfigurationValidator): Boolean =
    validator.executable.length <= MaxExecutableLength && validator.executable.matches(Executable) &&
      !validator.executable.split('/').exists(segment => segment == "." || segment == "..") &&
      validator.args.length <= MaxArgs && validator.args.forall(validArg)

  /** No control characters, no shell or template syntax, and braces only as an allowed placeholder. */
  private def validArg(arg: String): Boolean = {
    val withoutPlaceholders = arg.replace(Candidate, "").replace(Target, "")
    arg.length <= MaxArgLength && !arg.exists(c => c < ' ' || c == '\u007f') &&
      !withoutPlaceholders.exists(c => c == '{' || c == '}' || c == '$' || c == '`')
  }
}

final case class ConfigurationExecutionPolicy(
  activation: ConfigurationActivation,
  unitName: Option[String],
  validator: Option[ConfigurationValidator],
  newFileMode: Int
)
object ConfigurationExecutionPolicy {
  val DefaultFileMode: Int = Integer.parseInt("644", 8)
  val Default: ConfigurationExecutionPolicy = ConfigurationExecutionPolicy(ConfigurationActivation.None, None, None, DefaultFileMode)
  /** Plain permission bits only: setuid, setgid and sticky are never produced in this version. */
  val MaxFileMode: Int = Integer.parseInt("777", 8)
  private val UnitPattern = "[A-Za-z0-9_.@:-]+\\.service"

  def validUnit(name: String): Boolean =
    name.length <= 255 && name.matches(UnitPattern) && !name.startsWith("-")

  def validate(policy: ConfigurationExecutionPolicy): Either[IllegalArgumentException, ConfigurationExecutionPolicy] = {
    val unitValid = policy.activation match {
      case ConfigurationActivation.None => policy.unitName.isEmpty
      case _ => policy.unitName.exists(validUnit)
    }
    val validatorValid = policy.validator.forall(ConfigurationValidator.valid)
    if (unitValid && validatorValid && policy.newFileMode >= 0 && policy.newFileMode <= MaxFileMode) Right(policy)
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
  def of(sha256: Option[String]): ExpectedRemoteState = sha256.fold[ExpectedRemoteState](Missing)(Sha256.apply)
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
  rolloutItemId: Option[UUID],
  retryOfDeploymentId: Option[UUID] = None,
  /** The phase in which the failure that started a rollback happened. */
  rollbackFromPhase: Option[ConfigurationDeploymentPhase] = None,
  transientAttempts: Int = 0,
  backupRetained: Boolean = false
)

object ConfigurationDeployment {
  def sha256(text: String): String =
    sha256Bytes(text.getBytes(StandardCharsets.UTF_8))

  def sha256Bytes(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
      .map(byte => f"${byte & 0xff}%02x").mkString

  /** Remote artifacts live next to the target, so a rename never crosses a filesystem. Their names
    * are derived from the server-approved target and the generated deployment ID only; no path from
    * a client is ever removed.
    */
  final case class ArtifactPaths(temporary: String, backup: String)

  def artifactPaths(targetPath: String, deploymentId: UUID): ArtifactPaths = {
    val parent = targetPath.substring(0, targetPath.lastIndexOf('/') + 1)
    ArtifactPaths(s"${parent}.infradesk-$deploymentId.tmp", s"${parent}.infradesk-$deploymentId.bak")
  }
}

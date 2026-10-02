package ru.bitec.app.ops
package application.port

import domain.configuration.{
  ConfigurationDeployment,
  ConfigurationDeploymentPhase,
  ConfigurationDeploymentState
}
import domain.connection.Connection

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

/** The chosen connection must be an active SSH source of this exact resource and tenant. */
sealed trait ConfigurationDeploymentSource
object ConfigurationDeploymentSource {
  case object Missing extends ConfigurationDeploymentSource
  case object NotSource extends ConfigurationDeploymentSource
  final case class Ready(connection: Connection) extends ConfigurationDeploymentSource
}
trait ConfigurationDeploymentSourceQuery[F[_]] {
  def resolve(organizationId: UUID, resourceId: UUID, connectionId: UUID): F[ConfigurationDeploymentSource]
}

sealed trait ConfigurationDeploymentInsert
object ConfigurationDeploymentInsert {
  case object Written extends ConfigurationDeploymentInsert
  case object AlreadyActive extends ConfigurationDeploymentInsert
  final case class Repeated(id: UUID) extends ConfigurationDeploymentInsert
}

/** One durable lifecycle event of a deployment. Events carry no file content and no command output. */
final case class ConfigurationDeploymentEvent(sequence: Int, eventType: String, occurredAt: Instant)

/** A deployment as history shows it, with the names a person recognises, read in the same statement. */
final case class ConfigurationDeploymentListItem(
  deployment: ConfigurationDeployment,
  resourceName: String,
  connectionName: String,
  actorName: String
)

/** A retained rollout backup whose rollout has finished, claimed for removal under a short lease. */
final case class ConfigurationBackupCleanup(
  organizationId: UUID,
  deploymentId: UUID,
  resourceId: UUID,
  targetPath: String,
  connectionId: UUID,
  connectionUpdatedAt: Instant,
  attempts: Int
)

/** The last deployment of an assignment that InfraDesk itself completed successfully: a historical
  * fact about what was applied, never a claim about the file as it is now.
  */
final case class ConfigurationDeploymentSummary(
  assignmentId: UUID,
  lastSucceeded: Option[(UUID, Int, String, Instant)],
  active: Option[(UUID, ConfigurationDeploymentState, ConfigurationDeploymentPhase)]
)

/** All writes run in short transactions. Claim and every later update fence on the lease token. */
trait ConfigurationDeploymentRepository[F[_]] {
  def insert(deployment: ConfigurationDeployment): F[ConfigurationDeploymentInsert]
  def find(organizationId: UUID, id: UUID): F[Option[ConfigurationDeployment]]
  def findRequest(organizationId: UUID, requestId: UUID): F[Option[ConfigurationDeployment]]

  /** Claims queued work and work whose lease has expired, skipping rows another worker holds.
    * `perOrganization` bounds how many running deployments one organization may occupy; it is a
    * fairness limit, while per-resource serialization stays a database invariant. `scope`, when
    * given, restricts claiming to one organization; production workers claim for every tenant.
    */
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int,
            perOrganization: Int, scope: Option[UUID] = None): F[List[ConfigurationDeployment]]
  def renew(organizationId: UUID, id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]
  def advance(organizationId: UUID, id: UUID, token: UUID, expected: ConfigurationDeploymentPhase,
              next: ConfigurationDeploymentPhase, now: Instant): F[Boolean]

  /** Records why the deployment is being rolled back before any restoring remote action starts, so
    * a worker that takes over resumes the rollback instead of the deployment.
    */
  def enterRollback(organizationId: UUID, id: UUID, token: UUID, expected: ConfigurationDeploymentPhase,
                    failureCode: String, now: Instant): F[Boolean]

  /** Gives the lease up until `until` after a transient remote failure; the attempt is counted. */
  def postpone(organizationId: UUID, id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]

  /** Gives the lease up immediately at a safe boundary, so another instance continues at once. */
  def release(organizationId: UUID, id: UUID, token: UUID, now: Instant): F[Boolean]

  def finish(organizationId: UUID, id: UUID, token: UUID, state: ConfigurationDeploymentState,
             failureCode: Option[String], backupRetained: Boolean, now: Instant): F[Boolean]
  def cancel(organizationId: UUID, id: UUID, now: Instant): F[Boolean]
  def queueRollback(organizationId: UUID, id: UUID, rolloutId: UUID, now: Instant): F[Boolean]
  def view(organizationId: UUID, id: UUID): F[Option[ConfigurationDeploymentListItem]]
  def history(organizationId: UUID, profileId: Option[UUID], resourceId: Option[UUID],
              before: Option[(Instant, UUID)], limit: Int): F[List[ConfigurationDeploymentListItem]]
  def events(organizationId: UUID, id: UUID): F[List[ConfigurationDeploymentEvent]]
  def summaries(organizationId: UUID, assignmentIds: List[UUID]): F[List[ConfigurationDeploymentSummary]]

  def claimBackupCleanup(now: Instant, until: Instant, limit: Int,
                         scope: Option[UUID] = None): F[List[ConfigurationBackupCleanup]]
  def finishBackupCleanup(organizationId: UUID, id: UUID, removed: Boolean, maxAttempts: Int,
                          retryAt: Instant, now: Instant): F[Unit]
}

/** Why a remote configuration action did not happen. Codes are safe to show and to store: a
  * failure never carries file content, command output or a credential.
  */
sealed abstract class RemoteConfigurationFailure(val code: String, val transient: Boolean)
  extends RuntimeException(code) with NoStackTrace

object RemoteConfigurationFailure {
  /** The host could not be reached, or the connection broke: nothing is known to have changed. */
  case object Unavailable extends RemoteConfigurationFailure("CONFIGURATION_SSH_UNAVAILABLE", transient = true)
  case object HostKeyMismatch extends RemoteConfigurationFailure("CONFIGURATION_HOST_KEY_MISMATCH", transient = false)
  case object HostKeyNotTrusted extends RemoteConfigurationFailure("CONFIGURATION_HOST_KEY_NOT_TRUSTED", transient = false)
  case object AuthenticationFailed extends RemoteConfigurationFailure("CONFIGURATION_SSH_AUTHENTICATION_FAILED", transient = false)
  case object FileTooLarge extends RemoteConfigurationFailure("CONFIGURATION_REMOTE_FILE_TOO_LARGE", transient = false)
  case object PermissionDenied extends RemoteConfigurationFailure("CONFIGURATION_REMOTE_PERMISSION_DENIED", transient = false)
  /** The target is a symbolic link, a directory or a device: replacing it would change its kind. */
  case object NotRegularFile extends RemoteConfigurationFailure("CONFIGURATION_REMOTE_NOT_REGULAR_FILE", transient = false)
  case object DirectoryMissing extends RemoteConfigurationFailure("CONFIGURATION_REMOTE_DIRECTORY_MISSING", transient = false)
  case object AtomicReplaceUnsupported extends RemoteConfigurationFailure("CONFIGURATION_ATOMIC_REPLACE_UNSUPPORTED", transient = false)
  /** The candidate could not be given the mode and owner of the file it replaces. */
  case object MetadataMismatch extends RemoteConfigurationFailure("CONFIGURATION_REMOTE_METADATA_MISMATCH", transient = false)
  case object CommandTimeout extends RemoteConfigurationFailure("CONFIGURATION_REMOTE_COMMAND_TIMEOUT", transient = false)
  case object RemoteIo extends RemoteConfigurationFailure("CONFIGURATION_REMOTE_IO_FAILED", transient = false)
}

/** Permission bits and owner of a remote file. `permissions` never includes the file type bits. */
final case class RemoteFileMetadata(permissions: Int, uid: Int, gid: Int)

/** A bounded read of one remote regular file. Bytes stay in memory for hashing and diffing only. */
final case class RemoteConfigurationFile(exists: Boolean, bytes: Array[Byte], metadata: Option[RemoteFileMetadata]) {
  lazy val sha256: Option[String] = Option.when(exists)(RemoteConfigurationFile.sha256(bytes))
}
object RemoteConfigurationFile {
  val Missing: RemoteConfigurationFile = RemoteConfigurationFile(exists = false, Array.emptyByteArray, None)

  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString
}

/** How a new remote file must look before it may take a target's place. */
final case class RemoteFileCreation(permissions: Int, owner: Option[(Int, Int)])
final case class RemoteCommandOutput(exitCode: Int, stdout: String, stderr: String,
  stdoutTruncated: Boolean = false, stderrTruncated: Boolean = false)

/** A single authenticated, host-key-pinned SSH/SFTP connection. Every call is bounded in size and
  * time; every failure is a [[RemoteConfigurationFailure]].
  */
trait RemoteConfigurationSession[F[_]] {
  /** Whether the server offers a rename that atomically replaces an existing file. */
  def supportsAtomicReplace: F[Boolean]

  /** Reads a regular file without following a final symbolic link. */
  def read(path: String, maxBytes: Int): F[RemoteConfigurationFile]

  /** Creates `path` exclusively with exactly these bytes, then gives it exactly the requested mode
    * and owner and verifies both by reading them back.
    */
  def create(path: String, bytes: Array[Byte], creation: RemoteFileCreation): F[Unit]

  /** Atomically replaces `to` with `from` (POSIX rename semantics); never delete-then-rename. */
  def atomicReplace(from: String, to: String): F[Unit]

  /** Removes a regular file when it exists. */
  def remove(path: String): F[Unit]

  /** Runs one executable with arguments, each passed as exactly one argument. Output is discarded. */
  def execute(executable: String, args: List[String], timeout: FiniteDuration): F[Int]
  /** Controlled bounded capture for backend-owned read-only probes. */
  def executeCaptured(executable: String, args: List[String], timeout: FiniteDuration,
    maxOutputBytes: Int): F[RemoteCommandOutput] =
    throw new UnsupportedOperationException("This remote session does not support bounded command capture")
}

trait RemoteConfigurationTransport[F[_]] {
  def withSession[A](connection: Connection)(use: RemoteConfigurationSession[F] => F[A]): F[A]
  def withSessionBounded[A](connection: Connection, maxOutputBytes: Int)(use: RemoteConfigurationSession[F] => F[A]): F[A] =
    withSession(connection)(use)
}

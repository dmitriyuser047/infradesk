package ru.bitec.app.ops
package application.port

import domain.configuration.{
  ConfigurationDeployment,
  ConfigurationDeploymentPhase,
  ConfigurationDeploymentState
}
import domain.connection.Connection

import java.time.Instant
import java.util.UUID

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

/** All writes run in short transactions. Claim and every later update fence on the lease token. */
trait ConfigurationDeploymentRepository[F[_]] {
  def insert(deployment: ConfigurationDeployment): F[ConfigurationDeploymentInsert]
  def find(organizationId: UUID, id: UUID): F[Option[ConfigurationDeployment]]
  def findRequest(organizationId: UUID, requestId: UUID): F[Option[ConfigurationDeployment]]
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): F[List[ConfigurationDeployment]]
  def renew(organizationId: UUID, id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]
  def advance(organizationId: UUID, id: UUID, token: UUID, expected: ConfigurationDeploymentPhase,
              next: ConfigurationDeploymentPhase, now: Instant): F[Boolean]
  def finish(organizationId: UUID, id: UUID, token: UUID, state: ConfigurationDeploymentState,
             failureCode: Option[String], now: Instant): F[Boolean]
  def cancel(organizationId: UUID, id: UUID, now: Instant): F[Boolean]
  def history(organizationId: UUID, profileId: Option[UUID], before: Option[(Instant, UUID)],
              limit: Int): F[List[ConfigurationDeployment]]
}

final case class RemoteConfigurationFile(exists: Boolean, bytes: Array[Byte], mode: Option[Int],
                                         uid: Option[Int], gid: Option[Int])

/** A single authenticated, host-key-pinned SSH/SFTP connection. Implementations bound every read. */
trait RemoteConfigurationSession[F[_]] {
  def read(path: String, maxBytes: Int): F[RemoteConfigurationFile]
  def upload(path: String, bytes: Array[Byte], mode: Int, uid: Option[Int], gid: Option[Int]): F[Unit]
  def atomicReplace(from: String, to: String): F[Unit]
  def remove(path: String): F[Unit]
  def execute(executable: String, args: List[String], timeoutSeconds: Int): F[Int]
}

trait RemoteConfigurationTransport[F[_]] {
  def withSession[A](connection: Connection)(use: RemoteConfigurationSession[F] => F[A]): F[A]
}

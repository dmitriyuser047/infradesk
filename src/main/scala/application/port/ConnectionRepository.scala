package ru.bitec.app.ops
package application.port

import domain.connection.Connection

import java.util.UUID
import scala.language.higherKinds


trait ConnectionRepository[F[_]] {

  def findById(organizationId: UUID, id: UUID): F[Option[Connection]]

  def findByOrganization(organizationId: UUID): F[List[Connection]]

  def save(connection: Connection): F[Unit]

  /** Updates an existing connection only while it is still the snapshot the caller prepared
    * external work against. False means another writer won the race.
    */
  def saveIfUnmodified(connection: Connection, expectedUpdatedAt: java.time.Instant): F[Boolean]
}

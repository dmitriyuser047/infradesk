package ru.bitec.app.ops
package persistence.postgres

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.util.UUID

/** Shared advisory locks for approval and resource-scoped desired-state changes. Keep seeds stable. */
private[postgres] object PostgresProvisioningLocks {
  def lockRequest(organizationId: UUID, requestId: UUID): ConnectionIO[Unit] =
    sql"select 1 from pg_advisory_xact_lock(hashtextextended(cast($organizationId as text) || ':' || cast($requestId as text), 0))".query[Int].unique.void

  def lockResource(organizationId: UUID, resourceId: UUID): ConnectionIO[Unit] =
    sql"select 1 from pg_advisory_xact_lock(hashtextextended(cast($organizationId as text) || ':' || cast($resourceId as text), 1))".query[Int].unique.void

  def lockRequestThenResource(organizationId: UUID, requestId: UUID, resourceId: UUID): ConnectionIO[Unit] =
    lockRequest(organizationId, requestId) *> lockResource(organizationId, resourceId)
}

package ru.bitec.app.ops
package application.audit

import application.auth.ActorContext
import application.port.{AuditEventRepository, IdGenerator, TimeProvider}
import cats.Monad
import cats.syntax.all._
import domain.audit.{AuditAction, AuditEvent, AuditTargetType}

import java.util.UUID

/** Writes one journal entry inside the transaction of the mutation that produced it.
  *
  * The recorder is an ordinary dependency of a use case, not a bus and not an interceptor: if the
  * insert fails, the mutation fails with it and the transaction rolls back.
  */
final class AuditRecorder[Tx[_]: Monad](
  auditEvents: AuditEventRepository[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx]
) {

  def record(
    actor: ActorContext,
    action: AuditAction,
    targetType: AuditTargetType,
    targetId: Option[UUID]
  ): Tx[Unit] =
    for {
      id <- ids.nextId
      now <- time.now
      _ <- auditEvents.save(AuditEvent(id, actor.organizationId, actor.userId, action, targetType,
        targetId, now, now))
    } yield ()
}

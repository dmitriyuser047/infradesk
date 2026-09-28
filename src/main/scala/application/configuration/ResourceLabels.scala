package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{ResourceLabelRepository, ResourceLabelSet, ResourceLabelWrite, TimeProvider, TransactionRunner}
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration.ResourceLabel

import java.util.UUID

final case class ResourceLabelError(code: String, message: String) extends RuntimeException(message)
object ResourceLabelError {
  val NotFound: ResourceLabelError = ResourceLabelError("RESOURCE_NOT_FOUND", "Resource was not found")
  val Changed: ResourceLabelError = ResourceLabelError("RESOURCE_LABELS_CHANGED", "The labels were changed by someone else")
  def invalid(reason: String): ResourceLabelError = ResourceLabelError("INVALID_RESOURCE_LABELS", s"Invalid labels: $reason")
}

/** Resource labels. Changing them may make rules create desired state, so the whole set is replaced by
  * compare-and-set and journaled; the journal keeps the resource id only, never the labels.
  */
final class ResourceLabels[F[_]: MonadThrow, Tx[_]: MonadThrow](
  labels: ResourceLabelRepository[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx],
  reads: TransactionRunner[F, Tx],
  writes: TransactionRunner[F, Tx]
) {
  def get(organizationId: UUID, resourceId: UUID): F[ResourceLabelSet] =
    reads.run(labels.find(organizationId, resourceId)).flatMap(_.liftTo[F](ResourceLabelError.NotFound))

  def replace(actor: ActorContext, resourceId: UUID, expectedVersion: Int, requested: List[(String, String)]): F[ResourceLabelSet] =
    for {
      _ <- MonadThrow[F].raiseUnless(expectedVersion >= 0)(ResourceLabelError.invalid("version"))
      canonical <- ResourceLabel.validateSet(requested).leftMap(ResourceLabelError.invalid).liftTo[F]
      version <- writes.run(for {
        now <- time.now
        written <- labels.replace(actor.organizationId, resourceId, expectedVersion, canonical, now)
        version <- written match {
          case ResourceLabelWrite.Written(version) => version.pure[Tx]
          case ResourceLabelWrite.Stale => MonadThrow[Tx].raiseError[Int](ResourceLabelError.Changed)
          case ResourceLabelWrite.ResourceMissing => MonadThrow[Tx].raiseError[Int](ResourceLabelError.NotFound)
        }
        _ <- audit.record(actor, AuditAction.ResourceLabelsUpdated, AuditTargetType.Resource, Some(resourceId))
      } yield version)
    } yield ResourceLabelSet(resourceId, version, canonical)
}

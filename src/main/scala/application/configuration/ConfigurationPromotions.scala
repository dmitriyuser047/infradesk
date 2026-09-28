package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration.ConfigurationDesiredState

import java.util.UUID

final case class ConfigurationPromotionItem(assignmentId: UUID, expectedVersion: Int,
                                             compatible: Boolean, errorCode: Option[String],
                                             variableName: Option[String])
final case class ConfigurationPromotionPreview(revisionNumber: Int, items: List[ConfigurationPromotionItem]) {
  def compatible: Boolean = items.forall(_.compatible)
}
final case class ConfigurationPromotionResult(revisionNumber: Int, assignments: List[(UUID, Int)])

sealed abstract class ConfigurationPromotionError(val code: String, message: String) extends RuntimeException(message)
object ConfigurationPromotionError {
  case object Invalid extends ConfigurationPromotionError("INVALID_REQUEST", "Invalid promotion selection")
  case object Conflict extends ConfigurationPromotionError("CONFIGURATION_PROMOTION_CONFLICT", "An assignment changed")
  case object Incompatible extends ConfigurationPromotionError("CONFIGURATION_PROMOTION_INCOMPATIBLE", "Values are incompatible")
  case object RevisionMissing extends ConfigurationPromotionError("CONFIGURATION_REVISION_NOT_FOUND", "Revision was not found")
}

/** Preview validates every selected assignment; commit rechecks versions under ordered row locks. */
final class ConfigurationPromotions[F[_]: MonadThrow, Tx[_]: MonadThrow](
  assignments: ConfigurationAssignmentRepository[Tx],
  values: ConfigurationAssignmentQuery[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  promotions: ConfigurationPromotionRepository[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx],
  reads: TransactionRunner[F, Tx],
  writes: TransactionRunner[F, Tx]
) {
  import ConfigurationPromotionError._

  def preview(organizationId: UUID, profileId: UUID, revisionNumber: Int,
              selected: List[ConfigurationPromotionSelection]): F[ConfigurationPromotionPreview] = for {
    _ <- validate(revisionNumber, selected)
    revision <- reads.run(profiles.findRevision(organizationId, profileId, revisionNumber))
      .flatMap(_.liftTo[F](RevisionMissing))
    items <- selected.traverse { selection =>
      reads.run((assignments.find(organizationId, selection.assignmentId),
        values.values(organizationId, selection.assignmentId)).tupled).map { case (assignment, explicit) =>
        if (!assignment.exists(a => a.active && a.profileId == profileId && a.version == selection.expectedVersion))
          ConfigurationPromotionItem(selection.assignmentId, selection.expectedVersion, false, Some(Conflict.code), None)
        else ConfigurationDesiredState.render(revision.revision, explicit) match {
          case Left(error) => ConfigurationPromotionItem(selection.assignmentId, selection.expectedVersion,
            false, Some(error.code), Option(error.variableName).filter(_.nonEmpty))
          case Right(_) => ConfigurationPromotionItem(selection.assignmentId, selection.expectedVersion, true, None, None)
        }
      }
    }
  } yield ConfigurationPromotionPreview(revisionNumber, items)

  def commit(actor: ActorContext, profileId: UUID, revisionNumber: Int,
             selected: List[ConfigurationPromotionSelection]): F[ConfigurationPromotionResult] = for {
    checked <- preview(actor.organizationId, profileId, revisionNumber, selected)
    _ <- MonadThrow[F].raiseUnless(checked.compatible)(Incompatible)
    _ <- writes.run(for {
      now <- time.now
      changed <- promotions.promote(actor.organizationId, profileId, revisionNumber, selected, now)
      _ <- MonadThrow[Tx].raiseUnless(changed)(Conflict)
      _ <- audit.record(actor, AuditAction.ConfigurationAssignmentsPromoted,
        AuditTargetType.ConfigurationProfile, Some(profileId))
    } yield ())
  } yield ConfigurationPromotionResult(revisionNumber, selected.map(item => item.assignmentId -> (item.expectedVersion + 1)))

  private def validate(revisionNumber: Int, selected: List[ConfigurationPromotionSelection]): F[Unit] =
    MonadThrow[F].raiseUnless(revisionNumber >= 1 && selected.nonEmpty && selected.size <= 100 &&
      selected.forall(_.expectedVersion >= 1) && selected.map(_.assignmentId).distinct.size == selected.size)(Invalid)
}

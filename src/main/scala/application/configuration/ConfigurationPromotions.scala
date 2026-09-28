package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration.{ConfigurationDesiredState, ConfigurationLimits, ConfigurationRevision, ConfigurationVariableValue}

import java.util.UUID

/** One reason an assignment cannot move to the target revision, about one variable when it is. */
final case class ConfigurationPromotionIssue(code: String, variableName: Option[String])

final case class ConfigurationPromotionItem(
  assignmentId: UUID,
  expectedVersion: Int,
  resourceName: Option[String],
  currentRevisionNumber: Option[Int],
  issues: List[ConfigurationPromotionIssue]
) {
  def compatible: Boolean = issues.isEmpty
}

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

/** Moves many assignments of one profile to one revision as a single decision.
  *
  * Preview checks every selected assignment against the target revision and names every problem;
  * nothing is dropped silently, so an explicit value the new revision no longer declares blocks the
  * promotion until someone decides about it. Commit repeats every check under ordered row locks and
  * moves all assignments or none.
  */
final class ConfigurationPromotions[F[_]: MonadThrow, Tx[_]: MonadThrow](
  promotions: ConfigurationPromotionRepository[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx],
  reads: TransactionRunner[F, Tx],
  writes: TransactionRunner[F, Tx]
) {
  import ConfigurationPromotionError._

  val MaxSelection = 100

  def preview(organizationId: UUID, profileId: UUID, revisionNumber: Int,
              selected: List[ConfigurationPromotionSelection]): F[ConfigurationPromotionPreview] = for {
    _ <- validate(revisionNumber, selected)
    loaded <- reads.run((profiles.findRevision(organizationId, profileId, revisionNumber),
      promotions.load(organizationId, selected.map(_.assignmentId))).tupled)
    (revision, candidates) = loaded
    target <- revision.liftTo[F](RevisionMissing)
  } yield ConfigurationPromotionPreview(revisionNumber, check(profileId, target.revision, selected, candidates))

  def commit(actor: ActorContext, profileId: UUID, revisionNumber: Int,
             selected: List[ConfigurationPromotionSelection]): F[ConfigurationPromotionResult] = for {
    _ <- validate(revisionNumber, selected)
    versions <- writes.run(for {
      revision <- profiles.findRevision(actor.organizationId, profileId, revisionNumber)
        .flatMap(_.liftTo[Tx](RevisionMissing))
      now <- time.now
      // Locks first, then reads: values only change together with a version, which the lock pins.
      written <- promotions.promote(actor.organizationId, profileId, revisionNumber, selected, now)
      versions <- written match {
        case ConfigurationPromotionWrite.Conflict => MonadThrow[Tx].raiseError[List[(UUID, Int)]](Conflict)
        case ConfigurationPromotionWrite.Promoted(versions) => versions.pure[Tx]
      }
      candidates <- promotions.load(actor.organizationId, selected.map(_.assignmentId))
      // The versions have moved inside this transaction; compatibility is judged on the locked values.
      moved = selected.map(item => item.copy(expectedVersion = item.expectedVersion + 1))
      issues = check(profileId, revision.revision, moved, candidates.map(candidate =>
        candidate.copy(assignment = candidate.assignment.copy(profileRevisionNumber = revisionNumber))))
      _ <- MonadThrow[Tx].raiseUnless(issues.forall(_.compatible))(Incompatible)
      _ <- audit.record(actor, AuditAction.ConfigurationAssignmentsPromoted,
        AuditTargetType.ConfigurationProfile, Some(profileId))
    } yield versions)
  } yield ConfigurationPromotionResult(revisionNumber, versions)

  private def check(profileId: UUID, revision: ConfigurationRevision, selected: List[ConfigurationPromotionSelection],
                    candidates: List[ConfigurationPromotionCandidate]): List[ConfigurationPromotionItem] = {
    val byId = candidates.map(candidate => candidate.assignment.id -> candidate).toMap
    selected.map { selection =>
      byId.get(selection.assignmentId) match {
        case Some(candidate) if candidate.assignment.active && candidate.assignment.profileId == profileId &&
          candidate.assignment.version == selection.expectedVersion =>
          ConfigurationPromotionItem(selection.assignmentId, selection.expectedVersion, Some(candidate.resourceName),
            Some(candidate.assignment.profileRevisionNumber), issues(revision, candidate.values))
        case other =>
          ConfigurationPromotionItem(selection.assignmentId, selection.expectedVersion, other.map(_.resourceName),
            other.map(_.assignment.profileRevisionNumber), List(ConfigurationPromotionIssue(Conflict.code, None)))
      }
    }
  }

  /** Every incompatibility, not only the first one the renderer would stop at. */
  private def issues(revision: ConfigurationRevision,
                     values: List[ConfigurationVariableValue]): List[ConfigurationPromotionIssue] = {
    val definitions = revision.variables.map(variable => variable.name -> variable).toMap
    val given = values.map(_.name).toSet
    val overrides = values.flatMap { value =>
      definitions.get(value.name) match {
        case None => Some(ConfigurationPromotionIssue(ConfigurationPromotions.IncompatibleOverride, Some(value.name)))
        case Some(definition) if value.value.length > ConfigurationLimits.MaxValueLength ||
          !definition.valueType.accepts(value.value) =>
          Some(ConfigurationPromotionIssue("CONFIGURATION_VALUE_INVALID", Some(value.name)))
        case _ => None
      }
    }
    val missing = revision.variables.collect {
      case variable if variable.required && variable.defaultValue.isEmpty && !given.contains(variable.name) =>
        ConfigurationPromotionIssue("CONFIGURATION_VALUE_MISSING", Some(variable.name))
    }
    val found = overrides ++ missing
    // Anything else the one renderer refuses (a placeholder without value, the size limit) still blocks.
    if (found.nonEmpty) found
    else ConfigurationDesiredState.render(revision, values).fold(
      error => List(ConfigurationPromotionIssue(error.code, Option(error.variableName).filter(_.nonEmpty))), _ => Nil)
  }

  private def validate(revisionNumber: Int, selected: List[ConfigurationPromotionSelection]): F[Unit] =
    MonadThrow[F].raiseUnless(revisionNumber >= 1 && selected.nonEmpty && selected.size <= MaxSelection &&
      selected.forall(_.expectedVersion >= 1) && selected.map(_.assignmentId).distinct.size == selected.size)(Invalid)
}

object ConfigurationPromotions {
  /** An explicit value for a variable the target revision no longer declares. */
  val IncompatibleOverride = "CONFIGURATION_INCOMPATIBLE_OVERRIDE"
}

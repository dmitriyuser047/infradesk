package ru.bitec.app.ops
package application.port

import domain.configuration.{ConfigurationAssignment, ConfigurationVariableValue}

import java.time.Instant
import java.util.UUID

/** What a compare-and-set change of an assignment came to. */
sealed trait ConfigurationAssignmentWrite

object ConfigurationAssignmentWrite {
  case object Written extends ConfigurationAssignmentWrite
  /** No active assignment with this id in the organization. */
  case object Missing extends ConfigurationAssignmentWrite
  /** It exists and is active, but its version is no longer the one expected. */
  case object Stale extends ConfigurationAssignmentWrite
  /** Another active assignment already claims the target path on this resource. */
  case object PathTaken extends ConfigurationAssignmentWrite
}

sealed trait ConfigurationAssignmentEligibility
object ConfigurationAssignmentEligibility {
  case object Eligible extends ConfigurationAssignmentEligibility
  case object TargetMissing extends ConfigurationAssignmentEligibility
  case object TargetUnsupported extends ConfigurationAssignmentEligibility
  case object TargetInactive extends ConfigurationAssignmentEligibility
  case object ProfileMissing extends ConfigurationAssignmentEligibility
  case object ProfileArchived extends ConfigurationAssignmentEligibility
  case object RevisionMissing extends ConfigurationAssignmentEligibility
}

/** Writes assignments. Every method is scoped by organization, and every change is conditional:
  * the database decides who wins a race, never a lock held in this process.
  */
trait ConfigurationAssignmentRepository[F[_]] {

  /** Lock the resource before a rule when a write needs both rows. False when it is absent. */
  def lockResource(organizationId: UUID, resourceId: UUID): F[Boolean]

  /** Recheck create prerequisites while locking the mutable rows until the insert and audit commit. */
  def createEligibility(organizationId: UUID, resourceId: UUID, profileId: UUID,
                        revisionNumber: Int): F[ConfigurationAssignmentEligibility]

  /** Inserts an active assignment with its explicit values. `PathTaken` when an active assignment
    * of the resource already claims the path; nothing is written then.
    */
  def insert(assignment: ConfigurationAssignment, values: List[ConfigurationVariableValue]): F[ConfigurationAssignmentWrite]

  /** Moves an active assignment at `expectedVersion` to a revision, a path and a set of values, and
    * advances its version. The values replace the previous ones only when the assignment itself
    * changed.
    */
  def update(
    organizationId: UUID,
    id: UUID,
    expectedVersion: Int,
    profileRevisionNumber: Int,
    targetPath: String,
    values: List[ConfigurationVariableValue],
    at: Instant
  ): F[ConfigurationAssignmentWrite]

  /** Soft removal at the required version: the row and its values stay, the path is freed. */
  def remove(organizationId: UUID, id: UUID, expectedVersion: Int, at: Instant): F[ConfigurationAssignmentWrite]

  /** The assignment as stored, removed or not. */
  def find(organizationId: UUID, id: UUID): F[Option[ConfigurationAssignment]]
}

/** A resource as an assignment target sees it: what decides whether it can take one. */
final case class ConfigurationTarget(id: UUID, resourceTypeCode: String, active: Boolean)

trait ConfigurationTargetQuery[F[_]] {
  def find(organizationId: UUID, resourceId: UUID): F[Option[ConfigurationTarget]]
}

/** The resource of an assignment, named, with where it lives. */
final case class AssignmentResourceView(
  id: UUID,
  name: String,
  code: String,
  resourceTypeCode: String,
  active: Boolean,
  project: ProjectReference,
  environment: EnvironmentReference
)

/** The profile of an assignment: enough to say whether a newer revision exists or it is archived. */
final case class AssignmentProfileView(id: UUID, code: String, name: String, archived: Boolean, latestRevisionNumber: Int)

/** The rule that manages an assignment, when one does. */
final case class AssignmentRuleView(id: UUID, code: String, name: String)

/** An assignment as a list shows it. No template, no values, no rendered text. */
final case class ConfigurationAssignmentListItem(
  assignment: ConfigurationAssignment,
  resource: AssignmentResourceView,
  profile: AssignmentProfileView,
  rule: Option[AssignmentRuleView] = None
)

/** Where a page continues: the exact row the previous page ended on. */
final case class ConfigurationAssignmentCursor(createdAt: Instant, id: UUID)

final case class ConfigurationAssignmentFilter(resourceId: Option[UUID], profileId: Option[UUID], ruleId: Option[UUID] = None)

/** Reads assignments. A list is one statement, whatever resources, environments and profiles its
  * rows mention.
  */
trait ConfigurationAssignmentQuery[F[_]] {

  /** Active assignments, newest first, after the cursor when one is given. */
  def list(
    organizationId: UUID,
    filter: ConfigurationAssignmentFilter,
    before: Option[ConfigurationAssignmentCursor],
    limit: Int
  ): F[List[ConfigurationAssignmentListItem]]

  /** One assignment with its context, removed or not. */
  def find(organizationId: UUID, id: UUID): F[Option[ConfigurationAssignmentListItem]]

  /** Its explicit values, by name. */
  def values(organizationId: UUID, assignmentId: UUID): F[List[ConfigurationVariableValue]]
}

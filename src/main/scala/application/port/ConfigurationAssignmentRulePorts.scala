package ru.bitec.app.ops
package application.port

import domain.configuration._

import java.time.Instant
import java.util.UUID

/** A resource's labels and the version its whole set changes by. Version 0: never labelled. */
final case class ResourceLabelSet(resourceId: UUID, version: Int, labels: List[ResourceLabel])

sealed trait ResourceLabelWrite
object ResourceLabelWrite {
  final case class Written(version: Int) extends ResourceLabelWrite
  case object Stale extends ResourceLabelWrite
  case object ResourceMissing extends ResourceLabelWrite
}

trait ResourceLabelRepository[F[_]] {
  def find(organizationId: UUID, resourceId: UUID): F[Option[ResourceLabelSet]]

  /** Replaces the whole set if its version is still `expectedVersion`, and asks every enabled rule of
    * the organization to reconcile soon, since matches may have changed.
    */
  def replace(organizationId: UUID, resourceId: UUID, expectedVersion: Int, labels: List[ResourceLabel],
              at: Instant): F[ResourceLabelWrite]
}

/** One resource as a selector sees it, with what already occupies the rule's path there. */
final case class RuleCandidate(
  resourceId: UUID,
  excluded: Boolean,
  occupantAssignmentId: Option[UUID],
  occupantSourceRuleId: Option[UUID],
  occupantProfileId: Option[UUID],
  occupantRevision: Option[Int],
  resourceName: String,
  environmentName: String,
  projectName: String
)

/** A rule's listing: counts come from the same statement, never one query per rule. */
final case class ConfigurationAssignmentRuleListItem(
  rule: ConfigurationAssignmentRule,
  profileCode: String,
  profileName: String,
  profileArchived: Boolean,
  latestRevisionNumber: Int,
  matchedCount: Int,
  managedCount: Int,
  issueCount: Int,
  excludedCount: Int
)

/** One row of a rule's targets, with every fact needed to say how the resource stands. */
final case class ConfigurationRuleTargetRow(
  resourceId: UUID,
  resourceName: String,
  resourceCode: String,
  resourceActive: Boolean,
  projectId: UUID,
  projectName: String,
  environmentId: UUID,
  environmentName: String,
  matches: Boolean,
  excluded: Boolean,
  assignmentId: Option[UUID],
  assignmentVersion: Option[Int],
  assignmentRevision: Option[Int],
  assignmentSourceRuleId: Option[UUID],
  issueCode: Option[ConfigurationRuleIssueCode],
  issueVariable: Option[String],
  conflictingAssignmentId: Option[UUID]
)

/** A rule claimed for reconciliation: its exact version is part of every fenced write. */
final case class ClaimedRule(rule: ConfigurationAssignmentRule, token: UUID, profileArchived: Boolean)

sealed trait RuleAutoCreate
object RuleAutoCreate {
  case object Created extends RuleAutoCreate
  /** The lease or the rule version is gone: stop this reconciliation. */
  case object Fenced extends RuleAutoCreate
  /** The resource no longer matches, or was excluded, since it was listed. */
  case object NoLongerEligible extends RuleAutoCreate
  final case class Occupied(assignmentId: UUID, sourceRuleId: Option[UUID]) extends RuleAutoCreate
}

sealed trait RuleWrite
object RuleWrite {
  case object Written extends RuleWrite
  case object Missing extends RuleWrite
  case object Stale extends RuleWrite
  case object Archived extends RuleWrite
  case object Disabled extends RuleWrite
}

trait ConfigurationAssignmentRuleRepository[F[_]] {
  /** Inserts the rule and its selector; `false` when the code is already used in the organization. */
  def insert(rule: ConfigurationAssignmentRule): F[Boolean]
  def find(organizationId: UUID, id: UUID): F[Option[ConfigurationAssignmentRule]]
  def list(organizationId: UUID, profileId: Option[UUID], includeArchived: Boolean,
           after: Option[(Instant, UUID)], limit: Int): F[List[ConfigurationAssignmentRuleListItem]]
  def view(organizationId: UUID, id: UUID): F[Option[ConfigurationAssignmentRuleListItem]]
  /** Every project and environment belongs to the organization. */
  def validReferences(organizationId: UUID, projects: List[UUID], environments: List[UUID]): F[Boolean]
  def resourceExists(organizationId: UUID, resourceId: UUID): F[Boolean]

  /** Every change bumps the version, drops any reconciliation lease and schedules a reconcile. */
  def update(organizationId: UUID, id: UUID, expectedVersion: Int, name: String, description: Option[String],
             selector: ConfigurationRuleSelector, at: Instant): F[RuleWrite]
  def setEnabled(organizationId: UUID, id: UUID, expectedVersion: Int, enabled: Boolean, at: Instant): F[RuleWrite]
  def archive(organizationId: UUID, id: UUID, expectedVersion: Int, at: Instant): F[RuleWrite]
  def scheduleReconcile(organizationId: UUID, id: UUID, at: Instant): F[RuleWrite]

  def exclude(organizationId: UUID, ruleId: UUID, resourceId: UUID, actor: UUID, at: Instant): F[Boolean]
  def include(organizationId: UUID, ruleId: UUID, resourceId: UUID, at: Instant): F[Boolean]

  /** Matching resources of a selector, after `after` in resource id order, a page at a time. */
  def candidates(organizationId: UUID, selector: ConfigurationRuleSelector, ruleId: Option[UUID], targetPath: String,
                 after: Option[UUID], limit: Int): F[List[RuleCandidate]]
  /** Matching resources, and how many of them have the path free, taken manually, or taken by a rule. */
  def previewCounts(organizationId: UUID, selector: ConfigurationRuleSelector, targetPath: String): F[(Int, Int, Int, Int)]
  /** Whether the resource matches right now and is not excluded from the rule. */
  def eligible(organizationId: UUID, rule: ConfigurationAssignmentRule, resourceId: UUID): F[Boolean]
  /** Matching, managed, excluded and blocked resources of a rule, by resource name then id. */
  def targets(organizationId: UUID, rule: ConfigurationAssignmentRule,
              after: Option[(String, UUID)], limit: Int): F[List[ConfigurationRuleTargetRow]]

  // Reconciliation: every write below is fenced on the lease token and the exact rule version.
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, scope: Option[UUID]): F[Option[ClaimedRule]]
  def renew(claimed: ClaimedRule, now: Instant, until: Instant): F[Boolean]
  def autoCreate(claimed: ClaimedRule, resourceId: UUID, assignment: ConfigurationAssignment, now: Instant): F[RuleAutoCreate]
  def recordIssue(claimed: ClaimedRule, resourceId: UUID, code: ConfigurationRuleIssueCode, variable: Option[String],
                  conflicting: Option[UUID], now: Instant): F[Boolean]
  def clearIssues(claimed: ClaimedRule, resourceIds: List[UUID]): F[Boolean]
  /** Ends a sweep: issues not observed since it began are dropped, the next sweep is scheduled. */
  def finishSweep(claimed: ClaimedRule, sweepStartedAt: Instant, now: Instant, next: Instant): F[Boolean]
}

sealed trait ManagedAssignmentWrite
object ManagedAssignmentWrite {
  case object Written extends ManagedAssignmentWrite
  case object Missing extends ManagedAssignmentWrite
  case object Stale extends ManagedAssignmentWrite
  /** The assignment is not managed by this rule. */
  case object NotManaged extends ManagedAssignmentWrite
  /** The assignment is already managed by a rule. */
  case object AlreadyManaged extends ManagedAssignmentWrite
  /** Resource, path, profile or revision differ from the rule's. */
  case object Incompatible extends ManagedAssignmentWrite
}

/** Operations on the assignments a rule manages. Each is one short transaction with the rule locked. */
trait ConfigurationRuleAssignmentRepository[F[_]] {
  /** Keeps the desired state, ends management and excludes the resource so it is not taken again. */
  def detach(organizationId: UUID, ruleId: UUID, assignmentId: UUID, expectedVersion: Int, actor: UUID,
             at: Instant): F[ManagedAssignmentWrite]
  /** Excludes the resource and soft-removes the desired assignment; the server file is untouched. */
  def excludeAndRemove(organizationId: UUID, ruleId: UUID, assignmentId: UUID, expectedVersion: Int, actor: UUID,
                       at: Instant): F[ManagedAssignmentWrite]
  /** Puts a compatible manual assignment under the rule. */
  def adopt(organizationId: UUID, rule: ConfigurationAssignmentRule, assignmentId: UUID, expectedVersion: Int,
            at: Instant): F[ManagedAssignmentWrite]

  /** The rule, locked against every concurrent change until the transaction ends. */
  def lockRule(organizationId: UUID, ruleId: UUID): F[Option[(Int, Int, Boolean)]]
  /** The active managed assignments with their values; locked in id order when `lock`. */
  def managed(organizationId: UUID, ruleId: UUID, limit: Int, lock: Boolean): F[List[ConfigurationPromotionCandidate]]
  /** Moves the rule and all its managed assignments to the revision; each version exactly once. */
  def promote(organizationId: UUID, ruleId: UUID, revisionNumber: Int, assignmentIds: List[UUID],
              at: Instant): F[List[(UUID, Int)]]
}

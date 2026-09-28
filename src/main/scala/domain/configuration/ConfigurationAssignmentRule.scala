package ru.bitec.app.ops
package domain.configuration

import java.time.Instant
import java.util.Locale
import java.util.UUID

/** A key/value label on a resource. Metadata only: never executed, never a secret store. */
final case class ResourceLabel(key: String, value: String)

object ResourceLabel {
  val MaxPerResource = 32
  val MaxKeyLength = 63
  val MaxValueLength = 128
  private val KeyPattern = "[a-z][a-z0-9_.-]{0,62}"

  /** Keys are canonical lowercase; values are kept exactly, case included. */
  def canonical(key: String, value: String): Either[String, ResourceLabel] = {
    val normalized = key.trim.toLowerCase(Locale.ROOT)
    if (!normalized.matches(KeyPattern)) Left("key")
    else if (value.isEmpty || value.length > MaxValueLength || value.exists(c => Character.isISOControl(c) ||
      c == ' ' || c == ' ')) Left("value")
    else Right(ResourceLabel(normalized, value))
  }

  /** A whole label set: bounded, one value per key. */
  def validateSet(labels: List[(String, String)]): Either[String, List[ResourceLabel]] =
    if (labels.size > MaxPerResource) Left("labels")
    else labels.foldLeft[Either[String, List[ResourceLabel]]](Right(Nil)) {
      case (Right(done), (key, value)) => canonical(key, value).flatMap { label =>
        if (done.exists(_.key == label.key)) Left("duplicate") else Right(done :+ label)
      }
      case (failed, _) => failed
    }.map(_.sortBy(_.key))
}

/** Which nodes a rule applies to. Structured fields only: never an expression.
  *
  * A resource matches when it is an active NODE, its project is one of `projects` (any when empty),
  * its environment is one of `environments` (any when empty), it carries every `requiredLabels`
  * pair, and it carries none of the `excludedLabels` pairs.
  */
final case class ConfigurationRuleSelector(
  projects: List[UUID],
  environments: List[UUID],
  requiredLabels: List[ResourceLabel],
  excludedLabels: List[ResourceLabel]
)

object ConfigurationRuleSelector {
  val MaxProjects = 50
  val MaxEnvironments = 50
  val MaxLabels = 16

  def validate(selector: ConfigurationRuleSelector): Either[String, ConfigurationRuleSelector] = {
    def distinctLabels(labels: List[ResourceLabel]) = labels.distinct.size == labels.size
    if (selector.projects.size > MaxProjects || selector.environments.size > MaxEnvironments ||
      selector.requiredLabels.size > MaxLabels || selector.excludedLabels.size > MaxLabels) Left("too many")
    else if (selector.projects.distinct.size != selector.projects.size ||
      selector.environments.distinct.size != selector.environments.size ||
      !distinctLabels(selector.requiredLabels) || !distinctLabels(selector.excludedLabels)) Left("duplicate")
    else if (selector.requiredLabels.exists(selector.excludedLabels.contains)) Left("contradiction")
    else {
      val all = selector.requiredLabels ++ selector.excludedLabels
      all.traverseCanonical.map(_ => selector.copy(
        requiredLabels = selector.requiredLabels.sortBy(l => (l.key, l.value)),
        excludedLabels = selector.excludedLabels.sortBy(l => (l.key, l.value))))
    }
  }

  private implicit final class Canonical(private val labels: List[ResourceLabel]) extends AnyVal {
    def traverseCanonical: Either[String, List[ResourceLabel]] =
      labels.foldLeft[Either[String, List[ResourceLabel]]](Right(Nil)) {
        case (Right(done), label) => ResourceLabel.canonical(label.key, label.value).flatMap { canonical =>
          if (canonical.key != label.key) Left("key") else Right(done :+ canonical)
        }
        case (failed, _) => failed
      }
  }
}

/** A rule that keeps an assignment of one exact revision present at one path on every matching node.
  * It manages desired state only; it never deploys.
  */
final case class ConfigurationAssignmentRule(
  id: UUID,
  organizationId: UUID,
  code: String,
  name: String,
  description: Option[String],
  profileId: UUID,
  profileRevisionNumber: Int,
  targetPath: String,
  selector: ConfigurationRuleSelector,
  enabled: Boolean,
  archived: Boolean,
  version: Int,
  createdBy: UUID,
  createdAt: Instant,
  updatedAt: Instant,
  lastReconciledAt: Option[Instant],
  nextReconcileAt: Option[Instant]
)

object ConfigurationAssignmentRule {
  val MaxNameLength = 255
  val MaxDescriptionLength = 2000
  private val CodePattern = "[a-z0-9][a-z0-9-]{1,62}[a-z0-9]"

  def validCode(code: String): Boolean = code.matches(CodePattern)
  def validName(name: String): Boolean = name.trim.nonEmpty && name.length <= MaxNameLength
  def validDescription(description: Option[String]): Boolean = description.forall(_.length <= MaxDescriptionLength)
}

/** Why a matching resource has no assignment of the rule right now. */
sealed abstract class ConfigurationRuleIssueCode(val code: String)
object ConfigurationRuleIssueCode {
  case object NeedsValues extends ConfigurationRuleIssueCode("NEEDS_VALUES")
  case object TargetPathConflict extends ConfigurationRuleIssueCode("TARGET_PATH_CONFLICT")
  case object OtherRuleConflict extends ConfigurationRuleIssueCode("OTHER_RULE_CONFLICT")
  case object ProfileArchived extends ConfigurationRuleIssueCode("PROFILE_ARCHIVED")
  case object AssignmentInvalid extends ConfigurationRuleIssueCode("ASSIGNMENT_INVALID")
  val all: List[ConfigurationRuleIssueCode] = List(NeedsValues, TargetPathConflict, OtherRuleConflict, ProfileArchived, AssignmentInvalid)
  def fromCode(code: String): ConfigurationRuleIssueCode =
    all.find(_.code == code).getOrElse(throw new IllegalArgumentException("Invalid rule issue"))
}

/** How one resource stands with a rule, as the rule's targets list shows it. */
sealed abstract class ConfigurationRuleTargetStatus(val code: String)
object ConfigurationRuleTargetStatus {
  case object Assigned extends ConfigurationRuleTargetStatus("ASSIGNED")
  case object Pending extends ConfigurationRuleTargetStatus("PENDING")
  case object NeedsValues extends ConfigurationRuleTargetStatus("NEEDS_VALUES")
  case object TargetPathConflict extends ConfigurationRuleTargetStatus("TARGET_PATH_CONFLICT")
  case object OtherRuleConflict extends ConfigurationRuleTargetStatus("OTHER_RULE_CONFLICT")
  case object ProfileArchived extends ConfigurationRuleTargetStatus("PROFILE_ARCHIVED")
  case object AssignmentInvalid extends ConfigurationRuleTargetStatus("ASSIGNMENT_INVALID")
  case object Excluded extends ConfigurationRuleTargetStatus("EXCLUDED")
  case object NoLongerMatching extends ConfigurationRuleTargetStatus("NO_LONGER_MATCHING")
}

object ConfigurationRuleTargets {
  import ConfigurationRuleTargetStatus._

  /** How a resource stands with a rule. A managed assignment that stopped matching stays assigned:
    * rules never remove desired state, they only report it.
    */
  def status(ruleId: java.util.UUID, matches: Boolean, excluded: Boolean, assignmentSourceRuleId: Option[java.util.UUID],
             issue: Option[ConfigurationRuleIssueCode]): ConfigurationRuleTargetStatus =
    if (excluded) Excluded
    else if (assignmentSourceRuleId.contains(ruleId)) { if (matches) Assigned else NoLongerMatching }
    else issue match {
      case Some(ConfigurationRuleIssueCode.NeedsValues) => NeedsValues
      case Some(ConfigurationRuleIssueCode.TargetPathConflict) => TargetPathConflict
      case Some(ConfigurationRuleIssueCode.OtherRuleConflict) => OtherRuleConflict
      case Some(ConfigurationRuleIssueCode.ProfileArchived) => ProfileArchived
      case Some(ConfigurationRuleIssueCode.AssignmentInvalid) => AssignmentInvalid
      case None => if (matches) Pending else NoLongerMatching
    }
}

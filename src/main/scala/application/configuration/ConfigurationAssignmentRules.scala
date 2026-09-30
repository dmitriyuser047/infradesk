package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration._

import java.time.Instant
import java.util.UUID

final case class ConfigurationRuleError(code: String, message: String, variableName: Option[String] = None)
  extends RuntimeException(message)

object ConfigurationRuleError {
  val NotFound: ConfigurationRuleError = ConfigurationRuleError("CONFIGURATION_RULE_NOT_FOUND", "Rule was not found")
  val Changed: ConfigurationRuleError = ConfigurationRuleError("CONFIGURATION_RULE_CHANGED", "The rule was changed by someone else")
  val CodeExists: ConfigurationRuleError = ConfigurationRuleError("CONFIGURATION_RULE_CODE_EXISTS", "A rule with this code already exists")
  val Archived: ConfigurationRuleError = ConfigurationRuleError("CONFIGURATION_RULE_ARCHIVED", "The rule is archived")
  val Disabled: ConfigurationRuleError = ConfigurationRuleError("CONFIGURATION_RULE_DISABLED", "The rule is disabled")
  val ProfileArchived: ConfigurationRuleError = ConfigurationRuleError("CONFIGURATION_RULE_PROFILE_ARCHIVED", "The profile is archived")
  val ProfileNotFound: ConfigurationRuleError = ConfigurationRuleError(ConfigurationError.NotFoundCode, "Configuration profile was not found")
  val ProfileWrongKind: ConfigurationRuleError = ConfigurationRuleError(ConfigurationError.WrongKindCode,
    "This profile does not contain a file template")
  val RevisionNotFound: ConfigurationRuleError =
    ConfigurationRuleError(ConfigurationError.RevisionNotFoundCode, "Configuration revision was not found")
  val ResourceNotFound: ConfigurationRuleError = ConfigurationRuleError("RESOURCE_NOT_FOUND", "Resource was not found")
  def selectorInvalid(reason: String): ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_RULE_SELECTOR_INVALID", s"Invalid selector: $reason")
  def invalid(field: String): ConfigurationRuleError = ConfigurationRuleError("INVALID_CONFIGURATION_RULE", s"Invalid $field")
  val TargetConflict: ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_RULE_TARGET_CONFLICT", "Another assignment already manages this file on this resource")
  val Incompatible: ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_RULE_ASSIGNMENT_INCOMPATIBLE", "The assignment does not match the rule")
  val PromotionConflict: ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_RULE_PROMOTION_CONFLICT", "The managed assignments changed; preview the promotion again")
  val PromotionIncompatible: ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_PROMOTION_INCOMPATIBLE", "Some managed assignments are not compatible with the revision")
  val PromotionTooLarge: ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_RULE_PROMOTION_TOO_LARGE", "The rule manages more assignments than one promotion may move")
  def needsValues(error: ConfigurationRenderError): ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_RULE_NEEDS_VALUES", "The assignment needs values", Option(error.variableName).filter(_.nonEmpty))
  val NotManaged: ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_ASSIGNMENT_NOT_MANAGED", "The assignment is not managed by this rule")
  val AlreadyManaged: ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_ASSIGNMENT_ALREADY_MANAGED", "The assignment is already managed by a rule")
  val AssignmentNotFound: ConfigurationRuleError =
    ConfigurationRuleError(ConfigurationAssignmentError.NotFoundCode, "Configuration assignment was not found")
  val AssignmentChanged: ConfigurationRuleError =
    ConfigurationRuleError(ConfigurationAssignmentError.ChangedCode, "The configuration assignment was changed by someone else")
  val NotEligible: ConfigurationRuleError =
    ConfigurationRuleError("CONFIGURATION_RULE_RESOURCE_NOT_ELIGIBLE", "The resource does not match the rule or is excluded")
}

final case class ConfigurationRuleDraft(
  code: String,
  name: String,
  description: Option[String],
  profileId: UUID,
  profileRevisionNumber: Int,
  targetPath: String,
  selector: ConfigurationRuleSelector,
  enabled: Boolean
)

/** What a selector would do, computed from the database only: no server is contacted. */
final case class ConfigurationSelectorPreview(
  matched: Int,
  eligible: Int,
  needsValues: Int,
  manualConflicts: Int,
  ruleConflicts: Int,
  missingVariable: Option[String],
  items: List[(RuleCandidate, String)]
)

final case class ConfigurationRulePromotionPreview(
  ruleVersion: Int,
  fromRevision: Int,
  targetRevision: Int,
  items: List[ConfigurationPromotionItem]
) {
  def compatible: Boolean = items.forall(_.compatible)
}

/** Rules that keep assignments of one exact revision present on matching nodes.
  *
  * Everything here changes desired state in the database, in short transactions, journaled with the
  * acting user. Nothing deploys, and nothing reaches a server.
  */
final class ConfigurationAssignmentRules[F[_]: MonadThrow, Tx[_]: MonadThrow](
  rules: ConfigurationAssignmentRuleRepository[Tx],
  managed: ConfigurationRuleAssignmentRepository[Tx],
  assignments: ConfigurationAssignmentRepository[Tx],
  assignmentQuery: ConfigurationAssignmentQuery[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx],
  reads: TransactionRunner[F, Tx],
  writes: TransactionRunner[F, Tx]
) {
  import ConfigurationAssignmentRules._
  import ConfigurationRuleError._

  def create(actor: ActorContext, draft: ConfigurationRuleDraft): F[UUID] = for {
    _ <- MonadThrow[F].raiseUnless(ConfigurationAssignmentRule.validCode(draft.code))(invalid("code"))
    _ <- MonadThrow[F].raiseUnless(ConfigurationAssignmentRule.validName(draft.name))(invalid("name"))
    _ <- MonadThrow[F].raiseUnless(ConfigurationAssignmentRule.validDescription(draft.description))(invalid("description"))
    path <- ConfigurationTargetPath.validate(draft.targetPath).leftMap(_ => invalid("targetPath")).liftTo[F]
    selector <- ConfigurationRuleSelector.validate(draft.selector).leftMap(selectorInvalid).liftTo[F]
    id <- writes.run(for {
      _ <- revisionOf(actor.organizationId, draft.profileId, draft.profileRevisionNumber)
      _ <- references(actor.organizationId, selector)
      id <- ids.nextId
      now <- time.now
      rule = ConfigurationAssignmentRule(id, actor.organizationId, draft.code, draft.name.trim, draft.description,
        draft.profileId, draft.profileRevisionNumber, path, selector, draft.enabled, archived = false, version = 1,
        actor.userId, now, now, None, Option.when(draft.enabled)(now))
      inserted <- rules.insert(rule)
      _ <- MonadThrow[Tx].raiseUnless(inserted)(CodeExists)
      _ <- audit.record(actor, AuditAction.ConfigurationRuleCreated, AuditTargetType.ConfigurationAssignmentRule, Some(id))
      _ <- if (draft.enabled) audit.record(actor, AuditAction.ConfigurationRuleEnabled,
        AuditTargetType.ConfigurationAssignmentRule, Some(id)) else ().pure[Tx]
    } yield id)
  } yield id

  /** Name, description and selector; code, path, profile and revision never change here. */
  def update(actor: ActorContext, id: UUID, expectedVersion: Int, name: String, description: Option[String],
             selector: ConfigurationRuleSelector): F[Unit] = for {
    _ <- MonadThrow[F].raiseUnless(ConfigurationAssignmentRule.validName(name))(invalid("name"))
    _ <- MonadThrow[F].raiseUnless(ConfigurationAssignmentRule.validDescription(description))(invalid("description"))
    valid <- ConfigurationRuleSelector.validate(selector).leftMap(selectorInvalid).liftTo[F]
    _ <- writes.run(for {
      _ <- references(actor.organizationId, valid)
      now <- time.now
      written <- rules.update(actor.organizationId, id, expectedVersion, name.trim, description, valid, now)
      _ <- ruleWritten(written)
      _ <- audit.record(actor, AuditAction.ConfigurationRuleUpdated, AuditTargetType.ConfigurationAssignmentRule, Some(id))
    } yield ())
  } yield ()

  def setEnabled(actor: ActorContext, id: UUID, expectedVersion: Int, enabled: Boolean): F[Unit] = writes.run(for {
    now <- time.now
    written <- rules.setEnabled(actor.organizationId, id, expectedVersion, enabled, now)
    _ <- ruleWritten(written)
    _ <- audit.record(actor, if (enabled) AuditAction.ConfigurationRuleEnabled else AuditAction.ConfigurationRuleDisabled,
      AuditTargetType.ConfigurationAssignmentRule, Some(id))
  } yield ())

  /** Soft archive: managed assignments stay, nothing on a server changes. */
  def archive(actor: ActorContext, id: UUID, expectedVersion: Int): F[Unit] = writes.run(for {
    now <- time.now
    written <- rules.archive(actor.organizationId, id, expectedVersion, now)
    _ <- ruleWritten(written)
    _ <- audit.record(actor, AuditAction.ConfigurationRuleArchived, AuditTargetType.ConfigurationAssignmentRule, Some(id))
  } yield ())

  /** Only schedules: the worker reconciles, the request never waits for it. */
  def reconcileNow(actor: ActorContext, id: UUID): F[Unit] = writes.run(for {
    now <- time.now
    written <- rules.scheduleReconcile(actor.organizationId, id, now)
    _ <- ruleWritten(written)
    _ <- audit.record(actor, AuditAction.ConfigurationRuleReconcileRequested, AuditTargetType.ConfigurationAssignmentRule, Some(id))
  } yield ())

  def exclude(actor: ActorContext, ruleId: UUID, resourceId: UUID): F[Unit] = writes.run(for {
    _ <- existing(actor.organizationId, ruleId)
    found <- rules.resourceExists(actor.organizationId, resourceId)
    _ <- MonadThrow[Tx].raiseUnless(found)(ResourceNotFound)
    now <- time.now
    _ <- rules.exclude(actor.organizationId, ruleId, resourceId, actor.userId, now)
    _ <- audit.record(actor, AuditAction.ConfigurationRuleResourceExcluded, AuditTargetType.ConfigurationAssignmentRule, Some(ruleId))
  } yield ())

  def include(actor: ActorContext, ruleId: UUID, resourceId: UUID): F[Unit] = writes.run(for {
    _ <- existing(actor.organizationId, ruleId)
    now <- time.now
    _ <- rules.include(actor.organizationId, ruleId, resourceId, now)
    _ <- audit.record(actor, AuditAction.ConfigurationRuleResourceIncluded, AuditTargetType.ConfigurationAssignmentRule, Some(ruleId))
  } yield ())

  def list(organizationId: UUID, profileId: Option[UUID], includeArchived: Boolean, after: Option[(Instant, UUID)],
           limit: Int): F[List[ConfigurationAssignmentRuleListItem]] =
    reads.run(rules.list(organizationId, profileId, includeArchived, after, limit))

  def detail(organizationId: UUID, id: UUID): F[ConfigurationAssignmentRuleListItem] =
    reads.run(rules.view(organizationId, id)).flatMap(_.liftTo[F](NotFound))

  def targets(organizationId: UUID, id: UUID, after: Option[(String, UUID)],
              limit: Int): F[(ConfigurationAssignmentRule, List[ConfigurationRuleTargetRow])] =
    reads.run(for {
      rule <- rules.find(organizationId, id).flatMap(_.liftTo[Tx](NotFound))
      rows <- rules.targets(organizationId, rule, after, limit)
    } yield rule -> rows)

  /** Counts over every match in one statement, and the first page of matches. Nothing is written. */
  def previewSelector(organizationId: UUID, profileId: UUID, revisionNumber: Int, targetPath: String,
                      selector: ConfigurationRuleSelector, limit: Int): F[ConfigurationSelectorPreview] = for {
    path <- ConfigurationTargetPath.validate(targetPath).leftMap(_ => invalid("targetPath")).liftTo[F]
    valid <- ConfigurationRuleSelector.validate(selector).leftMap(selectorInvalid).liftTo[F]
    loaded <- reads.run(for {
      revision <- revisionOf(organizationId, profileId, revisionNumber)
      _ <- references(organizationId, valid)
      counts <- rules.previewCounts(organizationId, valid, path)
      page <- rules.candidates(organizationId, valid, None, path, None, limit)
    } yield (revision, counts, page))
    (revision, (matched, free, manual, byRule), page) = loaded
    missing = ConfigurationDesiredState.render(revision._2, Nil).swap.toOption
    state = (candidate: RuleCandidate) => (candidate.occupantAssignmentId, candidate.occupantSourceRuleId) match {
      case (Some(_), Some(_)) => ConfigurationRuleIssueCode.OtherRuleConflict.code
      case (Some(_), None) if candidate.occupantProfileId.contains(profileId) &&
        candidate.occupantRevision.contains(revisionNumber) => Adoptable
      case (Some(_), None) => ConfigurationRuleIssueCode.TargetPathConflict.code
      case (None, _) if missing.isDefined => ConfigurationRuleIssueCode.NeedsValues.code
      case (None, _) => Eligible
    }
  } yield ConfigurationSelectorPreview(matched, if (missing.isDefined) 0 else free, if (missing.isDefined) free else 0,
    manual, byRule, missing.map(_.variableName).filter(_.nonEmpty), page.map(candidate => candidate -> state(candidate)))

  /** Completes a NEEDS_VALUES target: a valid managed assignment with the values given, never an invalid one. */
  def completeAssignment(actor: ActorContext, ruleId: UUID, resourceId: UUID,
                         values: List[ConfigurationVariableValue]): F[UUID] = for {
    _ <- ConfigurationAssignmentValidation.values(values).leftMap(error => invalid(error.getMessage)).liftTo[F]
    id <- writes.run(for {
      // Label replacement and adoption lock the resource before the rule. Keep that order here.
      resourceExists <- assignments.lockResource(actor.organizationId, resourceId)
      _ <- MonadThrow[Tx].raiseUnless(resourceExists)(NotEligible)
      locked <- managed.lockRule(actor.organizationId, ruleId).flatMap(_.liftTo[Tx](NotFound))
      _ <- MonadThrow[Tx].raiseWhen(locked._3)(Archived)
      rule <- rules.find(actor.organizationId, ruleId).flatMap(_.liftTo[Tx](NotFound))
      revision <- revisionOf(actor.organizationId, rule.profileId, rule.profileRevisionNumber)
      _ <- MonadThrow[Tx].raiseWhen(revision._1.archived)(ProfileArchived)
      _ <- ConfigurationDesiredState.render(revision._2, values).leftMap(needsValues).liftTo[Tx]
      eligibility <- assignments.createEligibility(actor.organizationId, resourceId, rule.profileId, rule.profileRevisionNumber)
      _ <- MonadThrow[Tx].raiseUnless(eligibility == ConfigurationAssignmentEligibility.Eligible)(NotEligible)
      eligible <- rules.eligible(actor.organizationId, rule, resourceId)
      _ <- MonadThrow[Tx].raiseUnless(eligible)(NotEligible)
      id <- ids.nextId
      now <- time.now
      written <- assignments.insert(ConfigurationAssignment(id, actor.organizationId, resourceId, rule.profileId,
        rule.profileRevisionNumber, rule.targetPath, 1, None, now, now, Some(rule.id)), values)
      _ <- written match {
        case ConfigurationAssignmentWrite.Written => ().pure[Tx]
        case _ => MonadThrow[Tx].raiseError[Unit](TargetConflict)
      }
      _ <- audit.record(actor, AuditAction.ConfigurationAssignmentCreated, AuditTargetType.ConfigurationAssignment, Some(id))
    } yield id)
  } yield id

  def detach(actor: ActorContext, ruleId: UUID, assignmentId: UUID, expectedVersion: Int): F[Unit] =
    managedWrite(actor, assignmentId, AuditAction.ConfigurationAssignmentDetached)(
      managed.detach(actor.organizationId, ruleId, assignmentId, expectedVersion, actor.userId, _))

  def excludeAndRemove(actor: ActorContext, ruleId: UUID, assignmentId: UUID, expectedVersion: Int): F[Unit] =
    managedWrite(actor, assignmentId, AuditAction.ConfigurationAssignmentRemoved)(
      managed.excludeAndRemove(actor.organizationId, ruleId, assignmentId, expectedVersion, actor.userId, _))

  def adopt(actor: ActorContext, ruleId: UUID, assignmentId: UUID, expectedVersion: Int): F[Unit] = for {
    rule <- reads.run(rules.find(actor.organizationId, ruleId)).flatMap(_.liftTo[F](NotFound))
    _ <- MonadThrow[F].raiseWhen(rule.archived)(Archived)
    // Only a valid desired state is taken over; its values stay exactly as they are.
    loaded <- reads.run((assignments.find(actor.organizationId, assignmentId),
      assignmentQuery.values(actor.organizationId, assignmentId), revisionOf(actor.organizationId, rule.profileId,
        rule.profileRevisionNumber)).tupled)
    (assignment, values, revision) = loaded
    _ <- MonadThrow[F].raiseUnless(assignment.exists(_.active))(AssignmentNotFound)
    _ <- ConfigurationDesiredState.render(revision._2, values).leftMap(needsValues).liftTo[F]
    _ <- managedWrite(actor, assignmentId, AuditAction.ConfigurationAssignmentAdopted)(
      managed.adopt(actor.organizationId, rule, assignmentId, expectedVersion, _))
  } yield ()

  /** Every active managed assignment against the target revision, with every incompatibility named. */
  def promotionPreview(organizationId: UUID, ruleId: UUID, targetRevision: Int,
                       expectedRuleVersion: Int): F[ConfigurationRulePromotionPreview] = reads.run(for {
    rule <- rules.find(organizationId, ruleId).flatMap(_.liftTo[Tx](NotFound))
    _ <- MonadThrow[Tx].raiseWhen(rule.archived)(Archived)
    _ <- MonadThrow[Tx].raiseUnless(rule.version == expectedRuleVersion)(Changed)
    revision <- revisionOf(organizationId, rule.profileId, targetRevision)
    _ <- MonadThrow[Tx].raiseWhen(revision._1.archived)(ProfileArchived)
    candidates <- managed.managed(organizationId, ruleId, MaxPromotion + 1, lock = false)
    _ <- MonadThrow[Tx].raiseWhen(candidates.size > MaxPromotion)(PromotionTooLarge)
  } yield ConfigurationRulePromotionPreview(rule.version, rule.profileRevisionNumber, targetRevision,
    candidates.map(item(revision._2))))

  /** The rule and every managed assignment move to the revision together, or nothing moves. */
  def promote(actor: ActorContext, ruleId: UUID, targetRevision: Int, expectedRuleVersion: Int,
              expected: List[ConfigurationPromotionSelection]): F[List[(UUID, Int)]] = writes.run(for {
    locked <- managed.lockRule(actor.organizationId, ruleId).flatMap(_.liftTo[Tx](NotFound))
    (version, _, archived) = locked
    _ <- MonadThrow[Tx].raiseWhen(archived)(Archived)
    _ <- MonadThrow[Tx].raiseUnless(version == expectedRuleVersion)(Changed)
    rule <- rules.find(actor.organizationId, ruleId).flatMap(_.liftTo[Tx](NotFound))
    revision <- revisionOf(actor.organizationId, rule.profileId, targetRevision)
    _ <- MonadThrow[Tx].raiseWhen(revision._1.archived)(ProfileArchived)
    // Locked in id order: exactly the set that was previewed, each at the version that was previewed.
    candidates <- managed.managed(actor.organizationId, ruleId, MaxPromotion + 1, lock = true)
    _ <- MonadThrow[Tx].raiseWhen(candidates.size > MaxPromotion)(PromotionTooLarge)
    current = candidates.map(c => c.assignment.id -> c.assignment.version).toMap
    _ <- MonadThrow[Tx].raiseUnless(current == expected.map(e => e.assignmentId -> e.expectedVersion).toMap)(PromotionConflict)
    _ <- MonadThrow[Tx].raiseUnless(candidates.map(item(revision._2)).forall(_.compatible))(PromotionIncompatible)
    now <- time.now
    versions <- managed.promote(actor.organizationId, ruleId, targetRevision, candidates.map(_.assignment.id), now)
    _ <- audit.record(actor, AuditAction.ConfigurationRuleRevisionPromoted, AuditTargetType.ConfigurationAssignmentRule, Some(ruleId))
  } yield versions)

  private def item(revision: ConfigurationRevision)(candidate: ConfigurationPromotionCandidate): ConfigurationPromotionItem =
    ConfigurationPromotionItem(candidate.assignment.id, candidate.assignment.version, Some(candidate.resourceName),
      Some(candidate.assignment.profileRevisionNumber), ConfigurationPromotions.issues(revision, candidate.values))

  private def managedWrite(actor: ActorContext, assignmentId: UUID, action: AuditAction)(
    write: Instant => Tx[ManagedAssignmentWrite]): F[Unit] = writes.run(for {
    now <- time.now
    written <- write(now)
    _ <- written match {
      case ManagedAssignmentWrite.Written => ().pure[Tx]
      case ManagedAssignmentWrite.Missing => MonadThrow[Tx].raiseError[Unit](AssignmentNotFound)
      case ManagedAssignmentWrite.Stale => MonadThrow[Tx].raiseError[Unit](AssignmentChanged)
      case ManagedAssignmentWrite.NotManaged => MonadThrow[Tx].raiseError[Unit](NotManaged)
      case ManagedAssignmentWrite.AlreadyManaged => MonadThrow[Tx].raiseError[Unit](AlreadyManaged)
      case ManagedAssignmentWrite.Incompatible => MonadThrow[Tx].raiseError[Unit](Incompatible)
      case ManagedAssignmentWrite.NotEligible => MonadThrow[Tx].raiseError[Unit](NotEligible)
    }
    _ <- audit.record(actor, action, AuditTargetType.ConfigurationAssignment, Some(assignmentId))
  } yield ())

  private def existing(organizationId: UUID, id: UUID): Tx[ConfigurationAssignmentRule] =
    rules.find(organizationId, id).flatMap(_.liftTo[Tx](NotFound)).flatTap(rule => MonadThrow[Tx].raiseWhen(rule.archived)(Archived))

  private def references(organizationId: UUID, selector: ConfigurationRuleSelector): Tx[Unit] =
    rules.validReferences(organizationId, selector.projects, selector.environments)
      .flatMap(valid => MonadThrow[Tx].raiseUnless(valid)(selectorInvalid("unknown project or environment")))

  private def revisionOf(organizationId: UUID, profileId: UUID, revisionNumber: Int): Tx[(ConfigurationProfile, ConfigurationRevision)] =
    for {
      profile <- profiles.find(organizationId, profileId).flatMap(_.liftTo[Tx](ProfileNotFound))
      _ <- MonadThrow[Tx].raiseUnless(profile.kind == ConfigurationProfileKind.FileTemplate)(ProfileWrongKind)
      revision <- profiles.findRevision(organizationId, profileId, revisionNumber).flatMap(_.liftTo[Tx](RevisionNotFound))
    } yield (profile, revision.revision)

  private def ruleWritten(written: RuleWrite): Tx[Unit] = written match {
    case RuleWrite.Written => ().pure[Tx]
    case RuleWrite.Missing => MonadThrow[Tx].raiseError(NotFound)
    case RuleWrite.Stale => MonadThrow[Tx].raiseError(Changed)
    case RuleWrite.Archived => MonadThrow[Tx].raiseError(Archived)
    case RuleWrite.Disabled => MonadThrow[Tx].raiseError(Disabled)
  }
}

object ConfigurationAssignmentRules {
  /** The most managed assignments one promotion moves in a single transaction. */
  val MaxPromotion = 500
  val Eligible = "ELIGIBLE"
  val Adoptable = "ADOPTABLE"
}

package ru.bitec.app.ops
package application.configuration

import application.port._
import cats.effect.IO
import cats.syntax.all._
import domain.configuration._
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/** How rule reconciliation is paced and bounded. */
final case class ConfigurationRuleSettings(
  enabled: Boolean = true,
  pollInterval: FiniteDuration = 5.seconds,
  /** Every enabled rule is reconciled at least this often, whatever events were missed. */
  reconcileInterval: FiniteDuration = 45.seconds,
  leaseDuration: FiniteDuration = 2.minutes,
  batchSize: Int = 100,
  maxRulesPerTick: Int = 20
) {
  require(batchSize >= 1 && maxRulesPerTick >= 1, "Invalid configuration rule settings")
}

/** Keeps the assignments of every enabled rule present on the resources its selector matches.
  *
  * It only reads and writes desired state in the database. It has no transport, no credential and
  * no deployment dependency: it can create an assignment, never apply one. Each rule is claimed
  * under a lease; every write is fenced on that lease and on the exact rule version, so a rule changed
  * mid-sweep stops the sweep, and another instance may take over an expired lease.
  */
final class ConfigurationAssignmentRuleWorker[Tx[_]](
  rules: ConfigurationAssignmentRuleRepository[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  ids: IdGenerator[Tx],
  runner: TransactionRunner[IO, Tx],
  owner: UUID,
  settings: ConfigurationRuleSettings,
  logger: Logger[IO],
  clock: IO[Instant] = IO.realTimeInstant,
  scope: Option[UUID] = None
) {
  import ConfigurationAssignmentRuleWorker._

  def run: IO[Nothing] =
    (tick.handleErrorWith(error => logger.error(error)("configuration.rule.worker.failed")) *>
      IO.sleep(settings.pollInterval)).foreverM

  /** Reconciles every rule that is due, one full sweep each. */
  def tick: IO[Unit] = {
    def next(remaining: Int): IO[Unit] =
      if (remaining <= 0) IO.unit
      else for {
        token <- IO(UUID.randomUUID())
        now <- clock
        claimed <- runner.run(rules.claim(owner, token, now, now.plusMillis(settings.leaseDuration.toMillis), scope))
        _ <- claimed.traverse_(rule => reconcile(rule) *> next(remaining - 1))
      } yield ()
    next(settings.maxRulesPerTick)
  }

  private def reconcile(claimed: ClaimedRule): IO[Unit] = {
    val rule = claimed.rule
    val context = s"organizationId=${rule.organizationId} ruleId=${rule.id} ruleVersion=${rule.version} " +
      s"profileId=${rule.profileId} profileRevisionNumber=${rule.profileRevisionNumber}"
    val sweep = for {
      started <- clock
      revision <- runner.run(profiles.findRevision(rule.organizationId, rule.profileId, rule.profileRevisionNumber))
      // Revision defaults only: without explicit values an assignment is created only if it is valid.
      missing = revision.fold[Option[Option[String]]](Some(None))(found =>
        ConfigurationDesiredState.render(found.revision, Nil).swap.toOption.map(error => Option(error.variableName).filter(_.nonEmpty)))
      created <- pages(claimed, missing, None, 0)
      now <- clock
      finished <- runner.run(rules.finishSweep(claimed, started, now, now.plusMillis(settings.reconcileInterval.toMillis)))
      _ <- IO.raiseUnless(finished)(Fenced)
      _ <- if (created > 0) logger.info(s"configuration.rule.reconciled $context created=$created") else IO.unit
    } yield ()
    sweep.handleErrorWith {
      case Fenced => logger.info(s"configuration.rule.reconcile_stopped $context reason=fenced")
      case error => logger.error(error)(s"configuration.rule.reconcile_failed $context")
    }
  }

  /** Walks the matches a bounded page at a time, by resource id; never the whole table in memory. */
  private def pages(claimed: ClaimedRule, missing: Option[Option[String]], after: Option[UUID], created: Int): IO[Int] =
    for {
      page <- runner.run(rules.candidates(claimed.rule.organizationId, claimed.rule.selector, Some(claimed.rule.id),
        claimed.rule.targetPath, after, settings.batchSize))
      results <- page.traverse(candidate => handle(claimed, missing, candidate))
      // Fenced like every other write: a stale worker never clears what a current one recorded.
      cleared <- clock.flatMap(now => runner.run(rules.clearIssues(claimed,
        page.filter(_.occupantSourceRuleId.contains(claimed.rule.id)).map(_.resourceId), now)))
      _ <- IO.raiseUnless(cleared)(Fenced)
      now <- clock
      alive <- runner.run(rules.renew(claimed, now, now.plusMillis(settings.leaseDuration.toMillis)))
      _ <- IO.raiseUnless(alive)(Fenced)
      total = created + results.count(identity)
      more <- if (page.size < settings.batchSize) IO.pure(total) else pages(claimed, missing, page.lastOption.map(_.resourceId), total)
    } yield more

  /** One resource: create the assignment, or record why it cannot exist yet. True when created. */
  private def handle(claimed: ClaimedRule, missing: Option[Option[String]], candidate: RuleCandidate): IO[Boolean] = {
    val rule = claimed.rule
    def issue(code: ConfigurationRuleIssueCode, variable: Option[String] = None, conflicting: Option[UUID] = None): IO[Boolean] =
      clock.flatMap(now => runner.run(rules.recordIssue(claimed, candidate.resourceId, code, variable, conflicting, now)))
        .flatMap(written => IO.raiseUnless(written)(Fenced)).as(false)
    (candidate.occupantAssignmentId, candidate.occupantSourceRuleId) match {
      case _ if candidate.excluded => IO.pure(false)
      case (Some(_), Some(source)) if source == rule.id => IO.pure(false)
      // A conflict is shown, never resolved by the rule: no priorities, no takeover.
      case (Some(occupant), Some(_)) => issue(ConfigurationRuleIssueCode.OtherRuleConflict, conflicting = Some(occupant))
      case (Some(occupant), None) => issue(ConfigurationRuleIssueCode.TargetPathConflict, conflicting = Some(occupant))
      case (None, _) if claimed.profileArchived => issue(ConfigurationRuleIssueCode.ProfileArchived)
      case (None, _) if missing.isDefined => issue(ConfigurationRuleIssueCode.NeedsValues, missing.flatten)
      case (None, _) => for {
        id <- runner.run(ids.nextId)
        now <- clock
        assignment = ConfigurationAssignment(id, rule.organizationId, candidate.resourceId, rule.profileId,
          rule.profileRevisionNumber, rule.targetPath, 1, None, now, now, Some(rule.id))
        outcome <- runner.run(rules.autoCreate(claimed, candidate.resourceId, assignment, now))
        created <- outcome match {
          case RuleAutoCreate.Created =>
            logger.info(s"configuration.rule.assignment_created organizationId=${rule.organizationId} ruleId=${rule.id} " +
              s"resourceId=${candidate.resourceId} assignmentId=$id").as(true)
          case RuleAutoCreate.Fenced => IO.raiseError(Fenced)
          case RuleAutoCreate.NoLongerEligible => IO.pure(false)
          case RuleAutoCreate.Occupied(occupant, Some(source)) if source != rule.id =>
            issue(ConfigurationRuleIssueCode.OtherRuleConflict, conflicting = Some(occupant))
          case RuleAutoCreate.Occupied(_, Some(_)) => IO.pure(false)
          case RuleAutoCreate.Occupied(occupant, None) => issue(ConfigurationRuleIssueCode.TargetPathConflict, conflicting = Some(occupant))
        }
      } yield created
    }
  }
}

object ConfigurationAssignmentRuleWorker {
  private case object Fenced extends RuntimeException("Rule lease or version lost") with NoStackTrace
}

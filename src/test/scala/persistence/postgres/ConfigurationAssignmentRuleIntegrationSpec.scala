package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.configuration._
import application.port._
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._
import domain.audit.{AuditCursor, AuditEvent}
import domain.configuration._
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.util.log.{LogEvent, LogHandler}
import org.typelevel.doobie.{ConnectionIO, Transactor, WeakAsync}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID
import scala.concurrent.duration._

/** Resource labels, selectors and rules that keep assignments present, against a real database.
  * Nothing here has a transport: rules create desired state and never touch a server.
  */
final class ConfigurationAssignmentRuleIntegrationSpec extends FunSuite {
  private val Path = "/etc/xray/config.json"
  private val Defaults = List(
    ConfigurationVariableDefinition("domain", ConfigurationValueType.StringType, required = true, Some("vpn.example"), None),
    ConfigurationVariableDefinition("region", ConfigurationValueType.StringType, required = false, Some("eu"), None))
  private val Template = "server_name {{ domain }};\nregion {{ region }};\n"

  private final class Harness(val w: ConfigurationDeploymentWorld) {
    val rules = new PostgresConfigurationAssignmentRuleRepository
    def serviceWith(audit: AuditRecorder[ConnectionIO] = w.audit) =
      new ConfigurationAssignmentRules[IO, ConnectionIO](rules, rules, w.assignmentRepository, w.assignmentQuery,
        w.profileQuery, w.ids, w.time, audit, w.runner, w.runner)
    val service = serviceWith()
    def labelsWith(audit: AuditRecorder[ConnectionIO] = w.audit) =
      new ResourceLabels[IO, ConnectionIO](new PostgresResourceLabelRepository, w.time, audit, w.runner, w.runner)
    val labels = labelsWith()
    val failingAudit = new AuditRecorder[ConnectionIO](FailingAuditEvents, w.ids, w.time)
    val logger = org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("test.configuration.rule")
    // A second ahead, so a reconcile scheduled by the database clock "now" is always due.
    def worker(owner: UUID = UUID.randomUUID(), ahead: Long = 1) = new ConfigurationAssignmentRuleWorker[ConnectionIO](
      rules, w.profileQuery, w.ids, w.runner, owner, ConfigurationRuleSettings(batchSize = 7), logger,
      IO.realTimeInstant.map(_.plusSeconds(ahead)), Some(w.org))

    def profile(template: String = Template, variables: List[ConfigurationVariableDefinition] = Defaults): IO[UUID] =
      w.run(w.profiles.create(w.actor, CreateConfigurationProfileCommand(s"xray-${UUID.randomUUID().toString.take(8)}",
        ConfigurationProfileMetadata("Xray Default", None), w.content(template, variables)))).map(_._1.id)

    def label(resource: UUID, pairs: (String, String)*): IO[Unit] =
      labels.get(w.org, resource).flatMap(set => labels.replace(w.actor, resource, set.version, pairs.toList)).void

    def selector(environments: List[UUID] = Nil, required: List[(String, String)] = List("role" -> "vpn"),
                 excluded: List[(String, String)] = Nil, projects: List[UUID] = Nil) =
      ConfigurationRuleSelector(projects, environments, required.map((ResourceLabel.apply _).tupled),
        excluded.map((ResourceLabel.apply _).tupled))

    def rule(profile: UUID, sel: ConfigurationRuleSelector = selector(), enabled: Boolean = true, revision: Int = 1,
             path: String = Path): IO[UUID] =
      service.create(w.actor, ConfigurationRuleDraft(s"rule-${UUID.randomUUID().toString.take(8)}", "VPN Production", None,
        profile, revision, path, sel, enabled))

    def reconcile(ruleId: UUID): IO[Unit] = service.reconcileNow(w.actor, ruleId).attempt *> worker().tick

    def assigned(resource: UUID): IO[List[ConfigurationAssignment]] =
      w.run(sql"""select id from configuration_assignment where organization_id = ${w.org} and resource_id = $resource
                  and removed_at is null""".query[UUID].to[List]).flatMap(_.traverse(w.assignment))

    def issue(ruleId: UUID, resource: UUID): IO[Option[(String, Option[String])]] =
      w.run(sql"""select issue_code, variable_name from configuration_assignment_rule_issue
                  where rule_id = $ruleId and resource_id = $resource""".query[(String, Option[String])].option)

    def ruleOf(id: UUID): IO[ConfigurationAssignmentRule] = w.run(rules.find(w.org, id)).map(_.get)

    def statements[A](program: ConnectionIO[A]): IO[Int] =
      Ref.of[IO, Int](0).flatMap { counter =>
        val handler = new LogHandler[IO] { override def run(event: LogEvent): IO[Unit] = counter.update(_ + 1) }
        val config = PostgresTestDatabase.config
        val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, Some(handler))
        new DoobieTransactionRunner(xa).run(program) *> counter.get
      }
  }

  private def world(body: Harness => IO[Unit]): Unit = ConfigurationDeploymentWorld.run(w => body(new Harness(w)))

  private def code(result: Either[Throwable, _]): Option[String] = result.swap.toOption.collect {
    case e: ConfigurationRuleError => e.code
    case e: ResourceLabelError => e.code
    case e: ConfigurationAssignmentError => e.code
  }

  private def waitsFor(w: ConfigurationDeploymentWorld, blockerPid: Int): IO[Boolean] = {
    val blocked = w.run(sql"""select exists (select 1 from pg_stat_activity
                                where datname = current_database() and wait_event_type = 'Lock'
                                  and $blockerPid = any(pg_blocking_pids(pid))
                                  and query ilike '%resource%')""".query[Boolean].unique)
    def poll(left: Int): IO[Boolean] =
      blocked.flatMap(waiting => if (waiting || left == 0) IO.pure(waiting) else IO.sleep(20.millis) *> poll(left - 1))
    poll(250)
  }

  // Labels

  test("labels replace as a whole by compare-and-set, canonical, bounded, per resource and per tenant") {
    world { h =>
      val w = h.w
      for {
        node <- w.resource("fi")
        other <- w.resource("de")
        foreign <- w.resource("foreign", w.defaultEnvironment(w.foreignOrg), w.foreignOrg)
        empty <- h.labels.get(w.org, node)
        first <- h.labels.replace(w.actor, node, 0, List("Role" -> "vpn", "region" -> "EU"))
        second <- h.labels.replace(w.actor, node, 1, List("role" -> "vpn"))
        stale <- h.labels.replace(w.actor, node, 1, List("role" -> "db")).attempt
        duplicate <- h.labels.replace(w.actor, node, 2, List("role" -> "a", "ROLE" -> "b")).attempt
        tooMany <- h.labels.replace(w.actor, node, 2, (1 to 33).toList.map(i => s"k$i" -> "v")).attempt
        badValue <- h.labels.replace(w.actor, node, 2, List("role" -> "a\nb")).attempt
        sameKeyElsewhere <- h.labels.replace(w.actor, other, 0, List("role" -> "vpn"))
        crossTenant <- h.labels.replace(w.actor, foreign, 0, List("role" -> "vpn")).attempt
        failed <- h.labelsWith(h.failingAudit).replace(w.actor, node, 2, List("role" -> "db")).attempt
        stored <- h.labels.get(w.org, node)
      } yield {
        assertEquals(empty.version, 0)
        assertEquals(first.labels, List(ResourceLabel("region", "EU"), ResourceLabel("role", "vpn")))
        assertEquals((first.version, second.version), (1, 2))
        assertEquals(code(stale), Some("RESOURCE_LABELS_CHANGED"))
        assertEquals(List(duplicate, tooMany, badValue).map(code), List.fill(3)(Some("INVALID_RESOURCE_LABELS")))
        assertEquals(sameKeyElsewhere.version, 1)
        assertEquals(code(crossTenant), Some("RESOURCE_NOT_FOUND"))
        assert(failed.isLeft)
        assertEquals(stored, ResourceLabelSet(node, 2, List(ResourceLabel("role", "vpn"))))
      }
    }
  }

  // Selector

  test("a selector matches active nodes by environment, project and labels, and nothing else") {
    world { h =>
      val w = h.w
      for {
        prod <- w.environment("prod")
        staging <- w.environment("staging")
        n1 <- w.resource("n1", prod._2)
        n2 <- w.resource("n2", prod._2)
        n3 <- w.resource("n3", staging._2)
        n4 <- w.resource("n4", prod._2)
        inactive <- w.resource("n5", prod._2, active = false)
        container <- w.resource("c1", prod._2, typeId = w.ContainerType)
        foreign <- w.resource("f1", w.defaultEnvironment(w.foreignOrg), w.foreignOrg)
        _ <- List(n1 -> List("role" -> "vpn", "region" -> "eu"), n2 -> List("role" -> "vpn", "maintenance" -> "true"),
          n3 -> List("role" -> "vpn"), n4 -> List("role" -> "db"), inactive -> List("role" -> "vpn"),
          container -> List("role" -> "vpn")).traverse_ { case (id, pairs) => h.label(id, pairs: _*) }
        _ <- w.run(sql"""insert into resource_label (organization_id, resource_id, key, value, created_at, updated_at)
                         values (${w.foreignOrg}, $foreign, 'role', 'vpn', now(), now())""".update.run)
        matches = (s: ConfigurationRuleSelector) =>
          w.run(h.rules.candidates(w.org, s, None, Path, None, 100)).map(_.map(_.resourceId).toSet)
        prodVpn <- matches(h.selector(environments = List(prod._2), excluded = List("maintenance" -> "true")))
        allVpn <- matches(h.selector())
        bothEnvs <- matches(h.selector(environments = List(prod._2, staging._2)))
        allLabels <- matches(h.selector(required = List("role" -> "vpn", "region" -> "eu")))
        byProject <- matches(h.selector(projects = List(staging._1)))
        projectAndEnv <- matches(h.selector(projects = List(staging._1), environments = List(prod._2)))
        missing <- matches(h.selector(required = List("role" -> "web")))
      } yield {
        assertEquals(prodVpn, Set(n1))
        assertEquals(allVpn, Set(n1, n2, n3))
        assertEquals(bothEnvs, Set(n1, n2, n3))
        assertEquals(allLabels, Set(n1))
        assertEquals(byProject, Set(n3))
        assertEquals(projectAndEnv, Set.empty[UUID])
        assertEquals(missing, Set.empty[UUID])
      }
    }
  }

  test("selectors are bounded, canonical and consistent") {
    val tooMany = ConfigurationRuleSelector(Nil, Nil, (1 to 17).toList.map(i => ResourceLabel(s"k$i", "v")), Nil)
    assert(ConfigurationRuleSelector.validate(tooMany).isLeft)
    assert(ConfigurationRuleSelector.validate(ConfigurationRuleSelector(Nil, Nil, List(ResourceLabel("Role", "x")), Nil)).isLeft)
    assert(ConfigurationRuleSelector.validate(ConfigurationRuleSelector(Nil, Nil, List(ResourceLabel("a", "x")),
      List(ResourceLabel("a", "x")))).isLeft)
  }

  // Reconciliation

  test("matching nodes get managed assignments; non-matching, excluded and later ones behave") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        fi <- w.resource("fi")
        de <- w.resource("de")
        db <- w.resource("db")
        excluded <- w.resource("nl")
        _ <- h.label(fi, "role" -> "vpn") *> h.label(de, "role" -> "vpn") *> h.label(db, "role" -> "db") *>
          h.label(excluded, "role" -> "vpn")
        ruleId <- h.rule(profile, enabled = false)
        _ <- h.service.exclude(w.actor, ruleId, excluded)
        rule <- h.ruleOf(ruleId)
        _ <- h.service.setEnabled(w.actor, ruleId, rule.version, enabled = true)
        _ <- h.worker().tick
        first <- List(fi, de, db, excluded).traverse(h.assigned)
        // A node that appears later is picked up by the next periodic sweep, with no event.
        fr <- w.resource("fr")
        _ <- h.label(fr, "role" -> "vpn")
        _ <- h.reconcile(ruleId)
        later <- h.assigned(fr)
        // Stopping to match never removes desired state.
        _ <- h.label(de, "role" -> "db")
        _ <- w.run(sql"update resource set is_active = false where id = $fi".update.run)
        _ <- h.reconcile(ruleId)
        kept <- List(fi, de).traverse(h.assigned)
        targets <- h.service.targets(w.org, ruleId, None, 50)
        deployments <- w.run(sql"select count(*) from configuration_deployment where organization_id = ${w.org}".query[Long].unique)
      } yield {
        assertEquals(first.map(_.map(_.sourceRuleId)), List(List(Some(ruleId)), List(Some(ruleId)), Nil, Nil))
        assertEquals(first.head.head.profileRevisionNumber, 1)
        assertEquals(later.map(_.sourceRuleId), List(Some(ruleId)))
        assertEquals(kept.map(_.size), List(1, 1))
        val status = targets._2.map(row => row.resourceName -> ConfigurationRuleTargets.status(ruleId, row.matches,
          row.excluded, row.assignmentSourceRuleId, row.issueCode).code).toMap
        assertEquals(status, Map("fi" -> "NO_LONGER_MATCHING", "de" -> "NO_LONGER_MATCHING", "fr" -> "ASSIGNED",
          "nl" -> "EXCLUDED"))
        // Rules never deploy.
        assertEquals(deployments, 0L)
      }
    }
  }

  test("label and environment changes that start matching create assignments at the next reconcile") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        prod <- w.environment("prod")
        staging <- w.environment("staging")
        a <- w.resource("a", prod._2)
        b <- w.resource("b", staging._2)
        _ <- h.label(b, "role" -> "vpn")
        ruleId <- h.rule(profile, h.selector(environments = List(prod._2)))
        _ <- h.worker().tick
        before <- List(a, b).traverse(h.assigned)
        _ <- h.label(a, "role" -> "vpn")
        _ <- w.run(sql"update resource set environment_id = ${prod._2} where id = $b".update.run)
        _ <- h.reconcile(ruleId)
        after <- List(a, b).traverse(h.assigned)
      } yield {
        assertEquals(before.map(_.size), List(0, 0))
        assertEquals(after.map(_.map(_.sourceRuleId)), List(List(Some(ruleId)), List(Some(ruleId))))
      }
    }
  }

  test("disabled and archived rules create nothing; re-enabling does") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile, enabled = false)
        _ <- h.worker().tick
        disabled <- h.assigned(node)
        rule <- h.ruleOf(ruleId)
        _ <- h.service.setEnabled(w.actor, ruleId, rule.version, enabled = true)
        _ <- h.worker().tick
        enabled <- h.assigned(node)
        other <- w.resource("de")
        _ <- h.label(other, "role" -> "vpn")
        current <- h.ruleOf(ruleId)
        _ <- h.service.archive(w.actor, ruleId, current.version)
        archivedReconcile <- h.service.reconcileNow(w.actor, ruleId).attempt
        _ <- h.worker().tick
        archived <- h.assigned(other)
        kept <- h.assigned(node)
      } yield {
        assertEquals(disabled, Nil)
        assertEquals(enabled.map(_.sourceRuleId), List(Some(ruleId)))
        assertEquals(code(archivedReconcile), Some("CONFIGURATION_RULE_ARCHIVED"))
        assertEquals(archived, Nil)
        assertEquals(kept.size, 1)
      }
    }
  }

  test("an archived profile and missing values create no invalid assignment, but an issue") {
    world { h =>
      val w = h.w
      for {
        needsValues <- h.profile(variables = List(
          ConfigurationVariableDefinition("domain", ConfigurationValueType.StringType, required = true, None, None),
          ConfigurationVariableDefinition("region", ConfigurationValueType.StringType, required = false, Some("eu"), None)))
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(needsValues)
        _ <- h.worker().tick
        none <- h.assigned(node)
        issue <- h.issue(ruleId, node)
        // Completing the target makes a valid managed assignment with the given value.
        incomplete <- h.service.completeAssignment(w.actor, ruleId, node, Nil).attempt
        created <- h.service.completeAssignment(w.actor, ruleId, node, List(ConfigurationVariableValue("domain", "fi.example")))
        _ <- h.reconcile(ruleId)
        cleared <- h.issue(ruleId, node)
        values <- w.run(w.assignmentQuery.values(w.org, created))
        // An archived profile: nothing new is created, the existing assignment stays.
        archivedProfile <- h.profile()
        other <- w.resource("de")
        _ <- h.label(other, "role" -> "db")
        archivedRule <- h.rule(archivedProfile, h.selector(required = List("role" -> "db")), enabled = false)
        _ <- w.run(w.profiles.archive(w.actor, archivedProfile))
        rule <- h.ruleOf(archivedRule)
        _ <- h.service.setEnabled(w.actor, archivedRule, rule.version, enabled = true)
        _ <- h.worker().tick
        archivedAssignments <- h.assigned(other)
        archivedIssue <- h.issue(archivedRule, other)
      } yield {
        assertEquals(none, Nil)
        assertEquals(issue, Some("NEEDS_VALUES" -> Some("domain")))
        assertEquals(code(incomplete), Some("CONFIGURATION_RULE_NEEDS_VALUES"))
        assertEquals(cleared, None)
        assertEquals(values, List(ConfigurationVariableValue("domain", "fi.example")))
        assertEquals(archivedAssignments, Nil)
        assertEquals(archivedIssue.map(_._1), Some("PROFILE_ARCHIVED"))
      }
    }
  }

  test("a path taken manually or by another rule is a conflict, never a takeover; resolving it lets the rule in") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        manualNode <- w.node("fi")
        ruleNode <- w.resource("de")
        _ <- h.label(manualNode.resourceId, "role" -> "vpn") *> h.label(ruleNode, "role" -> "vpn", "tier" -> "gold")
        manual <- w.assignments.create(w.actor, manualNode.resourceId, profile, ConfigurationAssignmentDraft(1, Path,
          List(ConfigurationVariableValue("domain", "manual.example"))))
        first <- h.rule(profile, h.selector(required = List("tier" -> "gold")))
        _ <- h.worker().tick
        second <- h.rule(profile)
        _ <- h.worker().tick
        manualIssue <- h.issue(second, manualNode.resourceId)
        ruleIssue <- h.issue(second, ruleNode)
        manualKept <- w.assignment(manual)
        current <- w.assignment(manual)
        _ <- w.assignments.remove(w.actor, manual, current.version)
        _ <- h.reconcile(second)
        resolved <- h.assigned(manualNode.resourceId)
        cleared <- h.issue(second, manualNode.resourceId)
      } yield {
        assertEquals(manualIssue.map(_._1), Some("TARGET_PATH_CONFLICT"))
        assertEquals(ruleIssue.map(_._1), Some("OTHER_RULE_CONFLICT"))
        assertEquals(manualKept.sourceRuleId, None)
        assertEquals(resolved.map(_.sourceRuleId), List(Some(second)))
        assertEquals(cleared, None)
        assert(first != second)
      }
    }
  }

  // Concurrency

  test("two workers and two rules never create two assignments for one path") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        nodes <- (1 to 20).toList.traverse(i => w.resource(s"n$i"))
        _ <- nodes.traverse_(node => h.label(node, "role" -> "vpn"))
        a <- h.rule(profile)
        b <- h.rule(profile)
        _ <- (h.worker().tick, h.worker().tick, h.worker().tick).parTupled
        counts <- nodes.traverse(h.assigned).map(_.map(_.size))
        issues <- w.run(sql"""select count(*) from configuration_assignment_rule_issue where organization_id = ${w.org}
                              and issue_code = 'OTHER_RULE_CONFLICT'""".query[Long].unique)
      } yield {
        assertEquals(counts, List.fill(20)(1))
        assertEquals(issues, 20L)
        assert(a != b)
      }
    }
  }

  test("a worker holding an old rule version cannot create anything; an expired lease is taken over") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile)
        now <- IO.realTimeInstant.map(_.plusSeconds(1))
        claimed <- w.run(h.rules.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(30), Some(w.org))).map(_.get)
        second <- w.run(h.rules.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(30), Some(w.org)))
        rule <- h.ruleOf(ruleId)
        _ <- h.service.update(w.actor, ruleId, rule.version, "Renamed", None, h.selector(required = List("role" -> "vpn")))
        fenced <- w.run(h.rules.autoCreate(claimed, node, ConfigurationAssignment(UUID.randomUUID(), w.org, node,
          profile, 1, Path, 1, None, now, now, Some(ruleId)), now))
        issue <- w.run(h.rules.recordIssue(claimed, node, ConfigurationRuleIssueCode.NeedsValues, None, None, now))
        renew <- w.run(h.rules.renew(claimed, now, now.plusSeconds(30)))
        before <- h.assigned(node)
        // The update rescheduled the rule: a fresh claim works with the new version.
        _ <- h.worker().tick
        after <- h.assigned(node)
        // An abandoned lease blocks nobody once it expires.
        _ <- h.service.reconcileNow(w.actor, ruleId)
        late <- IO.realTimeInstant.map(_.plusSeconds(5))
        dead <- w.run(h.rules.claim(UUID.randomUUID(), UUID.randomUUID(), late, late.plusSeconds(1), Some(w.org)))
        taken <- w.run(h.rules.claim(UUID.randomUUID(), UUID.randomUUID(), late.plusSeconds(2), late.plusSeconds(60), Some(w.org)))
      } yield {
        assertEquals(second, None)
        assertEquals(fenced, RuleAutoCreate.Fenced)
        assert(!issue && !renew)
        assertEquals(before, Nil)
        assertEquals(after.map(_.sourceRuleId), List(Some(ruleId)))
        assert(dead.isDefined && taken.isDefined)
      }
    }
  }

  test("a stale worker cannot clear an issue a current reconciliation recorded, after a rule change or a lease takeover") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        quiet <- w.resource("de")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile)
        now <- IO.realTimeInstant.map(_.plusSeconds(1))
        claim = (at: java.time.Instant, until: java.time.Instant) =>
          w.run(h.rules.claim(UUID.randomUUID(), UUID.randomUUID(), at, until, Some(w.org))).map(_.get)
        // Worker A claims; the rule changes (new version, lease dropped); worker B works on the new version.
        a <- claim(now, now.plusSeconds(30))
        rule <- h.ruleOf(ruleId)
        _ <- h.service.update(w.actor, ruleId, rule.version, "Renamed", None, h.selector(required = List("role" -> "vpn")))
        b <- claim(now, now.plusSeconds(30))
        recorded <- w.run(h.rules.recordIssue(b, node, ConfigurationRuleIssueCode.NeedsValues, Some("domain"), None, now))
        staleAfterChange <- w.run(h.rules.clearIssues(a, List(node), now))
        keptAfterChange <- h.issue(ruleId, node)
        // Same version, lease taken over: B's lease expired, C holds the rule now.
        later = now.plusSeconds(60)
        c <- claim(later, later.plusSeconds(30))
        _ <- w.run(h.rules.recordIssue(c, node, ConfigurationRuleIssueCode.TargetPathConflict, None, None, later))
        staleAfterTakeover <- w.run(h.rules.clearIssues(b, List(node), later))
        keptAfterTakeover <- h.issue(ruleId, node)
        // The current holder: true whether or not a row was there, and its issues are gone.
        nothingThere <- w.run(h.rules.clearIssues(c, List(quiet), later))
        current <- w.run(h.rules.clearIssues(c, List(node), later))
        cleared <- h.issue(ruleId, node)
      } yield {
        assertEquals((b.rule.version, c.rule.version), (a.rule.version + 1, a.rule.version + 1))
        assert(recorded)
        assertEquals(staleAfterChange, false)
        assertEquals(keptAfterChange, Some("NEEDS_VALUES" -> Some("domain")))
        assertEquals(staleAfterTakeover, false)
        assertEquals(keptAfterTakeover, Some("TARGET_PATH_CONFLICT" -> None))
        assertEquals((nothingThere, current), (true, true))
        assertEquals(cleared, None)
      }
    }
  }

  test("two rule updates at the same version: one wins, the other is told the rule changed") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        ruleId <- h.rule(profile)
        rule <- h.ruleOf(ruleId)
        results <- (h.service.update(w.actor, ruleId, rule.version, "A", None, h.selector()).attempt,
          h.service.update(w.actor, ruleId, rule.version, "B", None, h.selector()).attempt).parTupled
        after <- h.ruleOf(ruleId)
        disable <- h.service.setEnabled(w.actor, ruleId, rule.version, enabled = false).attempt
      } yield {
        assertEquals(List(results._1, results._2).count(_.isRight), 1)
        assertEquals(List(results._1, results._2).flatMap(r => code(r)), List("CONFIGURATION_RULE_CHANGED"))
        assertEquals(after.version, rule.version + 1)
        assertEquals(code(disable), Some("CONFIGURATION_RULE_CHANGED"))
      }
    }
  }

  // Managed assignments

  test("detach keeps the desired state and excludes the resource; stale or failing detach changes nothing") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile)
        _ <- h.worker().tick
        managed <- h.assigned(node).map(_.head)
        // Per-node values stay editable on a managed assignment; what the rule manages does not.
        _ <- w.assignments.update(w.actor, managed.id, 1, ConfigurationAssignmentDraft(1, Path,
          List(ConfigurationVariableValue("region", "fi"))))
        movePath <- w.assignments.update(w.actor, managed.id, 2, ConfigurationAssignmentDraft(1, "/etc/other.json", Nil)).attempt
        remove <- w.assignments.remove(w.actor, managed.id, 2).attempt
        stale <- h.service.detach(w.actor, ruleId, managed.id, 1).attempt
        failed <- h.serviceWith(h.failingAudit).detach(w.actor, ruleId, managed.id, 2).attempt
        exclusionsBefore <- w.run(sql"select count(*) from configuration_assignment_rule_exclusion where rule_id = $ruleId".query[Long].unique)
        _ <- h.service.detach(w.actor, ruleId, managed.id, 2)
        detached <- w.assignment(managed.id)
        values <- w.run(w.assignmentQuery.values(w.org, managed.id))
        exclusions <- w.run(sql"select resource_id from configuration_assignment_rule_exclusion where rule_id = $ruleId".query[UUID].to[List])
        _ <- h.reconcile(ruleId)
        still <- h.assigned(node)
      } yield {
        assertEquals(code(movePath), Some(ConfigurationAssignmentError.ManagedCode))
        assertEquals(code(remove), Some(ConfigurationAssignmentError.ManagedCode))
        assertEquals(code(stale), Some(ConfigurationAssignmentError.ChangedCode))
        assert(failed.isLeft)
        assertEquals(exclusionsBefore, 0L)
        assertEquals((detached.sourceRuleId, detached.version, detached.profileRevisionNumber), (None, 3, 1))
        assertEquals(values, List(ConfigurationVariableValue("region", "fi")))
        assertEquals(exclusions, List(node))
        assertEquals(still.map(_.id), List(managed.id))
      }
    }
  }

  test("adopt takes over only a compatible manual assignment; exclude-and-remove leaves the server alone") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        otherProfile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile, enabled = false)
        manual <- w.assignments.create(w.actor, node, profile, ConfigurationAssignmentDraft(1, Path, Nil))
        wrongPath <- w.assignments.create(w.actor, node, profile, ConfigurationAssignmentDraft(1, "/etc/other.json", Nil))
        wrongProfile <- w.resource("de").flatMap(de => w.assignments.create(w.actor, de, otherProfile,
          ConfigurationAssignmentDraft(1, Path, Nil)))
        badPath <- h.service.adopt(w.actor, ruleId, wrongPath, 1).attempt
        badProfile <- h.service.adopt(w.actor, ruleId, wrongProfile, 1).attempt
        crossTenant <- h.service.adopt(w.foreignActor, ruleId, manual, 1).attempt
        _ <- h.service.adopt(w.actor, ruleId, manual, 1)
        adopted <- w.assignment(manual)
        again <- h.service.adopt(w.actor, ruleId, manual, 2).attempt
        _ <- h.service.excludeAndRemove(w.actor, ruleId, manual, 2)
        removed <- w.assignment(manual)
        excluded <- w.run(sql"select count(*) from configuration_assignment_rule_exclusion where rule_id = $ruleId".query[Long].unique)
      } yield {
        assertEquals(code(badPath), Some("CONFIGURATION_RULE_ASSIGNMENT_INCOMPATIBLE"))
        assertEquals(code(badProfile), Some("CONFIGURATION_RULE_ASSIGNMENT_INCOMPATIBLE"))
        assertEquals(code(crossTenant), Some("CONFIGURATION_RULE_NOT_FOUND"))
        assertEquals((adopted.sourceRuleId, adopted.version), (Some(ruleId), 2))
        assertEquals(code(again), Some("CONFIGURATION_ASSIGNMENT_ALREADY_MANAGED"))
        assert(removed.removedAt.isDefined)
        assertEquals(excluded, 1L)
      }
    }
  }

  test("adopt takes only a resource the selector matches right now and the rule does not exclude") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        database <- w.resource("db")
        excluded <- w.resource("nl")
        matching <- w.resource("fi")
        stale <- w.resource("se")
        _ <- h.label(database, "role" -> "db")
        _ <- List(excluded, matching, stale).traverse_(node => h.label(node, "role" -> "vpn"))
        // Disabled: a rule that creates nothing may still take over a compatible assignment.
        ruleId <- h.rule(profile, enabled = false)
        _ <- h.service.exclude(w.actor, ruleId, excluded)
        manual = (node: UUID) => w.assignments.create(w.actor, node, profile, ConfigurationAssignmentDraft(1, Path, Nil))
        onDatabase <- manual(database)
        onExcluded <- manual(excluded)
        onMatching <- manual(matching)
        onStale <- manual(stale)
        notMatched <- h.service.adopt(w.actor, ruleId, onDatabase, 1).attempt
        notIncluded <- h.service.adopt(w.actor, ruleId, onExcluded, 1).attempt
        staleVersion <- h.service.adopt(w.actor, ruleId, onStale, 7).attempt
        _ <- h.service.adopt(w.actor, ruleId, onMatching, 1)
        after <- List(onDatabase, onExcluded, onStale, onMatching).traverse(w.assignment)
        exclusions <- w.run(sql"select resource_id from configuration_assignment_rule_exclusion where rule_id = $ruleId"
          .query[UUID].to[List])
      } yield {
        assertEquals(code(notMatched), Some("CONFIGURATION_RULE_RESOURCE_NOT_ELIGIBLE"))
        assertEquals(code(notIncluded), Some("CONFIGURATION_RULE_RESOURCE_NOT_ELIGIBLE"))
        assertEquals(code(staleVersion), Some("CONFIGURATION_ASSIGNMENT_CHANGED"))
        assertEquals(after.map(a => (a.sourceRuleId, a.version)),
          List((None, 1), (None, 1), (None, 1), (Some(ruleId), 2)))
        assertEquals(exclusions, List(excluded))
      }
    }
  }

  test("an exclusion committed while an adoption waits leaves the assignment manual, never managed and excluded") {
    world { h =>
      val w = h.w
      val config = PostgresTestDatabase.config
      val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, None)
      // Whether another session waits on a row lock of a rule: the adoption reached the lock.
      val adoptionWaits = w.run(sql"""select exists (select 1 from pg_stat_activity where wait_event_type = 'Lock'
                                      and datname = current_database() and pid <> pg_backend_pid()
                                      and query ilike '%from configuration_assignment_rule%for update%')""".query[Boolean].unique)
      def untilWaiting(left: Int): IO[Boolean] =
        adoptionWaits.flatMap(waiting => if (waiting || left == 0) IO.pure(waiting) else IO.sleep(20.millis) *> untilWaiting(left - 1))
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile, enabled = false)
        assignment <- w.assignments.create(w.actor, node, profile, ConfigurationAssignmentDraft(1, Path, Nil))
        holding <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        now <- IO.realTimeInstant
        // The exclusion's transaction takes the rule lock and stays open until released.
        exclusion <- WeakAsync.liftK[IO, ConnectionIO].use { lift =>
          (h.rules.exclude(w.org, ruleId, node, w.actor.userId, now) <* lift(holding.complete(()) *> release.get)).transact(xa)
        }.start
        _ <- holding.get
        adoption <- h.service.adopt(w.actor, ruleId, assignment, 1).attempt.start
        waited <- untilWaiting(250)
        _ <- release.complete(())
        _ <- exclusion.joinWithNever
        adopted <- adoption.joinWithNever
        after <- w.assignment(assignment)
        excluded <- w.run(sql"""select count(*) from configuration_assignment_rule_exclusion
                                where rule_id = $ruleId and resource_id = $node""".query[Long].unique)
      } yield {
        assert(waited, "the adoption should wait for the rule lock held by the exclusion")
        assertEquals(code(adopted), Some("CONFIGURATION_RULE_RESOURCE_NOT_ELIGIBLE"))
        assertEquals((after.sourceRuleId, after.version), (None, 1))
        assertEquals(excluded, 1L)
      }
    }
  }

  test("label replacement and completion wait in resource-then-rule order without deadlock") {
    world { h =>
      val w = h.w
      val config = PostgresTestDatabase.config
      val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, None)
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile)
        // Ensure replacement's scheduling UPDATE actually locks this rule.
        _ <- w.run(sql"""update configuration_assignment_rule set next_reconcile_at = now() + interval '1 hour'
                         where id = $ruleId""".update.run)
        holding <- Deferred[IO, Int]
        release <- Deferred[IO, Unit]
        now <- IO.realTimeInstant
        replacement <- WeakAsync.liftK[IO, ConnectionIO].use { lift =>
          (for {
            _ <- sql"""select 1 from resource where organization_id = ${w.org} and id = $node
                       for no key update""".query[Int].unique
            pid <- sql"select pg_backend_pid()".query[Int].unique
            _ <- lift(holding.complete(pid) *> release.get)
            result <- new PostgresResourceLabelRepository().replace(w.org, node, 1,
              List(ResourceLabel("role", "db")), now)
          } yield result).transact(xa)
        }.start
        pid <- holding.get
        completion <- h.service.completeAssignment(w.actor, ruleId, node, Nil).attempt.start
        waited <- waitsFor(w, pid)
        _ <- release.complete(())
        replaced <- replacement.joinWithNever.timeout(10.seconds)
        completed <- completion.joinWithNever.timeout(10.seconds)
        assignments <- h.assigned(node)
      } yield {
        assert(waited, "completion should wait on the resource held by label replacement")
        assertEquals(replaced, ResourceLabelWrite.Written(2))
        assertEquals(code(completed), Some("CONFIGURATION_RULE_RESOURCE_NOT_ELIGIBLE"))
        assertEquals(assignments, Nil)
      }
    }
  }

  test("adoption and completion wait in resource-then-rule order without deadlock") {
    world { h =>
      val w = h.w
      val config = PostgresTestDatabase.config
      val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, None)
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile)
        rule <- h.ruleOf(ruleId)
        assignment <- w.assignments.create(w.actor, node, profile, ConfigurationAssignmentDraft(1, Path, Nil))
        holding <- Deferred[IO, Int]
        release <- Deferred[IO, Unit]
        now <- IO.realTimeInstant
        adoption <- WeakAsync.liftK[IO, ConnectionIO].use { lift =>
          (for {
            _ <- sql"""select r.id from configuration_assignment a
                       join resource r on r.id = a.resource_id and r.organization_id = a.organization_id
                       where a.organization_id = ${w.org} and a.id = $assignment for share of r""".query[UUID].unique
            pid <- sql"select pg_backend_pid()".query[Int].unique
            _ <- lift(holding.complete(pid) *> release.get)
            result <- h.rules.adopt(w.org, rule, assignment, 1, now)
          } yield result).transact(xa)
        }.start
        pid <- holding.get
        completion <- h.service.completeAssignment(w.actor, ruleId, node, Nil).attempt.start
        waited <- waitsFor(w, pid)
        _ <- release.complete(())
        adopted <- adoption.joinWithNever.timeout(10.seconds)
        completed <- completion.joinWithNever.timeout(10.seconds)
        after <- w.assignment(assignment)
      } yield {
        assert(waited, "completion should wait on the resource held by adoption")
        assertEquals(adopted, ManagedAssignmentWrite.Written)
        assertEquals(code(completed), Some("CONFIGURATION_RULE_TARGET_CONFLICT"))
        assertEquals(after.sourceRuleId, Some(ruleId))
      }
    }
  }

  // Promotion

  test("promotion moves the rule and every managed assignment together, keeping per-node values") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        nodes <- List("fi", "de", "se").traverse(name => w.resource(name))
        _ <- nodes.traverse_(node => h.label(node, "role" -> "vpn"))
        ruleId <- h.rule(profile)
        _ <- h.worker().tick
        managed <- nodes.traverse(node => h.assigned(node).map(_.head))
        _ <- managed.zip(List("fi", "de", "se")).traverse_ { case (a, region) =>
          w.assignments.update(w.actor, a.id, 1, ConfigurationAssignmentDraft(1, Path, List(ConfigurationVariableValue("region", region))))
        }
        _ <- w.revision(profile, "# v2\n" + Template, Defaults)
        rule <- h.ruleOf(ruleId)
        preview <- h.service.promotionPreview(w.org, ruleId, 2, rule.version)
        selection = preview.items.map(item => ConfigurationPromotionSelection(item.assignmentId, item.expectedVersion))
        stale <- h.service.promote(w.actor, ruleId, 2, rule.version, selection.map(s => s.copy(expectedVersion = 1))).attempt
        failed <- h.serviceWith(h.failingAudit).promote(w.actor, ruleId, 2, rule.version, selection).attempt
        unchanged <- managed.traverse(a => w.assignment(a.id)).map(_.map(_.profileRevisionNumber))
        versions <- h.service.promote(w.actor, ruleId, 2, rule.version, selection)
        promotedRule <- h.ruleOf(ruleId)
        promoted <- managed.traverse(a => w.assignment(a.id))
        values <- managed.traverse(a => w.run(w.assignmentQuery.values(w.org, a.id)))
        // A node that appears after the promotion gets the promoted revision.
        fr <- w.resource("fr")
        _ <- h.label(fr, "role" -> "vpn")
        _ <- h.reconcile(ruleId)
        late <- h.assigned(fr)
      } yield {
        assert(preview.compatible)
        assertEquals(preview.items.size, 3)
        assertEquals(code(stale), Some("CONFIGURATION_RULE_PROMOTION_CONFLICT"))
        assert(failed.isLeft)
        assertEquals(unchanged, List(1, 1, 1))
        assertEquals((promotedRule.profileRevisionNumber, promotedRule.version), (2, rule.version + 1))
        assertEquals(promoted.map(a => (a.profileRevisionNumber, a.version)), List.fill(3)((2, 3)))
        assertEquals(versions.map(_._2), List(3, 3, 3))
        assertEquals(values.map(_.map(_.value)), List(List("fi"), List("de"), List("se")))
        assertEquals(late.map(_.profileRevisionNumber), List(2))
      }
    }
  }

  test("promotion is blocked by a removed variable's override or a new required variable") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        ruleId <- h.rule(profile)
        _ <- h.worker().tick
        managed <- h.assigned(node).map(_.head)
        _ <- w.assignments.update(w.actor, managed.id, 1, ConfigurationAssignmentDraft(1, Path, List(ConfigurationVariableValue("region", "fi"))))
        // v2 drops `region`; v3 adds a required `server_id` without a default.
        _ <- w.revision(profile, "server_name {{ domain }};\n", Defaults.take(1))
        _ <- w.revision(profile, Template + "id {{ server_id }};\n", Defaults :+
          ConfigurationVariableDefinition("server_id", ConfigurationValueType.StringType, required = true, None, None))
        rule <- h.ruleOf(ruleId)
        removed <- h.service.promotionPreview(w.org, ruleId, 2, rule.version)
        added <- h.service.promotionPreview(w.org, ruleId, 3, rule.version)
        commit <- h.service.promote(w.actor, ruleId, 2, rule.version, List(ConfigurationPromotionSelection(managed.id, 2))).attempt
        after <- w.assignment(managed.id)
      } yield {
        assertEquals(removed.items.flatMap(_.issues).map(i => i.code -> i.variableName), List("CONFIGURATION_INCOMPATIBLE_OVERRIDE" -> Some("region")))
        assertEquals(added.items.flatMap(_.issues).map(i => i.code -> i.variableName), List("CONFIGURATION_VALUE_MISSING" -> Some("server_id")))
        assertEquals(code(commit), Some("CONFIGURATION_PROMOTION_INCOMPATIBLE"))
        assertEquals(after.profileRevisionNumber, 1)
      }
    }
  }

  test("bulk promotion of Stage 22D refuses rule-managed assignments") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        node <- w.resource("fi")
        _ <- h.label(node, "role" -> "vpn")
        _ <- h.rule(profile)
        _ <- h.worker().tick
        managed <- h.assigned(node).map(_.head)
        _ <- w.revision(profile, "# v2\n" + Template, Defaults)
        preview <- w.promotions.preview(w.org, profile, 2, List(ConfigurationPromotionSelection(managed.id, 1)))
      } yield assertEquals(preview.items.flatMap(_.issues.map(_.code)), List(ConfigurationAssignmentError.ManagedCode))
    }
  }

  // Read models

  test("rule list, targets and selector pages cost a fixed number of statements, however many rows") {
    world { h =>
      val w = h.w
      def load(nodes: Int, ruleCount: Int): IO[(Int, Int, Int)] = for {
        profile <- h.profile()
        ids <- (1 to nodes).toList.traverse(i => w.resource(s"load-$nodes-$i"))
        _ <- ids.traverse_(id => h.label(id, "role" -> s"r$nodes"))
        rules <- (1 to ruleCount).toList.traverse(_ => h.rule(profile, h.selector(required = List("role" -> s"r$nodes")), enabled = false))
        rule <- h.ruleOf(rules.head)
        list <- h.statements(h.rules.list(w.org, Some(profile), includeArchived = false, None, 100))
        targets <- h.statements(h.rules.targets(w.org, rule, None, 100))
        page <- h.statements(h.rules.candidates(w.org, rule.selector, Some(rule.id), Path, None, 100))
      } yield (list, targets, page)
      for {
        small <- load(3, 2)
        large <- load(60, 12)
      } yield assertEquals(small, large)
    }
  }

  test("selector preview counts eligible, conflicting and incomplete targets without writing") {
    world { h =>
      val w = h.w
      for {
        profile <- h.profile()
        a <- w.resource("a")
        b <- w.resource("b")
        _ <- h.label(a, "role" -> "vpn") *> h.label(b, "role" -> "vpn")
        _ <- w.assignments.create(w.actor, b, profile, ConfigurationAssignmentDraft(1, Path, Nil))
        preview <- h.service.previewSelector(w.org, profile, 1, Path, h.selector(), 50)
        rules <- w.run(sql"select count(*) from configuration_assignment_rule where organization_id = ${w.org}".query[Long].unique)
      } yield {
        assertEquals((preview.matched, preview.eligible, preview.manualConflicts), (2, 1, 1))
        assertEquals(preview.items.map(_._2).toSet, Set(ConfigurationAssignmentRules.Eligible, ConfigurationAssignmentRules.Adoptable))
        assertEquals(rules, 0L)
      }
    }
  }

  private object FailingAuditEvents extends AuditEventRepository[ConnectionIO] {
    override def save(event: AuditEvent): ConnectionIO[Unit] =
      new IllegalStateException("audit unavailable").raiseError[ConnectionIO, Unit]
    override def saveAll(events: List[AuditEvent]): ConnectionIO[Unit] = save(events.head)
    override def listByOrganization(organizationId: UUID, before: Option[AuditCursor],
                                    limit: Int): ConnectionIO[List[AuditEvent]] = List.empty[AuditEvent].pure[ConnectionIO]
  }

}

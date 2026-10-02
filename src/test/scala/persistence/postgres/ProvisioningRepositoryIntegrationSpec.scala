package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.{Deferred, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import application.port.ProvisioningStepResult
import application.provisioning.{ProvisioningError, ProvisioningPlanCleanup, ProvisioningRuns, ProvisioningSettings, ProvisioningWorker}
import domain.provisioning._
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.doobie.free.connection
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._

/** Durable plan approval and worker lease fencing against an isolated PostgreSQL integration DB. */
final class ProvisioningRepositoryIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run

  private def planned(w: ConfigurationDeploymentWorld, node: ConfigurationDeploymentWorld.Node, id: UUID,
    updated: java.time.Instant, resourceKind: String = "VPS") =
    ProvisioningRun(id, w.org, node.resourceId, None, None,
      ProvisioningInputSnapshot(1, ProvisioningRunKind.ServerBaselineCheck, w.org, node.resourceId,
        "NODE", resourceKind, node.connectionId, updated, ProvisioningStepKind.Baseline),
      ProvisioningRunState.Planned, updated, updated)

  test("parallel approvals for one plan and request ID enqueue exactly one run") {
    run { w =>
      val repo = new PostgresProvisioningRunRepository
      for {
        node <- w.node("provisioning-idempotency")
        updated <- w.run(sql"select updated_at from connection where id=${node.connectionId}".query[java.time.Instant].unique)
        planId = UUID.randomUUID()
        _ <- w.run(repo.insertPlan(planned(w, node, planId, updated)))
        requestId = UUID.randomUUID()
        now <- IO.realTimeInstant
        results <- List.fill(8)(()).parTraverse(_ => w.run(repo.start(w.org, planId, requestId,
          support.AuthorizationFixtures.ActorUserId, now)))
        history <- w.run(repo.history(w.org, Some(node.resourceId), 20))
      } yield {
        assertEquals(results.flatten.map(_._1.id).distinct, List(planId))
        assertEquals(results.count(_.exists(_._2)), 1)
        assertEquals(history.count(_.requestId.contains(requestId)), 1)
      }
    }
  }

  test("history excludes drafts and bounded cleanup deletes only expired PLANNED rows with their steps") {
    run { w =>
      val repo = new PostgresProvisioningRunRepository
      for {
        node <- w.node("provisioning-plan-cleanup")
        runningNode <- w.node("provisioning-plan-cleanup-running")
        updated <- w.run(sql"select updated_at from connection where id=${node.connectionId}".query[java.time.Instant].unique)
        runningUpdated <- w.run(sql"select updated_at from connection where id=${runningNode.connectionId}".query[java.time.Instant].unique)
        now <- IO.realTimeInstant
        ids = List.fill(5)(UUID.randomUUID())
        _ <- w.run(ids.take(3).traverse_(id => repo.insertPlan(planned(w, node, id, updated))) *>
          repo.insertPlan(planned(w, runningNode, ids(3), runningUpdated)) *>
          repo.insertPlan(planned(w, node, ids(4), updated)))
        _ <- w.run(sql"update provisioning_run set created_at=${now.minusSeconds(90000)} where id=${ids(0)}".update.run)
        request = UUID.randomUUID()
        _ <- w.run(repo.start(w.org, ids(2), request, support.AuthorizationFixtures.ActorUserId, now))
        _ <- w.run(sql"update provisioning_run set created_at=${now.minusSeconds(90000)} where id=${ids(2)}".update.run)
        _ <- w.run(sql"""update provisioning_run set status='RUNNING', request_id=${UUID.randomUUID()},
          requested_by_user_id=${support.AuthorizationFixtures.ActorUserId}, claimed_by=${UUID.randomUUID()},
          claim_token=${UUID.randomUUID()}, claim_until=${now.plusSeconds(90)}, created_at=${now.minusSeconds(90000)}
          where id=${ids(3)}""".update.run)
        _ <- w.run(sql"""update provisioning_run set status='SUCCEEDED', request_id=${UUID.randomUUID()},
          requested_by_user_id=${support.AuthorizationFixtures.ActorUserId}, started_at=$now, finished_at=$now,
          created_at=${now.minusSeconds(90000)} where id=${ids(4)}""".update.run)
        orgHistory <- w.run(repo.history(w.org, None, 20))
        resourceHistory <- w.run(repo.history(w.org, Some(node.resourceId), 20))
        deleted <- w.run(repo.deleteExpiredPlans(now.minusSeconds(86400), 500, Some(w.org)))
        expired <- w.run(repo.find(w.org, ids(0)))
        recent <- w.run(repo.find(w.org, ids(1)))
        queued <- w.run(repo.find(w.org, ids(2)))
        running <- w.run(repo.find(w.org, ids(3)))
        terminal <- w.run(repo.find(w.org, ids(4)))
        expiredSteps <- w.run(sql"select count(*) from provisioning_run_step where run_id=${ids(0)}".query[Long].unique)
      } yield {
        assertEquals(orgHistory.map(_.id).toSet, Set(ids(2), ids(3), ids(4)))
        assertEquals(resourceHistory.map(_.id).toSet, Set(ids(2), ids(4)))
        assertEquals(deleted, 1)
        assertEquals(expired, None)
        assert(recent.nonEmpty)
        assertEquals(queued.map(_._1.state), Some(ProvisioningRunState.Queued))
        assertEquals(running.map(_._1.state), Some(ProvisioningRunState.Running))
        assertEquals(terminal.map(_._1.state), Some(ProvisioningRunState.Succeeded))
        assertEquals(expiredSteps, 0L)
      }
    }
  }

  test("plan cleanup tick applies the shared 24-hour lifetime and organization scope") {
    run { w =>
      val repo = new PostgresProvisioningRunRepository
      val logger = Slf4jLogger.getLoggerFromName[cats.effect.IO]("test.provisioning.plan-cleanup")
      for {
        node <- w.node("provisioning-plan-cleanup-tick")
        updated <- w.run(sql"select updated_at from connection where id=${node.connectionId}".query[java.time.Instant].unique)
        now <- IO.realTimeInstant
        id = UUID.randomUUID()
        _ <- w.run(repo.insertPlan(planned(w, node, id, updated)))
        _ <- w.run(sql"update provisioning_run set created_at=${now.minusSeconds(90000)} where id=$id".update.run)
        cleanup = new ProvisioningPlanCleanup[ConnectionIO](repo, w.runner, logger, IO.pure(now), Some(w.org))
        deleted <- cleanup.tick
        found <- w.run(repo.find(w.org, id))
      } yield {
        assertEquals(deleted, 1)
        assertEquals(found, None)
      }
    }
  }

  test("expired approval is rejected, while an approved retry remains idempotent beyond plan lifetime") {
    run { w =>
      val repository = new PostgresProvisioningRunRepository
      val service = new ProvisioningRuns[IO, ConnectionIO](repository, new PostgresProvisioningTargetQuery,
        w.ids, w.time, w.audit, w.runner, w.runner, ProvisioningSettings.Default)
      for {
        node <- w.node("provisioning-plan-expiry")
        expired <- service.plan(w.org, node.resourceId)
        now <- IO.realTimeInstant
        _ <- w.run(sql"update provisioning_run set created_at=${now.minusSeconds(90000)} where id=${expired.run.id}".update.run)
        expiredResult <- service.start(w.actor, expired.run.id, UUID.randomUUID()).attempt
        expiredRow <- w.run(repository.find(w.org, expired.run.id))
        auditBefore <- w.run(sql"select count(*) from audit_event where organization_id=${w.org} and action='PROVISIONING_RUN_REQUESTED'".query[Long].unique)
        fresh <- service.plan(w.org, node.resourceId)
        requestId = UUID.randomUUID()
        approved <- service.start(w.actor, fresh.run.id, requestId)
        _ <- w.run(sql"update provisioning_run set created_at=${now.minusSeconds(90000)} where id=${fresh.run.id}".update.run)
        repeated <- service.start(w.actor, fresh.run.id, requestId)
        otherRequest <- service.start(w.actor, fresh.run.id, UUID.randomUUID()).attempt
        auditAfter <- w.run(sql"select count(*) from audit_event where organization_id=${w.org} and action='PROVISIONING_RUN_REQUESTED'".query[Long].unique)
      } yield {
        assertEquals(expiredResult.left.toOption.map(_.getClass), Some(ProvisioningError.PlanExpired.getClass))
        assertEquals(expiredRow.map(_._1.state), Some(ProvisioningRunState.Planned))
        assertEquals(expiredRow.flatMap(_._1.requestId), None)
        assertEquals(auditBefore, 0L)
        assertEquals(approved.state, ProvisioningRunState.Queued)
        assertEquals(repeated.id, approved.id)
        assertEquals(otherRequest.left.toOption.map(_.getClass), Some(ProvisioningError.AlreadyActive.getClass))
        assertEquals(auditAfter, 1L)
      }
    }
  }

  test("approval racing cleanup either queues and preserves the plan or cleanup removes it") {
    run { w =>
      val repository = new PostgresProvisioningRunRepository
      val service = new ProvisioningRuns[IO, ConnectionIO](repository, new PostgresProvisioningTargetQuery,
        w.ids, w.time, w.audit, w.runner, w.runner, ProvisioningSettings.Default)
      for {
        node <- w.node("provisioning-plan-cleanup-race")
        plan <- service.plan(w.org, node.resourceId)
        now <- IO.realTimeInstant
        request <- IO(UUID.randomUUID())
        outcome <- (service.start(w.actor, plan.run.id, request).attempt,
          w.run(repository.deleteExpiredPlans(now.plusSeconds(86400), 500, Some(w.org)))).parTupled
        finalState <- w.run(repository.find(w.org, plan.run.id))
      } yield outcome match {
        case (Right(run), deleted) =>
          assertEquals(run.state, ProvisioningRunState.Queued)
          assertEquals(deleted, 0)
          assertEquals(finalState.map(_._1.state), Some(ProvisioningRunState.Queued))
        case (Left(_: ProvisioningError.NotFound.type), deleted) =>
          assertEquals(deleted, 1)
          assertEquals(finalState, None)
        case other => fail(s"unexpected approval/cleanup race result: $other; final=$finalState")
      }
    }
  }

  test("active runs serialize across two plans for the same resource") {
    run { w =>
      val repo = new PostgresProvisioningRunRepository
      for {
        node <- w.node("provisioning-active")
        updated <- w.run(sql"select updated_at from connection where id=${node.connectionId}".query[java.time.Instant].unique)
        first = UUID.randomUUID(); second = UUID.randomUUID()
        _ <- w.run(repo.insertPlan(planned(w, node, first, updated)) *> repo.insertPlan(planned(w, node, second, updated)))
        now <- IO.realTimeInstant
        results <- List(first, second).parTraverse(id => w.run(repo.start(w.org, id, UUID.randomUUID(),
          support.AuthorizationFixtures.ActorUserId, now)))
      } yield assertEquals(results.flatten.size, 1)
    }
  }

  test("expired running work is marked UNKNOWN, its active step unknown and the rest skipped") {
    run { w =>
      val repo = new PostgresProvisioningRunRepository
      for {
        node <- w.node("provisioning-expired")
        updated <- w.run(sql"select updated_at from connection where id=${node.connectionId}".query[java.time.Instant].unique)
        id = UUID.randomUUID()
        _ <- w.run(repo.insertPlan(planned(w, node, id, updated)))
        queuedAt <- IO.realTimeInstant
        _ <- w.run(repo.start(w.org, id, UUID.randomUUID(), support.AuthorizationFixtures.ActorUserId, queuedAt))
        now <- IO.realTimeInstant
        token = UUID.randomUUID()
        claimed <- w.run(repo.claim(UUID.randomUUID(), token, now, now.plusSeconds(1), 100, Some(w.org)))
        _ <- IO(assert(claimed.exists(_.id == id)))
        _ <- w.run(repo.beginStep(w.org, id, token, ProvisioningStepKind.Preflight, now))
        _ <- w.run(repo.claim(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(2), now.plusSeconds(3), 1, Some(w.org)))
        found <- w.run(repo.find(w.org, id))
        stillFenced <- w.run(repo.renew(w.org, id, token, now.plusSeconds(3), now.plusSeconds(4)))
      } yield {
        assertEquals(found.map(_._1.state), Some(ProvisioningRunState.Unknown))
        assertEquals(found.map(_._2.map(_.state)), Some(List(ProvisioningStepState.Unknown, ProvisioningStepState.Skipped)))
        assert(!stillFenced)
      }
    }
  }

  test("worker records verified success, known failure, remote uncertainty and failed VERIFY without replay") {
    run { w =>
      val repo = new PostgresProvisioningRunRepository
      val targets = new PostgresProvisioningTargetQuery
      val cases = List("success", "denied", "unknown", "verify-false")
      val logger = Slf4jLogger.getLoggerFromName[cats.effect.IO]("test.provisioning.outcomes")
      for {
        fixtures <- cases.traverse(name => w.node(s"provisioning-outcome-$name"))
        selected <- fixtures.traverse(node => w.run(targets.eligible(w.org, node.resourceId)))
        _ <- IO(assert(selected.forall(_.isRight)))
        now <- IO.realTimeInstant
        runs = fixtures.zip(selected).zip(cases).map { case ((node, target), name) =>
          val eligible = target.toOption.get
          ProvisioningRun(UUID.randomUUID(), w.org, node.resourceId, None, None,
            ProvisioningInputSnapshot(1, ProvisioningRunKind.ServerBaselineCheck, w.org, node.resourceId,
              eligible.resourceType, eligible.resourceKind, eligible.connectionId, eligible.connectionUpdatedAt,
              ProvisioningStepKind.Baseline), ProvisioningRunState.Planned, now, now) -> name
        }
        _ <- w.run(runs.traverse_ { case (plannedRun, _) => repo.insertPlan(plannedRun) })
        _ <- runs.traverse_ { case (plannedRun, _) => w.run(repo.start(w.org, plannedRun.id,
          UUID.randomUUID(), support.AuthorizationFixtures.ActorUserId, now)) }
        byConnection = runs.map { case (plannedRun, name) => plannedRun.input.connectionId -> name }.toMap
        calls <- Ref.of[cats.effect.IO, Map[UUID, Int]](Map.empty)
        transport = new application.port.ProvisioningTransport[cats.effect.IO] {
          override def preflight(connection: domain.connection.Connection) = calls.update(m => m.updated(connection.id,
            m.getOrElse(connection.id, 0) + 1)) *> (byConnection(connection.id) match {
            case "denied" => IO.pure(ProvisioningStepResult(Map("diskAvailable" -> "false"), Some("PROVISIONING_DISK_INSUFFICIENT"), Some(false)))
            case "unknown" => IO.never[ProvisioningStepResult]
            case _ => IO.pure(ProvisioningStepResult(Map("os" -> "ubuntu"), None, None))
          })
          override def verify(connection: domain.connection.Connection) = calls.update(m => m.updated(connection.id,
            m.getOrElse(connection.id, 0) + 1)) *> IO.pure(if (byConnection(connection.id) == "verify-false")
              ProvisioningStepResult(Map("systemdAvailable" -> "false"), None, Some(false))
            else ProvisioningStepResult(Map("systemdAvailable" -> "true"), None, Some(true)))
        }
        worker = new ProvisioningWorker[ConnectionIO](repo, targets, transport, w.runner,
          ProvisioningSettings(batchSize = 10, maxConcurrency = 2, stepTimeout = 150.millis), logger, scope = Some(w.org))
        _ <- worker.tick *> worker.tick *> worker.tick
        outcomes <- runs.traverse { case (plannedRun, name) => w.run(repo.find(w.org, plannedRun.id)).map(name -> _) }
        callCounts <- calls.get
        _ <- worker.tick
        after <- calls.get
      } yield {
        def state(name: String) = outcomes.find(_._1 == name).flatMap(_._2.map(_._1.state))
        def steps(name: String) = outcomes.find(_._1 == name).flatMap(_._2.map(_._2.map(_.state)))
        assertEquals(state("success"), Some(ProvisioningRunState.Succeeded))
        assertEquals(steps("success"), Some(List(ProvisioningStepState.Succeeded, ProvisioningStepState.Succeeded)))
        assertEquals(state("denied"), Some(ProvisioningRunState.Failed))
        assertEquals(steps("denied"), Some(List(ProvisioningStepState.Failed, ProvisioningStepState.Skipped)))
        assertEquals(state("unknown"), Some(ProvisioningRunState.Unknown))
        assertEquals(steps("unknown"), Some(List(ProvisioningStepState.Unknown, ProvisioningStepState.Skipped)))
        assertEquals(outcomes.find(_._1 == "unknown").flatMap(_._2.flatMap(_._1.failureCode)),
          Some("PROVISIONING_REMOTE_TIMEOUT"))
        assertEquals(state("verify-false"), Some(ProvisioningRunState.Failed))
        assertEquals(steps("verify-false"), Some(List(ProvisioningStepState.Succeeded, ProvisioningStepState.Failed)))
        assertEquals(callCounts, after, "terminal runs must not be replayed")
      }
    }
  }

  test("a write waiting on the parent row lock cannot use a lease that expired while waiting") {
    run { w =>
      val repo = new PostgresProvisioningRunRepository
      for {
        node <- w.node("provisioning-fence")
        updated <- w.run(sql"select updated_at from connection where id=${node.connectionId}".query[java.time.Instant].unique)
        id = UUID.randomUUID()
        _ <- w.run(repo.insertPlan(planned(w, node, id, updated)))
        queuedAt <- IO.realTimeInstant
        _ <- w.run(repo.start(w.org, id, UUID.randomUUID(), support.AuthorizationFixtures.ActorUserId, queuedAt))
        leaseNow <- IO.realTimeInstant
        token = UUID.randomUUID()
        claimed <- w.run(repo.claim(UUID.randomUUID(), token, leaseNow, leaseNow.plusMillis(800), 100, Some(w.org)))
        _ <- IO(assert(claimed.exists(_.id == id), "the intended queued plan should be claimed"))
        began <- w.run(repo.beginStep(w.org, id, token, ProvisioningStepKind.Preflight, leaseNow))
        _ <- IO(assert(began, "the claimed step should begin under its current lease"))
        started = new CountDownLatch(1)
        blocker <- w.run(sql"select 1 from provisioning_run where id=$id for update".query[Int].unique
          .flatTap(_ => connection.delay(started.countDown())) *> connection.delay(Thread.sleep(1200))).start
        lockHeld <- IO.blocking(started.await(2, TimeUnit.SECONDS))
        staleNow <- IO.realTimeInstant
        deadline <- w.run(sql"select claim_until from provisioning_run where id=$id".query[java.time.Instant].unique)
        accepted <- w.run(repo.finishStep(w.org, id, token, ProvisioningStepKind.Preflight,
          ProvisioningStepState.Succeeded, Map.empty, None, false, staleNow))
        _ <- blocker.joinWithNever
        state <- w.run(repo.find(w.org, id))
      } yield {
        assert(lockHeld, "the blocker must hold the row lock before attempting the stale write")
        assert(staleNow.isBefore(deadline), "the supplied clock value must still be before expiry while waiting")
        assert(!accepted, "expired lease must be rejected after lock acquisition")
        assertEquals(state.flatMap(_._2.headOption).map(_.state), Some(ProvisioningStepState.Running))
      }
    }
  }

  test("disabled worker leaves queued and expired runs untouched; active loop enters SSH once and cancels") {
    run { w =>
      val repository = new PostgresProvisioningRunRepository
      val logger = Slf4jLogger.getLoggerFromName[cats.effect.IO]("test.provisioning.worker")
      val entered = Deferred.unsafe[IO, Unit]
      val cancelled = Deferred.unsafe[IO, Unit]
      val calls = Ref.unsafe[IO, Int](0)
      val blockingRemote = new application.port.ProvisioningTransport[IO] {
        override def preflight(connection: domain.connection.Connection) =
          calls.update(_ + 1) *> entered.complete(()) *> IO.never[ProvisioningStepResult]
            .onCancel(cancelled.complete(()).void)
        override def verify(connection: domain.connection.Connection) = IO.pure(
          ProvisioningStepResult(Map.empty, None, Some(true)))
      }
      for {
        expiredNode <- w.node("provisioning-worker-expired")
        queuedNode <- w.node("provisioning-worker-queued")
        target <- w.run(new PostgresProvisioningTargetQuery().eligible(w.org, queuedNode.resourceId))
        _ <- IO(assert(target.isRight, s"queued fixture should resolve its active SSH source: $target"))
        observedAt <- IO.realTimeInstant
        queuedConnectionAt = target.toOption.map(_.connectionUpdatedAt).getOrElse(observedAt)
        expiredId = UUID.randomUUID()
        queuedId = UUID.randomUUID()
        queuedKind = target.toOption.map(_.resourceKind).getOrElse("")
        _ <- w.run(repository.insertPlan(planned(w, expiredNode, expiredId, observedAt, "")) *>
          repository.insertPlan(planned(w, queuedNode, queuedId, queuedConnectionAt, queuedKind)))
        _ <- w.run(repository.start(w.org, expiredId, UUID.randomUUID(), support.AuthorizationFixtures.ActorUserId, observedAt))
        expiredToken = UUID.randomUUID()
        leaseAt <- IO.realTimeInstant
        _ <- w.run(repository.claim(UUID.randomUUID(), expiredToken, leaseAt, leaseAt.plusSeconds(30), 10, Some(w.org)))
        _ <- w.run(repository.beginStep(w.org, expiredId, expiredToken, ProvisioningStepKind.Preflight, leaseAt))
        _ <- w.run(sql"update provisioning_run set claim_until=current_timestamp - interval '1 second' where id=$expiredId".update.run.void)
        _ <- w.run(repository.start(w.org, queuedId, UUID.randomUUID(), support.AuthorizationFixtures.ActorUserId, observedAt))
        disabled = new ProvisioningWorker[org.typelevel.doobie.ConnectionIO](repository,
          new PostgresProvisioningTargetQuery, blockingRemote, w.runner,
          ProvisioningSettings(enabled = false), logger, scope = Some(w.org))
        _ <- disabled.tick
        untouched <- w.run((repository.find(w.org, expiredId), repository.find(w.org, queuedId)).tupled)
        _ <- IO(assertEquals(untouched._1.map(_._1.state), Some(ProvisioningRunState.Running)))
        _ <- IO(assertEquals(untouched._2.map(_._1.state), Some(ProvisioningRunState.Queued)))
        active = new ProvisioningWorker[org.typelevel.doobie.ConnectionIO](repository,
          new PostgresProvisioningTargetQuery, blockingRemote, w.runner,
          ProvisioningSettings(pollInterval = 10.millis), logger, scope = Some(w.org))
        fiber <- active.run.start
        _ <- entered.get.timeoutTo(5.seconds, w.run(repository.find(w.org, queuedId)).flatMap(state =>
          IO.raiseError(new RuntimeException(s"worker did not reach fake SSH; run=${state.map(_._1.state)}, steps=${state.map(_._2.map(s => s.state -> s.failureCode))}"))))
        enteredRun <- w.run(repository.find(w.org, queuedId))
        _ <- fiber.cancel
        _ <- cancelled.get.timeoutTo(2.seconds, IO.raiseError(new RuntimeException("fake SSH was not cancelled")))
        recovered <- w.run(repository.find(w.org, expiredId))
        callCount <- calls.get
        _ <- IO(assertEquals(recovered.map(_._1.state), Some(ProvisioningRunState.Unknown)))
        _ <- IO(assertEquals(recovered.map(_._2.map(_.state)), Some(List(ProvisioningStepState.Unknown, ProvisioningStepState.Skipped))))
        _ <- IO(assertEquals(enteredRun.map(_._1.state), Some(ProvisioningRunState.Running)))
        _ <- IO(assertEquals(enteredRun.map(_._2.head.state), Some(ProvisioningStepState.Running)))
        _ <- IO(assertEquals(callCount, 1))
        _ <- IO.sleep(50.millis)
        callCountAfter <- calls.get
      } yield assertEquals(callCountAfter, 1)
    }
  }
}

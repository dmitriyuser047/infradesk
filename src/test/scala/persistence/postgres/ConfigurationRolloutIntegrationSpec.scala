package ru.bitec.app.ops
package persistence.postgres

import application.configuration._
import application.port.ConfigurationPromotionSelection
import cats.effect.IO
import cats.syntax.all._
import domain.configuration._
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.InMemoryRemote

import java.time.Instant
import java.util.UUID

/** Bulk promotion and rolling rollouts against a real database. Rollouts are driven by the real
  * orchestrator and the real deployment worker; nodes are in-memory POSIX servers.
  */
final class ConfigurationRolloutIntegrationSpec extends FunSuite {
  import ConfigurationRolloutItemState._

  private val Path = "/etc/app/app.conf"
  private val V2 = "# v2\nserver_name {{ domain }};\nmode {{ mode }};\n"

  private final class Harness(val w: ConfigurationDeploymentWorld) {
    val memory = new InMemoryRemote
    val deployments = w.deploymentsWith(remoteTransport = memory)
    val rollouts = new ConfigurationRollouts[IO, org.typelevel.doobie.ConnectionIO](w.assignmentRepository,
      w.assignmentQuery, w.profileQuery, w.sources, deployments, w.rolloutRepository, w.ids, w.time, w.audit,
      w.runner, w.runner, w.settings)
    val offset = new java.util.concurrent.atomic.AtomicLong(0)
    val clock: IO[Instant] = IO.realTimeInstant.map(_.plusSeconds(offset.get()))
    val deployer = w.worker(remoteTransport = memory, clock = clock)
    val orchestrator = w.rolloutWorker(clock = clock)

    final case class Target(node: w.Node, assignmentId: UUID)

    /** `count` nodes on revision 1, then a revision 2 and every assignment promoted to it. */
    def fleet(count: Int, modes: Map[Int, String] = Map.empty): IO[(UUID, List[Target])] = for {
      profile <- w.profile()
      targets <- (1 to count).toList.traverse { index =>
        for {
          node <- w.node(s"node-$index")
          _ <- IO {
            memory.server(node.connectionId).write(Path, s"v1 node-$index\n")
            memory.server(node.connectionId).units.set(Map("app.service" -> Path))
          }
          assignmentId <- w.assign(node, profile, Path, "domain" -> s"node-$index.example",
            "mode" -> modes.getOrElse(index, "ok"))
        } yield Target(node, assignmentId)
      }
      _ <- w.revision(profile, V2)
      _ <- w.promotions.commit(w.actor, profile, 2, targets.map(t => ConfigurationPromotionSelection(t.assignmentId, 1)))
    } yield profile -> targets

    val Restart = ConfigurationExecutionPolicy(ConfigurationActivation.SystemdRestart, Some("app.service"),
      Some(ConfigurationValidator("/usr/local/bin/check-config", List("{candidate}"))), ConfigurationExecutionPolicy.DefaultFileMode)

    def launch(profile: UUID, targets: List[Target], strategy: ConfigurationRolloutStrategy,
               requestId: UUID = UUID.randomUUID()): IO[UUID] = for {
      versions <- targets.traverse(t => w.assignment(t.assignmentId).map(a => t -> a.version))
      requested = versions.map { case (t, version) => ConfigurationRolloutTarget(t.assignmentId, version, t.node.connectionId, Restart) }
      preflight <- rollouts.preflight(w.org, profile, 2, requested)
      _ <- IO(assert(preflight.forall(_.ready), clues(preflight)))
      approved = preflight.map(item => ConfigurationRolloutApprovedTarget(item.target, item.connectionUpdatedAt.get,
        item.desiredSha256.get, item.expectedRemoteState.get))
      id <- rollouts.launch(w.actor, profile, 2, requestId, strategy, approved)
    } yield id

    /** One orchestration pass and one deployment pass. */
    def step: IO[Unit] = orchestrator.tick *> deployer.tick

    def detail(id: UUID) = rollouts.detail(w.org, id)
    def state(id: UUID): IO[ConfigurationRolloutState] = detail(id).map(_._1.rollout.state)
    def items(id: UUID): IO[List[ConfigurationRolloutItemView]] = detail(id).map(_._2)

    def finish(id: UUID, rounds: Int = 40): IO[ConfigurationRolloutState] =
      (1 to rounds).toList.foldLeft(IO.unit)((previous, _) => previous *> state(id).flatMap(current =>
        if (current.terminal) IO.unit else IO(offset.addAndGet(3600)) *> step)) *> state(id)

    def text(target: Target): Option[String] = memory.server(target.node.connectionId).text(Path)
    def names(target: Target): Set[String] = memory.server(target.node.connectionId).names
  }

  private def world(body: Harness => IO[Unit]): Unit = ConfigurationDeploymentWorld.run(w => body(new Harness(w)))

  private val Sequential = ConfigurationRolloutStrategy(1, 1, 0, stopOnFailure = true, ConfigurationRollbackMode.FailedTargetOnly)

  // Promotion

  test("promotion preview names every incompatibility and blocks the commit") {
    world { h =>
      val w = h.w
      for {
        profile <- w.profile()
        nodes <- (1 to 3).toList.traverse(i => w.node(s"node-$i"))
        ok <- w.assign(nodes(0), profile, Path, "domain" -> "a")
        legacy <- w.assign(nodes(1), profile, Path, "domain" -> "b", "mode" -> "legacy")
        missing <- w.assign(nodes(2), profile, Path, "domain" -> "c")
        // v2 drops `mode` and adds a required `region` without a default.
        _ <- w.revision(profile, "server_name {{ domain }};\nregion {{ region }};\n", List(
          ConfigurationVariableDefinition("domain", ConfigurationValueType.StringType, required = true, None, None),
          ConfigurationVariableDefinition("region", ConfigurationValueType.StringType, required = true, None, None)))
        selection = List(ok, legacy, missing).map(ConfigurationPromotionSelection(_, 1))
        preview <- w.promotions.preview(w.org, profile, 2, selection)
        commit <- w.promotions.commit(w.actor, profile, 2, selection).attempt
        versions <- List(ok, legacy, missing).traverse(id => w.assignment(id).map(a => (a.version, a.profileRevisionNumber)))
      } yield {
        val issues = preview.items.map(item => item.assignmentId -> item.issues.map(i => i.code -> i.variableName.getOrElse(""))).toMap
        assertEquals(issues(ok), List("CONFIGURATION_VALUE_MISSING" -> "region"))
        assertEquals(issues(legacy), List("CONFIGURATION_INCOMPATIBLE_OVERRIDE" -> "mode", "CONFIGURATION_VALUE_MISSING" -> "region"))
        assertEquals(issues(missing), List("CONFIGURATION_VALUE_MISSING" -> "region"))
        assert(!preview.compatible)
        assertEquals(commit.swap.toOption.collect { case e: ConfigurationPromotionError => e.code }, Some("CONFIGURATION_PROMOTION_INCOMPATIBLE"))
        assertEquals(versions, List.fill(3)((1, 1)))
      }
    }
  }

  test("one stale assignment aborts the whole promotion") {
    world { h =>
      val w = h.w
      for {
        profile <- w.profile()
        nodes <- (1 to 3).toList.traverse(i => w.node(s"node-$i"))
        ids <- nodes.traverse(node => w.assign(node, profile, Path, "domain" -> node.name))
        _ <- w.revision(profile, V2)
        stale = ids.zipWithIndex.map { case (id, index) => ConfigurationPromotionSelection(id, if (index == 2) 7 else 1) }
        result <- w.promotions.commit(w.actor, profile, 2, stale).attempt
        versions <- ids.traverse(id => w.assignment(id).map(a => (a.version, a.profileRevisionNumber)))
        journal <- w.journal("CONFIGURATION_PROFILE")
      } yield {
        assertEquals(result.swap.toOption.collect { case e: ConfigurationPromotionError => e.code }, Some("CONFIGURATION_PROMOTION_CONFLICT"))
        assertEquals(versions, List.fill(3)((1, 1)))
        assert(!journal.exists(_._1 == "CONFIGURATION_ASSIGNMENTS_PROMOTED"))
      }
    }
  }

  test("twenty assignments move together, each version exactly once, under one audit record") {
    world { h =>
      val w = h.w
      for {
        profile <- w.profile()
        nodes <- (1 to 20).toList.traverse(i => w.node(s"node-$i"))
        ids <- nodes.traverse(node => w.assign(node, profile, Path, "domain" -> node.name))
        _ <- w.revision(profile, V2)
        result <- w.promotions.commit(w.actor, profile, 2, ids.map(ConfigurationPromotionSelection(_, 1)))
        stored <- ids.traverse(w.assignment)
        journal <- w.journal("CONFIGURATION_PROFILE")
      } yield {
        assertEquals(result.assignments.map(_._2).distinct, List(2))
        assertEquals(result.assignments.map(_._1).toSet, ids.toSet)
        assert(stored.forall(a => a.version == 2 && a.profileRevisionNumber == 2))
        assertEquals(journal.count(_._1 == "CONFIGURATION_ASSIGNMENTS_PROMOTED"), 1)
      }
    }
  }

  test("two overlapping promotions race: exactly one wins, neither deadlocks") {
    world { h =>
      val w = h.w
      for {
        profile <- w.profile()
        nodes <- (1 to 6).toList.traverse(i => w.node(s"node-$i"))
        ids <- nodes.traverse(node => w.assign(node, profile, Path, "domain" -> node.name))
        _ <- w.revision(profile, V2)
        forward = ids.map(ConfigurationPromotionSelection(_, 1))
        results <- (w.promotions.commit(w.actor, profile, 2, forward).attempt,
          w.promotions.commit(w.actor, profile, 2, forward.reverse).attempt).parTupled
        stored <- ids.traverse(w.assignment)
      } yield {
        assertEquals(List(results._1, results._2).count(_.isRight), 1)
        assert(stored.forall(_.version == 2))
      }
    }
  }

  // Rollouts

  test("the canary goes first and alone; the next batch waits for it") {
    world { h =>
      for {
        fleet <- h.fleet(4)
        (profile, targets) = fleet
        id <- h.launch(profile, targets, ConfigurationRolloutStrategy(1, 2, 0, stopOnFailure = true,
          ConfigurationRollbackMode.FailedTargetOnly))
        _ <- h.orchestrator.tick
        _ <- h.orchestrator.tick
        afterCanaryLaunch <- h.items(id)
        _ <- h.deployer.tick
        _ <- h.orchestrator.tick
        afterCanary <- h.items(id)
        state <- h.finish(id)
        finalItems <- h.items(id)
      } yield {
        assertEquals(afterCanaryLaunch.map(_.item.state), List(Deploying, Pending, Pending, Pending))
        assertEquals(afterCanary.map(_.item.state), List(Succeeded, Deploying, Deploying, Pending))
        assertEquals(state, ConfigurationRolloutState.Succeeded)
        assertEquals(finalItems.map(_.item.state), List.fill(4)(Succeeded))
        assert(targets.forall(t => h.text(t).exists(_.startsWith("# v2"))))
      }
    }
  }

  test("a pause holds the next batch until it has passed") {
    world { h =>
      for {
        fleet <- h.fleet(2)
        (profile, targets) = fleet
        id <- h.launch(profile, targets, Sequential.copy(pauseSeconds = 600))
        _ <- h.step
        _ <- h.orchestrator.tick
        paused <- h.detail(id)
        _ <- h.step
        stillPaused <- h.items(id)
        _ <- IO(h.offset.addAndGet(601))
        _ <- h.step
        resumed <- h.items(id)
      } yield {
        assertEquals(paused._1.rollout.state, ConfigurationRolloutState.Paused)
        assert(paused._1.rollout.nextActionAt.isDefined)
        assertEquals(stillPaused.map(_.item.state), List(Succeeded, Pending))
        assertEquals(resumed.map(_.item.state).last, Deploying)
      }
    }
  }

  test("stop on failure: later nodes are skipped, applied nodes stay, the failed node is untouched") {
    world { h =>
      for {
        fleet <- h.fleet(4, modes = Map(2 -> "INVALID"))
        (profile, targets) = fleet
        id <- h.launch(profile, targets, Sequential)
        state <- h.finish(id)
        _ <- h.deployer.tick
        items <- h.items(id)
      } yield {
        assertEquals(state, ConfigurationRolloutState.Failed)
        assertEquals(items.map(_.item.state), List(Succeeded, Failed, Skipped, Skipped))
        assertEquals(items(1).failureCode, Some("CONFIGURATION_VALIDATION_FAILED"))
        assert(h.text(targets(0)).exists(_.startsWith("# v2")))
        assertEquals(h.text(targets(1)), Some("v1 node-2\n"))
        assertEquals(h.text(targets(2)), Some("v1 node-3\n"))
        assert(targets.forall(t => h.names(t) == Set(Path)), "retained backups are removed once the rollout ends")
      }
    }
  }

  test("the running batch finishes safely even when one of its nodes fails") {
    world { h =>
      for {
        fleet <- h.fleet(4, modes = Map(2 -> "INVALID"))
        (profile, targets) = fleet
        id <- h.launch(profile, targets, ConfigurationRolloutStrategy(0, 2, 0, stopOnFailure = true,
          ConfigurationRollbackMode.FailedTargetOnly))
        state <- h.finish(id)
        items <- h.items(id)
      } yield {
        assertEquals(state, ConfigurationRolloutState.Failed)
        assertEquals(items.map(_.item.state), List(Succeeded, Failed, Skipped, Skipped))
      }
    }
  }

  test("ALL_APPLIED: a failure rolls back every applied node, the last applied first") {
    world { h =>
      for {
        fleet <- h.fleet(4, modes = Map(3 -> "BROKEN"))
        (profile, targets) = fleet
        id <- h.launch(profile, targets, Sequential.copy(rollbackMode = ConfigurationRollbackMode.AllApplied))
        state <- h.finish(id)
        items <- h.items(id)
        deploymentIds = items.flatMap(_.item.deploymentId)
        finished <- deploymentIds.traverse(h.w.deployment)
      } yield {
        assertEquals(state, ConfigurationRolloutState.RolledBack)
        assertEquals(items.map(_.item.state), List(RolledBack, RolledBack, RolledBack, Skipped))
        assertEquals(items(2).failureCode, Some("CONFIGURATION_HEALTH_CHECK_FAILED"))
        assert(targets.take(3).forall(t => h.text(t).exists(_.startsWith("v1"))), "every applied node is back on v1")
        // Reverse order: node 2 was restored before node 1.
        val restoredAt = finished.take(2).map(_.finishedAt.get)
        assert(restoredAt(1).isBefore(restoredAt(0)), clues(restoredAt))
        assert(targets.forall(t => h.names(t) == Set(Path)))
      }
    }
  }

  test("a rollout keeps each applied node's backup until it ends, then removes them") {
    world { h =>
      for {
        fleet <- h.fleet(2)
        (profile, targets) = fleet
        id <- h.launch(profile, targets, Sequential)
        _ <- h.step
        _ <- h.step
        during <- IO(h.names(targets.head))
        state <- h.finish(id)
        _ <- h.deployer.cleanupBackups
        after <- IO(targets.map(h.names))
      } yield {
        assert(during.exists(_.endsWith(".bak")), clues(during))
        assertEquals(state, ConfigurationRolloutState.Succeeded)
        assert(after.forall(_ == Set(Path)), clues(after))
      }
    }
  }

  test("cancel skips future nodes; cancel with rollback also restores the applied ones") {
    world { h =>
      for {
        fleet <- h.fleet(3)
        (profile, targets) = fleet
        plain <- h.launch(profile, targets, Sequential)
        _ <- h.step *> h.step
        _ <- h.rollouts.cancel(h.w.actor, plain, rollback = false)
        cancelled <- h.finish(plain)
        plainItems <- h.items(plain)
        journal <- h.w.journal("CONFIGURATION_ROLLOUT")
      } yield {
        assertEquals(cancelled, ConfigurationRolloutState.Cancelled)
        assertEquals(plainItems.map(_.item.state), List(Succeeded, Succeeded, Skipped))
        assertEquals(journal.map(_._1), List("CONFIGURATION_ROLLOUT_REQUESTED", "CONFIGURATION_ROLLOUT_CANCELLED"))
      }
    }
    world { h =>
      for {
        fleet <- h.fleet(3)
        (profile, targets) = fleet
        id <- h.launch(profile, targets, Sequential)
        _ <- h.step *> h.step
        _ <- h.rollouts.cancel(h.w.actor, id, rollback = true)
        state <- h.finish(id)
        items <- h.items(id)
      } yield {
        assertEquals(state, ConfigurationRolloutState.RolledBack)
        assertEquals(items.map(_.item.state), List(RolledBack, RolledBack, Skipped))
        assertEquals(targets.take(2).map(h.text), List(Some("v1 node-1\n"), Some("v1 node-2\n")))
      }
    }
  }

  test("two orchestrators never start the same node twice, and an expired rollout lease is taken over") {
    world { h =>
      for {
        fleet <- h.fleet(3)
        (profile, targets) = fleet
        id <- h.launch(profile, targets, ConfigurationRolloutStrategy(0, 3, 0, stopOnFailure = true,
          ConfigurationRollbackMode.FailedTargetOnly))
        other = h.w.rolloutWorker(clock = h.clock)
        _ <- (h.orchestrator.tick, other.tick, h.orchestrator.tick, other.tick).parTupled
        children <- h.w.run(sql"""select rollout_item_id, count(*) from configuration_deployment
            where rollout_id = $id group by rollout_item_id""".query[(UUID, Long)].to[List])
        // A lease held by a dead orchestrator blocks nobody once it expires.
        now <- h.clock
        _ <- h.w.run(h.w.rolloutRepository.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(60), Nil, Some(h.w.org)))
        blocked <- h.w.run(h.w.rolloutRepository.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(60), Nil, Some(h.w.org)))
        _ <- IO(h.offset.addAndGet(120))
        state <- h.finish(id)
      } yield {
        assertEquals(children.map(_._2).distinct, List(1L))
        assertEquals(children.size, 3)
        assertEquals(blocked.map(_.id).filter(_ == id), None)
        assertEquals(state, ConfigurationRolloutState.Succeeded)
      }
    }
  }

  test("a rollout request is idempotent, ordered as approved, tenant-isolated and paged") {
    world { h =>
      for {
        fleet <- h.fleet(3)
        (profile, targets) = fleet
        requestId = UUID.randomUUID()
        first <- h.launch(profile, targets.reverse, Sequential, requestId)
        again <- h.launch(profile, targets.reverse, Sequential, requestId)
        items <- h.items(first)
        foreign <- h.rollouts.detail(h.w.foreignOrg, first).attempt
        foreignHistory <- h.rollouts.history(h.w.foreignOrg, None, None, 10)
        _ <- h.finish(first)
        second <- h.launch(profile, targets, Sequential)
        page <- h.rollouts.history(h.w.org, Some(profile), None, 1)
        next <- h.rollouts.history(h.w.org, Some(profile), Some(page.head.rollout.createdAt -> page.head.rollout.id), 1)
        rows <- h.w.run(sql"select count(*) from configuration_rollout where organization_id = ${h.w.org}".query[Long].unique)
      } yield {
        assertEquals(again, first)
        assertEquals(rows, 2L)
        assertEquals(items.map(_.item.position), List(0, 1, 2))
        assertEquals(items.map(_.resourceName), List("node-3", "node-2", "node-1"))
        assertEquals(foreign.swap.toOption.collect { case e: ConfigurationRolloutError => e.code }, Some("CONFIGURATION_ROLLOUT_NOT_FOUND"))
        assertEquals(foreignHistory, Nil)
        assertEquals((page ++ next).map(_.rollout.id), List(second, first))
        assertEquals(page.head.items, 3)
      }
    }
  }

  test("preflight blocks a node whose assignment is not on the rollout revision or whose policy is invalid") {
    world { h =>
      for {
        fleet <- h.fleet(2)
        (profile, targets) = fleet
        badPolicy = ConfigurationExecutionPolicy(ConfigurationActivation.SystemdRestart, Some("x; reboot"), None, 420)
        items <- h.rollouts.preflight(h.w.org, profile, 1, List(
          ConfigurationRolloutTarget(targets(0).assignmentId, 2, targets(0).node.connectionId, h.Restart),
          ConfigurationRolloutTarget(targets(1).assignmentId, 2, targets(1).node.connectionId, badPolicy)))
      } yield assertEquals(items.map(_.errorCode), List(Some("CONFIGURATION_ASSIGNMENT_CHANGED"),
        Some(ConfigurationRollouts.InvalidExecutionPolicy)))
    }
  }
}

package ru.bitec.app.ops
package persistence.postgres

import application.auth.ActorContext
import application.integration._
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.integration._
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Stage 24D against a real database. Part of the inventory suite on purpose: the action and
  * desired-state workers claim across the whole database, so these tests must not run beside
  * another suite that queues actions.
  */
trait IntegrationDesiredStateTests extends FunSuite { self: IntegrationInventoryIntegrationSpec =>
  import IntegrationDesiredNodeState.{Disabled, Enabled}
  import IntegrationDesiredStateStatus._

  private def code(result: Either[Throwable, _]): Option[String] =
    result.left.toOption.collect { case e: IntegrationError => e.code }

  /** One managed integration and everything a scenario does to it. */
  private final class Scenario(val w: World, name: String, nodes: (String, Boolean)*) {
    private val externalIds = nodes.map { case (label, _) => label -> UUID.randomUUID().toString }.toMap
    @volatile private var disabled: Map[String, Boolean] = nodes.toMap
    val service: IntegrationDesiredStates[ConnectionIO] = w.desiredStates()

    /** What the panel reports from now on; nothing changes in the inventory until a sync. */
    def remote(label: String, isDisabled: Boolean): Unit = {
      disabled += label -> isDisabled
      w.observation = IO.pure(snapshot(nodes.map { case (l, _) => node(externalIds(l), l, disabled(l)) }: _*))
    }
    remote(nodes.head._1, nodes.head._2)
    val integration: Integration = w.create(name)
    sync()
    w.run.run(w.management.setEnabled(w.actor, integration.id, enabled = true)).unsafeRunSync()
    val id: UUID = integration.id

    def manage(): Integration = mode(IntegrationManagementMode.ManagedSelected).toOption.get
    def mode(value: IntegrationManagementMode): Either[Throwable, Integration] =
      w.run.run(service.setMode(w.actor, id, value)).attempt.unsafeRunSync()
    /** A later observation: it begins strictly after everything that happened before. */
    def sync(): IntegrationSyncSession = { Thread.sleep(15); val session = w.manual(integration.id); Thread.sleep(15); session }
    def obj(label: String): UUID = w.run.run(sql"""select id from integration_inventory_object
        where integration_id = ${integration.id} and external_id = ${externalIds(label)}""".query[UUID].unique).unsafeRunSync()
    def set(label: String, state: IntegrationDesiredNodeState): Either[Throwable, DesiredStateView] =
      w.run.run(service.set(w.actor, id, obj(label), state)).attempt.unsafeRunSync()
    def remove(label: String): Either[Throwable, Unit] =
      w.run.run(service.remove(w.actor, id, obj(label))).attempt.unsafeRunSync()
    def status(label: String): Option[IntegrationDesiredStateStatus] =
      w.run.run(w.desiredRepository.view(w.org, id, obj(label))).unsafeRunSync().map(_.status)
    /** Every intent is due, then one full pass of the desired-state worker. */
    def reconcile(): Unit = {
      w.run.run(w.desiredRepository.nudge(w.org, id, Instant.now())).unsafeRunSync()
      Thread.sleep(2)
      w.desiredWorker().tick.unsafeRunSync()
    }
    /** Actions created by reconciliation, oldest first: code, status, intent version. */
    def automatic: List[(String, String, Long)] = w.run.run(sql"""select action_code, status, desired_state_version_snapshot
        from integration_action_execution where integration_id = $id and source = 'DESIRED_STATE'
        order by created_at, id""".query[(String, String, Long)].to[List]).unsafeRunSync()
    /** The action worker executes whatever is queued, with this remote outcome. */
    def execute(outcome: IntegrationActionRemoteOutcome = IntegrationActionRemoteOutcome.Succeeded): Unit = {
      w.remoteOutcome = IO.pure(outcome)
      w.actionWorker().tick.unsafeRunSync()
    }
    def manual(label: String, action: IntegrationActionCode): Either[Throwable, IntegrationActionExecution] =
      w.run.run(w.actionService.request(w.actor, id, obj(label), UUID.randomUUID(), action)).attempt.unsafeRunSync()
    def audited(action: String): Int = w.actions.count(_ == action)
  }

  test("24D mode: every integration starts OBSERVE, and managing is an explicit, reversible opt-in") {
    withWorld { w =>
      val fresh = w.create("Fresh")
      assertEquals(fresh.managementMode, IntegrationManagementMode.Observe)
      assertEquals(w.run.run(sql"select management_mode from integration where id = ${fresh.id}".query[String].unique)
        .unsafeRunSync(), "OBSERVE")
      // Without automatic observation there is nothing to hold a desired state against.
      assertEquals(code(w.run.run(w.desiredStates().setMode(w.actor, fresh.id, IntegrationManagementMode.ManagedSelected))
        .attempt.unsafeRunSync()), Some("INTEGRATION_MANAGEMENT_REQUIRES_SYNC"))

      val s = new Scenario(w, "Mode", "a" -> false, "b" -> true)
      assertEquals(code(w.run.run(w.desiredStates(desiredStateOperational = false).setMode(w.actor, s.id,
        IntegrationManagementMode.ManagedSelected)).attempt.unsafeRunSync()), Some("INTEGRATION_DESIRED_STATE_DISABLED"))
      assertEquals(code(s.set("a", Enabled)), Some("INTEGRATION_MANAGEMENT_MODE_REQUIRED"))
      assertEquals(s.manage().managementMode, IntegrationManagementMode.ManagedSelected)
      assertEquals(s.manage().managementMode, IntegrationManagementMode.ManagedSelected)
      assertEquals(s.audited("INTEGRATION_MANAGEMENT_MODE_CHANGED"), 1)

      // A managed integration keeps its observation, its endpoint and its credential; its name is free.
      def update(url: String, credential: Option[RemnawaveCredential]) = w.run.run(w.management.update(w.actor, s.id,
        UpdateIntegrationCommand("Renamed", url, credential))).attempt.unsafeRunSync()
      assertEquals(code(w.run.run(w.management.setEnabled(w.actor, s.id, enabled = false)).attempt.unsafeRunSync()),
        Some("INTEGRATION_MANAGEMENT_REQUIRES_SYNC"))
      assertEquals(code(update("https://other.example.test", None)), Some("INTEGRATION_MANAGEMENT_ACTIVE"))
      assertEquals(code(update("https://panel.example.test", Some(RemnawaveCredential("t2", None)))),
        Some("INTEGRATION_MANAGEMENT_ACTIVE"))
      assertEquals(update("https://panel.example.test", None).map(_.name), Right("Renamed"))

      // Back to OBSERVE: intents are removed, nothing is sent to Remnawave, remote state is untouched.
      assert(s.set("a", Disabled).isRight)
      assert(s.set("b", Enabled).isRight)
      assertEquals(s.mode(IntegrationManagementMode.Observe).map(_.managementMode), Right(IntegrationManagementMode.Observe))
      assertEquals(w.run.run(sql"select count(*) from integration_desired_state where integration_id = ${s.id}"
        .query[Long].unique).unsafeRunSync(), 0L)
      assertEquals(w.run.run(sql"select count(*) from integration_action_execution where integration_id = ${s.id}"
        .query[Long].unique).unsafeRunSync(), 0L)
      assertEquals(w.remoteActionCalls, 0)
      assertEquals(s.audited("INTEGRATION_MANAGEMENT_MODE_CHANGED"), 2)
      s.reconcile()
      assertEquals(s.automatic, Nil)
      // Once it only observes again, the endpoint may change.
      assert(update("https://other.example.test", None).isRight)
    }
  }

  test("24D database: an OBSERVE integration cannot hold a desired state, a managed one cannot stop observing") {
    withWorld { w =>
      val s = new Scenario(w, "Invariants", "a" -> false)
      def refused(statement: ConnectionIO[Int]) = w.run.run(statement).attempt.unsafeRunSync().left.toOption
        .collect { case e: java.sql.SQLException => e.getSQLState }
      val insert = sql"""insert into integration_desired_state (id, organization_id, integration_id,
          inventory_object_id, desired_state, version, set_by_user_id, created_at, updated_at, next_reconcile_at)
          values (${UUID.randomUUID()}, ${w.org}, ${s.id}, ${s.obj("a")}, 'ENABLED', 1, ${w.user}, now(), now(), now())"""
        .update.run
      assertEquals(refused(insert), Some("23503"))
      s.manage()
      assert(s.set("a", Enabled).isRight)
      assertEquals(refused(sql"update integration set management_mode = 'OBSERVE' where id = ${s.id}".update.run),
        Some("23503"))
      assertEquals(refused(sql"update integration set enabled = false where id = ${s.id}".update.run), Some("23514"))
      assertEquals(refused(sql"""insert into integration_action_execution (id, organization_id, integration_id,
          inventory_object_id, request_id, action_code, external_id_snapshot, display_name_snapshot,
          requested_by_user_id, status, created_at, updated_at, source)
          values (${UUID.randomUUID()}, ${w.org}, ${s.id}, ${s.obj("a")}, ${UUID.randomUUID()}, 'NODE_ENABLE', 'x', 'x',
            ${w.user}, 'QUEUED', now(), now(), 'DESIRED_STATE')""".update.run), Some("23514"))
    }
  }

  test("24D intent: versioned, idempotent, NODE only, tenant-scoped, and frozen while an action is active") {
    withWorld { w =>
      val s = new Scenario(w, "Intent", "a" -> false, "gone" -> false)
      s.manage()
      val created = s.set("a", Enabled).toOption.get
      assertEquals((created.state, created.version, created.status), (Enabled: IntegrationDesiredNodeState, 1L, Compliant: IntegrationDesiredStateStatus))
      Thread.sleep(10)
      val repeated = s.set("a", Enabled).toOption.get
      assertEquals((repeated.version, repeated.updatedAt), (1L, created.updatedAt))
      assertEquals(s.audited("INTEGRATION_DESIRED_STATE_SET"), 1)
      val changed = s.set("a", Disabled).toOption.get
      assertEquals((changed.id, changed.version, changed.status), (created.id, 2L, Drifted: IntegrationDesiredStateStatus))
      assertEquals(s.audited("INTEGRATION_DESIRED_STATE_SET"), 2)

      // Only an active NODE of this integration, of this organization.
      w.observation = IO.pure(snapshot(node(UUID.randomUUID().toString, "other"), host))
      val other = w.create("Other intent")
      w.manual(other.id)
      val hostId = w.objects(other.id).find(_._1 == "host-1").get._5
      w.run.run(w.management.setEnabled(w.actor, other.id, enabled = true)).unsafeRunSync()
      w.run.run(s.service.setMode(w.actor, other.id, IntegrationManagementMode.ManagedSelected)).unsafeRunSync()
      def setOn(actor: ActorContext, integration: UUID, objectId: UUID) =
        code(w.run.run(s.service.set(actor, integration, objectId, Enabled)).attempt.unsafeRunSync())
      assertEquals(setOn(w.actor, other.id, hostId), Some("INTEGRATION_DESIRED_STATE_UNSUPPORTED"))
      assertEquals(setOn(w.actor, other.id, s.obj("a")), Some("INTEGRATION_OBJECT_NOT_FOUND"))
      assertEquals(setOn(ActorContext(w.user, w.foreign), s.id, s.obj("a")), Some("INTEGRATION_NOT_FOUND"))
      w.run.run(sql"update integration_inventory_object set is_active = false where id = ${s.obj("gone")}".update.run)
        .unsafeRunSync()
      assertEquals(code(s.set("gone", Enabled)), Some("INTEGRATION_OBJECT_INACTIVE"))

      // No cancellation: while an action is queued or running, the intent cannot change or go away.
      s.reconcile()
      assertEquals(s.automatic, List(("NODE_DISABLE", "QUEUED", 2L)))
      assertEquals(code(s.set("a", Enabled)), Some("INTEGRATION_ACTION_ALREADY_RUNNING"))
      assertEquals(code(s.remove("a")), Some("INTEGRATION_ACTION_ALREADY_RUNNING"))
      assertEquals(code(s.mode(IntegrationManagementMode.Observe)), Some("INTEGRATION_ACTION_ALREADY_RUNNING"))
      assert(s.set("a", Disabled).isRight, "the same intent is still a no-op")
      s.execute()
      // Removing never sends the opposite action; removing twice is quiet.
      assert(s.remove("a").isRight)
      assert(s.remove("a").isRight)
      assertEquals(s.audited("INTEGRATION_DESIRED_STATE_REMOVED"), 1)
      assertEquals(s.status("a"), None)
      assertEquals(s.automatic.size, 1)
      assertEquals(w.remoteActionCalls, 1)
    }
  }

  test("24D reconciliation: drift creates exactly the action that closes it, as a desired-state action") {
    withWorld { w =>
      val s = new Scenario(w, "Matrix", "on-on" -> false, "on-off" -> true, "off-on" -> false, "off-off" -> true, "free" -> true)
      s.manage()
      List("on-on" -> Enabled, "on-off" -> Enabled, "off-on" -> Disabled, "off-off" -> Disabled)
        .foreach { case (label, state) => assert(s.set(label, state).isRight, label) }
      assertEquals(List("on-on", "on-off", "off-on", "off-off", "free").map(s.status),
        List(Some(Compliant), Some(Drifted), Some(Drifted), Some(Compliant), None))
      s.reconcile()
      val created = w.run.run(sql"""select o.display_name, a.action_code, a.source, a.status, a.requested_by_user_id,
          a.desired_state_id_snapshot = d.id, a.desired_state_version_snapshot
          from integration_action_execution a
          join integration_inventory_object o on o.id = a.inventory_object_id
          join integration_desired_state d on d.inventory_object_id = o.id
          where a.integration_id = ${s.id} order by o.display_name"""
        .query[(String, String, String, String, UUID, Boolean, Long)].to[List]).unsafeRunSync()
      // The unmanaged node and the compliant ones are left alone; the person who set the intent is named.
      assertEquals(created, List(("off-on", "NODE_DISABLE", "DESIRED_STATE", "QUEUED", w.user, true, 1L),
        ("on-off", "NODE_ENABLE", "DESIRED_STATE", "QUEUED", w.user, true, 1L)))
      assertEquals(List("on-off", "off-on").map(s.status), List(Some(Applying), Some(Applying)))
      // Reconciliation itself is not a user action and sends nothing.
      assertEquals(s.audited("INTEGRATION_ACTION_REQUESTED"), 0)
      assertEquals(w.remoteActionCalls, 0)
      val overview = w.run.run(w.query.overviews(w.org)).unsafeRunSync().apply(s.id).desired
      assertEquals(overview, DesiredStateCounts(managed = 4, compliant = 2, drifted = 0, applying = 2, needsAttention = 0))
      val listed = w.page(s.id, InventoryFilter(None, None, None, 100, 0)).items
        .map(item => item.obj.displayName -> item.desiredState.map(v => (v.state, v.status)))
      assertEquals(listed.toMap.apply("free"), None)
      assertEquals(listed.toMap.apply("on-off"), Some((Enabled, Applying)))
      val history = w.run.run(w.actionRepository.recent(w.org, s.id, 10)).unsafeRunSync()
      assert(history.forall(a => a.source == IntegrationActionSource.DesiredState && a.desiredStateVersion.contains(1L)))
    }
  }

  test("24D anti-loop: one observation allows one action, however often reconciliation runs") {
    withWorld { w =>
      val s = new Scenario(w, "Loop", "a" -> false)
      s.manage()
      s.set("a", Disabled)
      s.reconcile()
      assertEquals(s.automatic, List(("NODE_DISABLE", "QUEUED", 1L)))
      // The panel accepts the action but keeps reporting the node enabled.
      s.execute()
      assertEquals(s.status("a"), Some(WaitingRefresh))
      (1 to 100).foreach(_ => s.reconcile())
      assertEquals(s.automatic.size, 1)
      assertEquals(w.remoteActionCalls, 1)
      // A new observation, still drifted: exactly one more action, and again only one.
      s.sync()
      assertEquals(s.status("a"), Some(Drifted))
      (1 to 20).foreach(_ => s.reconcile())
      assertEquals(s.automatic.map(_._1), List("NODE_DISABLE", "NODE_DISABLE"))
      s.execute()
      (1 to 20).foreach(_ => s.reconcile())
      assertEquals(s.automatic.size, 2)
      assertEquals(w.remoteActionCalls, 2)
    }
  }

  test("24D anti-loop: an observation that began before the action finished is not a fresh one") {
    withWorld { w =>
      val s = new Scenario(w, "Overlap", "a" -> false)
      s.manage()
      s.set("a", Disabled)
      s.reconcile()
      // The provider is read while the action is still in flight; the snapshot is stored after it finished.
      val gate = cats.effect.Deferred.unsafe[IO, Unit]
      val reading = cats.effect.Deferred.unsafe[IO, Unit]
      w.observation = reading.complete(()) *> gate.get.as(snapshot(node(w.objects(s.id).head._1, "a")))
      val observing = w.sync.manual(w.actor, s.id).start.unsafeRunSync()
      reading.get.unsafeRunSync()
      s.execute()
      gate.complete(()).unsafeRunSync()
      assertEquals(observing.joinWithNever.unsafeRunSync().status, IntegrationSyncStatus.Completed)
      assertEquals(s.status("a"), Some(WaitingRefresh))
      (1 to 10).foreach(_ => s.reconcile())
      assertEquals(s.automatic.size, 1)
    }
  }

  test("24D UNKNOWN: never retried blindly; a fresh observation decides") {
    withWorld { w =>
      val s = new Scenario(w, "Unknown", "heals" -> false, "stays" -> false)
      s.manage()
      s.set("heals", Disabled); s.set("stays", Disabled)
      s.reconcile()
      s.execute(IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN"))
      assertEquals(s.automatic.map(_._2), List("UNKNOWN", "UNKNOWN"))
      assertEquals(List("heals", "stays").map(s.status), List(Some(WaitingRefresh), Some(WaitingRefresh)))
      (1 to 25).foreach(_ => s.reconcile())
      assertEquals(s.automatic.size, 2)
      // The action did reach one node and not the other; only the observation can tell.
      s.remote("heals", isDisabled = true)
      s.sync()
      assertEquals(List("heals", "stays").map(s.status), List(Some(Compliant), Some(Drifted)))
      (1 to 25).foreach(_ => s.reconcile())
      assertEquals(s.automatic.size, 3)
      assertEquals(s.status("stays"), Some(Applying))
      assertEquals(w.remoteActionCalls, 2)
    }
  }

  test("24D UNKNOWN from a manual action also holds reconciliation until it is observed") {
    withWorld { w =>
      val s = new Scenario(w, "Manual unknown", "a" -> false)
      s.manage()
      assert(s.manual("a", IntegrationActionCode.NodeRestart).isRight)
      s.execute(IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN"))
      s.set("a", Disabled)
      (1 to 5).foreach(_ => s.reconcile())
      assertEquals(s.automatic, Nil)
      s.sync()
      s.reconcile()
      assertEquals(s.automatic.map(_._1), List("NODE_DISABLE"))
    }
  }

  test("24D FAILED: no retry on the same observation, one new remediation after a fresh one") {
    withWorld { w =>
      val s = new Scenario(w, "Failed", "a" -> true)
      s.manage()
      s.set("a", Enabled)
      s.reconcile()
      s.execute(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_FORBIDDEN"))
      assertEquals(s.automatic, List(("NODE_ENABLE", "FAILED", 1L)))
      assertEquals(s.status("a"), Some(RemediationFailed))
      (1 to 50).foreach(_ => s.reconcile())
      assertEquals(s.automatic.size, 1)
      assertEquals(w.run.run(w.query.overviews(w.org)).unsafeRunSync().apply(s.id).desired.needsAttention, 1L)
      s.sync()
      assertEquals(s.status("a"), Some(Drifted))
      (1 to 50).foreach(_ => s.reconcile())
      assertEquals(s.automatic.map(_._2), List("FAILED", "QUEUED"))
      assertEquals(w.remoteActionCalls, 1)
    }
  }

  test("24D fencing: a worker holding an old version or an old claim creates nothing") {
    withWorld { w =>
      val s = new Scenario(w, "Fenced", "a" -> false, "b" -> false)
      s.manage()
      s.set("a", Disabled); s.set("b", Disabled)
      w.run.run(w.desiredRepository.nudge(w.org, s.id, Instant.now())).unsafeRunSync()
      Thread.sleep(2)
      val token = UUID.randomUUID(); val now = Instant.now()
      val claimed = w.run.run(w.desiredRepository.claim(UUID.randomUUID(), token, now, now.plusSeconds(30), 50))
        .unsafeRunSync().filter(_.integrationId == s.id)
      assertEquals(claimed.map(_.version), List(1L, 1L))
      // Nobody else can claim what is leased.
      assertEquals(w.run.run(w.desiredRepository.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(30), 50))
        .unsafeRunSync().filter(_.integrationId == s.id), Nil)
      // The person changes the intent of `a` while the sweep is under way: version 3 of nothing the worker saw.
      assert(s.set("a", Enabled).isRight)
      assert(s.set("a", Disabled).isRight)
      val intents = claimed.map(c => DesiredStateIntent(c.id, c.version, IntegrationActionCode.NodeDisable, c.lastSeenAt))
      val created = w.run.run(w.desiredRepository.createActions(token, Instant.now(), intents)).unsafeRunSync()
      assertEquals(created.map(_.inventoryObjectId), List(s.obj("b")))
      // The same batch again, a stranger's token, a stale observation: nothing.
      assertEquals(w.run.run(w.desiredRepository.createActions(token, Instant.now(), intents)).unsafeRunSync(), Nil)
      assertEquals(w.run.run(w.desiredRepository.createActions(UUID.randomUUID(), Instant.now(), intents)).unsafeRunSync(), Nil)
      assertEquals(s.automatic, List(("NODE_DISABLE", "QUEUED", 1L)))
      w.run.run(w.desiredRepository.release(token, now, Instant.now().plusSeconds(3600))).unsafeRunSync()
      // The changed intent was made due by the change itself and is reconciled as version 3.
      w.desiredWorker().tick.unsafeRunSync()
      assertEquals(s.automatic.map(_._3).sorted, List(1L, 3L))
    }
  }

  test("24D multi-instance: concurrent workers create one action per drifted node") {
    withWorld { w =>
      val labels = (1 to 40).map(n => f"n$n%02d")
      val s = new Scenario(w, "Instances", labels.map(_ -> false): _*)
      s.manage()
      labels.foreach(label => s.set(label, Disabled))
      w.run.run(w.desiredRepository.nudge(w.org, s.id, Instant.now())).unsafeRunSync()
      Thread.sleep(2)
      List.fill(6)(w.desiredWorker(batch = 7, concurrency = 3).tick).parSequence.unsafeRunSync()
      assertEquals(s.automatic.size, 40)
      assertEquals(w.run.run(sql"""select count(distinct inventory_object_id) from integration_action_execution
          where integration_id = ${s.id}""".query[Long].unique).unsafeRunSync(), 40L)
      assertEquals(w.remoteActionCalls, 0)
    }
  }

  test("24D loop: reconcile, act, observe, comply — inventory changes only by observation") {
    withWorld { w =>
      val s = new Scenario(w, "Loop end to end", "a" -> false)
      s.manage()
      s.set("a", Disabled)
      def observedDisabled = w.run.run(sql"""select (summary ->> 'isDisabled')::boolean from integration_inventory_object
          where id = ${s.obj("a")}""".query[Boolean].unique).unsafeRunSync()
      s.reconcile()
      assertEquals(s.automatic, List(("NODE_DISABLE", "QUEUED", 1L)))
      assertEquals(s.status("a"), Some(Applying))
      s.remote("a", isDisabled = true)
      s.execute()
      assertEquals(s.automatic.map(_._2), List("SUCCEEDED"))
      // Success is not an observation: nothing is written into the inventory on its strength.
      assert(!observedDisabled)
      assertEquals(s.status("a"), Some(WaitingRefresh))
      // A successful snapshot makes every intent of the integration due in the same transaction.
      w.run.run(sql"update integration_desired_state set next_reconcile_at = now() + interval '1 day' where integration_id = ${s.id}"
        .update.run).unsafeRunSync()
      s.sync()
      assert(observedDisabled)
      assertEquals(s.status("a"), Some(Compliant))
      def nextReconcile = w.run.run(sql"select next_reconcile_at from integration_desired_state where integration_id = ${s.id}"
        .query[Instant].unique).unsafeRunSync()
      assert(!nextReconcile.isAfter(Instant.now()))
      w.desiredWorker().tick.unsafeRunSync()
      assertEquals(s.automatic.size, 1)
      // A failed synchronization is not an observation and nudges nothing.
      w.run.run(sql"update integration_desired_state set next_reconcile_at = now() + interval '1 day' where integration_id = ${s.id}"
        .update.run).unsafeRunSync()
      w.observation = IO.raiseError(IntegrationError("INTEGRATION_REMOTE_UNAVAILABLE", "x"))
      assertEquals(w.manual(s.id).status, IntegrationSyncStatus.Failed)
      assert(nextReconcile.isAfter(Instant.now().plusSeconds(3600)))
    }
  }

  test("24D unavailable: a node that left the inventory is reported, never acted on") {
    withWorld { w =>
      val s = new Scenario(w, "Unavailable", "a" -> false)
      s.manage()
      s.set("a", Disabled)
      w.observation = IO.pure(snapshot())
      s.sync()
      assertEquals(s.status("a"), Some(Unavailable))
      (1 to 5).foreach(_ => s.reconcile())
      assertEquals(s.automatic, Nil)
    }
  }

  test("24D manual actions: nothing may work against the intent; restart and unmanaged nodes are as in 24C") {
    withWorld { w =>
      val s = new Scenario(w, "Conflicts", "keep-on" -> false, "keep-off" -> true, "drift-on" -> true, "free" -> false)
      s.manage()
      s.set("keep-on", Enabled); s.set("keep-off", Disabled); s.set("drift-on", Enabled)
      assertEquals(code(s.manual("keep-on", IntegrationActionCode.NodeDisable)),
        Some("INTEGRATION_ACTION_CONFLICTS_WITH_DESIRED_STATE"))
      assertEquals(code(s.manual("keep-off", IntegrationActionCode.NodeEnable)),
        Some("INTEGRATION_ACTION_CONFLICTS_WITH_DESIRED_STATE"))
      // An action in the direction of the intent is allowed when the observed state makes it relevant.
      assert(s.manual("drift-on", IntegrationActionCode.NodeEnable).isRight)
      assertEquals(code(s.manual("keep-on", IntegrationActionCode.NodeEnable)), Some("INTEGRATION_ACTION_UNSUPPORTED"))
      assert(s.manual("keep-on", IntegrationActionCode.NodeRestart).isRight)
      assert(s.manual("free", IntegrationActionCode.NodeDisable).isRight)
      val sources = w.run.run(w.actionRepository.recent(w.org, s.id, 10)).unsafeRunSync().map(_.source).distinct
      assertEquals(sources, List[IntegrationActionSource](IntegrationActionSource.Manual))
      // A manual action already under way on a drifted node: reconciliation adds nothing beside it.
      s.reconcile()
      assertEquals(s.automatic, Nil)
    }
  }

  test("24D scale: 1000 managed nodes are reconciled in bounded batches without any remote request") {
    withWorld { w =>
      val labels = (1 to 1000).map(n => f"node-$n%04d")
      // Every other node is observed disabled; all are wanted enabled: 500 drifts.
      val s = new Scenario(w, "Scale", labels.zipWithIndex.map { case (label, index) => label -> (index % 2 == 0) }: _*)
      s.manage()
      val seeded = Instant.now()
      w.run.run(sql"""insert into integration_desired_state (id, organization_id, integration_id, inventory_object_id,
          desired_state, version, set_by_user_id, created_at, updated_at, next_reconcile_at)
          select gen_random_uuid(), o.organization_id, o.integration_id, o.id, 'ENABLED', 1, ${w.user}, $seeded, $seeded, $seeded
          from integration_inventory_object o where o.integration_id = ${s.id}""".update.run).unsafeRunSync()
      val claims = new AtomicInteger; val writes = new AtomicInteger; val releases = new AtomicInteger
      val largest = new AtomicInteger
      val counting = new IntegrationDesiredStateRepository[ConnectionIO] {
        private val real = w.desiredRepository
        override def find(o: UUID, i: UUID, n: UUID) = real.find(o, i, n)
        override def save(value: IntegrationDesiredState, at: Instant) = real.save(value, at)
        override def delete(o: UUID, i: UUID, n: UUID) = real.delete(o, i, n)
        override def deleteAll(o: UUID, i: UUID) = real.deleteAll(o, i)
        override def activeActionExists(o: UUID, i: UUID, n: Option[UUID]) = real.activeActionExists(o, i, n)
        override def view(o: UUID, i: UUID, n: UUID) = real.view(o, i, n)
        override def nudge(o: UUID, i: UUID, at: Instant) = real.nudge(o, i, at)
        override def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int) =
          real.claim(owner, token, now, until, limit).map { rows =>
            claims.incrementAndGet(); largest.accumulateAndGet(rows.size, math.max); rows }
        override def createActions(token: UUID, now: Instant, intents: List[DesiredStateIntent]) =
          real.createActions(token, now, intents).map { rows => writes.incrementAndGet(); rows }
        override def release(token: UUID, claimedAt: Instant, next: Instant) =
          real.release(token, claimedAt, next).map { rows => releases.incrementAndGet(); rows }
      }
      val worker = new IntegrationDesiredStateWorker[ConnectionIO](counting, w.run,
        new infrastructure.runtime.SystemTimeProvider, org.typelevel.log4cats.noop.NoOpLogger[IO],
        IntegrationDesiredStateSettings(scala.concurrent.duration.DurationInt(1).second, 50, 4,
          scala.concurrent.duration.DurationInt(30).seconds, scala.concurrent.duration.DurationInt(1).hour),
        UUID.randomUUID())
      Thread.sleep(5)
      worker.tick.unsafeRunSync()
      assertEquals(s.automatic.size, 500)
      assertEquals(s.automatic.map(_._1).distinct, List("NODE_ENABLE"))
      // Memory and round trips follow the batch size, not the number of nodes: about 20 batches of
      // at most 50 (lanes skipping each other's locked rows may split a few), one empty claim per
      // lane, and at most one write and one release per claim. 1000 nodes never mean 1000 statements.
      assert(largest.get <= 50, largest.get)
      assert(claims.get >= 20 && claims.get <= 40, claims.get)
      assert(writes.get <= claims.get && releases.get <= claims.get, (claims.get, writes.get, releases.get))
      assertEquals(w.remoteActionCalls, 0)
      val counts = w.run.run(w.query.overviews(w.org)).unsafeRunSync().apply(s.id).desired
      assertEquals(counts, DesiredStateCounts(managed = 1000, compliant = 500, drifted = 0, applying = 500, needsAttention = 0))
      // A second pass over an unchanged observation creates nothing.
      w.run.run(w.desiredRepository.nudge(w.org, s.id, Instant.now())).unsafeRunSync()
      Thread.sleep(2)
      worker.tick.unsafeRunSync()
      assertEquals(s.automatic.size, 500)
    }
  }
}

package ru.bitec.app.ops
package application.integration

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.integration._
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import org.typelevel.log4cats.noop.NoOpLogger
import scala.concurrent.duration._
import support.NodeUpgradeFixtures

final class NodeUpgradeWorkerSpec extends FunSuite {
  private class World(count: Int = 3, auto: Boolean = false, pause: Boolean = false) {
    var parent = NodeUpgradeFixtures.run(count, auto, pause)
    var nodes = NodeUpgradeFixtures.members(parent)
    var journal = List.empty[RemnawaveFleetUpgradeAction]
    var ownsLease = true
    var gate: Option[String] = None
    var panelBad = false
    var localBad = false
    var sshLost = false
    var pauseOnSwitch = false
    var switches = List.empty[(UUID, String)]
    var pulls = List.empty[UUID]
    var activations = 0
    var refreshes = 0
    var outcomes = 0
    var actual = parent.snapshot.members.map(m => m.resourceId -> m.baseline).toMap
    val tx = new TransactionRunner[IO, IO] { def run[A](value: IO[A]) = value }
    val repo = new RemnawaveFleetUpgradeRepository[IO] {
      def releaseTarget(o: UUID, f: UUID) = IO.pure(None)
      def releaseRevision(o: UUID, f: UUID, id: UUID) = IO.pure(None)
      def promote(r: FleetNodeReleaseRevision) = IO.unit
      def nextRevisionNumber(o: UUID, f: UUID) = IO.pure(1)
      def observations(o: UUID, f: UUID) = IO.pure(Nil)
      def saveObservation(o: FleetNodeImageObservation) = IO.unit
      def insertPlan(r: RemnawaveFleetUpgradeRun, m: List[RemnawaveFleetUpgradeMember]) = IO.unit
      def run(o: UUID, f: UUID, id: UUID, lock: Boolean = false) = IO.pure(Some(parent))
      def byId(id: UUID) = IO.pure(Some(parent))
      def byRequest(o: UUID, id: UUID) = IO.pure(None)
      def active(o: UUID, f: UUID) = IO.pure(Option.when(parent.state.active)(parent))
      def history(o: UUID, f: UUID) = IO.pure(List(parent))
      def members(id: UUID) = IO.pure(nodes)
      def actions(id: UUID) = IO.pure(journal)
      def start(o: UUID, id: UUID, request: UUID, now: Instant) = IO.pure(false)
      def requestPause(o: UUID, id: UUID, now: Instant) = IO { parent = parent.copy(pauseRequestedAt = Some(now)); true }
      def resume(o: UUID, id: UUID, now: Instant) = IO { parent = parent.copy(state = FleetRolloutState.Queued); true }
      def requestRollback(o: UUID, id: UUID, scope: FleetRollbackScope, now: Instant) = IO.pure(false)
      def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int) = IO {
        if (Set[FleetRolloutState](FleetRolloutState.Queued, FleetRolloutState.Running, FleetRolloutState.RollingBack)(parent.state)) {
          parent = parent.copy(claimToken = Some(token)); List(parent)
        } else Nil
      }
      def renew(id: UUID, token: UUID, now: Instant, until: Instant) = owns(id, token, now)
      def owns(id: UUID, token: UUID, now: Instant) = IO.pure(ownsLease && parent.claimToken.contains(token))
      def save(r: RemnawaveFleetUpgradeRun, token: UUID, now: Instant, release: Boolean) = owns(r.id, token, now).map { ok =>
        if (ok) parent = r.copy(pauseRequestedAt = if (r.state == FleetRolloutState.Paused) None else parent.pauseRequestedAt,
          claimToken = if (release) None else Some(token)); ok
      }
      def saveMember(m: RemnawaveFleetUpgradeMember, token: UUID, now: Instant) = owns(m.upgradeId, token, now).map { ok =>
        if (ok) nodes = nodes.map(v => if (v.id == m.id) m else v); ok
      }
      def saveAction(a: RemnawaveFleetUpgradeAction, token: UUID, now: Instant) = owns(a.upgradeId, token, now).map { ok =>
        if (ok) {
          journal.find(_.id == a.id).foreach(old => if (old.state == FleetActionState.Succeeded || old.state == FleetActionState.Unknown || old.state == FleetActionState.Failed)
            assertEquals(a, old, "terminal action is immutable"))
          journal = journal.filterNot(_.id == a.id) :+ a
        }; ok
      }
    }
    val execution = new NodeUpgradeExecution[IO] {
      val settings = NodeUpgradeSettings(pollInterval = 1.millisecond, verificationTimeout = 1.second)
      def admission(r: RemnawaveFleetUpgradeRun, atomicMember: Option[UUID] = None) = IO.pure(gate)
      def verification(r: RemnawaveFleetUpgradeRun, m: NodeUpgradeMemberPlan, release: NodeRelease, after: Instant) =
        IO.pure(Option.when(panelBad && release == NodeUpgradeFixtures.target)(NodeUpgradeAdmission.HealthGate))
      def connection(org: UUID, member: NodeUpgradeMemberPlan) = IO.pure(Connection(member.sourceConnectionId, org,
        ConnectionScope.Organization, "SSH", "node", "Node", ConnectionConfig(Map.empty), None, true,
        member.sourceUpdatedAt, member.sourceUpdatedAt))
      def panel(org: UUID, integration: UUID) = IO.pure(NodeUpgradeFixtures.panel)
      def spec(s: NodeUpgradeSnapshot, m: NodeUpgradeMemberPlan) = RemnawaveNodeRemoteSpec(m.onboardingId, m.resourceId,
        m.externalNodeId, s.configuration.nodePort, m.originalImageReference, s.configuration.panelCidrs)
      def recordOutcome(r: RemnawaveFleetUpgradeRun) = IO { outcomes += 1 }
    }
    val remote = new RemnawaveNodeImageRemote[IO] {
      def observeImage(c: Connection, s: RemnawaveNodeRemoteSpec) =
        if (sshLost) IO.raiseError(NodeImageRemoteFailure("NODE_UPGRADE_REMOTE_UNKNOWN", true)) else IO.pure(actual(s.resourceId))
      def prefetchImage(c: Connection, s: RemnawaveNodeRemoteSpec, release: NodeRelease, p: NodeReleasePlatform,
        baseline: NodeImageObservation, authorize: IO[Unit]) = authorize *> IO { pulls = pulls :+ s.resourceId }
      def switchImage(c: Connection, s: RemnawaveNodeRemoteSpec, ref: String, expected: String, compose: String, marker: String, authorize: IO[Unit]) =
        authorize *> IO {
          switches = switches :+ (s.resourceId -> ref)
          val release = if (ref == NodeUpgradeFixtures.previous.forPlatform("linux/amd64").map(p => s"ghcr.io/remnawave/node@${p.manifestDigest}").get)
            NodeUpgradeFixtures.previous else NodeUpgradeFixtures.target
          actual += s.resourceId -> NodeUpgradeFixtures.image(release).copy(containerRunning = !localBad || release == NodeUpgradeFixtures.previous)
          if (pauseOnSwitch) parent = parent.copy(pauseRequestedAt = Some(Instant.now()))
        }
      def activateImage(c: Connection, s: RemnawaveNodeRemoteSpec, ref: String, id: String, marker: String, authorize: IO[Unit]) =
        authorize *> IO { activations += 1; actual += s.resourceId -> NodeUpgradeFixtures.image(NodeUpgradeFixtures.target) }
    }
    val verifier = new RemnawaveNodeReleaseVerifier { def verify(r: NodeRelease, p: NodeReleasePlatform) = IO.unit }
    val worker = new RemnawaveFleetUpgradeWorker(repo, execution, remote, verifier,
      (_, _, _, _) => IO { refreshes += 1 }, tx, NoOpLogger.impl[IO])
    def tick(n: Int = 1): Unit = (0 until n).foreach(_ => worker.tick.unsafeRunSync())
    def until(done: => Boolean): Unit = { var n = 0; while (!done && n < 60) { tick(); n += 1 }; assert(done, parent.toString) }
    def recoveredSwitch(target: Boolean, committedOnly: Boolean = false): Unit = {
      parent = parent.copy(phase = NodeUpgradePhase.UpgradeCanary, state = FleetRolloutState.Running)
      nodes = nodes.map(m => if (m.wave == 0) m.copy(state = FleetRolloutMemberState.Running, startedAt = Some(Instant.now())) else m)
      val m = nodes.head
      val p = parent.snapshot.members.head
      val ref = s"ghcr.io/remnawave/node@${NodeUpgradeFixtures.target.forPlatform("linux/amd64").get.manifestDigest}"
      journal = List(RemnawaveFleetUpgradeAction(NodeUpgradeFixtures.uid, parent.organizationId, parent.id, m.id, "SWITCH", false,
        FleetActionState.Running, ref, None, Instant.now().minusSeconds(1), None))
      actual += p.resourceId -> (if (target) NodeUpgradeFixtures.image(NodeUpgradeFixtures.target) else if (committedOnly)
        p.baseline.copy(configuredImage = Some(ref), composeHash = Some("d" * 64)) else p.baseline)
    }
  }
  test("three nodes run canary first, each exact digest is prefetched, verified, and journaled once") {
    val w = new World
    w.until(w.parent.state.terminal)
    assertEquals(w.parent.state, FleetRolloutState.Succeeded)
    assertEquals(w.switches.map(_._1), w.parent.snapshot.members.map(_.resourceId))
    assertEquals(w.pulls, w.parent.snapshot.members.map(_.resourceId))
    assertEquals(w.journal.count(a => a.kind == "SWITCH" && a.state == FleetActionState.Succeeded), 3)
    assertEquals(w.outcomes, 1)
  }
  test("stale admission pauses before any journal or remote mutation") {
    val w = new World; w.gate = Some(NodeUpgradeAdmission.RefreshRequired); w.tick()
    assertEquals(w.parent.state, FleetRolloutState.Paused)
    assertEquals(w.parent.pauseReason, Some("PAUSED_REFRESH_REQUIRED"))
    assert(w.switches.isEmpty && w.pulls.isEmpty && w.journal.isEmpty)
  }
  test("crash after member failure cannot advance the next wave") {
    val w = new World
    w.parent = w.parent.copy(phase = NodeUpgradePhase.UpgradeCanary)
    w.nodes = w.nodes.map(m => if (m.wave == 0) m.copy(state = FleetRolloutMemberState.Failed, failureCode = Some("NODE_UPGRADE_LOCAL_HEALTH_FAILED")) else m)
    w.tick()
    assertEquals(w.parent.state, FleetRolloutState.Failed)
    assert(w.switches.isEmpty && w.pulls.isEmpty)
  }
  test("crash after successful Panel journal reconciles member without replay or journal rewrite") {
    val w = new World
    w.recoveredSwitch(target = true)
    val m = w.nodes.head
    val at = Instant.now().minusSeconds(1)
    w.journal = w.journal.map(_.copy(state = FleetActionState.Succeeded, finishedAt = Some(at)))
    val saved = RemnawaveFleetUpgradeAction(NodeUpgradeFixtures.uid, w.parent.organizationId, w.parent.id, m.id,
      "PANEL_VERIFY", false, FleetActionState.Succeeded, w.journal.head.targetReference, None, at, Some(at))
    w.journal = w.journal :+ saved
    w.tick()
    assertEquals(w.nodes.head.state, FleetRolloutMemberState.Succeeded)
    assertEquals(w.journal.find(_.id == saved.id), Some(saved))
    assert(w.switches.isEmpty)
  }
  test("a degraded completed canary prevents the next wave and automatic rollback") {
    val w = new World(auto = true, pause = true); w.until(w.parent.state == FleetRolloutState.Paused)
    assertEquals(w.switches.size, 1)
    w.parent = w.parent.copy(state = FleetRolloutState.Queued)
    w.gate = Some(NodeUpgradeAdmission.HealthGate); w.tick()
    assertEquals(w.parent.state, FleetRolloutState.Paused)
    assertEquals(w.switches.size, 1)
    assertEquals(w.pulls.size, 1)
  }
  test("recovery after up observes target and never issues a duplicate switch") {
    val w = new World(1); w.recoveredSwitch(target = true); w.tick()
    assert(w.switches.isEmpty)
    assertEquals(w.activations, 0)
    assertEquals(w.nodes.head.state, FleetRolloutMemberState.Succeeded)
    assertEquals(w.journal.find(_.kind == "SWITCH").get.state, FleetActionState.Succeeded)
  }
  test("recovery after Compose commit activates only the proven target and keeps original installation") {
    val w = new World(1); w.recoveredSwitch(target = false, committedOnly = true); w.tick()
    assert(w.switches.isEmpty)
    assertEquals(w.activations, 1)
    assertEquals(w.nodes.head.state, FleetRolloutMemberState.Succeeded)
  }
  test("SSH uncertainty is UNKNOWN with no automatic rollback, replay or next node") {
    val w = new World(auto = true); w.recoveredSwitch(target = true); w.sshLost = true; w.tick(3)
    assertEquals(w.parent.state, FleetRolloutState.Unknown)
    assert(w.switches.isEmpty)
    assertEquals(w.journal.size, 1)
  }
  test("unknown actual digest during recovery makes no mutation") {
    val w = new World(auto = true); w.recoveredSwitch(target = false)
    val resource = w.parent.snapshot.members.head.resourceId
    w.actual += resource -> w.actual(resource).copy(actualImageId = Some("sha256:" + "0" * 64))
    w.tick(); assertEquals(w.parent.state, FleetRolloutState.Unknown); assert(w.switches.isEmpty)
  }
  test("healthy local target with unhealthy fresh Panel evidence rolls back only known successful switch") {
    val w = new World(auto = true); w.panelBad = true; w.until(w.parent.state.terminal)
    assertEquals(w.parent.state, FleetRolloutState.RolledBack)
    assertEquals(w.switches.size, 2)
    assertEquals(w.switches.last._2, w.parent.snapshot.members.head.previousImageReference.get)
    assertEquals(w.nodes.head.state, FleetRolloutMemberState.RolledBack)
    assertEquals(w.nodes.tail.map(_.state), List(FleetRolloutMemberState.Pending, FleetRolloutMemberState.Pending))
  }
  test("known local failure rolls back to exact retained previous image and verifies it") {
    val w = new World(auto = true); w.localBad = true; w.until(w.parent.state.terminal)
    assertEquals(w.parent.state, FleetRolloutState.RolledBack)
    assertEquals(w.switches.last._2, w.parent.snapshot.members.head.previousImageReference.get)
    assert(w.nodes.head.panelVerifiedAt.nonEmpty)
  }
  test("operator pause during switch finishes current node and stops before the next") {
    val w = new World; w.pauseOnSwitch = true; w.until(w.parent.state == FleetRolloutState.Paused)
    assertEquals(w.switches.size, 1)
    assertEquals(w.nodes.head.state, FleetRolloutMemberState.Succeeded)
  }
  test("lost fencing authority admits no remote mutation and writes no journal") {
    val w = new World; w.ownsLease = false; w.tick()
    assert(w.switches.isEmpty && w.pulls.isEmpty && w.journal.isEmpty)
    assertEquals(w.parent.state, FleetRolloutState.Queued)
  }
}

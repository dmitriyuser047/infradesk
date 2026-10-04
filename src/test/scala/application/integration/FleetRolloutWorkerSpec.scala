package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.audit.{AuditCursor, AuditEvent}
import domain.integration._
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration._
import support.ServerProfileFixtures

/** Recovery and gates use the real worker/member state machines, without a database or remote calls. */
final class FleetRolloutWorkerSpec extends FunSuite {
  private def uid = UUID.randomUUID()
  private val org = uid
  private val integrationId = uid
  private val fleetId = uid
  private val revisionId = uid
  private val actorId = uid
  private val at = Instant.parse("2026-10-04T12:00:00Z")
  private val content = FleetDesiredContent(uid, uid, 5, ServerProfileFixtures.content.hash, uid, uid,
    uid, uid, 9, "b" * 64, List(uid), 2222, List("198.51.100.0/24"), IntegrationDesiredNodeState.Enabled)
  private val tx = new TransactionRunner[IO, IO] { def run[A](program: IO[A]): IO[A] = program }
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.fleet.rollout")

  /** Unused service ports fail immediately rather than returning a permissive default. */
  private def unused[A](kind: Class[A]): A = Proxy.newProxyInstance(kind.getClassLoader, Array(kind),
    new InvocationHandler {
      def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        throw new AssertionError(s"Unexpected dependency call: ${kind.getSimpleName}.${method.getName}")
    }).asInstanceOf[A]

  private def port[A](kind: Class[A])(read: PartialFunction[String, AnyRef]): A =
    Proxy.newProxyInstance(kind.getClassLoader, Array(kind), new InvocationHandler {
      def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        read.applyOrElse(method.getName, (name: String) => throw new AssertionError(s"Unexpected ${kind.getSimpleName}.$name"))
    }).asInstanceOf[A]

  private def plan(wave: Int, actions: List[FleetActionKind]) = FleetRolloutMemberPlan(uid, 1L, uid, uid,
    "external-node", s"edge-$wave", wave, wave, None, actions,
    FleetMemberBaseline(Some(uid), Some(4), Some(IntegrationDesiredNodeState.Disabled), "DRIFTED", "HEALTHY"))

  private def snapshot(plans: List[FleetRolloutMemberPlan], shared: Boolean = false) = FleetRolloutSnapshot(
    fleetId, integrationId, at, revisionId, 3, "h" * 64, content,
    FleetRolloutPolicy(1, plans.filter(_.wave == 0).map(_.membershipId), false, false, FleetRollbackScope.CurrentWave),
    FleetRolloutSharedConfig(shared, content.inventoryConfigProfileId, 9, "b" * 64, Some(8), Some("a" * 64), 0, 0), plans)

  private def rollout(s: FleetRolloutSnapshot, phase: FleetRolloutPhase) = RemnawaveFleetRollout(
    uid, org, integrationId, fleetId, revisionId, None, FleetRolloutState.Running, phase, s, s.hash,
    0, s.waveCount, false, false, FleetRollbackScope.CurrentWave, actorId, at, at.plusSeconds(86400),
    Some(at), None, None, None, false, None, None, None, None, None, None, at, at, None, 1L, at)

  private def member(r: RemnawaveFleetRollout, p: FleetRolloutMemberPlan, state: FleetRolloutMemberState) =
    RemnawaveFleetRolloutMember(uid, org, r.id, fleetId, p.membershipId, p.membershipVersion, p.inventoryNodeId,
      p.resourceId, p.externalNodeId, p.wave, p.position, state, None, p.actions, None, None, None,
      Some(at.minusSeconds(2)), Some(at), 1L, at)

  private def evidence(p: FleetRolloutMemberPlan, compliance: FleetCompliance, health: FleetHealth,
    reasons: List[FleetDriftReason] = Nil) = {
    val observed = at.plusSeconds(2)
    val membership = RemnawaveFleetMembership(p.membershipId, org, fleetId, integrationId,
      p.inventoryNodeId, p.resourceId, p.membershipVersion, actorId, at)
    val assessment = RemnawaveFleetNodeAssessment(uid, org, fleetId, revisionId, p.membershipId,
      p.membershipVersion, p.inventoryNodeId, p.resourceId, compliance, health, reasons, Nil, Nil,
      Some(observed), Some(observed), Some(observed), observed.plusSeconds(1))
    FleetMemberRow(membership, p.nodeName, "192.0.2.1", None, true, false, "server", true,
      None, None, None, Some(content.externalConfigProfileId.toString), None, None, true, Some(assessment))
  }

  private def action(r: RemnawaveFleetRollout, m: RemnawaveFleetRolloutMember, kind: FleetActionKind,
    state: FleetActionState, rollback: Boolean = false) = RemnawaveFleetRolloutAction(uid, org, r.id,
    Some(m.id), rollback, kind, 10, state, uid, None, None, None, None, None, None,
    Some(at), if (state == FleetActionState.Succeeded) Some(at) else None, 1L, at)

  private final class MemoryRepository(var current: RemnawaveFleetRollout,
    var memberList: List[RemnawaveFleetRolloutMember], var journal: List[RemnawaveFleetRolloutAction] = Nil)
    extends RemnawaveFleetRolloutRepository[IO] {
    var startWrites = 0
    var resumeWrites = 0
    private def no[A]: IO[A] = IO.raiseError(new AssertionError("Unexpected repository operation"))
    def insertPlan(r: RemnawaveFleetRollout, m: List[RemnawaveFleetRolloutMember]): IO[Unit] = no
    def rollout(o: UUID, f: UUID, id: UUID): IO[Option[RemnawaveFleetRollout]] = IO(Some(current))
    def rolloutForUpdate(o: UUID, f: UUID, id: UUID): IO[Option[RemnawaveFleetRollout]] = rollout(o, f, id)
    def rolloutById(id: UUID): IO[Option[RemnawaveFleetRollout]] = IO(Some(current))
    def byRequest(o: UUID, id: UUID): IO[Option[RemnawaveFleetRollout]] = IO.pure(None)
    def history(o: UUID, f: UUID, limit: Int): IO[List[RemnawaveFleetRollout]] = no
    def activeOf(o: UUID, f: UUID): IO[Option[RemnawaveFleetRollout]] = no
    def start(o: UUID, id: UUID, request: UUID, now: Instant): IO[Boolean] = IO { startWrites += 1; true }
    def members(id: UUID): IO[List[RemnawaveFleetRolloutMember]] = IO.pure(memberList)
    def actions(id: UUID): IO[List[RemnawaveFleetRolloutAction]] = IO.pure(journal)
    def requestPause(o: UUID, id: UUID, by: UUID, now: Instant): IO[Boolean] = no
    def resume(o: UUID, id: UUID, now: Instant): IO[Boolean] = IO { resumeWrites += 1; true }
    def requestRollback(o: UUID, id: UUID, by: UUID, scope: FleetRollbackScope, now: Instant): IO[Boolean] = no
    def claimDue(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int) = IO {
      current = current.copy(claimToken = Some(token)); List(current)
    }
    def renewClaim(id: UUID, token: UUID, now: Instant, until: Instant) = IO.pure(current.claimToken.contains(token))
    def save(r: RemnawaveFleetRollout, token: UUID, now: Instant, clearPauseRequest: Boolean,
      release: Boolean): IO[Boolean] = IO {
      val owns = current.claimToken.contains(token)
      if (owns) current = r.copy(claimToken = if (release) None else Some(token),
        pauseRequestedAt = if (clearPauseRequest) None else r.pauseRequestedAt)
      owns
    }
    def saveMember(m: RemnawaveFleetRolloutMember, token: UUID, now: Instant): IO[Boolean] = IO {
      val owns = current.claimToken.contains(token)
      if (owns) memberList = memberList.map(old => if (old.id == m.id) m else old)
      owns
    }
    def saveAction(a: RemnawaveFleetRolloutAction, token: UUID, now: Instant): IO[Boolean] = IO {
      val owns = current.claimToken.contains(token)
      if (owns) journal = journal.filterNot(_.id == a.id) :+ a
      owns
    }
    def purgeExpired(now: Instant, limit: Int): IO[Int] = IO.pure(0)
  }

  private final class MemoryQuery(var rows: List[FleetMemberRow],
    var consumers: List[FleetRolloutConfigConsumer] = Nil) extends RemnawaveFleetQuery[IO] {
    def configConsumers(o: UUID, i: UUID, p: String) = IO(consumers)
    private def no[A]: IO[A] = IO.raiseError(new AssertionError("Unexpected query"))
    def memberRows(o: UUID, f: UUID): IO[List[FleetMemberRow]] = IO.pure(rows)
    def summaries(o: UUID, i: UUID, ids: List[UUID]): IO[Map[UUID, RemnawaveFleetSummary]] = no
    def candidates(o: UUID, i: UUID, limit: Int): IO[List[FleetCandidate]] = no
    def storedEvidence(o: UUID, m: UUID, revision: RemnawaveFleetRevision): IO[Option[FleetStoredEvidence]] = no
    def provenance(o: UUID, i: UUID, r: UUID, n: UUID): IO[Option[FleetLocalProvenance]] = no
    def serverProfileName(o: UUID, id: UUID): IO[Option[String]] = no
    def configurationProfileName(o: UUID, id: UUID): IO[Option[String]] = no
    def lastSuccessfulSyncAt(o: UUID, i: UUID): IO[Option[Instant]] = IO.realTimeInstant.map(t => Some(t.minusSeconds(30)))
  }

  private final class FakeChildren extends FleetRolloutChildren {
    var firewallCalls = 0
    var previewCalls = 0
    var startCalls = 0
    var observedRuns = List.empty[UUID]
    var refreshCalls = 0
    var verifyCalls = 0
    var desiredCalls = 0
    var configStarts = 0
    var configReads = 0
    var impactReads = 0
    var recoveredConfig: Option[UUID] = None
    var impact: Option[IntegrationConfigRolloutPreview] = Some(IntegrationConfigRolloutPreview(8, 9, "a" * 64,
      "b" * 64, 1, 1, 0))
    var afterApply: () => Unit = () => ()
    var restored: ChildOutcome = ChildOutcome.Running
    private def no[A]: IO[A] = IO.raiseError(new AssertionError("Unexpected child mutation"))
    def sshSource(o: UUID, r: UUID): IO[Option[(UUID, Instant)]] = IO.pure(None)
    def assignment(o: UUID, r: UUID): IO[Option[(UUID, Int)]] = no
    def assign(a: ActorContext, r: UUID, p: UUID, n: Int): IO[Unit] = no
    def unassign(a: ActorContext, r: UUID): IO[Unit] = no
    def previewApply(a: ActorContext, r: UUID): IO[Either[String, UUID]] = IO { previewCalls += 1; Right(uid) }
    def startApply(a: ActorContext, p: UUID, request: UUID): IO[UUID] = IO { startCalls += 1; uid }
    def applyOutcome(o: UUID, id: UUID): IO[ChildOutcome] = IO { observedRuns = observedRuns :+ id; afterApply(); ChildOutcome.Succeeded }
    def managedCidrs(o: UUID, i: UUID, r: UUID, n: UUID, port: Int): IO[Either[String, List[String]]] =
      IO.pure(Right(List("192.0.2.0/24")))
    def reconcileFirewall(o: UUID, i: UUID, r: UUID, n: UUID, port: Int, cidrs: List[String]): IO[ChildOutcome] =
      IO { firewallCalls += 1; ChildOutcome.Succeeded }
    def desiredRecord(o: UUID, i: UUID, n: UUID): IO[Option[IntegrationDesiredNodeState]] = no
    def desiredView(o: UUID, i: UUID, n: UUID): IO[Option[DesiredStateView]] = no
    def setDesired(a: ActorContext, i: UUID, n: UUID, state: IntegrationDesiredNodeState): IO[Unit] =
      IO { desiredCalls += 1 }
    def removeDesired(a: ActorContext, i: UUID, n: UUID): IO[Unit] = no
    def sharedImpact(o: UUID, i: UUID, id: UUID, n: Int): IO[Option[IntegrationConfigRolloutPreview]] = IO {
      impactReads += 1; impact
    }
    def startConfig(a: ActorContext, i: UUID, id: UUID, n: Int, request: UUID): IO[UUID] = IO { configStarts += 1; uid }
    def configOutcome(o: UUID, id: UUID): IO[ChildOutcome] = IO { configReads += 1; ChildOutcome.Succeeded }
    def configByRequest(o: UUID, request: UUID): IO[Option[UUID]] = IO(recoveredConfig)
    def verifyRestored(a: ActorContext, i: UUID, f: UUID, p: FleetRolloutMemberPlan,
      kinds: Set[FleetActionKind], cidrs: Option[List[String]], port: Int, after: Instant): IO[ChildOutcome] =
      IO { verifyCalls += 1; restored }
    def refresh(o: UUID, i: UUID, f: UUID, when: Instant): IO[Unit] = IO { refreshCalls += 1 }
  }

  private def service(repo: MemoryRepository, query: MemoryQuery, children: FakeChildren,
    configHash: String = "a" * 64) = {
    val auditEvents = new AuditEventRepository[IO] {
      def save(event: AuditEvent): IO[Unit] = IO.unit
      def saveAll(events: List[AuditEvent]): IO[Unit] = IO.unit
      def listByOrganization(o: UUID, before: Option[AuditCursor], limit: Int): IO[List[AuditEvent]] = IO.pure(Nil)
    }
    val audit = new AuditRecorder[IO](auditEvents, new IdGenerator[IO] { def nextId: IO[UUID] = IO(uid) },
      new TimeProvider[IO] { def now: IO[Instant] = IO.realTimeInstant })
    val fleetPort = port(classOf[RemnawaveFleetRepository[IO]]) {
      case "lockFleet" => IO.unit
      case "rolloutActive" => IO.pure(false)
    }
    val integrationPort = port(classOf[IntegrationRepository[IO]]) {
      case "findById" => IO.pure(Some(Integration(integrationId, org, "panel", IntegrationProviderType.Remnawave,
        IntegrationBaseUrl.parse("https://panel.example.test").toOption.get, true, uid, false, at, at)))
    }
    val inventoryPort = port(classOf[IntegrationInventoryRepository[IO]]) {
      case "findObject" => IO.realTimeInstant.map(_.minusSeconds(30)).map(t => Some(IntegrationInventoryObject(content.inventoryConfigProfileId,
        org, integrationId, IntegrationObjectType.ConfigProfile, content.externalConfigProfileId.toString, "Config",
        RemnawaveConfigProfileSummary(0, t, t, Nil, Nil, Some(configHash)), true, t, t, uid)))
    }
    new RemnawaveFleetRollouts[IO](fleetPort, query, repo, integrationPort, inventoryPort,
      unused(classOf[IntegrationBindingRepository[IO]]), children, audit, tx, FleetRolloutSettings(1.hour, 15.minutes, 25))
  }

  private def worker(repo: MemoryRepository, rows: List[FleetMemberRow], children: FakeChildren,
    consumers: List[FleetRolloutConfigConsumer] = Nil, configHash: String = "a" * 64) = {
    val query = new MemoryQuery(rows, consumers)
    val memberRunner = new FleetRolloutMemberRunner[IO](repo, children, tx,
      FleetMemberRunnerSettings(actionTimeout = 1.minute, pollInterval = 1.millis))
    new RemnawaveFleetRolloutWorker[IO](repo, query, children, memberRunner, service(repo, query, children, configHash), tx, logger,
      RemnawaveFleetRolloutWorkerSettings(pollInterval = 1.millis, claimLease = 60.seconds,
        verifyTimeout = 36500.days, maxMemberConcurrency = 1))
  }

  test("degraded canary pauses at verification without advancing or mutating the next wave") {
    val plans = List(plan(0, List(FleetActionKind.ServerProfileApply)), plan(1, List(FleetActionKind.ServerProfileApply)))
    val r = rollout(snapshot(plans), FleetRolloutPhase.VerifyCanary)
    val repo = new MemoryRepository(r, plans.map(p => member(r, p,
      if (p.wave == 0) FleetRolloutMemberState.Succeeded else FleetRolloutMemberState.Pending)))
    val children = new FakeChildren
    worker(repo, List(evidence(plans.head, FleetCompliance.Compliant, FleetHealth.Degraded)), children).tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.Paused)
    assertEquals(repo.current.phase, FleetRolloutPhase.VerifyCanary)
    assertEquals(repo.current.currentWave, 0)
    assertEquals(repo.current.pauseReason, Some("PAUSED_HEALTH_GATE"))
    assertEquals(repo.memberList.last.state, FleetRolloutMemberState.Pending)
    assertEquals(children.startCalls, 0)
  }

  test("failed canary never advances to the next wave") {
    val plans = List(plan(0, Nil), plan(1, Nil))
    val r = rollout(snapshot(plans), FleetRolloutPhase.VerifyCanary)
    val repo = new MemoryRepository(r, plans.map(p => member(r, p, FleetRolloutMemberState.Succeeded)))
    val children = new FakeChildren
    worker(repo, List(evidence(plans.head, FleetCompliance.Drifted, FleetHealth.Healthy)), children).tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.Failed)
    assertEquals(repo.current.currentWave, 0)
    assertEquals(repo.memberList.head.state, FleetRolloutMemberState.Failed)
    assertEquals(children.startCalls, 0)
  }

  test("shared configuration verification allows remaining server profile drift before individual mutations") {
    val p = plan(0, List(FleetActionKind.ServerProfileApply))
    val r = rollout(snapshot(List(p), shared = true), FleetRolloutPhase.VerifySharedConfig)
    val repo = new MemoryRepository(r, List(member(r, p, FleetRolloutMemberState.Pending)))
    val children = new FakeChildren
    worker(repo, List(evidence(p, FleetCompliance.Drifted, FleetHealth.Healthy,
      List(FleetDriftReason.ServerProfileContentDrift))), children).tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.Running)
    assertEquals(repo.current.phase, FleetRolloutPhase.ApplyCanary)
    assertEquals(repo.memberList.head.state, FleetRolloutMemberState.Pending)
    assertEquals(children.startCalls, 0)
  }

  List(FleetActionState.Failed -> FleetRolloutState.Failed,
    FleetActionState.Unknown -> FleetRolloutState.Unknown).foreach { case (actionState, parentState) =>
    test(s"recovered shared configuration $actionState remains terminal without restarting its child") {
      val p = plan(0, Nil)
      val r = rollout(snapshot(List(p), shared = true), FleetRolloutPhase.ApplySharedConfig)
      val m = member(r, p, FleetRolloutMemberState.Pending)
      val a = action(r, m, FleetActionKind.ConfigRollout, actionState).copy(memberId = None,
        failureCode = Some("TEST_SHARED_FAILURE"))
      val repo = new MemoryRepository(r, List(m), List(a))
      worker(repo, Nil, new FakeChildren).tick.unsafeRunSync()
      assertEquals(repo.current.state, parentState)
      assertEquals(repo.current.failureCode, Some("TEST_SHARED_FAILURE"))
      assertEquals(repo.journal, List(a))
    }
  }

  test("fresh final evidence that remains DRIFTED never reports success") {
    val p = plan(0, Nil)
    val r = rollout(snapshot(List(p)), FleetRolloutPhase.FinalVerify)
    val repo = new MemoryRepository(r, List(member(r, p, FleetRolloutMemberState.Succeeded)))
    worker(repo, List(evidence(p, FleetCompliance.Drifted, FleetHealth.Healthy)), new FakeChildren).tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.Failed)
    assertEquals(repo.current.failureCode, Some("REMNAWAVE_FLEET_ROLLOUT_MEMBER_NOT_COMPLIANT"))
  }

  test("an operator pause marker is consumed at the safe boundary before any member action") {
    val p = plan(0, List(FleetActionKind.NetworkFirewall))
    val r = rollout(snapshot(List(p)), FleetRolloutPhase.ApplyCanary).copy(pauseRequestedAt = Some(at))
    val repo = new MemoryRepository(r, List(member(r, p, FleetRolloutMemberState.Pending)))
    val children = new FakeChildren
    worker(repo, Nil, children).tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.Paused)
    assertEquals(repo.current.phase, FleetRolloutPhase.ApplyCanary)
    assertEquals(repo.current.pauseRequestedAt, None)
    assertEquals(repo.current.pauseReason, Some("OPERATOR"))
    assertEquals(children.firewallCalls, 0)
    assertEquals(repo.journal, Nil)
  }

  test("a recovered RUNNING firewall action becomes UNKNOWN without a duplicate mutation") {
    val p = plan(0, List(FleetActionKind.NetworkFirewall))
    val r = rollout(snapshot(List(p)), FleetRolloutPhase.ApplyCanary)
    val m = member(r, p, FleetRolloutMemberState.Running)
    val a = action(r, m, FleetActionKind.NetworkFirewall, FleetActionState.Running)
    val repo = new MemoryRepository(r, List(m), List(a))
    val children = new FakeChildren
    worker(repo, Nil, children).tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.Unknown)
    assertEquals(repo.memberList.head.state, FleetRolloutMemberState.Unknown)
    assertEquals(repo.journal.head.state, FleetActionState.Unknown)
    assertEquals(repo.journal.head.failureCode, Some("REMNAWAVE_FLEET_ROLLOUT_FIREWALL_RESULT_UNKNOWN"))
    assertEquals(children.firewallCalls, 0)
  }

  test("a recovered successful action is not restarted") {
    val p = plan(0, List(FleetActionKind.ServerProfileApply))
    val r = rollout(snapshot(List(p)), FleetRolloutPhase.ApplyCanary)
    val m = member(r, p, FleetRolloutMemberState.Running)
    val a = action(r, m, FleetActionKind.ServerProfileApply, FleetActionState.Succeeded)
    val repo = new MemoryRepository(r, List(m), List(a))
    val children = new FakeChildren
    worker(repo, Nil, children).tick.unsafeRunSync()
    assertEquals(repo.current.phase, FleetRolloutPhase.VerifyCanary)
    assertEquals(repo.memberList.head.state, FleetRolloutMemberState.Succeeded)
    assertEquals(children.previewCalls, 0)
    assertEquals(children.startCalls, 0)
    assertEquals(children.observedRuns, Nil)
    assertEquals(repo.journal, List(a))
  }

  test("a recovered running profile action observes its existing child instead of restarting") {
    val p = plan(0, List(FleetActionKind.ServerProfileApply))
    val r = rollout(snapshot(List(p)), FleetRolloutPhase.ApplyCanary)
    val m = member(r, p, FleetRolloutMemberState.Running)
    val runId = uid
    val a = action(r, m, FleetActionKind.ServerProfileApply, FleetActionState.Running)
      .copy(serverProfilePlanId = Some(uid), serverProfileRunId = Some(runId))
    val repo = new MemoryRepository(r, List(m), List(a))
    val children = new FakeChildren
    worker(repo, Nil, children).tick.unsafeRunSync()
    assertEquals(children.previewCalls, 0)
    assertEquals(children.startCalls, 0)
    assertEquals(children.observedRuns, List(runId))
    assertEquals(repo.journal.head.state, FleetActionState.Succeeded)
    assertEquals(repo.memberList.head.state, FleetRolloutMemberState.Succeeded)
  }

  test("rollback verification Waiting stays ROLLING_BACK and retries verification without repeating compensation") {
    val p = plan(0, List(FleetActionKind.DesiredState))
    val r = rollout(snapshot(List(p)), FleetRolloutPhase.Rollback).copy(state = FleetRolloutState.RollingBack)
    val m = member(r, p, FleetRolloutMemberState.Succeeded)
    val forward = action(r, m, FleetActionKind.DesiredState, FleetActionState.Succeeded)
    val repo = new MemoryRepository(r, List(m), List(forward))
    val children = new FakeChildren
    val w = worker(repo, Nil, children)
    w.tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.RollingBack)
    assertEquals(repo.memberList.head.state, FleetRolloutMemberState.RollingBack)
    assertEquals(repo.journal.find(a => a.rollback && a.kind == FleetActionKind.Verify).map(_.state), Some(FleetActionState.Running))
    assertEquals(children.desiredCalls, 1)
    children.restored = ChildOutcome.Succeeded
    w.tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.RolledBack)
    assertEquals(repo.memberList.head.state, FleetRolloutMemberState.RolledBack)
    assertEquals(children.desiredCalls, 1)
    assertEquals(children.verifyCalls, 2)
    assertEquals(children.refreshCalls, 1)
  }

  test("pause requested during a child finishes it, but starts neither its next action nor the next member") {
    val plans = List(plan(0, List(FleetActionKind.ServerProfileApply, FleetActionKind.NetworkFirewall)),
      plan(0, List(FleetActionKind.ServerProfileApply, FleetActionKind.NetworkFirewall)).copy(position = 1))
    val r = rollout(snapshot(plans), FleetRolloutPhase.ApplyCanary)
    val repo = new MemoryRepository(r, plans.map(p => member(r, p, FleetRolloutMemberState.Pending)))
    val children = new FakeChildren
    children.afterApply = () => { repo.current = repo.current.copy(pauseRequestedAt = Some(at)) }
    worker(repo, Nil, children).tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.Paused)
    assertEquals(repo.current.phase, FleetRolloutPhase.ApplyCanary)
    assertEquals(repo.memberList.count(_.state == FleetRolloutMemberState.Running), 1)
    assertEquals(repo.memberList.count(_.state == FleetRolloutMemberState.Pending), 1)
    assertEquals(children.startCalls, 1)
    assertEquals(children.firewallCalls, 0)
    assertEquals(repo.journal.map(_.kind), List(FleetActionKind.ServerProfileApply))
    assertEquals(repo.journal.head.state, FleetActionState.Succeeded)
  }

  private def freshEvidence(p: FleetRolloutMemberPlan) = {
    val t = Instant.now().minusSeconds(30)
    val row = evidence(p, FleetCompliance.Drifted, FleetHealth.Healthy, List(FleetDriftReason.ServerProfileContentDrift))
    row.copy(assessment = row.assessment.map(_.copy(computedAt = t,
      inventoryObservedAt = Some(t), serverObservedAt = Some(t), localObservedAt = Some(t))))
  }

  List("computedAt", "oldestEvidenceAt", "UNKNOWN", "BLOCKED", "health", "blocker").foreach { problem =>
    List(false, true).foreach { resume =>
      test(s"${if (resume) "resume" else "start"} rejects current $problem before any state transition or mutation") {
        val p = plan(0, List(FleetActionKind.ServerProfileApply))
        val r = rollout(snapshot(List(p)), if (resume) FleetRolloutPhase.ApplyWaves else FleetRolloutPhase.Validate)
          .copy(state = if (resume) FleetRolloutState.Paused else FleetRolloutState.Planned,
            expiresAt = Instant.now().plusSeconds(3600))
        val base = freshEvidence(p)
        val row = base.copy(assessment = base.assessment.map(a => problem match {
          case "computedAt" => a.copy(computedAt = Instant.now().minusSeconds(3600))
          case "oldestEvidenceAt" => a.copy(serverObservedAt = Some(Instant.now().minusSeconds(3600)))
          case "UNKNOWN" => a.copy(compliance = FleetCompliance.Unknown)
          case "BLOCKED" => a.copy(compliance = FleetCompliance.Blocked)
          case "health" => a.copy(health = FleetHealth.Degraded)
          case _ => a.copy(rolloutBlockers = List(FleetRolloutBlocker.NoTrustedSsh))
        }))
        val repo = new MemoryRepository(r, List(member(r, p, FleetRolloutMemberState.Pending)))
        val children = new FakeChildren
        val svc = service(repo, new MemoryQuery(List(row)), children)
        val result = (if (resume) svc.resume(ActorContext(actorId, org), integrationId, fleetId, r.id)
          else svc.start(ActorContext(actorId, org), integrationId, fleetId, r.id, uid)).attempt.unsafeRunSync()
        val expected = if (problem == "computedAt" || problem == "oldestEvidenceAt") FleetRolloutPreconditions.RefreshRequired
          else FleetRolloutPreconditions.PlanChanged
        assertEquals(result.left.toOption.collect { case e: IntegrationError => e.code }, Some(expected))
        assertEquals(repo.startWrites + repo.resumeWrites, 0)
        assertEquals(repo.current.state, r.state)
        assertEquals(children.startCalls + children.configStarts + children.firewallCalls + children.desiredCalls, 0)
      }
    }
  }

  test("worker VALIDATE rejects evidence that expired after queue admission, with zero mutations") {
    val p = plan(0, List(FleetActionKind.ServerProfileApply))
    val r = rollout(snapshot(List(p)), FleetRolloutPhase.Validate)
    val repo = new MemoryRepository(r, List(member(r, p, FleetRolloutMemberState.Pending)))
    val children = new FakeChildren
    worker(repo, List(evidence(p, FleetCompliance.Drifted, FleetHealth.Healthy)), children).tick.unsafeRunSync()
    assertEquals(repo.current.state, FleetRolloutState.Failed)
    assertEquals(repo.current.failureCode, Some(FleetRolloutPreconditions.RefreshRequired))
    assertEquals(repo.journal, Nil)
    assertEquals(children.startCalls + children.configStarts + children.firewallCalls, 0)
  }

  List("disconnected", "stale", "identities", "baseline", "impact").foreach { problem =>
    test(s"first shared Config child is not started when current $problem differs from approval") {
      val p = plan(0, List(FleetActionKind.ServerProfileApply))
      val consumer = FleetRolloutConfigConsumer(uid, "external-01", "external", false, true, Instant.now().minusSeconds(30))
      val s = snapshot(List(p), shared = true)
      val r = rollout(s.copy(shared = s.shared.copy(consumers = List(consumer), externalNodes = 1)),
        FleetRolloutPhase.ApplySharedConfig)
      val current = problem match {
        case "disconnected" => consumer.copy(connected = false)
        case "stale" => consumer.copy(observedAt = Instant.now().minusSeconds(3600))
        case "identities" => consumer.copy(externalNodeId = "replacement")
        case _ => consumer
      }
      val repo = new MemoryRepository(r, List(member(r, p, FleetRolloutMemberState.Pending)))
      val children = new FakeChildren
      if (problem == "impact") children.impact = children.impact.map(_.copy(preexistingUnhealthyNodes = 1, healthyNodes = 0))
      worker(repo, List(freshEvidence(p)), children, List(current),
        if (problem == "baseline") "c" * 64 else "a" * 64).tick.unsafeRunSync()
      assertEquals(children.configStarts, 0)
      assertEquals(children.configReads, 0)
      assertEquals(repo.current.state, FleetRolloutState.Failed)
      assertEquals(repo.current.failureCode, Some(if (problem == "stale") FleetRolloutPreconditions.RefreshRequired
        else FleetRolloutPreconditions.PlanChanged))
    }
  }

  test("a first shared child starts with fresh matching approval; recovered child skips preflight and is only observed") {
    val p = plan(0, List(FleetActionKind.ServerProfileApply))
    val consumer = FleetRolloutConfigConsumer(uid, "external-01", "external", false, true, Instant.now().minusSeconds(30))
    val s = snapshot(List(p), shared = true)
    val r = rollout(s.copy(shared = s.shared.copy(consumers = List(consumer), externalNodes = 1)), FleetRolloutPhase.ApplySharedConfig)
    val repo = new MemoryRepository(r, List(member(r, p, FleetRolloutMemberState.Pending)))
    val children = new FakeChildren
    worker(repo, List(freshEvidence(p)), children, List(consumer)).tick.unsafeRunSync()
    assertEquals(children.configStarts, 1)
    assertEquals(children.configReads, 1)
    assertEquals(repo.current.phase, FleetRolloutPhase.VerifySharedConfig)

    val recoveredRepo = new MemoryRepository(r, repo.memberList)
    val recovered = new FakeChildren
    recovered.recoveredConfig = Some(uid)
    worker(recoveredRepo, Nil, recovered).tick.unsafeRunSync()
    assertEquals(recovered.configStarts, 0)
    assertEquals(recovered.impactReads, 0)
    assertEquals(recovered.configReads, 1)
    assertEquals(recoveredRepo.current.phase, FleetRolloutPhase.VerifySharedConfig)
  }
}

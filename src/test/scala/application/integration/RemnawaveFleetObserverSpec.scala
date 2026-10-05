package ru.bitec.app.ops
package application.integration

import application.port._
import cats.syntax.all._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.integration._
import domain.provisioning._
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import org.typelevel.log4cats.noop.NoOpLogger
import scala.concurrent.duration._
import support.ServerProfileFixtures

/** The observer reads and judges. These cases pin down that it writes nothing remote, that a result
  * computed against a source that has since changed is discarded, and that one failed read is
  * uncertainty rather than a verdict.
  */
final class RemnawaveFleetObserverSpec extends FunSuite {
  private val now = Instant.parse("2026-10-04T12:00:00Z")
  private def uid = UUID.randomUUID()
  private val org = uid
  private val integrationId = uid
  private val resource = uid
  private val nodeId = uid
  private val inbound = uid
  private val externalNode = uid
  private val configProfile = uid
  private val connection = Connection(uid, org, ConnectionScope.Organization, "SSH", "edge", "Edge",
    ConnectionConfig(Map.empty), None, true, now, now)
  private val content = ServerProfileFixtures.content

  private def desiredContent = FleetDesiredContent(uid, uid, 5, content.hash, uid, configProfile, uid, uid, 9,
    "b" * 64, List(inbound), 2222, List("198.51.100.0/24"), IntegrationDesiredNodeState.Enabled)

  private final class Harness(localVerified: Boolean = true, localFails: Boolean = false,
    revisionChangesDuringRead: Boolean = false, membershipRemovedDuringRead: Boolean = false,
    bindingChangesDuringRead: Boolean = false, connectionChangesDuringRead: Boolean = false,
    connectionReplacedDuringRead: Boolean = false, connectionLostDuringRead: Boolean = false,
    leaseExpiresDuringRead: Boolean = false) {
    val fleetId = uid
    val revisionId = uid
    val membershipId = uid
    val content = desiredContent
    var revisionRecord = RemnawaveFleetRevision(revisionId, org, fleetId, 1, 1, content.hash, content, uid, now)
    var fleetRecord = RemnawaveFleet(fleetId, org, integrationId, "production", "Production", None,
      Some(revisionId), 1L, archived = false, uid, now, now)
    var membershipRecord = RemnawaveFleetMembership(membershipId, org, fleetId, integrationId, nodeId, resource,
      1L, uid, now)
    var bindingResource: Option[UUID] = Some(resource)
    /** The SSH source as the target query currently reports it. */
    var sourceConnection: Option[(UUID, Instant)] = Some(connection.id -> connection.updatedAt)
    var leaseValid = true
    var saved = List.empty[RemnawaveFleetNodeAssessment]
    var rescheduled = 0
    var claimToken: Option[UUID] = None
    val localObservations = new AtomicInteger(0)
    /** Every mutating transport call would be counted here; Stage25D must never make one. */
    val mutations = new AtomicInteger(0)

    private val summary = RemnawaveNodeSummary("192.0.2.10", Some(2222), true, false, false, None, None, None,
      0L, false, None, None, 0L, "NL", None, None, None, Some(configProfile.toString), Nil, None, None,
      Some(List(inbound.toString)))

    val repo = new RemnawaveFleetRepository[IO] {
      override def insertFleet(value: RemnawaveFleet) = IO.pure(true)
      override def fleet(o: UUID, i: UUID, id: UUID) = IO.pure(Option.when(id == fleetId)(fleetRecord))
      override def fleetForUpdate(o: UUID, i: UUID, id: UUID) = fleet(o, i, id)
      override def fleets(o: UUID, i: UUID, archived: Boolean, limit: Int) = IO.pure(List(fleetRecord))
      override def updateFleet(value: RemnawaveFleet, expected: Long, at: Instant) = IO.pure(true)
      override def insertRevision(value: RemnawaveFleetRevision) = IO.pure(true)
      override def revision(o: UUID, f: UUID, id: UUID) = IO.pure(Option.when(id == revisionRecord.id)(revisionRecord))
      override def revisions(o: UUID, f: UUID, limit: Int) = IO.pure(List(revisionRecord))
      override def nextRevisionNumber(o: UUID, f: UUID) = IO.pure(2)
      override def promote(o: UUID, f: UUID, r: UUID, expected: Long, at: Instant) = IO.pure(true)
      override def insertMembership(value: RemnawaveFleetMembership, next: Instant) = IO.pure(true)
      override def membership(o: UUID, f: UUID, id: UUID) = IO.pure(Option.when(id == membershipId)(membershipRecord))
      override def members(o: UUID, f: UUID) = IO.pure(List(membershipRecord))
      override def activeMembershipOf(o: UUID, i: UUID, node: UUID) = IO.pure(None)
      override def removeMembership(o: UUID, f: UUID, id: UUID, at: Instant) = IO.pure(true)
      override def markDue(o: UUID, f: Option[UUID], i: UUID, at: Instant) = IO.pure(1)
      override def claimDue(owner: UUID, token: UUID, at: Instant, until: Instant, limit: Int) = IO {
        claimToken = Some(token); List(membershipRecord)
      }
      override def renewClaim(value: RemnawaveFleetMembership, token: UUID, at: Instant, until: Instant) =
        IO.pure(true)
      /** Mirrors the real fencing: the verdict lands only while its sources still hold. */
      override def saveAssessment(value: RemnawaveFleetNodeAssessment, token: UUID, at: Instant,
        next: Instant) = IO {
        val current = claimToken.contains(token) && leaseValid && membershipRecord.active &&
          membershipRecord.version == value.membershipVersion &&
          fleetRecord.desiredRevisionId.contains(value.fleetRevisionId) &&
          bindingResource.contains(membershipRecord.resourceId)
        if (current) { saved :+= value; true } else false
      }
      override def releaseMemberships(o: UUID, f: UUID, at: Instant) = IO {
        membershipRecord = membershipRecord.copy(removedAt = Some(at), version = membershipRecord.version + 1)
        saved = Nil
        1
      }
      override def reschedule(value: RemnawaveFleetMembership, token: UUID, at: Instant, next: Instant) =
        IO { if (claimToken.contains(token) && leaseValid) { rescheduled += 1; true } else false }
      override def assessments(o: UUID, f: UUID) = IO.pure(saved)
      override def lockFleet(o: UUID, f: UUID) = IO.unit
      override def rolloutActive(o: UUID, f: UUID) = IO.pure(false)
    }

    val query = new RemnawaveFleetQuery[IO] {
      override def configConsumers(o: UUID, i: UUID, p: String) = IO.pure(Nil)
      override def summaries(o: UUID, i: UUID, ids: List[UUID]) = IO.pure(Map.empty)
      override def memberRowsBatch(o: UUID, f: UUID, ids: List[UUID]): IO[List[FleetMemberRow]] = IO.pure(Nil)
      override def memberRows(o: UUID, f: UUID) = IO.pure(Nil)
      override def candidates(o: UUID, i: UUID, limit: Int) = IO.pure(Nil)
      override def storedEvidenceBatch(o: UUID, ids: List[UUID], value: RemnawaveFleetRevision) = ids.traverse(id => storedEvidence(o, id, value).map(_.map(id -> _))).map(_.flatten.toMap)
      override def storedEvidence(o: UUID, id: UUID, value: RemnawaveFleetRevision) = IO.pure(Some(
        FleetStoredEvidence(membershipRecord, bindingResource.nonEmpty, bindingResource, resourceActive = true,
          Some(summary), inventoryActive = true, Some(now), Some("b" * 64), configProfileAvailable = true,
          serverProfileAvailable = true, Some(ServerProfileFixtures.content),
          Some(ServerProfileAssignment(uid, org, resource,
            value.content.serverProfileId, value.content.serverProfileRevisionId, 5, 1L, uid, now)),
          Some(ServerProfileObservation(uid, org, resource, uid, now, None, None, None,
            ServerProfileFixtures.observed(), "c" * 64, now)),
          Some(IntegrationDesiredState(uid, org, integrationId, nodeId, IntegrationDesiredNodeState.Enabled,
            1L, uid, now, now, None, None)),
          Some(FleetLocalProvenance(uid, externalNode, "remnawave/node:2.8.0", provisioningReady = true, now)),
          busy = false)))
      override def provenance(o: UUID, i: UUID, r: UUID, node: UUID) = IO.pure(None)
      override def serverProfileName(o: UUID, id: UUID) = IO.pure(Some("VPN Production"))
      override def configurationProfileName(o: UUID, id: UUID) = IO.pure(Some("Reality"))
      override def lastSuccessfulSyncAt(o: UUID, i: UUID) = IO.pure(Some(now))
    }

    val targets = new ProvisioningTargetQuery[IO] {
      def eligibleBatch(o: UUID, ids: List[UUID]) = ids.traverse(id => eligible(o, id).map(id -> _)).map(_.toMap)
      override def eligible(o: UUID, r: UUID) = IO.pure(sourceConnection match {
        case Some((id, at)) => Right(ProvisioningTarget("NODE", "VPS", id, at, connection.copy(id = id,
          updatedAt = at)))
        case None => Left("PROVISIONING_TARGET_NOT_FOUND")
      })
      override def unchanged(snapshot: ProvisioningInputSnapshot) = IO.pure(true)
    }

    /** Only `observe` may ever be called. The rest exist to prove they are not. */
    val remote = new RemnawaveNodeRemote[IO] {
      override def recoveryPreflight(c: Connection,s: RemnawaveNodeRemoteSpec) = preflight(c,s.resourceId,s.nodePort)
      override def repair(c: Connection,s: RemnawaveNodeRemoteSpec,d: NodeInstallationData) = install(c,s,d)
      override def retireInstallation(c: Connection,s: RemnawaveNodeRemoteSpec) = IO { mutations.incrementAndGet(); ProvisioningStepResult(Map.empty,None,Some(true)) }
      override def preflight(c: Connection, r: UUID, p: Int) = IO { mutations.incrementAndGet()
        ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def installationPrerequisites(c: Connection) = IO { mutations.incrementAndGet()
        ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def configureFirewall(c: Connection, s: RemnawaveNodeRemoteSpec) = IO {
        mutations.incrementAndGet(); ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def configureOnboardingFirewall(c: Connection, s: RemnawaveNodeRemoteSpec) = configureFirewall(c,s)
      override def install(c: Connection, s: RemnawaveNodeRemoteSpec, d: NodeInstallationData) = IO {
        mutations.incrementAndGet(); ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def start(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { mutations.incrementAndGet()
        ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def observe(c: Connection, s: RemnawaveNodeRemoteSpec) = IO {
        localObservations.incrementAndGet()
        assertEquals(s.nodePort, 2222)
        assertEquals(s.panelCidrs, List("198.51.100.0/24"))
        // A change that lands while the read is in flight must invalidate the result.
        if (revisionChangesDuringRead) {
          val next = uid
          revisionRecord = revisionRecord.copy(id = next, number = 2)
          fleetRecord = fleetRecord.copy(desiredRevisionId = Some(next), version = 2L)
        }
        if (membershipRemovedDuringRead) membershipRecord =
          membershipRecord.copy(removedAt = Some(now), version = 2L)
        if (bindingChangesDuringRead) bindingResource = Some(uid)
        // The SSH source is edited, replaced or withdrawn while this read is in flight.
        if (connectionChangesDuringRead) sourceConnection = Some(connection.id -> now.plusSeconds(60))
        if (connectionReplacedDuringRead) sourceConnection = Some(uid -> connection.updatedAt)
        if (connectionLostDuringRead) sourceConnection = None
        if (leaseExpiresDuringRead) leaseValid = false
        if (localFails) throw new java.io.IOException("ssh unavailable")
        RemnawaveNodeLocalEvidence(managedFiles = localVerified, imageMatches = localVerified,
          containerRunning = localVerified, portListening = localVerified, stable = localVerified,
          firewallMatches = localVerified)
      }
      override def installationPresent(c: Connection, s: RemnawaveNodeRemoteSpec) = IO.pure(true)
      override def firewallPresent(c: Connection, s: RemnawaveNodeRemoteSpec) = IO.pure(true)
      override def managedPanelCidrs(c: Connection, s: RemnawaveNodeRemoteSpec) = IO.pure(s.panelCidrs)
    }

    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]) = program }
    val observer = new RemnawaveFleetObserver[IO](repo, query, targets, remote, runner, NoOpLogger[IO],
      RemnawaveFleetObserverSettings(enabled = true, pollInterval = 1.second, batchSize = 5,
        maxConcurrency = 2, claimLease = 120.seconds, observationTimeout = 45.seconds,
        recheckInterval = 300.seconds, staleAfter = 900.seconds), uid, IO.pure(now))
  }

  test("a fully matching member is assessed compliant and no transport mutation is attempted") {
    val h = new Harness()
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved.map(_.compliance), List(FleetCompliance.Compliant))
    assertEquals(h.saved.map(_.health), List(FleetHealth.Healthy))
    assertEquals(h.saved.head.fleetRevisionId, h.revisionId)
    assertEquals(h.localObservations.get(), 1)
    // The single most important Stage25D guarantee.
    assertEquals(h.mutations.get(), 0)
  }

  test("drift on every observable dimension still performs no mutation") {
    val h = new Harness(localVerified = false)
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved.map(_.compliance), List(FleetCompliance.Drifted))
    assert(h.saved.head.driftReasons.contains(FleetDriftReason.NodePortDrift))
    assert(h.saved.head.driftReasons.contains(FleetDriftReason.PanelCidrDrift))
    assert(h.saved.head.driftReasons.contains(FleetDriftReason.LocalInstallationDrift))
    assertEquals(h.mutations.get(), 0)
  }

  test("a desired revision promoted during the read discards the result instead of mislabelling it") {
    val h = new Harness(revisionChangesDuringRead = true)
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved, Nil)
    assertEquals(h.mutations.get(), 0)
  }

  test("a membership removed during the read does not come back as a current assessment") {
    val h = new Harness(membershipRemovedDuringRead = true)
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved, Nil)
  }

  test("a node rebound during the read has its result rejected rather than written stale") {
    val h = new Harness(bindingChangesDuringRead = true)
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved, Nil)
  }

  test("a failed SSH read leaves the member unknown and never fails the fleet") {
    val h = new Harness(localFails = true)
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved.map(_.compliance), List(FleetCompliance.Unknown))
    assert(h.saved.head.driftReasons.contains(FleetDriftReason.LocalObservationUnavailable))
    assertEquals(h.saved.head.health, FleetHealth.Unknown)
    assertEquals(h.saved.head.localObservedAt, None)
    assertEquals(h.mutations.get(), 0)
  }

  test("a fleet with no desired revision is rescheduled without an observation or a verdict") {
    val h = new Harness()
    h.fleetRecord = h.fleetRecord.copy(desiredRevisionId = None)
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved, Nil)
    assertEquals(h.rescheduled, 1)
    assertEquals(h.localObservations.get(), 0)
  }

  test("an SSH source edited, replaced or withdrawn during the read discards the result") {
    List[Harness](
      new Harness(connectionChangesDuringRead = true),
      new Harness(connectionReplacedDuringRead = true),
      new Harness(connectionLostDuringRead = true),
    ).foreach { h =>
      h.observer.tick.unsafeRunSync()
      // The observation happened against a source that is no longer the current one, so it is
      // never written as this member's state - not even as an UNKNOWN of the old connection.
      assertEquals(h.saved, Nil)
      assertEquals(h.localObservations.get(), 1)
      assertEquals(h.mutations.get(), 0)
      // The lease is still ours, so the member is simply put back in the queue.
      assertEquals(h.rescheduled, 1)
    }
  }

  test("an unchanged SSH source keeps writing the verdict as before") {
    val h = new Harness()
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved.map(_.compliance), List(FleetCompliance.Compliant))
    assertEquals(h.saved.head.localObservedAt.nonEmpty, true)
    assertEquals(h.rescheduled, 0)
  }

  test("a lease that expired during the read writes nothing and reschedules nothing") {
    val h = new Harness(leaseExpiresDuringRead = true)
    h.observer.tick.unsafeRunSync()
    assertEquals(h.saved, Nil)
    // A worker that lost its lease must not push the next check out from under the new owner.
    assertEquals(h.rescheduled, 0)
    assertEquals(h.mutations.get(), 0)
  }

  test("observer lease covers both sequential reads and the fenced-save margin") {
    intercept[IllegalArgumentException] {
      RemnawaveFleetObserverSettings(claimLease = 60.seconds, observationTimeout = 45.seconds)
    }
    intercept[IllegalArgumentException] {
      RemnawaveFleetObserverSettings(claimLease = 95.seconds, observationTimeout = 45.seconds)
    }
    assertEquals(RemnawaveFleetObserverSettings().claimLease, 120.seconds)
    assertEquals(RemnawaveFleetObserverSettings(claimLease = 96.seconds).claimLease, 96.seconds)
  }

  test("a disabled observer does nothing at all") {
    val h = new Harness()
    val idle = new RemnawaveFleetObserver[IO](h.repo, h.query, h.targets, h.remote, h.runner, NoOpLogger[IO],
      RemnawaveFleetObserverSettings(enabled = false), uid, IO.pure(now))
    idle.tick.unsafeRunSync()
    assertEquals(h.saved, Nil)
    assertEquals(h.localObservations.get(), 0)
    assertEquals(h.mutations.get(), 0)
  }
}

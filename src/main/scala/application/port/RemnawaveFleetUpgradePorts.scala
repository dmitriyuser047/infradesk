package ru.bitec.app.ops
package application.port

import cats.effect.IO
import domain.connection.Connection
import domain.integration._
import java.time.Instant
import java.util.UUID

/** Safe, content-free failure. Uncertain means observe before any further mutation. */
final case class NodeImageRemoteFailure(code: String, uncertain: Boolean = false) extends RuntimeException(code)
/** Authorization callbacks must retain their classification through the SSH adapter. */
abstract class NodeImageAuthorizationFailure(code: String) extends RuntimeException(code)

/** Software lifecycle stays separate from Stage25D's configuration desired revision. */
trait RemnawaveFleetUpgradeRepository[F[_]] {
  def releaseTarget(org: UUID, fleetId: UUID): F[Option[FleetNodeReleaseRevision]]
  def releaseRevision(org: UUID, fleetId: UUID, id: UUID): F[Option[FleetNodeReleaseRevision]]
  def promote(revision: FleetNodeReleaseRevision): F[Unit]
  def nextRevisionNumber(org: UUID, fleetId: UUID): F[Int]
  def observationsBatch(org: UUID, fleetId: UUID, ids: List[UUID]): F[List[FleetNodeImageObservation]]
  def observations(org: UUID, fleetId: UUID): F[List[FleetNodeImageObservation]]
  def saveObservation(value: FleetNodeImageObservation): F[Unit]
  def insertPlan(run: RemnawaveFleetUpgradeRun, members: List[RemnawaveFleetUpgradeMember]): F[Unit]
  def run(org: UUID, fleetId: UUID, id: UUID, lock: Boolean = false): F[Option[RemnawaveFleetUpgradeRun]]
  def byId(id: UUID): F[Option[RemnawaveFleetUpgradeRun]]
  def byRequest(org: UUID, requestId: UUID): F[Option[RemnawaveFleetUpgradeRun]]
  def active(org: UUID, fleetId: UUID): F[Option[RemnawaveFleetUpgradeRun]]
  def history(org: UUID, fleetId: UUID): F[List[RemnawaveFleetUpgradeRun]]
  def members(id: UUID): F[List[RemnawaveFleetUpgradeMember]]
  def actions(id: UUID): F[List[RemnawaveFleetUpgradeAction]]
  def start(org: UUID, id: UUID, request: UUID, now: Instant): F[Boolean]
  def requestPause(org: UUID, id: UUID, now: Instant): F[Boolean]
  def resume(org: UUID, id: UUID, now: Instant): F[Boolean]
  def requestRollback(org: UUID, id: UUID, scope: FleetRollbackScope, now: Instant): F[Boolean]
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): F[List[RemnawaveFleetUpgradeRun]]
  def renew(id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]
  def owns(id: UUID, token: UUID, now: Instant): F[Boolean]
  def save(run: RemnawaveFleetUpgradeRun, token: UUID, now: Instant, release: Boolean): F[Boolean]
  def saveMember(member: RemnawaveFleetUpgradeMember, token: UUID, now: Instant): F[Boolean]
  def saveAction(action: RemnawaveFleetUpgradeAction, token: UUID, now: Instant): F[Boolean]
}

/** Backend-owned operations only, on the Stage25C installation. No image or shell from API input. */
trait RemnawaveNodeImageRemote[F[_]] {
  def observeImage(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[NodeImageObservation]
  def prefetchImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, release: NodeRelease,
    platform: NodeReleasePlatform, baseline: NodeImageObservation, authorize: F[Unit]): F[Unit]
  def switchImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, reference: String,
    expectedImageId: String, expectedComposeHash: String, expectedMarkerHash: String,
    authorize: F[Unit]): F[Unit]
  /** Recovery after the owned target Compose was committed but the baseline container remains.
    * Implementations re-prove both immutable artifact and ownership before this one typed up. */
  def activateImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, reference: String,
    expectedImageId: String, expectedMarkerHash: String, authorize: F[Unit]): F[Unit]
}

/** Validates the reviewed immutable OCI chain and published unsigned BuildKit provenance.
  * Unsigned provenance is an integrity/source check, never presented as a signature. */
trait RemnawaveNodeReleaseVerifier {
  def verify(release: NodeRelease, platform: NodeReleasePlatform): IO[Unit]
}

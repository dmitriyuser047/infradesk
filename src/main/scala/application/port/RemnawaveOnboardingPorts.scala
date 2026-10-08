package ru.bitec.app.ops
package application.port

import application.integration.IntegrationRuntimeContext
import domain.integration._
import domain.provisioning.ProvisioningRun
import java.time.Instant
import java.util.UUID

trait RemnawaveOnboardingRepository[F[_]] {
  def insertCertificate(certificate: NodeTlsCertificate, secret: IntegrationSecret): F[Unit] =
    throw new UnsupportedOperationException("Certificate storage is unavailable")
  def certificate(org: UUID, resourceId: UUID, id: UUID): F[Option[(NodeTlsCertificate,IntegrationSecret)]] =
    throw new UnsupportedOperationException("Certificate storage is unavailable")
  def certificateMetadata(org: UUID, resourceId: UUID, id: UUID): F[Option[NodeTlsCertificate]] =
    throw new UnsupportedOperationException("Certificate metadata is unavailable")
  def saveIssuedCertificate(run: RemnawaveNodeOnboardingRun, token: UUID, certificate: NodeTlsCertificate,
    secret: IntegrationSecret, now: Instant): F[Unit] =
    throw new UnsupportedOperationException("Certificate storage is unavailable")
  def insertPlan(run: RemnawaveNodeOnboardingRun): F[Unit]
  def lockResource(org: UUID, resourceId: UUID): F[Unit]
  def active(org: UUID, resourceId: UUID): F[Boolean]
  def start(org: UUID, integrationId: UUID, planId: UUID, requestId: UUID, actor: UUID, now: Instant, confirmRecreate: Boolean = false): F[RemnawaveNodeOnboardingRun]
  def startResult(org: UUID, integrationId: UUID, planId: UUID, requestId: UUID, actor: UUID, now: Instant,
    confirmRecreate: Boolean = false): F[OnboardingStartResult]
  def find(org: UUID, integrationId: UUID, id: UUID): F[Option[(RemnawaveNodeOnboardingRun,List[OnboardingPhaseRecord])]]
  def history(org: UUID, integrationId: UUID, limit: Int): F[List[RemnawaveNodeOnboardingRun]]
  /** Latest run for each previously created node on this server; at most two prove ambiguity. */
  def createdNodes(org: UUID, resourceId: UUID): F[List[RemnawaveNodeOnboardingRun]]
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): F[List[RemnawaveNodeOnboardingRun]]
  def renew(run: RemnawaveNodeOnboardingRun, token: UUID, now: Instant, until: Instant): F[Boolean]
  /** true means a fresh boundary; false means it was already in flight and needs reconciliation. */
  def beginPhase(run: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): F[Boolean]
  def persist(run: RemnawaveNodeOnboardingRun, token: UUID, next: RemnawaveNodeOnboardingRun, now: Instant,
    completePhase: Boolean): F[Boolean]
  def attachBaseline(run: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): F[UUID]
  /** Records which synchronization session this phase depends on, in the transaction that claimed it.
    * Unlike `persist` this replaces an earlier ID, because a session that did not observe the new
    * node is replaced by a fresh one.
    */
  def attachSync(run: RemnawaveNodeOnboardingRun, token: UUID, sessionId: UUID, now: Instant): F[Unit]
  def saveSecret(run: RemnawaveNodeOnboardingRun, token: UUID, secret: IntegrationSecret, now: Instant): F[Unit]
  def secret(run: RemnawaveNodeOnboardingRun): F[Option[IntegrationSecret]]
  def deleteSecret(run: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): F[Unit]
  def replaceFleetMembership(run: RemnawaveNodeOnboardingRun, token: UUID, inventoryNodeId: UUID, now: Instant): F[Unit]
  def cleanupPlans(before: Instant, limit: Int): F[Int]
}

final case class OnboardingStartResult(run: RemnawaveNodeOnboardingRun, newlyStarted: Boolean)

final case class OnboardingServerCandidate(id: UUID, name: String, address: String, environmentName: String)
final case class OnboardingServerStatus(profileName: Option[String], revisionNumber: Option[Int],
  profileAssigned: Boolean, profileState: String, busy: Boolean, bindingConflict: Boolean, previousBindingReview: Boolean = false)
trait RemnawaveOnboardingQuery[F[_]] {
  /** A HOST binding alone is not a Panel host: its managed hostname must match this API endpoint. */
  def managedPanelAddresses(org: UUID,integration: UUID,endpointHost: String)(implicit F: cats.Applicative[F]): F[List[String]] = F.pure(Nil)
  /** Fixed-query projection; targets are the already batched, trusted SSH source identities. */
  def serverStatuses(org: UUID, targets: Map[UUID, Either[String, ProvisioningTarget]], integration: Option[UUID] = None): F[Map[UUID, OnboardingServerStatus]]
  def candidates(org: UUID, limit: Int): F[List[OnboardingServerCandidate]]
  def bindingConflict(org: UUID, resource: UUID, expectedExternalId: Option[UUID]): F[Boolean]
  /** Only an exact inactive same-resource binding may be reviewed for replacement. */
  def replacementBinding(org: UUID, integration: UUID, resource: UUID, previousExternalId: UUID)
    (implicit F: cats.Applicative[F]): F[Either[String,Option[UUID]]] = F.pure(Left("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
  def externalNode(org: UUID, integration: UUID, externalId: UUID): F[Option[IntegrationInventoryObject]]
  /** The onboarding that owns an approved baseline plan; recovery proves the child before starting it. */
  def baselinePlanParent(org: UUID, planId: UUID): F[Option[UUID]]
}

/** Purpose-specific authenticated envelope; implementation reuses InfraDesk AES-GCM infrastructure. */
trait NodeInstallationCryptography {
  def encryptTls(id: UUID, org: UUID, material: NodeTlsMaterial): IntegrationSecret =
    throw new UnsupportedOperationException("Certificate encryption is unavailable")
  def decryptTls(secret: IntegrationSecret): NodeTlsMaterial =
    throw new UnsupportedOperationException("Certificate encryption is unavailable")
  def encrypt(runId: UUID, org: UUID, data: NodeInstallationData): IntegrationSecret
  def decrypt(secret: IntegrationSecret): NodeInstallationData
}

/** Which synchronization session the SYNC_INVENTORY phase now depends on. Either way the ID is
  * already durable on the onboarding when this is returned, so a crash recovers by that ID.
  */
sealed trait OnboardingSyncClaim[F[_]] { def session: IntegrationSyncSession }
object OnboardingSyncClaim {
  /** This onboarding holds the slot; `observe` reads the provider outside any transaction. */
  final case class Owned[F[_]](session: IntegrationSyncSession, observe: F[IntegrationSyncSession])
    extends OnboardingSyncClaim[F]
  /** Another synchronization of the same integration holds the slot; its outcome is read, not retried. */
  final case class Foreign[F[_]](session: IntegrationSyncSession) extends OnboardingSyncClaim[F]
}

/** Bridges existing application services, never a parallel importer/apply/action implementation. */
trait RemnawaveOnboardingOperations[F[_]] {
  def runtime(run: RemnawaveNodeOnboardingRun): F[(IntegrationRuntimeContext,domain.connection.Connection)]
  def validate(run: RemnawaveNodeOnboardingRun, requireCompliant: Boolean): F[Unit]
  /** Returns the approved baseline child, starting it only while it is still PLANNED. Recovery after
    * a crash between the attach and the start finds exactly that child, never a new one.
    */
  def ensureBaselineStarted(run: RemnawaveNodeOnboardingRun, token: UUID): F[ProvisioningRun]
  def baseline(run: RemnawaveNodeOnboardingRun, id: UUID): F[ProvisioningRun]
  /** Claims the synchronization slot and stores the session ID in the same transaction. */
  def claimSync(run: RemnawaveNodeOnboardingRun, token: UUID): F[OnboardingSyncClaim[F]]
  def syncSession(run: RemnawaveNodeOnboardingRun, id: UUID): F[Option[IntegrationSyncSession]]
  /** Whether the stored inventory now holds the active node this onboarding created. */
  def inventoryHasNode(run: RemnawaveNodeOnboardingRun): F[Boolean]
  def unbindPrevious(run: RemnawaveNodeOnboardingRun, token: UUID): F[Unit] =
    throw new UnsupportedOperationException("Previous binding retirement is unavailable")
  def bind(run: RemnawaveNodeOnboardingRun, token: UUID): F[Unit]
  def setDesiredState(run: RemnawaveNodeOnboardingRun, token: UUID): F[Unit]
  def verifyInventoryBindingDesired(run: RemnawaveNodeOnboardingRun): F[Boolean]
}

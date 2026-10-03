package ru.bitec.app.ops
package application.port

import application.integration.IntegrationRuntimeContext
import domain.integration._
import domain.provisioning.ProvisioningRun
import java.time.Instant
import java.util.UUID

trait RemnawaveOnboardingRepository[F[_]] {
  def insertPlan(run: RemnawaveNodeOnboardingRun): F[Unit]
  def lockResource(org: UUID, resourceId: UUID): F[Unit]
  def active(org: UUID, resourceId: UUID): F[Boolean]
  def start(org: UUID, integrationId: UUID, planId: UUID, requestId: UUID, actor: UUID, now: Instant): F[RemnawaveNodeOnboardingRun]
  def find(org: UUID, integrationId: UUID, id: UUID): F[Option[(RemnawaveNodeOnboardingRun,List[OnboardingPhaseRecord])]]
  def history(org: UUID, integrationId: UUID, limit: Int): F[List[RemnawaveNodeOnboardingRun]]
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): F[List[RemnawaveNodeOnboardingRun]]
  def renew(run: RemnawaveNodeOnboardingRun, token: UUID, now: Instant, until: Instant): F[Boolean]
  /** true means a fresh boundary; false means it was already in flight and needs reconciliation. */
  def beginPhase(run: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): F[Boolean]
  def persist(run: RemnawaveNodeOnboardingRun, token: UUID, next: RemnawaveNodeOnboardingRun, now: Instant,
    completePhase: Boolean): F[Boolean]
  def attachBaseline(run: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): F[UUID]
  def saveSecret(run: RemnawaveNodeOnboardingRun, token: UUID, secret: IntegrationSecret, now: Instant): F[Unit]
  def secret(run: RemnawaveNodeOnboardingRun): F[Option[IntegrationSecret]]
  def deleteSecret(run: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): F[Unit]
  def cleanupPlans(before: Instant, limit: Int): F[Int]
}

final case class OnboardingServerCandidate(id: UUID, name: String, address: String, environmentName: String)
trait RemnawaveOnboardingQuery[F[_]] {
  def candidates(org: UUID, limit: Int): F[List[OnboardingServerCandidate]]
  def bindingConflict(org: UUID, resource: UUID, expectedExternalId: Option[UUID]): F[Boolean]
  def externalNode(org: UUID, integration: UUID, externalId: UUID): F[Option[IntegrationInventoryObject]]
}

/** Purpose-specific authenticated envelope; implementation reuses InfraDesk AES-GCM infrastructure. */
trait NodeInstallationCryptography {
  def encrypt(runId: UUID, org: UUID, data: NodeInstallationData): IntegrationSecret
  def decrypt(secret: IntegrationSecret): NodeInstallationData
}

/** Bridges existing application services, never a parallel importer/apply/action implementation. */
trait RemnawaveOnboardingOperations[F[_]] {
  def runtime(run: RemnawaveNodeOnboardingRun): F[(IntegrationRuntimeContext,domain.connection.Connection)]
  def validate(run: RemnawaveNodeOnboardingRun, requireCompliant: Boolean): F[Unit]
  def startBaseline(run: RemnawaveNodeOnboardingRun, token: UUID): F[UUID]
  def baseline(run: RemnawaveNodeOnboardingRun, id: UUID): F[ProvisioningRun]
  def sync(run: RemnawaveNodeOnboardingRun): F[IntegrationSyncSession]
  def bind(run: RemnawaveNodeOnboardingRun, token: UUID): F[Unit]
  def setDesiredState(run: RemnawaveNodeOnboardingRun, token: UUID): F[Unit]
  def verifyInventoryBindingDesired(run: RemnawaveNodeOnboardingRun): F[Boolean]
}

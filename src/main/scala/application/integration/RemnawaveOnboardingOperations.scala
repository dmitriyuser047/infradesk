package ru.bitec.app.ops
package application.integration

import application.auth.ActorContext
import application.port._
import application.provisioning.ProvisioningRuns
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import domain.provisioning._
import java.util.UUID

final class ExistingRemnawaveOnboardingOperations[Tx[_]: MonadThrow](
  runs: RemnawaveOnboardingRepository[Tx], query: RemnawaveOnboardingQuery[Tx],
  integrations: IntegrationRepository[Tx], secrets: IntegrationSecretRepository[Tx], cipher: IntegrationCryptography,
  targets: ProvisioningTargetQuery[Tx], profiles: ServerProfileRepository[Tx], provisioning: ProvisioningRunRepository[Tx],
  profileRemote: ServerProfileRemote[IO], provisioningService: ProvisioningRuns[IO,Tx], syncService: IntegrationSync[Tx],
  inventory: IntegrationInventoryRepository[Tx], bindings: IntegrationBindingRepository[Tx], bindingService: IntegrationBindings[Tx],
  desired: IntegrationDesiredStateRepository[Tx], desiredService: IntegrationDesiredStates[Tx],
  runner: TransactionRunner[IO,Tx]) extends RemnawaveOnboardingOperations[IO] {
  private def error(code: String) = IntegrationError(code,"The reviewed onboarding state changed")
  private def checked(r: RemnawaveNodeOnboardingRun): Tx[(Integration,ProvisioningTarget)] = for {
    integration <- integrations.findById(r.organizationId,r.integrationId).flatMap(_.liftTo[Tx](error("INTEGRATION_NOT_FOUND")))
    target <- targets.eligible(r.organizationId,r.resourceId).flatMap(_.leftMap(error).liftTo[Tx])
    _ <- MonadThrow[Tx].raiseUnless(integration.providerType==IntegrationProviderType.Remnawave && integration.enabled &&
      integration.managementMode==IntegrationManagementMode.ManagedSelected && integration.secretId==r.snapshot.integrationSecretId &&
      integration.updatedAt==r.snapshot.integrationUpdatedAt && target.connectionId==r.snapshot.connectionId &&
      target.connectionUpdatedAt==r.snapshot.connectionUpdatedAt)(error("REMNAWAVE_ONBOARDING_SOURCE_CHANGED"))
    assignment <- profiles.assignment(r.organizationId,r.resourceId)
    revision <- profiles.revision(r.organizationId,r.snapshot.profileId,r.snapshot.revisionNumber)
    _ <- MonadThrow[Tx].raiseUnless(assignment.exists(a => a.id==r.snapshot.assignmentId && a.version==r.snapshot.assignmentVersion &&
      a.revisionId==r.snapshot.revisionId) && revision.exists(v => v.id==r.snapshot.revisionId && v.contentHash==r.snapshot.revisionHash))(
      error("REMNAWAVE_ONBOARDING_PROFILE_CHANGED"))
    conflict <- query.bindingConflict(r.organizationId,r.resourceId,r.externalNodeId)
    _ <- MonadThrow[Tx].raiseWhen(conflict)(error("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
  } yield integration -> target
  def runtime(r: RemnawaveNodeOnboardingRun): IO[(IntegrationRuntimeContext,domain.connection.Connection)] = runner.run(for {
    state <- checked(r)
    secret <- secrets.find(r.organizationId,state._1.secretId).flatMap(_.liftTo[Tx](error("INTEGRATION_CREDENTIAL_INVALID")))
  } yield (state._1,state._2,secret)).flatMap { case (i,t,s) => IO.delay(
    IntegrationRuntimeContext(i.id,i.organizationId,i.baseUrl,cipher.decrypt(s)) -> t.connection) }
  def validate(r: RemnawaveNodeOnboardingRun, requireCompliant: Boolean): IO[Unit] = runner.run(for {
    state <- checked(r)
    profile <- profiles.revision(r.organizationId,r.snapshot.profileId,r.snapshot.revisionNumber)
    _ <- MonadThrow[Tx].raiseUnless(profile.nonEmpty)(error("REMNAWAVE_ONBOARDING_PROFILE_CHANGED"))
  } yield state._2.connection -> profile.get.content).flatMap { case (connection,content) =>
    if (!requireCompliant) IO.unit else profileRemote.observe(connection,r.resourceId,Some(content)).flatMap(o =>
      IO.raiseUnless(o.failureCode.isEmpty && o.blockingProblems.isEmpty && ServerProfileDiff.assess(content,o.content).compliant)(
        error("REMNAWAVE_ONBOARDING_PROFILE_NOT_COMPLIANT")))
  }
  def startBaseline(r: RemnawaveNodeOnboardingRun, token: UUID): IO[UUID] = for {
    now <- IO.realTimeInstant
    id <- runner.run(runs.attachBaseline(r,token,now))
    child <- provisioningService.start(ActorContext(r.createdBy,r.organizationId),id,
      UUID.nameUUIDFromBytes((r.id.toString+":baseline").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
  } yield child.id
  def baseline(r: RemnawaveNodeOnboardingRun,id: UUID): IO[ProvisioningRun] = runner.run(provisioning.find(r.organizationId,id))
    .flatMap(_.map(_._1).liftTo[IO](error("REMNAWAVE_ONBOARDING_BASELINE_MISSING")))
  def sync(r: RemnawaveNodeOnboardingRun): IO[IntegrationSyncSession] =
    syncService.manual(ActorContext(r.createdBy,r.organizationId),r.integrationId)
  private def fenced[A](r: RemnawaveNodeOnboardingRun,token: UUID)(op: Tx[A]): IO[A] = IO.realTimeInstant.flatMap(now => runner.run(for {
    _ <- runs.lockResource(r.organizationId,r.resourceId)
    valid <- runs.renew(r,token,now,now.plusSeconds(900))
    _ <- MonadThrow[Tx].raiseUnless(valid)(error("REMNAWAVE_ONBOARDING_LEASE_LOST"))
    _ <- checked(r)
    result <- op
  } yield result))
  def bind(r: RemnawaveNodeOnboardingRun,token: UUID): IO[Unit] = fenced(r,token)(for {
    node <- query.externalNode(r.organizationId,r.integrationId,r.externalNodeId.get).flatMap(_.filter(_.isActive).liftTo[Tx](error("REMNAWAVE_ONBOARDING_INVENTORY_MISSING")))
    existing <- bindings.find(r.organizationId,node.id)
    _ <- MonadThrow[Tx].raiseUnless(existing.forall(_.resourceId==r.resourceId))(error("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
    _ <- bindingService.bindCreated(ActorContext(r.createdBy,r.organizationId),r.integrationId,node.id,r.resourceId)
  } yield ())
  def setDesiredState(r: RemnawaveNodeOnboardingRun,token: UUID): IO[Unit] = fenced(r,token)(for {
    node <- query.externalNode(r.organizationId,r.integrationId,r.externalNodeId.get).flatMap(_.filter(_.isActive).liftTo[Tx](error("REMNAWAVE_ONBOARDING_INVENTORY_MISSING")))
    _ <- desiredService.set(ActorContext(r.createdBy,r.organizationId),r.integrationId,node.id,IntegrationDesiredNodeState.Enabled)
  } yield ())
  def verifyInventoryBindingDesired(r: RemnawaveNodeOnboardingRun): IO[Boolean] = runner.run(for {
    _ <- checked(r)
    node <- query.externalNode(r.organizationId,r.integrationId,r.externalNodeId.get)
    b <- node.traverse(n => bindings.find(r.organizationId,n.id)).map(_.flatten)
    d <- node.traverse(n => desired.find(r.organizationId,r.integrationId,n.id)).map(_.flatten)
  } yield node.exists(n => n.isActive && n.summary.isInstanceOf[RemnawaveNodeSummary] && n.summary.asInstanceOf[RemnawaveNodeSummary].isConnected &&
    !n.summary.asInstanceOf[RemnawaveNodeSummary].isDisabled &&
    n.summary.asInstanceOf[RemnawaveNodeSummary].activeConfigProfileUuid.contains(r.snapshot.input.configProfileId.toString)) &&
    b.exists(_.resourceId==r.resourceId) && d.exists(_.state==IntegrationDesiredNodeState.Enabled))
}

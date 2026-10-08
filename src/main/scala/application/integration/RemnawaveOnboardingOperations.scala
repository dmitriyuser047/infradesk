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
import java.time.Instant
import java.util.UUID

final class ExistingRemnawaveOnboardingOperations[Tx[_]: MonadThrow](
  runs: RemnawaveOnboardingRepository[Tx], query: RemnawaveOnboardingQuery[Tx],
  integrations: IntegrationRepository[Tx], secrets: IntegrationSecretRepository[Tx], cipher: IntegrationCryptography,
  targets: ProvisioningTargetQuery[Tx], profiles: ServerProfileRepository[Tx], provisioning: ProvisioningRunRepository[Tx],
  profileRemote: ServerProfileRemote[IO], provisioningService: ProvisioningRuns[IO,Tx], syncService: IntegrationSync[Tx],
  syncTransactions: IntegrationSyncTransactions[Tx], syncSessions: IntegrationSyncSessionRepository[Tx],
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
    previousConflict <- r.snapshot.recovery.filter(!_.reusesNode).fold(true.pure[Tx])(proof =>
      query.bindingConflict(r.organizationId,r.resourceId,proof.previousExternalNodeId))
    _ <- if(!r.snapshot.replacement || r.externalNodeId.nonEmpty) MonadThrow[Tx].unit else for {
      proof <- r.snapshot.recovery.liftTo[Tx](error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
      previous <- proof.previousExternalNodeId.liftTo[Tx](error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
      current <- query.replacementBinding(r.organizationId,r.integrationId,r.resourceId,previous).flatMap(_.leftMap(error).liftTo[Tx])
      _ <- MonadThrow[Tx].raiseUnless(current.forall(id => proof.previousInstallation.flatMap(_.inventoryObjectId).contains(id)))(
        error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
    } yield ()
    _ <- MonadThrow[Tx].raiseWhen(conflict && previousConflict)(error("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
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
  /** Nothing but the exact approved child of this onboarding may be started on its behalf. */
  private def identical(r: RemnawaveNodeOnboardingRun, child: ProvisioningRun, parent: Option[UUID]): Boolean =
    child.id==r.snapshot.baselinePlanId && child.organizationId==r.organizationId && child.resourceId==r.resourceId &&
      child.input.runKind==ProvisioningRunKind.ServerProfileApply && parent.contains(r.id) &&
      child.input.profileApply.exists(p => p.assignmentId==r.snapshot.assignmentId && p.assignmentVersion==r.snapshot.assignmentVersion &&
        p.profileId==r.snapshot.profileId && p.revisionId==r.snapshot.revisionId && p.revisionNumber==r.snapshot.revisionNumber &&
        p.revisionHash==r.snapshot.revisionHash)
  def ensureBaselineStarted(r: RemnawaveNodeOnboardingRun, token: UUID): IO[ProvisioningRun] = for {
    child <- baseline(r,r.snapshot.baselinePlanId)
    // A PLANNED child carries no parent link on the first pass, where attachBaseline proves it
    // atomically instead. In every other state the link must already be this onboarding.
    parent <- runner.run(query.baselinePlanParent(r.organizationId,child.id))
    _ <- IO.raiseUnless(identical(r,child,parent.orElse(Option.when(child.state==ProvisioningRunState.Planned)(r.id))))(
      error("REMNAWAVE_ONBOARDING_BASELINE_CHANGED"))
    // Only PLANNED is ours to start. QUEUED and RUNNING are waited on and a terminal child is read
    // as it stands; none of them is ever restarted.
    started <- if (child.state!=ProvisioningRunState.Planned) IO.pure(child) else for {
      now <- IO.realTimeInstant
      // Fenced and idempotent: it proves tenant, resource and parent link in one statement.
      id <- runner.run(runs.attachBaseline(r,token,now))
      _ <- provisioningService.start(ActorContext(r.createdBy,r.organizationId),id,
        RemnawaveNodeOnboardingRun.baselineRequestId(r.id))
      current <- baseline(r,id)
    } yield current
  } yield started
  def baseline(r: RemnawaveNodeOnboardingRun,id: UUID): IO[ProvisioningRun] = runner.run(provisioning.find(r.organizationId,id))
    .flatMap(_.map(_._1).liftTo[IO](error("REMNAWAVE_ONBOARDING_BASELINE_MISSING")))
  def claimSync(r: RemnawaveNodeOnboardingRun,token: UUID): IO[OnboardingSyncClaim[IO]] = fenced(r,token) { now =>
    syncTransactions.prepare(r.organizationId,r.integrationId,IntegrationSyncTrigger.Manual,
      Some(ActorContext(r.createdBy,r.organizationId)),syncService.recoveryWindow,requireEnabled = false)
      .flatMap[OnboardingSyncClaim[IO]] {
      case None => error("INTEGRATION_NOT_FOUND").raiseError[Tx,OnboardingSyncClaim[IO]]
      // The session row and the reference to it commit together, so no crash can leave a RUNNING
      // session this onboarding is unable to name.
      case Some((Some(owned),_)) => runs.attachSync(r,token,owned.session.id,now)
        .as[OnboardingSyncClaim[IO]](OnboardingSyncClaim.Owned(owned.session,syncService.execute(owned)))
      case Some((None,_)) => for {
        recent <- syncSessions.recent(r.organizationId,r.integrationId,8)
        holder <- recent.find(s => s.status==IntegrationSyncStatus.Running && s.organizationId==r.organizationId &&
          s.integrationId==r.integrationId).liftTo[Tx](error("REMNAWAVE_ONBOARDING_SYNC_UNKNOWN"))
        _ <- runs.attachSync(r,token,holder.id,now)
      } yield OnboardingSyncClaim.Foreign[IO](holder)
    }
  }
  def syncSession(r: RemnawaveNodeOnboardingRun,id: UUID): IO[Option[IntegrationSyncSession]] =
    runner.run(syncSessions.find(r.organizationId,id)).map(_.filter(_.integrationId==r.integrationId))
  def inventoryHasNode(r: RemnawaveNodeOnboardingRun): IO[Boolean] = r.externalNodeId.fold(IO.pure(false))(id =>
    runner.run(query.externalNode(r.organizationId,r.integrationId,id)).map(_.exists(_.isActive)))
  private def fenced[A](r: RemnawaveNodeOnboardingRun,token: UUID)(op: Instant => Tx[A]): IO[A] = IO.realTimeInstant.flatMap(now => runner.run(for {
    _ <- integrations.findByIdForUpdate(r.organizationId,r.integrationId).flatMap(_.liftTo[Tx](error("INTEGRATION_NOT_FOUND")))
    _ <- runs.lockResource(r.organizationId,r.resourceId)
    valid <- runs.renew(r,token,now,now.plusSeconds(900))
    _ <- MonadThrow[Tx].raiseUnless(valid)(error("REMNAWAVE_ONBOARDING_LEASE_LOST"))
    _ <- checked(r)
    result <- op(now)
  } yield result))
  override def unbindPrevious(r: RemnawaveNodeOnboardingRun,token: UUID): IO[Unit] = fenced(r,token)(_ => for {
    proof <- r.snapshot.recovery.filter(_ => r.snapshot.replacement).liftTo[Tx](error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
    previous <- proof.previousExternalNodeId.liftTo[Tx](error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
    pinned <- proof.previousInstallation.flatMap(_.inventoryObjectId).liftTo[Tx](error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
    node <- query.externalNode(r.organizationId,r.integrationId,previous).flatMap(_.filter(n => !n.isActive && n.id==pinned)
      .liftTo[Tx](error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED")))
    bound <- bindings.find(r.organizationId,node.id)
    _ <- MonadThrow[Tx].raiseUnless(bound.forall(_.resourceId==r.resourceId))(error("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
    actor=ActorContext(r.createdBy,r.organizationId)
    _ <- desiredService.remove(actor,r.integrationId,node.id)
    _ <- bindingService.unbind(actor,r.integrationId,node.id)
  } yield ())
  def bind(r: RemnawaveNodeOnboardingRun,token: UUID): IO[Unit] = fenced(r,token)(now => for {
    node <- query.externalNode(r.organizationId,r.integrationId,r.externalNodeId.get).flatMap(_.filter(_.isActive).liftTo[Tx](error("REMNAWAVE_ONBOARDING_INVENTORY_MISSING")))
    _ <- r.snapshot.recovery.filter(!_.reusesNode).traverse_ { proof => for {
      old <- proof.previousExternalNodeId.traverse(query.externalNode(r.organizationId,r.integrationId,_)).map(_.flatten)
      _ <- old.traverse_ { previous => for {
        _ <- MonadThrow[Tx].raiseWhen(previous.isActive)(error("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW"))
        bound <- bindings.find(r.organizationId,previous.id)
        _ <- MonadThrow[Tx].raiseUnless(bound.forall(_.resourceId==r.resourceId))(error("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
        actor=ActorContext(r.createdBy,r.organizationId)
        _ <- desiredService.remove(actor,r.integrationId,previous.id)
        _ <- bindingService.unbind(actor,r.integrationId,previous.id)
      } yield () }
    } yield () }
    existing <- bindings.find(r.organizationId,node.id)
    _ <- MonadThrow[Tx].raiseUnless(existing.forall(_.resourceId==r.resourceId))(error("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
    _ <- bindingService.bindCreated(ActorContext(r.createdBy,r.organizationId),r.integrationId,node.id,r.resourceId)
    _ <- runs.replaceFleetMembership(r,token,node.id,now)
  } yield ())
  def setDesiredState(r: RemnawaveNodeOnboardingRun,token: UUID): IO[Unit] = fenced(r,token)(_ => for {
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
    n.summary.asInstanceOf[RemnawaveNodeSummary].activeConfigProfileUuid.contains(r.effectiveInput.configProfileId.toString)) &&
    b.exists(_.resourceId==r.resourceId) && d.exists(_.state==IntegrationDesiredNodeState.Enabled))
}

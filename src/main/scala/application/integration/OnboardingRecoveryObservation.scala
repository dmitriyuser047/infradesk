package ru.bitec.app.ops
package application.integration

import application.port._
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import domain.provisioning.ProvisioningRunState
import java.util.UUID
import scala.concurrent.duration._

/** Fresh read-only classification. Stored inventory activity never proves remote presence or absence. */
object OnboardingRecoveryObservation {
  final case class Evidence(recovery: Option[OnboardingRecovery], node: Option[UUID], correlation: UUID, localState: Option[LocalInstallationState] = None) {
    def localInstallation: Option[LocalInstallationObservation] = recovery.flatMap(_.localInstallation)
  }
  def inspect(previous: Option[RemnawaveNodeOnboardingRun],input: OnboardingInput,
    context: IntegrationRuntimeContext,nodes: NodeProvisioningTransport[IO],remote: RemnawaveNodeRemote[IO],connection: Option[domain.connection.Connection],
    action: String,reviewedAddressChange: Boolean = false): IO[Evidence] = if(action=="RECREATE_WITH_NEW_CONFIG")
      inspectReplacement(previous,input,context,nodes,remote,connection) else previous match {
    case None => IO.pure(Evidence(None,None,UUID.randomUUID()))
    case Some(r) if r.organizationId!=context.organizationId || r.integrationId!=context.id || r.resourceId!=input.resourceId =>
      IO.raiseError(IntegrationError("REMNAWAVE_ONBOARDING_NOT_FOUND","Onboarding was not found in this scope"))
    case Some(r) =>
      val unknownCreate=r.externalNodeId.isEmpty && r.phase==OnboardingPhase.CreateNode && r.state==ProvisioningRunState.Unknown
      val known=r.externalNodeId.orElse(if(unknownCreate) None else r.snapshot.recovery.flatMap(_.previousExternalNodeId))
      val correlation=if(r.externalNodeId.nonEmpty || unknownCreate) r.snapshot.correlationId
        else r.snapshot.recovery.fold(r.snapshot.correlationId)(_.previousCorrelationId)
      val selected=if(input.protocol.nonEmpty && r.snapshot.input.protocol==input.protocol && r.protocolBinding.nonEmpty)
        input.copy(configProfileId=r.effectiveInput.configProfileId,activeInboundIds=r.effectiveInput.activeInboundIds) else input
      val intent=NodeCreateIntent(input.nodeName,input.address,input.nodePort,selected.configProfileId,selected.activeInboundIds,correlation)
      val owner=if(r.externalNodeId.isEmpty && !unknownCreate) r.snapshot.recovery.fold(r.id)(_.installationOwnerId)
        else RemnawaveNodeOnboardingRun.installationOwner(r)
      val previousCidrs = if(r.externalNodeId.isEmpty && !unknownCreate)
        r.snapshot.recovery.flatMap(_.previousPanelCidrs).getOrElse(r.snapshot.input.panelCidrs)
        else r.effectivePanelSources
      val previousImage = if(r.externalNodeId.isEmpty && !unknownCreate)
        r.snapshot.recovery.flatMap(_.previousImageReference).getOrElse(r.snapshot.installationImageReference)
        else r.snapshot.installationImageReference
      def result(state: String,node: Option[UUID],local: Option[LocalInstallationObservation] = None, verified: Boolean = false,
        sources: List[String] = previousCidrs,problem: Option[PanelConnectivityProblem] = None,oldAddress: Option[String] = None): Evidence = {
        val operation=if(state=="CONFIRMED_NOT_FOUND") "RECREATE" else if(verified && sources.isEmpty && action=="RECOVER") "REPAIR_PANEL_CONNECTIVITY" else action
        Evidence(Some(OnboardingRecovery(r.id,known.orElse(node),correlation,owner,state,operation,Some(sources),Some(previousImage),local,verified,problem,
          oldAddress)),
          if(Set("RECOVER","REPAIR_PANEL_CONNECTIVITY")(operation) && Set("PRESENT_EXACT","PRESENT_UNHEALTHY")(state)) node.orElse(known) else None,
          correlation,local.map(_.state))
      }
      (for {
        exact <- known.traverse(id => nodes.lookupNode(context,id).timeoutTo(15.seconds,
          IO.pure(NodeLookupOutcome.Unknown("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN"))))
        candidates <- nodes.findNodes(context).timeout(15.seconds).map(_.filter(OnboardingRecovery.candidate(_,intent)))
        lookup=exact.getOrElse(candidates match {
          case List(n) if OnboardingRecovery.matches(n,intent,None) => NodeLookupOutcome.Found(n)
          case Nil => NodeLookupOutcome.Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN")
          case _ => NodeLookupOutcome.Unknown("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW")
        })
        out <- lookup match {
          case NodeLookupOutcome.Found(n) if !(OnboardingRecovery.matches(n,intent,known) || reviewedAddressChange && known.contains(n.externalId) &&
            (n.address==r.snapshot.input.address || r.snapshot.recovery.flatMap(_.previousNodeAddress).contains(n.address)) &&
            OnboardingRecovery.matches(n,intent.copy(address=n.address),known)) || candidates.exists(_.externalId!=n.externalId) =>
            IO.pure(result("PRESENT_CONFLICT",known))
          case NodeLookupOutcome.Found(n) => connection.liftTo[IO](IntegrationError("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN","SSH connection is unavailable")).flatMap { conn =>
            remote.observe(conn,RemnawaveNodeRemoteSpec(owner,input.resourceId,n.externalId,input.nodePort,previousImage,previousCidrs,r.snapshot.input.certificateId)).timeout(35.seconds)
              .flatMap { local =>
                val reviewed = (previousCidrs ++ r.snapshot.input.panelCidrs ++ r.snapshot.recovery.toList.flatMap(_.previousPanelCidrs.toList.flatten)).distinct.sorted
                val baseline = if(local.verified) IO.pure(Some(previousCidrs)) else if(r.snapshot.lifecycleVersion>=4 && local.locallyHealthy)
                  remote.connectivitySources(conn,RemnawaveNodeRemoteSpec(owner,input.resourceId,n.externalId,input.nodePort,previousImage,previousCidrs,r.snapshot.input.certificateId),reviewed).map(Some(_))
                  else IO.pure(None)
                baseline.map { sources =>
                  val healthy = local.locallyHealthy && sources.nonEmpty
                  result(if(local.installationState==LocalInstallationState.Unknown) "UNKNOWN"
                    else if(n.connected && !n.disabled && healthy) "PRESENT_EXACT" else "PRESENT_UNHEALTHY",
                    Some(n.externalId),Some(LocalInstallationObservation.fromState(local.installationState)),healthy && !n.disabled,
                    sources.getOrElse(previousCidrs),oldAddress=Option.when(n.address!=input.address)(n.address))
                }
              }.timeout(35.seconds)
          }.handleError {
            case PanelConnectivityFailure.ManualOnly => result("PRESENT_UNHEALTHY",Some(n.externalId),
              Some(LocalInstallationObservation.fromState(LocalInstallationState.OwnedComplete)),problem=Some(PanelConnectivityProblem.ManualOnly))
            case _ => result("UNKNOWN",Some(n.externalId),Some(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.SshUnavailable)))
          }
          case NodeLookupOutcome.ConfirmedNotFound if known.nonEmpty && candidates.isEmpty =>
            connection.fold(IO.pure(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.SshUnavailable)))(conn =>
              remote.localInstallationObservation(conn,
                RemnawaveNodeRemoteSpec(owner,input.resourceId,known.get,input.nodePort,previousImage,previousCidrs,r.snapshot.input.certificateId)))
              .timeoutTo(35.seconds,IO.pure(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ObservationTimeout)))
              .handleError {
                case _: java.util.concurrent.TimeoutException => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ObservationTimeout)
                case _ => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.SshUnavailable)
              }.map(local => result("CONFIRMED_NOT_FOUND",None,Some(local)))
          case NodeLookupOutcome.ConfirmedNotFound => IO.pure(result("PRESENT_CONFLICT",known))
          case NodeLookupOutcome.Unknown("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW") => IO.pure(result("PRESENT_CONFLICT",known))
          case _ => IO.pure(result("UNKNOWN",known))
        }
      } yield out)
        .handleError(_ => result("UNKNOWN",known))
  }

  private def inspectReplacement(previous: Option[RemnawaveNodeOnboardingRun],input: OnboardingInput,
    context: IntegrationRuntimeContext,nodes: NodeProvisioningTransport[IO],remote: RemnawaveNodeRemote[IO],
    connection: Option[domain.connection.Connection]): IO[Evidence] = previous.liftTo[IO](
      IntegrationError("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW","Previous ownership chain is unavailable")).flatMap { r =>
    IO.raiseUnless(r.organizationId==context.organizationId && r.integrationId==context.id && r.resourceId==input.resourceId)(
      IntegrationError("REMNAWAVE_ONBOARDING_NOT_FOUND","Previous onboarding belongs to another scope")) *> IO.defer {
      val old=PreviousNodeInstallation.fromRun(r)
      val node=r.externalNodeId.orElse(r.snapshot.recovery.flatMap(_.previousExternalNodeId))
      val correlation=if(r.externalNodeId.nonEmpty) r.snapshot.correlationId else r.snapshot.recovery.fold(r.snapshot.correlationId)(_.previousCorrelationId)
      val owner=if(r.externalNodeId.nonEmpty) RemnawaveNodeOnboardingRun.installationOwner(r) else r.snapshot.recovery.fold(r.id)(_.installationOwnerId)
      val cidrs=if(r.externalNodeId.nonEmpty) r.effectivePanelSources else r.snapshot.recovery.flatMap(_.previousPanelCidrs).getOrElse(r.snapshot.input.panelCidrs)
      val image=if(r.externalNodeId.nonEmpty) r.snapshot.installationImageReference else r.snapshot.recovery.flatMap(_.previousImageReference).getOrElse(r.snapshot.installationImageReference)
      def result(state: String,local: Option[LocalInstallationObservation]): Evidence = Evidence(
        Some(OnboardingRecovery(r.id,node,correlation,owner,state,"RECREATE_WITH_NEW_CONFIG",Some(cidrs),Some(image),local,
          previousNodeAddress=Some(old.address),previousInstallation=Some(old))),None,correlation,local.map(_.state))
      (for {
        id <- node.liftTo[IO](IntegrationError("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW","Previous UUID is unavailable"))
        exact <- nodes.lookupNode(context,id).timeout(15.seconds)
        inventory <- nodes.findNodes(context).timeout(15.seconds)
        oldIntent=NodeCreateIntent(old.nodeName,old.address,old.nodePort,r.effectiveInput.configProfileId,r.effectiveInput.activeInboundIds,correlation)
        newIntent=NodeCreateIntent(input.nodeName,input.address,input.nodePort,input.configProfileId,input.activeInboundIds,correlation)
        conflict=inventory.exists(n => n.externalId==id || OnboardingRecovery.candidate(n,oldIntent) || OnboardingRecovery.candidate(n,newIntent))
        out <- exact match {
          case NodeLookupOutcome.ConfirmedNotFound if !conflict =>
            connection.liftTo[IO](IntegrationError("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN","SSH is unavailable")).flatMap(conn =>
              remote.localInstallationObservation(conn,RemnawaveNodeRemoteSpec(owner,input.resourceId,id,old.nodePort,image,cidrs,old.certificateId)))
              .timeoutTo(35.seconds,IO.pure(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ObservationTimeout)))
              .handleError(_ => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.SshUnavailable))
              .map(local => result("CONFIRMED_NOT_FOUND",Some(local)))
          case _: NodeLookupOutcome.Unknown => IO.pure(result("UNKNOWN",Some(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.StateUnknown))))
          case _ => IO.pure(result("PRESENT_CONFLICT",None))
        }
      } yield out).handleError(_ => result("UNKNOWN",Some(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.SshUnavailable))))
    }
  }

}

package ru.bitec.app.ops
package application.integration

import application.port._
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import domain.provisioning.ProvisioningRunState
import java.util.UUID

/** Fresh read-only classification. Stored inventory activity never proves remote presence or absence. */
object OnboardingRecoveryObservation {
  final case class Evidence(recovery: Option[OnboardingRecovery], node: Option[UUID], correlation: UUID, localState: Option[LocalInstallationState] = None)
  def inspect(previous: Option[RemnawaveNodeOnboardingRun],input: OnboardingInput,
    context: IntegrationRuntimeContext,nodes: NodeProvisioningTransport[IO],remote: RemnawaveNodeRemote[IO],connection: Option[domain.connection.Connection],
    action: String): IO[Evidence] = previous match {
    case None => IO.pure(Evidence(None,None,UUID.randomUUID()))
    case Some(r) if r.organizationId!=context.organizationId || r.integrationId!=context.id || r.resourceId!=input.resourceId =>
      IO.raiseError(IntegrationError("REMNAWAVE_ONBOARDING_NOT_FOUND","Onboarding was not found in this scope"))
    case Some(r) =>
      val unknownCreate=r.externalNodeId.isEmpty && r.phase==OnboardingPhase.CreateNode && r.state==ProvisioningRunState.Unknown
      val known=r.externalNodeId.orElse(if(unknownCreate) None else r.snapshot.recovery.flatMap(_.previousExternalNodeId))
      val correlation=if(r.externalNodeId.nonEmpty || unknownCreate) r.snapshot.correlationId
        else r.snapshot.recovery.fold(r.snapshot.correlationId)(_.previousCorrelationId)
      val intent=NodeCreateIntent(input.nodeName,input.address,input.nodePort,input.configProfileId,input.activeInboundIds,correlation)
      val owner=if(r.externalNodeId.isEmpty && !unknownCreate) r.snapshot.recovery.fold(r.id)(_.installationOwnerId)
        else RemnawaveNodeOnboardingRun.installationOwner(r)
      val previousCidrs = if(r.externalNodeId.isEmpty && !unknownCreate)
        r.snapshot.recovery.flatMap(_.previousPanelCidrs).getOrElse(r.snapshot.input.panelCidrs)
        else r.snapshot.input.panelCidrs
      val previousImage = if(r.externalNodeId.isEmpty && !unknownCreate)
        r.snapshot.recovery.flatMap(_.previousImageReference).getOrElse(r.snapshot.installationImageReference)
        else r.snapshot.installationImageReference
      def result(state: String,node: Option[UUID],local: Option[LocalInstallationState] = None): Evidence = {
        val operation=if(state=="CONFIRMED_NOT_FOUND") "RECREATE" else action
        Evidence(Some(OnboardingRecovery(r.id,known.orElse(node),correlation,owner,state,operation,Some(previousCidrs),Some(previousImage))),
          if(operation=="RECOVER" && Set("PRESENT_EXACT","PRESENT_UNHEALTHY")(state)) node.orElse(known) else None,
          if(operation=="RECOVER") correlation else UUID.randomUUID(),local)
      }
      (for {
        exact <- known.traverse(nodes.lookupNode(context,_))
        candidates <- nodes.findNodes(context).map(_.filter(OnboardingRecovery.candidate(_,intent)))
        lookup=exact.getOrElse(candidates match {
          case List(n) if OnboardingRecovery.matches(n,intent,None) => NodeLookupOutcome.Found(n)
          case Nil => NodeLookupOutcome.Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN")
          case _ => NodeLookupOutcome.Unknown("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW")
        })
        out <- lookup match {
          case NodeLookupOutcome.Found(n) if !OnboardingRecovery.matches(n,intent,known) || candidates.exists(_.externalId!=n.externalId) =>
            IO.pure(result("PRESENT_CONFLICT",known))
          case NodeLookupOutcome.Found(n) => connection.liftTo[IO](IntegrationError("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN","SSH connection is unavailable")).flatMap { conn =>
            remote.observe(conn,RemnawaveNodeRemoteSpec(owner,input.resourceId,n.externalId,input.nodePort,previousImage,input.panelCidrs))
              .map(local => result(if(local.installationState==LocalInstallationState.Unknown) "UNKNOWN"
                else if(n.connected && !n.disabled && local.verified) "PRESENT_EXACT" else "PRESENT_UNHEALTHY",
                Some(n.externalId),Some(local.installationState)))
          }
          case NodeLookupOutcome.ConfirmedNotFound if known.nonEmpty && candidates.isEmpty =>
            connection.traverse(conn => remote.localInstallationState(conn,
              RemnawaveNodeRemoteSpec(owner,input.resourceId,known.get,input.nodePort,previousImage,previousCidrs)))
              .map(local => result("CONFIRMED_NOT_FOUND",None,Some(local.getOrElse(LocalInstallationState.Unknown))))
          case NodeLookupOutcome.ConfirmedNotFound => IO.pure(result("PRESENT_CONFLICT",known))
          case NodeLookupOutcome.Unknown("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW") => IO.pure(result("PRESENT_CONFLICT",known))
          case _ => IO.pure(result("UNKNOWN",known))
        }
      } yield out).timeoutTo(scala.concurrent.duration.DurationInt(45).seconds,IO.pure(result("UNKNOWN",known)))
        .handleError(_ => result("UNKNOWN",known))
  }

}

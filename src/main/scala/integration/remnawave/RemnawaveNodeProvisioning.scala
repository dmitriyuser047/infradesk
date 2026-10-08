package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationError, IntegrationRuntimeContext}
import application.port.NodeProvisioningTransport
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import io.circe.parser.parse
import java.util.UUID

/** Live read evidence plus the reviewed upstream catalog; no version branches in application code. */
final class RemnawaveNodeProvisioning(client: RemnawaveClient) extends NodeProvisioningTransport[IO] {
  private val unconfirmed = "INTEGRATION_API_CONTRACT_UNCONFIRMED"
  private val changed = "INTEGRATION_API_CONTRACT_CHANGED"

  override def inspect(context: IntegrationRuntimeContext): IO[NodeApiCompatibility] = credential(context).flatMap { auth =>
    // Metadata is optional for read-only inventory, but mandatory for provisioning.
    client.get(context.baseUrl.endpoint("api/system/metadata"), auth, RemnawaveClient.MaxResponseBytes).attempt.flatMap { result =>
      val metadata = result.toOption
      val version = metadata.flatMap(parse(_).toOption).flatMap(
        _.hcursor.downField("response").get[String]("version").toOption)
        .filter(v => v.length <= 64 && !v.exists(_.isControl))
      val release = metadata.flatMap(RemnawaveNodeApi.releaseFromMetadata)
      findNodes(context).attempt.map { inventory =>
        val readCapabilities: Set[NodeProvisioningCapability] = if (inventory.isRight)
          Set(NodeProvisioningCapability.Inventory, NodeProvisioningCapability.Status) else Set.empty
        val confirmed = release.nonEmpty && inventory.isRight
        NodeApiCompatibility(version, release.map(_.adapter.code), release.map(_.commit),
          readCapabilities ++ (if (confirmed) RemnawaveNodeApi.writeCapabilities ++
            Option.when(release.exists(_.addressUpdate))(NodeProvisioningCapability.AddressUpdate) ++
            Option.when(release.exists(_.protocolProfileCreate))(NodeProvisioningCapability.ProtocolProfileCreate) else Set.empty[NodeProvisioningCapability]),
          if (confirmed) None else Some(result.left.toOption.collect { case e: IntegrationError => e.code }
            .orElse(inventory.left.toOption.collect { case e: IntegrationError => e.code }).getOrElse(unconfirmed)))
      }
    }
  }

  override def ensureProtocolProfile(context: IntegrationRuntimeContext, name: String, tag: String,
    config: io.circe.Json, reviewed: NodeApiCompatibility, fresh: Boolean): IO[ProtocolProfileOutcome] = {
    import ProtocolProfileOutcome._
    (for {
      actual <- requireReviewed(context, reviewed)
      _ <- IO.raiseUnless(actual.capabilities(NodeProvisioningCapability.ProtocolProfileCreate) &&
        reviewed.capabilities(NodeProvisioningCapability.ProtocolProfileCreate))(
        RemnawaveErrors.error(unconfirmed))
      _ <- IO.raiseUnless(name.matches("ID_[0-9a-f]{24}") && tag.matches("(HY2|SS)_[0-9a-f]{32}") && config.isObject)(
        RemnawaveErrors.error("REMNAWAVE_PROTOCOL_INVALID"))
      auth <- credential(context)
      existing <- client.protocolProfileLookup(context.baseUrl, auth, name, tag, config)
      result <- existing match {
        case Some(binding) if !fresh => IO.pure[ProtocolProfileOutcome](Confirmed(binding))
        case Some(_) => IO.pure[ProtocolProfileOutcome](Rejected("REMNAWAVE_PROTOCOL_PROFILE_CONFLICT"))
        case None if !fresh => IO.pure[ProtocolProfileOutcome](Unknown("REMNAWAVE_PROTOCOL_PROFILE_CREATE_UNKNOWN"))
        case None => client.createProtocolProfileWire(context.baseUrl, auth, name, tag, config).flatMap {
          case _: Unknown => client.protocolProfileLookup(context.baseUrl, auth, name, tag, config).map(
            _.fold[ProtocolProfileOutcome](Unknown("REMNAWAVE_PROTOCOL_PROFILE_CREATE_UNKNOWN"))(Confirmed.apply))
            .handleError(_ => Unknown("REMNAWAVE_PROTOCOL_PROFILE_CREATE_UNKNOWN"))
          case outcome => IO.pure(outcome)
        }
      }
    } yield result).handleError {
      case e: IntegrationError => if(fresh) Rejected(e.code) else Unknown(e.code)
      case _ => Unknown("REMNAWAVE_PROTOCOL_PROFILE_CREATE_UNKNOWN")
    }
  }

  override def updateNodeAddress(context: IntegrationRuntimeContext,externalId: UUID,expected: NodeCreateIntent,
    desired: NodeCreateIntent,reviewed: NodeApiCompatibility): IO[IntegrationActionRemoteOutcome] = {
    import IntegrationActionRemoteOutcome._
    (for {
      actual <- requireReviewed(context,reviewed)
      _ <- IO.raiseUnless(actual.capabilities(NodeProvisioningCapability.AddressUpdate) && reviewed.capabilities(NodeProvisioningCapability.AddressUpdate) &&
        expected.copy(address=desired.address)==desired && RemnawaveNodeApi.validIntent(desired))(
        RemnawaveErrors.error("INTEGRATION_API_CONTRACT_UNCONFIRMED"))
      current <- getNode(context,externalId)
      _ <- IO.raiseUnless(RemnawaveNodeApi.matches(current,expected))(RemnawaveErrors.error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
      others <- findNodes(context)
      _ <- IO.raiseWhen(others.exists(n => n.externalId!=externalId && OnboardingRecovery.candidate(n,desired)))(RemnawaveErrors.error("INTEGRATION_NODE_CONFLICT"))
      auth <- credential(context)
      outcome <- client.updateNodeAddressWire(context.baseUrl,auth,externalId,desired)
    } yield outcome).handleError {
      case e: IntegrationError => DefinitelyFailed(e.code)
      case _ => DefinitelyFailed("INTEGRATION_UNREACHABLE")
    }
  }

  override def findNodes(context: IntegrationRuntimeContext): IO[List[ProvisionedNode]] = credential(context).flatMap { auth =>
    client.get(context.baseUrl.endpoint(RemnawaveClient.NodesPath), auth, RemnawaveClient.DefaultInventoryMaxResponseBytes)
      .flatMap(body => RemnawaveNodeApi.nodes(body).filter(_.size <= RemnawaveClient.DefaultInventoryMaxObjects)
        .liftTo[IO](RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE")))
  }

  override def getNode(context: IntegrationRuntimeContext, externalId: UUID): IO[ProvisionedNode] = credential(context).flatMap { auth =>
    client.get(context.baseUrl.endpoint(s"${RemnawaveClient.NodesPath}/$externalId"), auth,
      RemnawaveClient.ConfigProfileMaxResponseBytes).flatMap { body =>
      parse(body).toOption.flatMap(_.hcursor.downField("response").success).flatMap(RemnawaveNodeApi.node)
        .filter(_.externalId == externalId).liftTo[IO](RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE"))
    }
  }

  override def lookupNode(context: IntegrationRuntimeContext, externalId: UUID): IO[NodeLookupOutcome] =
    credential(context).flatMap(client.lookupNodeWire(context.baseUrl, _, externalId)).handleError {
      case e: IntegrationError => NodeLookupOutcome.Unknown(e.code)
      case _ => NodeLookupOutcome.Unknown("INTEGRATION_NODE_LOOKUP_RESULT_UNKNOWN")
    }

  override def deleteNode(context: IntegrationRuntimeContext, externalId: UUID,
    reviewed: NodeApiCompatibility): IO[NodeDeleteOutcome] = {
    (for {
      _ <- requireReviewed(context, reviewed)
      auth <- credential(context)
      outcome <- client.deleteNodeWire(context.baseUrl, auth, externalId)
    } yield outcome).handleError {
      case e: IntegrationError => NodeDeleteOutcome.Rejected(e.code)
      case _ => NodeDeleteOutcome.Rejected("INTEGRATION_UNREACHABLE")
    }
  }

  override def createNode(context: IntegrationRuntimeContext, intent: NodeCreateIntent,
    reviewed: NodeApiCompatibility): IO[NodeCreateOutcome] = {
    if (!RemnawaveNodeApi.validIntent(intent)) IO.pure(NodeCreateOutcome.Rejected("INTEGRATION_NODE_INVALID_REQUEST"))
    else (for {
      _ <- requireReviewed(context, reviewed)
      nodes <- findNodes(context)
      conflict = nodes.exists(node => node.name == intent.name || node.address == intent.address ||
        node.correlationTags.contains(RemnawaveNodeApi.correlationTag(intent.correlationId)))
      auth <- credential(context)
      result <- if (conflict) IO.pure[NodeCreateOutcome](NodeCreateOutcome.Rejected("INTEGRATION_NODE_CONFLICT"))
        else client.createNodeWire(context.baseUrl, auth, intent)
    } yield result).handleError {
      case e: IntegrationError => NodeCreateOutcome.Rejected(e.code)
      case _ => NodeCreateOutcome.Rejected("INTEGRATION_UNREACHABLE")
    }
  }

  override def installationData(context: IntegrationRuntimeContext,
    reviewed: NodeApiCompatibility): IO[NodeInstallationData] = for {
    actual <- requireReviewed(context, reviewed)
    adapter <- RemnawaveNodeApi.all.find(a => actual.apiGeneration.contains(a.code))
      .liftTo[IO](RemnawaveErrors.error(unconfirmed))
    auth <- credential(context)
    body <- client.get(context.baseUrl.endpoint("api/keygen"), auth, RemnawaveClient.MaxResponseBytes)
    data <- adapter.installationData(body).liftTo[IO](RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE"))
  } yield data

  /** Absence does not prove a timed-out POST failed. Never calls create, even when no match exists. */
  override def reconcileCreate(context: IntegrationRuntimeContext, intent: NodeCreateIntent,
    reviewed: NodeApiCompatibility): IO[NodeCreateReconciliation] = for {
    _ <- requireReviewed(context, reviewed)
    nodes <- findNodes(context)
    candidates = nodes.filter(node => node.name == intent.name || node.address == intent.address ||
      node.correlationTags.contains(RemnawaveNodeApi.correlationTag(intent.correlationId)))
  } yield candidates match {
    case List(node) if RemnawaveNodeApi.matches(node, intent) => NodeCreateReconciliation.Confirmed(node)
    case _ => NodeCreateReconciliation.NotProven
  }

  // This gate belongs only to the reviewed create/install/reconciliation contract.
  private def requireProvisioningCompatible(context: IntegrationRuntimeContext): IO[NodeApiCompatibility] =
    inspect(context).flatMap(actual => IO.raiseUnless(actual.provisioningReady)(
      RemnawaveErrors.error(actual.blocker.getOrElse(unconfirmed))).as(actual))

  private def requireReviewed(context: IntegrationRuntimeContext, reviewed: NodeApiCompatibility): IO[NodeApiCompatibility] =
    requireProvisioningCompatible(context).flatMap { actual =>
      IO.raiseUnless(reviewed.provisioningReady && reviewed.serverVersion == actual.serverVersion &&
        reviewed.apiGeneration == actual.apiGeneration && reviewed.sourceCommit == actual.sourceCommit)(
        RemnawaveErrors.error(changed)).as(actual)
    }

  private def credential(context: IntegrationRuntimeContext): IO[RemnawaveCredential] = context.credential match {
    case auth: RemnawaveCredential if auth.valid => IO.pure(auth)
    case _ => IO.raiseError(RemnawaveErrors.error("INTEGRATION_CREDENTIAL_INVALID"))
  }
}

package ru.bitec.app.ops
package application.port

import application.integration.IntegrationRuntimeContext
import domain.integration._
import java.util.UUID

/** One cross-version boundary. Reviewed compatibility must be pinned in the onboarding snapshot.
  * Implementations recheck it before any create or registration-data request; unknown versions
  * cannot gain write capabilities merely by returning a recognizable inventory response.
  */
trait NodeProvisioningTransport[F[_]] {
  def updateNodeAddress(context: IntegrationRuntimeContext,externalId: UUID,expected: NodeCreateIntent,
    desired: NodeCreateIntent,reviewed: NodeApiCompatibility): F[IntegrationActionRemoteOutcome] =
    throw new UnsupportedOperationException("Reviewed Node address updates are unavailable")
  def inspect(context: IntegrationRuntimeContext): F[NodeApiCompatibility]
  def findNodes(context: IntegrationRuntimeContext): F[List[ProvisionedNode]]
  def getNode(context: IntegrationRuntimeContext, externalId: UUID): F[ProvisionedNode]
  def lookupNode(context: IntegrationRuntimeContext, externalId: UUID): F[NodeLookupOutcome]
  def deleteNode(context: IntegrationRuntimeContext, externalId: UUID,
    reviewed: NodeApiCompatibility): F[NodeDeleteOutcome]
  def createNode(context: IntegrationRuntimeContext, intent: NodeCreateIntent,
    reviewed: NodeApiCompatibility): F[NodeCreateOutcome]
  def installationData(context: IntegrationRuntimeContext,
    reviewed: NodeApiCompatibility): F[NodeInstallationData]
  def reconcileCreate(context: IntegrationRuntimeContext, intent: NodeCreateIntent,
    reviewed: NodeApiCompatibility): F[NodeCreateReconciliation]
}

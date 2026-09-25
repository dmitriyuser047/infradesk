package ru.bitec.app.ops
package infrastructure.http.dto

import application.overview.OperationsOverview
import application.port._
import infrastructure.http.mapper.ConnectionHttpMapper

import java.time.Instant
import java.util.UUID

/** The operations overview as the API returns it.
  *
  * Counts, typed references and safe status codes only. No connection configuration, no host
  * fingerprint, no secret metadata and no rendered text: the UI decides how a fact reads.
  */
final case class OperationsOverviewResponse(
  scope: ConnectionScopeResponse,
  summary: OverviewSummaryResponse,
  attention: OverviewAttentionResponse,
  recentActivity: List[HistoryEventResponse],
  operationsHorizonHours: Long
)

final case class OverviewSummaryResponse(
  nodes: OverviewNodesResponse,
  containers: OverviewContainersResponse,
  connections: OverviewConnectionsResponse,
  incidents: OverviewIncidentsResponse,
  operations: OverviewOperationsResponse
)

final case class OverviewNodesResponse(total: Int, online: Int, offline: Int)
final case class OverviewContainersResponse(total: Int, running: Int, stopped: Int)
final case class OverviewConnectionsResponse(total: Int, healthy: Int, failing: Int, neverSynced: Int)
final case class OverviewIncidentsResponse(open: Int, threshold: Int, noData: Int)
final case class OverviewOperationsResponse(failed: Int, unknown: Int)

final case class OverviewAttentionResponse(items: List[AttentionItemResponse], total: Int)

/** One attention item. `kind` says which of the optional parts are present. */
final case class AttentionItemResponse(
  kind: String,
  priority: Int,
  id: UUID,
  occurredAt: Instant,
  resource: Option[OverviewResourceResponse],
  connection: Option[HistoryConnectionResponse],
  incident: Option[AttentionIncidentResponse],
  operation: Option[AttentionOperationResponse],
  sync: Option[AttentionSyncResponse]
)

final case class OverviewResourceResponse(
  id: UUID,
  name: String,
  resourceTypeCode: String,
  environmentId: UUID
)
final case class AttentionIncidentResponse(reason: String, metricCode: String)
final case class AttentionOperationResponse(
  operationCode: String,
  errorCode: Option[String],
  errorMessage: Option[String]
)
final case class AttentionSyncResponse(errorCode: Option[String], errorMessage: Option[String])

object OperationsOverviewResponse {

  def from(overview: OperationsOverview): OperationsOverviewResponse = {
    val summary = overview.summary
    OperationsOverviewResponse(
      ConnectionHttpMapper.toScopeResponse(overview.scope),
      OverviewSummaryResponse(
        OverviewNodesResponse(summary.nodes.total, summary.nodes.online, summary.nodes.offline),
        OverviewContainersResponse(summary.containers.total, summary.containers.running,
          summary.containers.stopped),
        OverviewConnectionsResponse(summary.connections.total, summary.connections.healthy,
          summary.connections.failing, summary.connections.neverSynced),
        OverviewIncidentsResponse(summary.incidents.open, summary.incidents.threshold,
          summary.incidents.noData),
        OverviewOperationsResponse(summary.operations.failed, summary.operations.unknown)
      ),
      OverviewAttentionResponse(overview.attention.items.map(item), overview.attention.total),
      overview.recentActivity.map(HistoryEventResponse.from),
      overview.operationsHorizon.toHours
    )
  }

  private def item(value: AttentionItem): AttentionItemResponse = {
    val base = AttentionItemResponse(value.kind.code, value.kind.rank, value.id, value.occurredAt,
      None, None, None, None, None)
    value match {
      case incident: IncidentAttention =>
        base.copy(resource = Some(resource(incident.resource)),
          incident = Some(AttentionIncidentResponse(incident.reason, incident.metricCode)))
      case operation: OperationAttention =>
        base.copy(resource = Some(resource(operation.resource)),
          operation = Some(AttentionOperationResponse(operation.operationCode, operation.errorCode,
            operation.errorMessage)))
      case node: NodeOfflineAttention =>
        base.copy(resource = Some(resource(node.resource)))
      case sync: SyncFailureAttention =>
        base.copy(connection = Some(HistoryConnectionResponse(sync.connection.id, sync.connection.name)),
          sync = Some(AttentionSyncResponse(sync.errorCode, sync.errorMessage)))
    }
  }

  private def resource(value: OverviewResourceView): OverviewResourceResponse =
    OverviewResourceResponse(value.id, value.name, value.resourceTypeCode, value.environmentId)
}

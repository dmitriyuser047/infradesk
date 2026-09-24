package ru.bitec.app.ops
package infrastructure.http.dto

import application.port.HistoryEventView

import java.time.Instant
import java.util.UUID

/** The timeline as the API returns it: identifiers, display names and safe status codes.
  *
  * Nothing that describes how an operation reached the host — no external id, no connection
  * configuration, no command, no output — is part of this contract.
  */
final case class HistoryEventResponse(
  id: UUID,
  eventType: String,
  source: String,
  occurredAt: Instant,
  resource: Option[HistoryResourceResponse],
  connection: Option[HistoryConnectionResponse],
  actor: Option[HistoryActorResponse],
  incident: Option[HistoryIncidentResponse],
  operation: Option[HistoryOperationResponse],
  sync: Option[HistorySyncResponse]
)

final case class HistoryResourceResponse(id: UUID, name: String, resourceTypeCode: String)
final case class HistoryConnectionResponse(id: UUID, name: String)
final case class HistoryActorResponse(id: UUID, displayName: String)
final case class HistoryIncidentResponse(id: UUID, status: String, reason: String, monitorRuleId: UUID)
final case class HistoryOperationResponse(
  id: UUID,
  operationCode: String,
  status: String,
  errorCode: Option[String],
  errorMessage: Option[String]
)
final case class HistorySyncResponse(id: UUID, status: String, errorCode: Option[String])

object HistoryEventResponse {

  def from(view: HistoryEventView): HistoryEventResponse =
    HistoryEventResponse(
      view.id,
      view.eventType.code,
      view.source.code,
      view.occurredAt,
      view.resource.map(value => HistoryResourceResponse(value.id, value.name, value.resourceTypeCode)),
      view.connection.map(value => HistoryConnectionResponse(value.id, value.name)),
      view.actor.map(value => HistoryActorResponse(value.id, value.displayName)),
      view.incident.map(value =>
        HistoryIncidentResponse(value.id, value.status, value.reason, value.monitorRuleId)),
      view.operation.map(value =>
        HistoryOperationResponse(value.id, value.operationCode, value.status, value.errorCode,
          value.errorMessage)),
      view.sync.map(value => HistorySyncResponse(value.id, value.status, value.errorCode))
    )
}

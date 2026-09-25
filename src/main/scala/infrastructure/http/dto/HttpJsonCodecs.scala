package ru.bitec.app.ops
package infrastructure.http.dto

import io.circe.{Encoder, Json}
import io.circe.Decoder

import java.time.Instant
import java.util.UUID

object HttpJsonCodecs {

  implicit val uuidEncoder: Encoder[UUID] = Encoder.encodeString.contramap(_.toString)
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  implicit val uuidDecoder: Decoder[UUID] = Decoder.decodeString.emap { raw =>
    scala.util.Try(UUID.fromString(raw)).toEither.left.map(_ => "Invalid UUID")
  }

  implicit val connectionScopeRequestDecoder: Decoder[ConnectionScopeRequest] =
    Decoder.forProduct3("type", "projectId", "environmentId")(ConnectionScopeRequest.apply)
  implicit val sshSettingsRequestDecoder: Decoder[SshSettingsRequest] =
    Decoder.forProduct5("host", "port", "username", "authenticationType", "hostKeyFingerprint")(SshSettingsRequest.apply)
  implicit val sshCredentialsRequestDecoder: Decoder[SshCredentialsRequest] =
    Decoder.forProduct4("type", "password", "privateKey", "passphrase")(SshCredentialsRequest.apply)
  implicit val connectionScheduleRequestDecoder: Decoder[ConnectionScheduleRequest] =
    Decoder.forProduct2("enabled", "intervalSeconds")(ConnectionScheduleRequest.apply)
  implicit val saveSshConnectionRequestDecoder: Decoder[SaveSshConnectionRequest] =
    Decoder.forProduct7("connectorType", "code", "name", "scope", "ssh", "credentials", "schedule")(SaveSshConnectionRequest.apply)
  implicit val testSshConnectionRequestDecoder: Decoder[TestSshConnectionRequest] =
    Decoder.forProduct5("host", "port", "username", "hostKeyFingerprint", "credentials")(
      TestSshConnectionRequest.apply)
  implicit val probeSshHostRequestDecoder: Decoder[ProbeSshHostRequest] =
    Decoder.forProduct3("host", "port", "username")(ProbeSshHostRequest.apply)
  implicit val probeSshHostResponseEncoder: Encoder[ProbeSshHostResponse] =
    Encoder.forProduct1("hostKeyFingerprint")(_.hostKeyFingerprint)
  implicit val testSshConnectionResponseEncoder: Encoder[TestSshConnectionResponse] =
    Encoder.forProduct2("success", "hostKeyFingerprint")(v => (v.success, v.hostKeyFingerprint))

  implicit val apiErrorResponseEncoder: Encoder[ApiErrorResponse] =
    Encoder.forProduct2("code", "message")(value => (value.code, value.message))

  implicit val loginRequestDecoder: Decoder[LoginRequest] =
    Decoder.forProduct2("email", "password")(LoginRequest.apply)

  implicit val meResponseEncoder: Encoder[MeResponse] =
    Encoder.forProduct3("id", "email", "displayName")(value => (value.id, value.email, value.displayName))

  implicit val myOrganizationResponseEncoder: Encoder[MyOrganizationResponse] =
    Encoder.forProduct4("id", "code", "name", "role") { value =>
      (value.id, value.code, value.name, value.role)
    }

  implicit val organizationResponseEncoder: Encoder[OrganizationResponse] =
    Encoder.forProduct3("id", "code", "name")(value => (value.id, value.code, value.name))

  implicit val projectResponseEncoder: Encoder[ProjectResponse] =
    Encoder.forProduct5("id", "organizationId", "code", "name", "description") { value =>
      (value.id, value.organizationId, value.code, value.name, value.description)
    }

  implicit val environmentResponseEncoder: Encoder[EnvironmentResponse] =
    Encoder.forProduct6("id", "organizationId", "projectId", "code", "name", "kind") { value =>
      (value.id, value.organizationId, value.projectId, value.code, value.name, value.kind)
    }

  implicit val metricObservationResponseEncoder: Encoder[MetricObservationResponse] =
    Encoder.forProduct3("metricCode", "value", "observedAt")(value => (value.metricCode, value.value, value.observedAt))
  implicit val incidentResponseEncoder: Encoder[IncidentResponse] = Encoder.forProduct10("id","monitorRuleId","resourceId","status","reason","startedAt","openedAt","resolvedAt","createdAt","updatedAt")(v => (v.id,v.monitorRuleId,v.resourceId,v.status,v.reason,v.startedAt,v.openedAt,v.resolvedAt,v.createdAt,v.updatedAt))
  implicit val monitorRuleResponseEncoder: Encoder[MonitorRuleResponse] = Encoder.forProduct11("id","resourceId","metricCode","operator","threshold","forSeconds","noDataSeconds","enabled","status","createdAt","updatedAt")(v=>(v.id,v.resourceId,v.metricCode,v.operator,v.threshold,v.forSeconds,v.noDataSeconds,v.enabled,v.status,v.createdAt,v.updatedAt))
  implicit val auditEventResponseEncoder: Encoder[AuditEventResponse] =
    Encoder.forProduct6("id", "actorUserId", "action", "targetType", "targetId", "occurredAt") { value =>
      (value.id, value.actorUserId, value.action, value.targetType, value.targetId, value.occurredAt)
    }
  implicit val historyResourceResponseEncoder: Encoder[HistoryResourceResponse] =
    Encoder.forProduct4("id", "name", "resourceTypeCode", "environmentId")(v =>
      (v.id, v.name, v.resourceTypeCode, v.environmentId))
  implicit val historyConnectionResponseEncoder: Encoder[HistoryConnectionResponse] =
    Encoder.forProduct2("id", "name")(v => (v.id, v.name))
  implicit val historyActorResponseEncoder: Encoder[HistoryActorResponse] =
    Encoder.forProduct2("id", "displayName")(v => (v.id, v.displayName))
  implicit val historyIncidentResponseEncoder: Encoder[HistoryIncidentResponse] =
    Encoder.forProduct4("id", "status", "reason", "monitorRuleId")(v =>
      (v.id, v.status, v.reason, v.monitorRuleId))
  implicit val historyOperationResponseEncoder: Encoder[HistoryOperationResponse] =
    Encoder.forProduct5("id", "operationCode", "status", "errorCode", "errorMessage")(v =>
      (v.id, v.operationCode, v.status, v.errorCode, v.errorMessage))
  implicit val historySyncResponseEncoder: Encoder[HistorySyncResponse] =
    Encoder.forProduct3("id", "status", "errorCode")(v => (v.id, v.status, v.errorCode))
  implicit val historyEventResponseEncoder: Encoder[HistoryEventResponse] =
    Encoder.forProduct10("id", "eventType", "source", "occurredAt", "resource", "connection",
      "actor", "incident", "operation", "sync")(v =>
      (v.id, v.eventType, v.source, v.occurredAt, v.resource, v.connection, v.actor, v.incident,
        v.operation, v.sync))

  implicit val createMonitorRuleDecoder: Decoder[CreateMonitorRuleRequest] = Decoder.forProduct6("metricCode","operator","threshold","forSeconds","noDataSeconds","enabled")(CreateMonitorRuleRequest.apply)
  implicit val updateMonitorRuleDecoder: Decoder[UpdateMonitorRuleRequest] = Decoder.forProduct6("metricCode","operator","threshold","forSeconds","noDataSeconds","enabled")(UpdateMonitorRuleRequest.apply)

  implicit val organizationConnectionScopeResponseEncoder: Encoder[OrganizationConnectionScopeResponse] =
    Encoder.forProduct1("type")(_.scopeType)

  implicit val projectConnectionScopeResponseEncoder: Encoder[ProjectConnectionScopeResponse] =
    Encoder.forProduct2("type", "projectId")(value => (value.scopeType, value.projectId))

  implicit val environmentConnectionScopeResponseEncoder: Encoder[EnvironmentConnectionScopeResponse] =
    Encoder.forProduct3("type", "projectId", "environmentId") { value =>
      (value.scopeType, value.projectId, value.environmentId)
    }

  implicit val connectionScopeResponseEncoder: Encoder[ConnectionScopeResponse] = Encoder.instance {
    case value: OrganizationConnectionScopeResponse => organizationConnectionScopeResponseEncoder(value)
    case value: ProjectConnectionScopeResponse => projectConnectionScopeResponseEncoder(value)
    case value: EnvironmentConnectionScopeResponse => environmentConnectionScopeResponseEncoder(value)
  }

  implicit val syncSessionResponseEncoder: Encoder[SyncSessionResponse] =
    Encoder.forProduct6("id", "status", "startedAt", "finishedAt", "errorCode", "errorMessage") { value =>
      (value.id, value.status, value.startedAt, value.finishedAt, value.errorCode, value.errorMessage)
    }

  implicit val environmentContextResponseEncoder: Encoder[EnvironmentContextResponse] =
    Encoder.forProduct2("project", "environment")(value => (value.project, value.environment))

  implicit val createProjectRequestDecoder: Decoder[CreateProjectRequest] =
    Decoder.forProduct3("code", "name", "description")(CreateProjectRequest.apply)

  implicit val createEnvironmentRequestDecoder: Decoder[CreateEnvironmentRequest] =
    Decoder.forProduct3("code", "name", "kind")(CreateEnvironmentRequest.apply)

  implicit val connectionScheduleResponseEncoder: Encoder[ConnectionScheduleResponse] =
    Encoder.forProduct3("enabled", "intervalSeconds", "nextRunAt") { value =>
      (value.enabled, value.intervalSeconds, value.nextRunAt)
    }

  implicit val sshConnectionResponseEncoder: Encoder[SshConnectionResponse] =
    Encoder.forProduct7("host", "port", "username", "hostKeyFingerprint", "credentialConfigured",
      "authenticationType", "hostTrusted") { value =>
      (value.host, value.port, value.username, value.hostKeyFingerprint, value.credentialConfigured,
        value.authenticationType, value.hostTrusted)
    }

  implicit val connectionResponseEncoder: Encoder[ConnectionResponse] =
    Encoder.forProduct11(
      "id",
      "connectorType",
      "code",
      "name",
      "scope",
      "active",
      "schedule",
      "lastSync",
      "createdAt",
      "updatedAt",
      "ssh"
    ) { value =>
      (
        value.id,
        value.connectorType,
        value.code,
        value.name,
        value.scope,
        value.active,
        value.schedule,
        value.lastSync,
        value.createdAt,
        value.updatedAt,
        value.ssh
      )
    }

  implicit val nodeSpecResponseEncoder: Encoder[NodeSpecResponse] =
    Encoder.forProduct8(
      "hostname", "operatingSystem", "distribution", "kernelVersion",
      "architecture", "cpuModel", "cpuCores", "memoryMb"
    ) { value =>
      (value.hostname, value.operatingSystem, value.distribution, value.kernelVersion,
        value.architecture, value.cpuModel, value.cpuCores, value.memoryMb)
    }
  implicit val availableResourceOperationsResponseEncoder: Encoder[AvailableResourceOperationsResponse] =
    Encoder.forProduct2("operations", "unavailableReason")(v => (v.operations, v.unavailableReason))
  implicit val operationExecutionResponseEncoder: Encoder[OperationExecutionResponse] =
    Encoder.forProduct9("id", "resourceId", "operationCode", "status", "actorUserId", "startedAt",
      "finishedAt", "errorCode", "errorMessage")(v => (v.id, v.resourceId, v.operationCode, v.status,
      v.actorUserId, v.startedAt, v.finishedAt, v.errorCode, v.errorMessage))

  implicit val nodeStatusResponseEncoder: Encoder[NodeStatusResponse] =
    Encoder.forProduct4("online", "cpuUsagePercent", "memoryUsagePercent", "uptimeSeconds") { value =>
      (value.online, value.cpuUsagePercent, value.memoryUsagePercent, value.uptimeSeconds)
    }

  implicit val containerSpecResponseEncoder: Encoder[ContainerSpecResponse] =
    Encoder.forProduct1("image")(_.image)

  implicit val containerStatusResponseEncoder: Encoder[ContainerStatusResponse] =
    Encoder.forProduct1("state")(_.state)

  implicit val resourceDataResponseEncoder: Encoder[ResourceDataResponse] = Encoder.instance {
    case NodeResourceDataResponse(spec, status) =>
      Json.obj(
        "kind" -> Json.fromString("NODE"),
        "spec" -> Encoder.encodeOption[NodeSpecResponse].apply(spec),
        "status" -> Encoder.encodeOption[NodeStatusResponse].apply(status)
      )
    case ContainerResourceDataResponse(spec, status) =>
      Json.obj(
        "kind" -> Json.fromString("CONTAINER"),
        "spec" -> Encoder.encodeOption[ContainerSpecResponse].apply(spec),
        "status" -> Encoder.encodeOption[ContainerStatusResponse].apply(status)
      )
  }

  implicit val resourceResponseEncoder: Encoder[ResourceResponse] =
    Encoder.forProduct12(
      "id",
      "organizationId",
      "environmentId",
      "resourceTypeId",
      "parentResourceId",
      "code",
      "name",
      "resourceTypeCode",
      "active",
      "data",
      "createdAt",
      "updatedAt"
    ) { value =>
      (
        value.id,
        value.organizationId,
        value.environmentId,
        value.resourceTypeId,
        value.parentResourceId,
        value.code,
        value.name,
        value.resourceTypeCode,
        value.active,
        value.data,
        value.createdAt,
        value.updatedAt
      )
    }

  // Declared after the connection scope and history encoders it is built from.
  implicit val overviewNodesResponseEncoder: Encoder[OverviewNodesResponse] =
    Encoder.forProduct3("total", "online", "offline")(v => (v.total, v.online, v.offline))
  implicit val overviewContainersResponseEncoder: Encoder[OverviewContainersResponse] =
    Encoder.forProduct3("total", "running", "stopped")(v => (v.total, v.running, v.stopped))
  implicit val overviewConnectionsResponseEncoder: Encoder[OverviewConnectionsResponse] =
    Encoder.forProduct4("total", "healthy", "failing", "neverSynced")(v =>
      (v.total, v.healthy, v.failing, v.neverSynced))
  implicit val overviewIncidentsResponseEncoder: Encoder[OverviewIncidentsResponse] =
    Encoder.forProduct3("open", "threshold", "noData")(v => (v.open, v.threshold, v.noData))
  implicit val overviewOperationsResponseEncoder: Encoder[OverviewOperationsResponse] =
    Encoder.forProduct2("failed", "unknown")(v => (v.failed, v.unknown))
  implicit val overviewSummaryResponseEncoder: Encoder[OverviewSummaryResponse] =
    Encoder.forProduct5("nodes", "containers", "connections", "incidents", "operations")(v =>
      (v.nodes, v.containers, v.connections, v.incidents, v.operations))
  implicit val overviewResourceResponseEncoder: Encoder[OverviewResourceResponse] =
    Encoder.forProduct4("id", "name", "resourceTypeCode", "environmentId")(v =>
      (v.id, v.name, v.resourceTypeCode, v.environmentId))
  implicit val attentionIncidentResponseEncoder: Encoder[AttentionIncidentResponse] =
    Encoder.forProduct2("reason", "metricCode")(v => (v.reason, v.metricCode))
  implicit val attentionOperationResponseEncoder: Encoder[AttentionOperationResponse] =
    Encoder.forProduct3("operationCode", "errorCode", "errorMessage")(v =>
      (v.operationCode, v.errorCode, v.errorMessage))
  implicit val attentionSyncResponseEncoder: Encoder[AttentionSyncResponse] =
    Encoder.forProduct2("errorCode", "errorMessage")(v => (v.errorCode, v.errorMessage))
  implicit val attentionItemResponseEncoder: Encoder[AttentionItemResponse] =
    Encoder.forProduct9("kind", "priority", "id", "occurredAt", "resource", "connection",
      "incident", "operation", "sync")(v =>
      (v.kind, v.priority, v.id, v.occurredAt, v.resource, v.connection, v.incident, v.operation,
        v.sync))
  implicit val overviewAttentionResponseEncoder: Encoder[OverviewAttentionResponse] =
    Encoder.forProduct2("items", "total")(v => (v.items, v.total))
  implicit val operationsOverviewResponseEncoder: Encoder[OperationsOverviewResponse] =
    Encoder.forProduct5("scope", "summary", "attention", "recentActivity", "operationsHorizonHours")(v =>
      (v.scope, v.summary, v.attention, v.recentActivity, v.operationsHorizonHours))
}

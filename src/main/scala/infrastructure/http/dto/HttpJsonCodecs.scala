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
    Decoder.forProduct3("host", "port", "username")(SshSettingsRequest.apply)
  implicit val passwordCredentialsRequestDecoder: Decoder[PasswordCredentialsRequest] =
    Decoder.forProduct2("type", "password")(PasswordCredentialsRequest.apply)
  implicit val connectionScheduleRequestDecoder: Decoder[ConnectionScheduleRequest] =
    Decoder.forProduct2("enabled", "intervalSeconds")(ConnectionScheduleRequest.apply)
  implicit val saveSshConnectionRequestDecoder: Decoder[SaveSshConnectionRequest] =
    Decoder.forProduct7("connectorType", "code", "name", "scope", "ssh", "credentials", "schedule")(SaveSshConnectionRequest.apply)
  implicit val testSshConnectionRequestDecoder: Decoder[TestSshConnectionRequest] =
    Decoder.forProduct4("host", "port", "username", "credentials")(TestSshConnectionRequest.apply)
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
    Encoder.forProduct5("host", "port", "username", "hostKeyFingerprint", "credentialConfigured") { value =>
      (value.host, value.port, value.username, value.hostKeyFingerprint, value.credentialConfigured)
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
}

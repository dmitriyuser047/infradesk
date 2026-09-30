package ru.bitec.app.ops
package domain.integration

import java.time.Instant
import java.util.UUID

final case class IntegrationConfigProfileBinding(id: UUID, organizationId: UUID, integrationId: UUID,
  inventoryObjectId: UUID, configurationProfileId: UUID, createdByUserId: UUID,
  createdAt: Instant, updatedAt: Instant)

sealed trait IntegrationConfigDeploymentStatus { def code: String }
object IntegrationConfigDeploymentStatus {
  case object Queued extends IntegrationConfigDeploymentStatus { val code = "QUEUED" }
  case object Running extends IntegrationConfigDeploymentStatus { val code = "RUNNING" }
  case object Succeeded extends IntegrationConfigDeploymentStatus { val code = "SUCCEEDED" }
  case object Failed extends IntegrationConfigDeploymentStatus { val code = "FAILED" }
  case object Unknown extends IntegrationConfigDeploymentStatus { val code = "UNKNOWN" }
  val All: List[IntegrationConfigDeploymentStatus] = List(Queued, Running, Succeeded, Failed, Unknown)
  def fromCode(code: String): Either[IllegalArgumentException, IntegrationConfigDeploymentStatus] =
    All.find(_.code == code).toRight(new IllegalArgumentException("Unknown integration config deployment status"))
}

final case class IntegrationConfigDeployment(id: UUID, organizationId: UUID, integrationId: UUID,
  inventoryObjectId: UUID, bindingId: UUID, configurationProfileId: UUID,
  configurationRevisionId: UUID, revisionNumber: Int, requestId: UUID,
  requestedByUserId: UUID, status: IntegrationConfigDeploymentStatus,
  expectedRemoteSha256: String, desiredSha256: String, createdAt: Instant,
  startedAt: Option[Instant] = None, recoverAfterAt: Option[Instant] = None,
  finishedAt: Option[Instant] = None, claimedBy: Option[UUID] = None,
  claimToken: Option[UUID] = None, errorCode: Option[String] = None,
  errorMessage: Option[String] = None)

sealed trait IntegrationConfigStatus { def code: String }
object IntegrationConfigStatus {
  case object Unavailable extends IntegrationConfigStatus { val code = "UNAVAILABLE" }
  case object Deploying extends IntegrationConfigStatus { val code = "DEPLOYING" }
  case object WaitingRefresh extends IntegrationConfigStatus { val code = "WAITING_REFRESH" }
  case object DeploymentFailed extends IntegrationConfigStatus { val code = "DEPLOYMENT_FAILED" }
  case object InSync extends IntegrationConfigStatus { val code = "IN_SYNC" }
  case object LocalChanges extends IntegrationConfigStatus { val code = "LOCAL_CHANGES" }
  case object RemoteDrift extends IntegrationConfigStatus { val code = "REMOTE_DRIFT" }

  def derive(active: Boolean, observedAt: Instant, remoteHash: Option[String], latestHash: String,
    latestSucceededHash: Option[String], latest: Option[IntegrationConfigDeployment]): IntegrationConfigStatus =
    if (!active) Unavailable
    else if (latest.exists(d => d.status == IntegrationConfigDeploymentStatus.Queued ||
      d.status == IntegrationConfigDeploymentStatus.Running)) Deploying
    else if (latest.exists(d => (d.status == IntegrationConfigDeploymentStatus.Succeeded ||
      d.status == IntegrationConfigDeploymentStatus.Unknown) && d.finishedAt.exists(!observedAt.isAfter(_)))) WaitingRefresh
    else if (latest.exists(_.status == IntegrationConfigDeploymentStatus.Failed)) DeploymentFailed
    else if (remoteHash.contains(latestHash)) InSync
    else if (remoteHash.exists(hash => latestSucceededHash.contains(hash))) LocalChanges
    else RemoteDrift
}

package ru.bitec.app.ops
package infrastructure.http.dto

import application.configuration.{ConfigurationDeploymentDetail, ConfigurationDeploymentPreview}
import application.port.{ConfigurationDeploymentListItem, ConfigurationDeploymentSummary}
import domain.configuration._
import io.circe.{Decoder, Encoder, Json}

import java.util.UUID

final case class DeploymentPreviewRequest(expectedAssignmentVersion: Int, connectionId: UUID)
final case class DeploymentRequest(expectedAssignmentVersion: Int, connectionId: UUID,
                                   expectedRemoteSha256: Option[String], expectedRemoteMissing: Boolean,
                                   requestId: UUID, execution: ConfigurationExecutionPolicy,
                                   retryOfDeploymentId: Option[UUID])

/** Wire format of deployments. Nothing here carries file content except the preview diff, which is
  * returned only to the user who asked for it and never stored.
  */
object ConfigurationDeploymentDtos {
  object Codecs {
    import HttpJsonCodecs.{instantEncoder, uuidEncoder}

    implicit val previewRequestDecoder: Decoder[DeploymentPreviewRequest] =
      Decoder.forProduct2("expectedAssignmentVersion", "connectionId")(DeploymentPreviewRequest.apply)

    implicit val activationDecoder: Decoder[ConfigurationActivation] = Decoder.decodeString.emap { code =>
      ConfigurationActivation.fromCode(code).left.map(_.getMessage)
    }
    implicit val validatorDecoder: Decoder[ConfigurationValidator] = Decoder.instance { cursor =>
      for {
        executable <- cursor.get[String]("executable")
        args <- cursor.getOrElse[List[String]]("args")(Nil)
      } yield ConfigurationValidator(executable, args)
    }
    implicit val policyDecoder: Decoder[ConfigurationExecutionPolicy] = Decoder.instance { cursor =>
      for {
        activation <- cursor.getOrElse[ConfigurationActivation]("activation")(ConfigurationActivation.None)
        unit <- cursor.get[Option[String]]("unitName")
        validator <- cursor.get[Option[ConfigurationValidator]]("validator")
        mode <- cursor.getOrElse[Int]("newFileMode")(ConfigurationExecutionPolicy.DefaultFileMode)
      } yield ConfigurationExecutionPolicy(activation, unit.filter(_.nonEmpty), validator, mode)
    }
    implicit val requestDecoder: Decoder[DeploymentRequest] = Decoder.instance { cursor =>
      for {
        version <- cursor.get[Int]("expectedAssignmentVersion")
        connection <- cursor.get[UUID]("connectionId")
        hash <- cursor.get[Option[String]]("expectedRemoteSha256")
        missing <- cursor.getOrElse[Boolean]("expectedRemoteMissing")(false)
        requestId <- cursor.get[UUID]("requestId")
        execution <- cursor.getOrElse[ConfigurationExecutionPolicy]("execution")(ConfigurationExecutionPolicy.Default)
        retryOf <- cursor.get[Option[UUID]]("retryOfDeploymentId")
      } yield DeploymentRequest(version, connection, hash, missing, requestId, execution, retryOf)
    }

    implicit val diffEncoder: Encoder[ConfigurationDiff] = Encoder.instance { diff =>
      Json.obj("text" -> Json.fromString(diff.text),
        "truncated" -> Json.fromBoolean(diff.truncated),
        "approximate" -> Json.fromBoolean(diff.approximate),
        "addedLines" -> Json.fromInt(diff.addedLines),
        "removedLines" -> Json.fromInt(diff.removedLines))
    }

    implicit val previewEncoder: Encoder[ConfigurationDeploymentPreview] = Encoder.instance { value =>
      Json.obj(
        "assignmentVersion" -> Json.fromInt(value.assignmentVersion),
        "profileRevisionNumber" -> Json.fromInt(value.profileRevisionNumber),
        "targetPath" -> Json.fromString(value.targetPath),
        "remote" -> Json.obj("exists" -> Json.fromBoolean(value.remoteExists),
          "sha256" -> value.remoteSha256.fold(Json.Null)(Json.fromString),
          "text" -> Json.fromBoolean(value.remoteText)),
        "desired" -> Json.obj("sha256" -> Json.fromString(value.desiredSha256)),
        "changed" -> Json.fromBoolean(value.changed),
        "atomicReplaceSupported" -> Json.fromBoolean(value.atomicReplaceSupported),
        "diff" -> diffEncoder(value.diff),
        "connection" -> Json.obj("id" -> uuidEncoder(value.connectionId),
          "name" -> Json.fromString(value.connectionName),
          "updatedAt" -> instantEncoder(value.connectionUpdatedAt))
      )
    }

    private def policyJson(policy: ConfigurationExecutionPolicy): Json = Json.obj(
      "activation" -> Json.fromString(policy.activation.code),
      "unitName" -> policy.unitName.fold(Json.Null)(Json.fromString),
      "validator" -> policy.validator.fold(Json.Null)(validator => Json.obj(
        "executable" -> Json.fromString(validator.executable),
        "args" -> Json.arr(validator.args.map(Json.fromString): _*))),
      "newFileMode" -> Json.fromInt(policy.newFileMode))

    def deploymentJson(item: ConfigurationDeploymentListItem): Json = {
      val value = item.deployment
      Json.obj(
        "id" -> uuidEncoder(value.id),
        "assignmentId" -> uuidEncoder(value.assignmentId),
        "assignmentVersion" -> Json.fromInt(value.assignmentVersion),
        "resource" -> Json.obj("id" -> uuidEncoder(value.resourceId), "name" -> Json.fromString(item.resourceName)),
        "profileId" -> uuidEncoder(value.profileId),
        "profileRevisionNumber" -> Json.fromInt(value.profileRevisionNumber),
        "targetPath" -> Json.fromString(value.targetPath),
        "connection" -> Json.obj("id" -> uuidEncoder(value.connectionId), "name" -> Json.fromString(item.connectionName)),
        "desiredSha256" -> Json.fromString(value.desiredSha256),
        "expectedRemoteSha256" -> (value.expectedRemoteState match {
          case ExpectedRemoteState.Sha256(hash) => Json.fromString(hash)
          case ExpectedRemoteState.Missing => Json.Null
        }),
        "expectedRemoteMissing" -> Json.fromBoolean(value.expectedRemoteState == ExpectedRemoteState.Missing),
        "execution" -> policyJson(value.policy),
        "state" -> Json.fromString(value.state.code),
        "phase" -> Json.fromString(value.phase.code),
        "rollbackFromPhase" -> value.rollbackFromPhase.fold(Json.Null)(phase => Json.fromString(phase.code)),
        "failureCode" -> value.failureCode.fold(Json.Null)(Json.fromString),
        "cancelRequested" -> Json.fromBoolean(value.cancelRequested),
        "backupRetained" -> Json.fromBoolean(value.backupRetained),
        "actor" -> Json.obj("id" -> uuidEncoder(value.actorUserId), "name" -> Json.fromString(item.actorName)),
        "createdAt" -> instantEncoder(value.createdAt),
        "startedAt" -> value.startedAt.fold(Json.Null)(instantEncoder(_)),
        "finishedAt" -> value.finishedAt.fold(Json.Null)(instantEncoder(_)),
        "rolloutId" -> value.rolloutId.fold(Json.Null)(uuidEncoder(_)),
        "retryOfDeploymentId" -> value.retryOfDeploymentId.fold(Json.Null)(uuidEncoder(_))
      )
    }

    implicit val listItemEncoder: Encoder[ConfigurationDeploymentListItem] = Encoder.instance(deploymentJson)

    implicit val detailEncoder: Encoder[ConfigurationDeploymentDetail] = Encoder.instance { detail =>
      deploymentJson(detail.item).deepMerge(Json.obj("events" -> Json.arr(detail.events.map { event =>
        Json.obj("sequence" -> Json.fromInt(event.sequence), "type" -> Json.fromString(event.eventType),
          "occurredAt" -> instantEncoder(event.occurredAt))
      }: _*)))
    }

    implicit val summaryEncoder: Encoder[ConfigurationDeploymentSummary] = Encoder.instance { summary =>
      Json.obj(
        "assignmentId" -> uuidEncoder(summary.assignmentId),
        // A historical fact about what InfraDesk applied; never a claim about the file as it is now.
        "lastSuccessfulDeployment" -> summary.lastSucceeded.fold(Json.Null) { case (id, revision, sha, finished) =>
          Json.obj("deploymentId" -> uuidEncoder(id), "revision" -> Json.fromInt(revision),
            "desiredSha256" -> Json.fromString(sha), "finishedAt" -> instantEncoder(finished))
        },
        "activeDeployment" -> summary.active.fold(Json.Null) { case (id, state, phase) =>
          Json.obj("deploymentId" -> uuidEncoder(id), "state" -> Json.fromString(state.code),
            "phase" -> Json.fromString(phase.code))
        })
    }
  }
}

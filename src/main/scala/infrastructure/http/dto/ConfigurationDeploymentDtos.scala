package ru.bitec.app.ops
package infrastructure.http.dto

import application.configuration.{ConfigurationDeploymentPreview, ConfigurationLineDiff}
import domain.configuration._
import io.circe.{Decoder, Encoder, Json}

import java.util.UUID

final case class DeploymentPreviewRequest(expectedAssignmentVersion: Int, connectionId: UUID)
final case class DeploymentRequest(expectedAssignmentVersion: Int, connectionId: UUID,
                                   expectedRemoteSha256: Option[String], expectedRemoteMissing: Boolean,
                                   requestId: UUID, execution: ConfigurationExecutionPolicy)

object ConfigurationDeploymentDtos {
  object Codecs {
    import HttpJsonCodecs.{instantEncoder, uuidEncoder}

    implicit val previewRequestDecoder: Decoder[DeploymentPreviewRequest] =
      Decoder.forProduct2("expectedAssignmentVersion", "connectionId")(DeploymentPreviewRequest.apply)

    implicit val activationDecoder: Decoder[ConfigurationActivation] = Decoder.decodeString.emap { code =>
      ConfigurationActivation.fromCode(code).left.map(_.getMessage)
    }
    implicit val validatorDecoder: Decoder[ConfigurationValidator] =
      Decoder.forProduct2("executable", "args")(ConfigurationValidator.apply)
    implicit val policyDecoder: Decoder[ConfigurationExecutionPolicy] = Decoder.instance { cursor =>
      for {
        activation <- cursor.get[ConfigurationActivation]("activation")
        unit <- cursor.get[Option[String]]("unitName")
        validator <- cursor.get[Option[ConfigurationValidator]]("validator")
        mode <- cursor.getOrElse[Int]("newFileMode")(420)
      } yield ConfigurationExecutionPolicy(activation, unit, validator, mode)
    }
    implicit val requestDecoder: Decoder[DeploymentRequest] = Decoder.instance { cursor =>
      for {
        version <- cursor.get[Int]("expectedAssignmentVersion")
        connection <- cursor.get[UUID]("connectionId")
        hash <- cursor.get[Option[String]]("expectedRemoteSha256")
        missing <- cursor.get[Boolean]("expectedRemoteMissing")
        requestId <- cursor.get[UUID]("requestId")
        execution <- cursor.get[ConfigurationExecutionPolicy]("execution")
      } yield DeploymentRequest(version, connection, hash, missing, requestId, execution)
    }

    implicit val previewEncoder: Encoder[ConfigurationDeploymentPreview] = Encoder.instance { value =>
      Json.obj(
        "assignmentVersion" -> Json.fromInt(value.assignmentVersion),
        "profileRevisionNumber" -> Json.fromInt(value.profileRevisionNumber),
        "targetPath" -> Json.fromString(value.targetPath),
        "remote" -> Json.obj("exists" -> Json.fromBoolean(value.remoteExists),
          "sha256" -> value.remoteSha256.fold(Json.Null)(Json.fromString)),
        "desired" -> Json.obj("sha256" -> Json.fromString(value.desiredSha256)),
        "changed" -> Json.fromBoolean(value.changed),
        "diff" -> Json.obj("text" -> Json.fromString(value.diff.text),
          "truncated" -> Json.fromBoolean(value.diff.truncated),
          "addedLines" -> Json.fromInt(value.diff.addedLines),
          "removedLines" -> Json.fromInt(value.diff.removedLines)),
        "connection" -> Json.obj("id" -> uuidEncoder(value.connectionId),
          "name" -> Json.fromString(value.connectionName),
          "updatedAt" -> instantEncoder(value.connectionUpdatedAt))
      )
    }

    implicit val deploymentEncoder: Encoder[ConfigurationDeployment] = Encoder.instance { value =>
      Json.obj(
        "id" -> uuidEncoder(value.id),
        "assignmentId" -> uuidEncoder(value.assignmentId),
        "assignmentVersion" -> Json.fromInt(value.assignmentVersion),
        "resourceId" -> uuidEncoder(value.resourceId),
        "profileId" -> uuidEncoder(value.profileId),
        "profileRevisionNumber" -> Json.fromInt(value.profileRevisionNumber),
        "targetPath" -> Json.fromString(value.targetPath),
        "connectionId" -> uuidEncoder(value.connectionId),
        "desiredSha256" -> Json.fromString(value.desiredSha256),
        "expectedRemoteSha256" -> (value.expectedRemoteState match {
          case ExpectedRemoteState.Sha256(hash) => Json.fromString(hash)
          case ExpectedRemoteState.Missing => Json.Null
        }),
        "state" -> Json.fromString(value.state.code),
        "phase" -> Json.fromString(value.phase.code),
        "failureCode" -> value.failureCode.fold(Json.Null)(Json.fromString),
        "createdAt" -> instantEncoder(value.createdAt),
        "startedAt" -> value.startedAt.fold(Json.Null)(instantEncoder(_)),
        "finishedAt" -> value.finishedAt.fold(Json.Null)(instantEncoder(_)),
        "rolloutId" -> value.rolloutId.fold(Json.Null)(uuidEncoder(_))
      )
    }
  }
}

package ru.bitec.app.ops
package infrastructure.http.dto

import application.configuration.{ConfigurationAssignmentDetail, ConfigurationAssignmentPreview}
import application.port.ConfigurationAssignmentListItem
import io.circe.{Decoder, Encoder, Json}

import java.util.UUID

final case class CreateConfigurationAssignmentRequest(
  resourceId: UUID,
  profileId: UUID,
  profileRevisionNumber: Int,
  targetPath: String,
  values: List[ConfigurationValueRequest]
)

/** The whole new desired state, and the version it was based on. */
final case class UpdateConfigurationAssignmentRequest(
  expectedVersion: Int,
  profileRevisionNumber: Int,
  targetPath: String,
  values: List[ConfigurationValueRequest]
)

final case class PreviewConfigurationAssignmentRequest(
  profileId: UUID,
  profileRevisionNumber: Int,
  values: List[ConfigurationValueRequest]
)

object ConfigurationAssignmentDtos {

  object Codecs {
    import ConfigurationDtos.Codecs.{actorEncoder, definitionEncoder, renderErrorEncoder, valueRequestDecoder}
    import HttpJsonCodecs.{instantEncoder, uuidEncoder}

    implicit val createDecoder: Decoder[CreateConfigurationAssignmentRequest] =
      Decoder.forProduct5("resourceId", "profileId", "profileRevisionNumber", "targetPath", "values")(
        CreateConfigurationAssignmentRequest.apply)
    implicit val updateDecoder: Decoder[UpdateConfigurationAssignmentRequest] =
      Decoder.forProduct4("expectedVersion", "profileRevisionNumber", "targetPath", "values")(
        UpdateConfigurationAssignmentRequest.apply)
    implicit val previewDecoder: Decoder[PreviewConfigurationAssignmentRequest] =
      Decoder.forProduct3("profileId", "profileRevisionNumber", "values")(PreviewConfigurationAssignmentRequest.apply)

    /** An assignment with its context. Never a template, a value or rendered text. */
    implicit val listItemEncoder: Encoder[ConfigurationAssignmentListItem] = Encoder.instance { value =>
      val assignment = value.assignment
      val resource = value.resource
      val profile = value.profile
      Json.obj(
        "id" -> uuidEncoder(assignment.id),
        "version" -> Json.fromInt(assignment.version),
        "targetPath" -> Json.fromString(assignment.targetPath),
        "profileRevisionNumber" -> Json.fromInt(assignment.profileRevisionNumber),
        "removedAt" -> assignment.removedAt.fold(Json.Null)(instantEncoder(_)),
        "createdAt" -> instantEncoder(assignment.createdAt),
        "updatedAt" -> instantEncoder(assignment.updatedAt),
        "resource" -> Json.obj(
          "id" -> uuidEncoder(resource.id),
          "name" -> Json.fromString(resource.name),
          "code" -> Json.fromString(resource.code),
          "resourceTypeCode" -> Json.fromString(resource.resourceTypeCode),
          "active" -> Json.fromBoolean(resource.active),
          "project" -> Json.obj("id" -> uuidEncoder(resource.project.id), "name" -> Json.fromString(resource.project.name)),
          "environment" -> Json.obj(
            "id" -> uuidEncoder(resource.environment.id),
            "name" -> Json.fromString(resource.environment.name),
            "kind" -> Json.fromString(resource.environment.kind))
        ),
        "profile" -> Json.obj(
          "id" -> uuidEncoder(profile.id),
          "code" -> Json.fromString(profile.code),
          "name" -> Json.fromString(profile.name),
          "archived" -> Json.fromBoolean(profile.archived),
          "latestRevisionNumber" -> Json.fromInt(profile.latestRevisionNumber))
      )
    }

    /** What the editor needs: the pinned revision's definitions (not its template) and the explicit
      * values. Defaults stay in the definitions; they are never presented as values.
      */
    implicit val detailEncoder: Encoder[ConfigurationAssignmentDetail] = Encoder.instance { value =>
      listItemEncoder(value.item).deepMerge(Json.obj(
        "revision" -> Json.obj(
          "revisionNumber" -> Json.fromInt(value.revision.revision.revisionNumber),
          "variables" -> Encoder.encodeList(definitionEncoder)(value.revision.revision.variables),
          "createdBy" -> actorEncoder(value.revision.createdBy),
          "createdAt" -> instantEncoder(value.revision.revision.createdAt)),
        "values" -> Json.arr(value.values.map(item =>
          Json.obj("name" -> Json.fromString(item.name), "value" -> Json.fromString(item.value))): _*)
      ))
    }

    implicit val previewEncoder: Encoder[ConfigurationAssignmentPreview] = Encoder.instance { value =>
      Json.obj(
        "valid" -> Json.fromBoolean(value.rendered.isRight),
        "resolvedRevisionNumber" -> Json.fromInt(value.revisionNumber),
        "renderedPreview" -> value.rendered.fold(_ => Json.Null, Json.fromString),
        "error" -> value.rendered.fold(renderErrorEncoder(_), _ => Json.Null)
      )
    }
  }
}

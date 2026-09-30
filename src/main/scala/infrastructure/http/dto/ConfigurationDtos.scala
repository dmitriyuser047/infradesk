package ru.bitec.app.ops
package infrastructure.http.dto

import application.configuration.ConfigurationProfileDetail
import application.port.{ConfigurationActor, ConfigurationProfileSummary, ConfigurationRevisionSummary, ConfigurationRevisionView}
import cats.syntax.all._
import domain.configuration.{
  ConfigurationDiagnostic,
  ConfigurationProfile,
  ConfigurationRenderError,
  ConfigurationValueType,
  ConfigurationVariableDefinition,
  ConfigurationVariableValue
}
import io.circe.{Decoder, Encoder, Json}

import java.time.Instant
import java.util.UUID

final case class ConfigurationVariableRequest(
  name: String,
  `type`: String,
  required: Option[Boolean],
  defaultValue: Option[String],
  description: Option[String]
)

final case class ConfigurationValueRequest(name: String, value: String)

final case class CreateConfigurationProfileRequest(
  code: String,
  name: String,
  description: Option[String],
  template: String,
  variables: List[ConfigurationVariableRequest]
)

final case class UpdateConfigurationProfileRequest(name: String, description: Option[String])

final case class CreateConfigurationRevisionRequest(template: String, variables: List[ConfigurationVariableRequest])

final case class ValidateConfigurationRequest(
  template: String,
  variables: List[ConfigurationVariableRequest],
  previewValues: Option[List[ConfigurationValueRequest]]
)

/** What the validation endpoint answers: whether the content can be saved, what it references,
  * every finding, and — when every referenced variable resolves — the rendered text.
  */
final case class ConfigurationValidationResponse(
  valid: Boolean,
  referencedVariables: List[String],
  diagnostics: List[ConfigurationDiagnostic],
  renderedPreview: Option[String],
  previewError: Option[ConfigurationRenderError]
)

object ConfigurationDtos {

  /** A variable definition as the wire says it; an unknown type is a bad request, not a guess. */
  def definition(request: ConfigurationVariableRequest): Either[IllegalArgumentException, ConfigurationVariableDefinition] =
    ConfigurationValueType.fromCode(request.`type`).map(valueType =>
      ConfigurationVariableDefinition(request.name, valueType, request.required.getOrElse(true),
        request.defaultValue, request.description.map(_.trim).filter(_.nonEmpty)))

  def definitions(requests: List[ConfigurationVariableRequest]): Either[IllegalArgumentException, List[ConfigurationVariableDefinition]] =
    requests.traverse(definition)

  def values(requests: List[ConfigurationValueRequest]): List[ConfigurationVariableValue] =
    requests.map(request => ConfigurationVariableValue(request.name, request.value))

  object Codecs {
    import HttpJsonCodecs.{instantEncoder, uuidEncoder}

    implicit val variableRequestDecoder: Decoder[ConfigurationVariableRequest] =
      Decoder.forProduct5("name", "type", "required", "defaultValue", "description")(ConfigurationVariableRequest.apply)
    implicit val valueRequestDecoder: Decoder[ConfigurationValueRequest] =
      Decoder.forProduct2("name", "value")(ConfigurationValueRequest.apply)
    implicit val createProfileDecoder: Decoder[CreateConfigurationProfileRequest] =
      Decoder.forProduct5("code", "name", "description", "template", "variables")(CreateConfigurationProfileRequest.apply)
    implicit val updateProfileDecoder: Decoder[UpdateConfigurationProfileRequest] =
      Decoder.forProduct2("name", "description")(UpdateConfigurationProfileRequest.apply)
    implicit val createRevisionDecoder: Decoder[CreateConfigurationRevisionRequest] =
      Decoder.forProduct2("template", "variables")(CreateConfigurationRevisionRequest.apply)
    implicit val validateDecoder: Decoder[ValidateConfigurationRequest] =
      Decoder.forProduct3("template", "variables", "previewValues")(ValidateConfigurationRequest.apply)

    implicit val actorEncoder: Encoder[ConfigurationActor] =
      Encoder.forProduct2("id", "displayName")(value => (value.id, value.displayName))

    implicit val definitionEncoder: Encoder[ConfigurationVariableDefinition] =
      Encoder.forProduct5("name", "type", "required", "defaultValue", "description")(value =>
        (value.name, value.valueType.code, value.required, value.defaultValue, value.description))

    implicit val diagnosticEncoder: Encoder[ConfigurationDiagnostic] =
      Encoder.forProduct5("code", "severity", "variableName", "line", "column")(value =>
        (value.code, value.severity.code, value.variableName, value.line, value.column))

    implicit val renderErrorEncoder: Encoder[ConfigurationRenderError] =
      Encoder.forProduct2("code", "variableName")(value => (value.code, value.variableName))

    implicit val validationEncoder: Encoder[ConfigurationValidationResponse] =
      Encoder.forProduct5("valid", "referencedVariables", "diagnostics", "renderedPreview", "previewError")(value =>
        (value.valid, value.referencedVariables, value.diagnostics, value.renderedPreview, value.previewError))

    /** A profile's metadata; content is never part of it. */
    def profileJson(profile: ConfigurationProfile, latestRevisionCreatedAt: Option[Instant]): Json =
      Json.obj(
        "id" -> uuidEncoder(profile.id),
        "code" -> Json.fromString(profile.code),
        "name" -> Json.fromString(profile.name),
        "description" -> profile.description.fold(Json.Null)(Json.fromString),
        "kind" -> Json.fromString(profile.kind.code),
        "archived" -> Json.fromBoolean(profile.archived),
        "latestRevisionNumber" -> Json.fromInt(profile.latestRevisionNumber),
        "latestRevisionCreatedAt" -> latestRevisionCreatedAt.fold(Json.Null)(instantEncoder(_)),
        "createdAt" -> instantEncoder(profile.createdAt),
        "updatedAt" -> instantEncoder(profile.updatedAt)
      )

    implicit val summaryEncoder: Encoder[ConfigurationProfileSummary] =
      Encoder.instance(value => profileJson(value.profile, Some(value.latestRevisionCreatedAt)))

    implicit val revisionSummaryEncoder: Encoder[ConfigurationRevisionSummary] =
      Encoder.forProduct4("revisionNumber", "variableCount", "createdBy", "createdAt")(value =>
        (value.revisionNumber, value.variableCount, value.createdBy, value.createdAt))

    implicit val revisionEncoder: Encoder[ConfigurationRevisionView] = Encoder.instance { value =>
      Json.obj(
        "revisionNumber" -> Json.fromInt(value.revision.revisionNumber),
        "template" -> Json.fromString(value.revision.template),
        "variables" -> Encoder.encodeList(definitionEncoder)(value.revision.variables),
        "createdBy" -> actorEncoder(value.createdBy),
        "createdAt" -> instantEncoder(value.revision.createdAt)
      )
    }

    implicit val detailEncoder: Encoder[ConfigurationProfileDetail] = Encoder.instance { value =>
      Json.obj(
        "profile" -> profileJson(value.profile, Some(value.latestRevision.revision.createdAt)),
        "latestRevision" -> revisionEncoder(value.latestRevision)
      )
    }

    def uuidJson(value: UUID): Json = uuidEncoder(value)
  }
}

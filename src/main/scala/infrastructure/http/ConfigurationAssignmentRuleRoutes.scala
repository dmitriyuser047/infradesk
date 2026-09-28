package ru.bitec.app.ops
package infrastructure.http

import application.configuration._
import application.port.{ConfigurationAssignmentRuleListItem, ConfigurationPromotionSelection, ResourceLabelSet}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.configuration._
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.{Decoder, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Assignment rules and resource labels. Desired state only: every route needs the configuration
  * capability and none reaches a server, so none needs DEPLOY_CONFIGURATIONS. Labels, selectors and
  * values are never logged.
  */
final class ConfigurationAssignmentRuleRoutes[Tx[_]](
  rules: ConfigurationAssignmentRules[IO, Tx],
  labels: ResourceLabels[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._
  import ConfigurationAssignmentRuleRoutes._

  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid configuration rule request")
  private val MaxBodyBytes = 256 * 1024
  private val base = "configuration-assignment-rules"

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / id / "labels" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { context => withId(id) { resourceId =>
        respond(request, context, "resource.labels.get")(labels.get(context.organizationId, resourceId).flatMap(set => Ok(labelsJson(set))))
      } }

    case request @ PUT -> Root / "api" / "v1" / "organizations" / _ / "resources" / id / "labels" =>
      manage(request) { context => withId(id) { resourceId => body[LabelsRequest](request) { input =>
        respond(request, context, "resource.labels.replace")(
          labels.replace(context.actor, resourceId, input.expectedVersion, input.labels).flatMap(set => Ok(labelsJson(set))))
      } } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / `base` =>
      manage(request) { context =>
        val params = request.uri.query.params
        val profile = params.get("profileId").traverse(value => Try(UUID.fromString(value)).toOption)
        val cursor = (params.get("afterCreatedAt"), params.get("afterId")) match {
          case (None, None) => Some(None)
          case (Some(at), Some(id)) => (Try(Instant.parse(at)).toOption, Try(UUID.fromString(id)).toOption).mapN((a, b) => Some(a -> b))
          case _ => None
        }
        val limit = params.get("limit").fold[Option[Int]](Some(DefaultLimit))(v => Try(v.toInt).toOption.filter(n => n >= 1 && n <= MaxLimit))
        val archived = params.get("includeArchived").contains("true")
        (profile, cursor, limit) match {
          case (Some(p), Some(c), Some(n)) => respond(request, context, "configuration.rule.list")(
            rules.list(context.organizationId, p, archived, c, n + 1).flatMap { rows =>
              Ok(Json.obj("items" -> Json.arr(rows.take(n).map(ruleJson): _*),
                "nextCursor" -> rows.lift(n - 1).filter(_ => rows.size > n).fold(Json.Null)(last => Json.obj(
                  "afterCreatedAt" -> instantEncoder(last.rule.createdAt), "afterId" -> Json.fromString(last.rule.id.toString)))))
            })
          case _ => BadRequest(invalid)
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` =>
      manage(request) { context => body[CreateRequest](request) { input =>
        respond(request, context, "configuration.rule.create")(rules.create(context.actor, input.draft)
          .flatMap(id => rules.detail(context.organizationId, id)).flatMap(item => Created(ruleJson(item))))
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` / "selector-preview" =>
      manage(request) { context => body[PreviewRequest](request) { input =>
        respond(request, context, "configuration.rule.preview")(rules.previewSelector(context.organizationId,
          input.profileId, input.revisionNumber, input.targetPath, input.selector, PreviewLimit).flatMap(preview => Ok(Json.obj(
          "matchedCount" -> Json.fromInt(preview.matched),
          "eligibleCount" -> Json.fromInt(preview.eligible),
          "needsValuesCount" -> Json.fromInt(preview.needsValues),
          "conflictCount" -> Json.fromInt(preview.manualConflicts + preview.ruleConflicts),
          "manualConflictCount" -> Json.fromInt(preview.manualConflicts),
          "ruleConflictCount" -> Json.fromInt(preview.ruleConflicts),
          "missingVariable" -> preview.missingVariable.fold(Json.Null)(Json.fromString),
          "items" -> Json.arr(preview.items.map { case (candidate, state) => Json.obj(
            "resourceId" -> Json.fromString(candidate.resourceId.toString),
            "resourceName" -> Json.fromString(candidate.resourceName),
            "environmentName" -> Json.fromString(candidate.environmentName),
            "projectName" -> Json.fromString(candidate.projectName),
            "state" -> Json.fromString(state),
            "assignmentId" -> candidate.occupantAssignmentId.fold(Json.Null)(id => Json.fromString(id.toString))) }: _*)))))
      } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / `base` / id =>
      manage(request) { context => withId(id) { ruleId =>
        respond(request, context, "configuration.rule.detail")(rules.detail(context.organizationId, ruleId).flatMap(item => Ok(ruleJson(item))))
      } }

    case request @ PATCH -> Root / "api" / "v1" / "organizations" / _ / `base` / id =>
      manage(request) { context => withId(id) { ruleId => body[UpdateRequest](request) { input =>
        respond(request, context, "configuration.rule.update")(rules.update(context.actor, ruleId, input.expectedVersion,
          input.name, input.description, input.selector) *> rules.detail(context.organizationId, ruleId).flatMap(item => Ok(ruleJson(item))))
      } } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` / id / action
      if action == "enable" || action == "disable" =>
      manage(request) { context => withId(id) { ruleId => body[VersionRequest](request) { input =>
        respond(request, context, s"configuration.rule.$action")(rules.setEnabled(context.actor, ruleId,
          input.expectedVersion, action == "enable") *> rules.detail(context.organizationId, ruleId).flatMap(item => Ok(ruleJson(item))))
      } } }

    case request @ DELETE -> Root / "api" / "v1" / "organizations" / _ / `base` / id =>
      manage(request) { context => withId(id) { ruleId =>
        request.uri.query.params.get("expectedVersion").flatMap(v => Try(v.toInt).toOption) match {
          case Some(version) => respond(request, context, "configuration.rule.archive")(
            rules.archive(context.actor, ruleId, version) *> rules.detail(context.organizationId, ruleId).flatMap(item => Ok(ruleJson(item))))
          case None => BadRequest(invalid)
        }
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` / id / "reconcile" =>
      manage(request) { context => withId(id) { ruleId =>
        respond(request, context, "configuration.rule.reconcile")(
          rules.reconcileNow(context.actor, ruleId) *> Accepted(Json.obj("scheduled" -> Json.True)))
      } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / `base` / id / "targets" =>
      manage(request) { context => withId(id) { ruleId =>
        val params = request.uri.query.params
        val cursor = (params.get("afterName"), params.get("afterId")) match {
          case (None, None) => Some(None)
          case (Some(name), Some(after)) => Try(UUID.fromString(after)).toOption.map(uuid => Some(name -> uuid))
          case _ => None
        }
        val limit = params.get("limit").fold[Option[Int]](Some(DefaultLimit))(v => Try(v.toInt).toOption.filter(n => n >= 1 && n <= MaxLimit))
        (cursor, limit) match {
          case (Some(c), Some(n)) => respond(request, context, "configuration.rule.targets")(
            rules.targets(context.organizationId, ruleId, c, n + 1).flatMap { case (rule, rows) =>
              Ok(Json.obj("items" -> Json.arr(rows.take(n).map(targetJson(rule)): _*),
                "nextCursor" -> rows.lift(n - 1).filter(_ => rows.size > n).fold(Json.Null)(last => Json.obj(
                  "afterName" -> Json.fromString(last.resourceName), "afterId" -> Json.fromString(last.resourceId.toString)))))
            })
          case _ => BadRequest(invalid)
        }
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` / id / "exclude" / resource =>
      manage(request) { context => withId(id) { ruleId => withId(resource) { resourceId =>
        respond(request, context, "configuration.rule.exclude")(rules.exclude(context.actor, ruleId, resourceId) *> NoContent())
      } } }

    case request @ DELETE -> Root / "api" / "v1" / "organizations" / _ / `base` / id / "exclude" / resource =>
      manage(request) { context => withId(id) { ruleId => withId(resource) { resourceId =>
        respond(request, context, "configuration.rule.include")(rules.include(context.actor, ruleId, resourceId) *> NoContent())
      } } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` / id / "targets" / resource / "assignment" =>
      manage(request) { context => withId(id) { ruleId => withId(resource) { resourceId => body[ValuesRequest](request) { input =>
        respond(request, context, "configuration.rule.complete")(rules.completeAssignment(context.actor, ruleId, resourceId,
          input.values).flatMap(assignmentId => Created(Json.obj("assignmentId" -> Json.fromString(assignmentId.toString)))))
      } } } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` / id / "assignments" / assignment / action
      if action == "detach" || action == "adopt" || action == "exclude-and-remove" =>
      manage(request) { context => withId(id) { ruleId => withId(assignment) { assignmentId => body[VersionRequest](request) { input =>
        val run = action match {
          case "detach" => rules.detach(context.actor, ruleId, assignmentId, input.expectedVersion)
          case "adopt" => rules.adopt(context.actor, ruleId, assignmentId, input.expectedVersion)
          case _ => rules.excludeAndRemove(context.actor, ruleId, assignmentId, input.expectedVersion)
        }
        respond(request, context, s"configuration.rule.assignment.$action")(run *> NoContent())
      } } } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` / id / "promotion-preview" =>
      manage(request) { context => withId(id) { ruleId => body[PromotionPreviewRequest](request) { input =>
        respond(request, context, "configuration.rule.promotion.preview")(rules.promotionPreview(context.organizationId, ruleId,
          input.targetRevisionNumber, input.expectedRuleVersion).flatMap(preview => Ok(Json.obj(
          "ruleVersion" -> Json.fromInt(preview.ruleVersion),
          "fromRevision" -> Json.fromInt(preview.fromRevision),
          "targetRevision" -> Json.fromInt(preview.targetRevision),
          "compatible" -> Json.fromBoolean(preview.compatible),
          "assignmentCount" -> Json.fromInt(preview.items.size),
          "items" -> Json.arr(preview.items.map { item => Json.obj(
            "assignmentId" -> Json.fromString(item.assignmentId.toString),
            "expectedVersion" -> Json.fromInt(item.expectedVersion),
            "resourceName" -> item.resourceName.fold(Json.Null)(Json.fromString),
            "compatible" -> Json.fromBoolean(item.compatible),
            "issues" -> Json.arr(item.issues.map(issue => Json.obj("code" -> Json.fromString(issue.code),
              "variableName" -> issue.variableName.fold(Json.Null)(Json.fromString))): _*)) }: _*)))))
      } } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / `base` / id / "promote" =>
      manage(request) { context => withId(id) { ruleId => body[PromoteRequest](request) { input =>
        respond(request, context, "configuration.rule.promote")(rules.promote(context.actor, ruleId, input.targetRevisionNumber,
          input.expectedRuleVersion, input.assignments).flatMap(versions => Ok(Json.obj(
          "revisionNumber" -> Json.fromInt(input.targetRevisionNumber),
          "assignments" -> Json.arr(versions.map { case (assignmentId, version) =>
            Json.obj("assignmentId" -> Json.fromString(assignmentId.toString), "version" -> Json.fromInt(version)) }: _*)))))
      } } }
  }

  private def manage(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageConfigurations)(next)

  private def withId(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption.fold(BadRequest(invalid))(next)

  private def body[A: Decoder](request: Request[IO])(next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(MaxBodyBytes + 1L).compile.toVector.map(_.toArray).flatMap { bytes =>
      if (bytes.length > MaxBodyBytes) BadRequest(invalid)
      else io.circe.parser.decode[A](new String(bytes, StandardCharsets.UTF_8)).fold(_ => BadRequest(invalid), next)
    }

  private def respond(request: Request[IO], context: OrganizationAccessContext, operation: String)(
    action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case error: ConfigurationRuleError =>
      val body = Json.obj("code" -> Json.fromString(error.code), "message" -> Json.fromString(error.getMessage),
        "variableName" -> error.variableName.fold(Json.Null)(Json.fromString))
      if (NotFoundCodes.contains(error.code)) NotFound(body)
      else if (error.code.startsWith("INVALID") || error.code == "CONFIGURATION_RULE_SELECTOR_INVALID") BadRequest(body)
      else Conflict(body)
    case error: ResourceLabelError => error.code match {
      case "RESOURCE_NOT_FOUND" => NotFound(ApiErrorResponse(error.code, error.message))
      case "RESOURCE_LABELS_CHANGED" => Conflict(ApiErrorResponse(error.code, error.message))
      case _ => BadRequest(ApiErrorResponse(error.code, error.message))
    }
    case error => ReadModelHttp.failedAs(logger, "configuration.rule.failed", request, operation,
      "organizationId" -> context.organizationId, "actorUserId" -> context.user.id)(error)
  }
}

object ConfigurationAssignmentRuleRoutes {
  import HttpJsonCodecs._

  val DefaultLimit = 25
  val MaxLimit = 100
  val PreviewLimit = 50
  private val NotFoundCodes = Set("CONFIGURATION_RULE_NOT_FOUND", "RESOURCE_NOT_FOUND", "CONFIGURATION_ASSIGNMENT_NOT_FOUND",
    ConfigurationError.NotFoundCode, ConfigurationError.RevisionNotFoundCode)

  final case class LabelsRequest(expectedVersion: Int, labels: List[(String, String)])
  final case class CreateRequest(draft: ConfigurationRuleDraft)
  final case class PreviewRequest(profileId: UUID, revisionNumber: Int, targetPath: String, selector: ConfigurationRuleSelector)
  final case class UpdateRequest(expectedVersion: Int, name: String, description: Option[String], selector: ConfigurationRuleSelector)
  final case class VersionRequest(expectedVersion: Int)
  final case class ValuesRequest(values: List[ConfigurationVariableValue])
  final case class PromotionPreviewRequest(targetRevisionNumber: Int, expectedRuleVersion: Int)
  final case class PromoteRequest(targetRevisionNumber: Int, expectedRuleVersion: Int, assignments: List[ConfigurationPromotionSelection])

  private implicit val labelDecoder: Decoder[ResourceLabel] = Decoder.forProduct2("key", "value")(ResourceLabel.apply)
  private implicit val pairDecoder: Decoder[(String, String)] = Decoder.forProduct2("key", "value")((k: String, v: String) => k -> v)
  implicit val selectorDecoder: Decoder[ConfigurationRuleSelector] = Decoder.instance { cursor =>
    for {
      projects <- cursor.getOrElse[List[UUID]]("projects")(Nil)
      environments <- cursor.getOrElse[List[UUID]]("environments")(Nil)
      required <- cursor.getOrElse[List[(String, String)]]("requiredLabels")(Nil)
      excluded <- cursor.getOrElse[List[(String, String)]]("excludedLabels")(Nil)
    } yield ConfigurationRuleSelector(projects, environments,
      // Keys are canonicalized here the same way labels are; the domain validates the rest.
      required.map { case (k, v) => ResourceLabel(k.trim.toLowerCase(java.util.Locale.ROOT), v) },
      excluded.map { case (k, v) => ResourceLabel(k.trim.toLowerCase(java.util.Locale.ROOT), v) })
  }
  implicit val labelsRequestDecoder: Decoder[LabelsRequest] = Decoder.forProduct2("expectedVersion", "labels")(LabelsRequest.apply)
  implicit val createDecoder: Decoder[CreateRequest] = Decoder.instance { c =>
    for {
      code <- c.get[String]("code"); name <- c.get[String]("name"); description <- c.get[Option[String]]("description")
      profileId <- c.get[UUID]("profileId"); revision <- c.get[Int]("profileRevisionNumber")
      path <- c.get[String]("targetPath"); selector <- c.getOrElse[ConfigurationRuleSelector]("selector")(ConfigurationRuleSelector(Nil, Nil, Nil, Nil))
      enabled <- c.getOrElse[Boolean]("enabled")(false)
    } yield CreateRequest(ConfigurationRuleDraft(code, name, description.filter(_.trim.nonEmpty), profileId, revision, path, selector, enabled))
  }
  implicit val previewDecoder: Decoder[PreviewRequest] =
    Decoder.forProduct4("profileId", "revisionNumber", "targetPath", "selector")(PreviewRequest.apply)
  implicit val updateDecoder: Decoder[UpdateRequest] = Decoder.instance { c =>
    for {
      version <- c.get[Int]("expectedVersion"); name <- c.get[String]("name")
      description <- c.get[Option[String]]("description"); selector <- c.get[ConfigurationRuleSelector]("selector")
    } yield UpdateRequest(version, name, description.filter(_.trim.nonEmpty), selector)
  }
  implicit val versionDecoder: Decoder[VersionRequest] = Decoder.forProduct1("expectedVersion")(VersionRequest.apply)
  private implicit val valueDecoder: Decoder[ConfigurationVariableValue] = Decoder.forProduct2("name", "value")(ConfigurationVariableValue.apply)
  implicit val valuesDecoder: Decoder[ValuesRequest] = Decoder.forProduct1("values")(ValuesRequest.apply)
  implicit val promotionPreviewDecoder: Decoder[PromotionPreviewRequest] =
    Decoder.forProduct2("targetRevisionNumber", "expectedRuleVersion")(PromotionPreviewRequest.apply)
  private implicit val selectionDecoder: Decoder[ConfigurationPromotionSelection] =
    Decoder.forProduct2("assignmentId", "expectedVersion")(ConfigurationPromotionSelection.apply)
  implicit val promoteDecoder: Decoder[PromoteRequest] =
    Decoder.forProduct3("targetRevisionNumber", "expectedRuleVersion", "assignments")(PromoteRequest.apply)

  private def labelJson(label: ResourceLabel): Json = Json.obj("key" -> Json.fromString(label.key), "value" -> Json.fromString(label.value))

  def labelsJson(set: ResourceLabelSet): Json =
    Json.obj("version" -> Json.fromInt(set.version), "labels" -> Json.arr(set.labels.map(labelJson): _*))

  def ruleJson(item: ConfigurationAssignmentRuleListItem): Json = {
    val rule = item.rule
    Json.obj(
      "id" -> Json.fromString(rule.id.toString), "code" -> Json.fromString(rule.code), "name" -> Json.fromString(rule.name),
      "description" -> rule.description.fold(Json.Null)(Json.fromString),
      "profile" -> Json.obj("id" -> Json.fromString(rule.profileId.toString), "code" -> Json.fromString(item.profileCode),
        "name" -> Json.fromString(item.profileName), "archived" -> Json.fromBoolean(item.profileArchived),
        "latestRevisionNumber" -> Json.fromInt(item.latestRevisionNumber)),
      "profileRevisionNumber" -> Json.fromInt(rule.profileRevisionNumber),
      "targetPath" -> Json.fromString(rule.targetPath),
      "selector" -> Json.obj(
        "projects" -> Json.arr(rule.selector.projects.map(id => Json.fromString(id.toString)): _*),
        "environments" -> Json.arr(rule.selector.environments.map(id => Json.fromString(id.toString)): _*),
        "requiredLabels" -> Json.arr(rule.selector.requiredLabels.map(labelJson): _*),
        "excludedLabels" -> Json.arr(rule.selector.excludedLabels.map(labelJson): _*)),
      "enabled" -> Json.fromBoolean(rule.enabled), "archived" -> Json.fromBoolean(rule.archived),
      "version" -> Json.fromInt(rule.version),
      "counts" -> Json.obj("matched" -> Json.fromInt(item.matchedCount), "managed" -> Json.fromInt(item.managedCount),
        "issues" -> Json.fromInt(item.issueCount), "excluded" -> Json.fromInt(item.excludedCount)),
      "createdAt" -> instantEncoder(rule.createdAt), "updatedAt" -> instantEncoder(rule.updatedAt),
      "lastReconciledAt" -> rule.lastReconciledAt.fold(Json.Null)(instantEncoder(_)),
      "nextReconcileAt" -> rule.nextReconcileAt.fold(Json.Null)(instantEncoder(_)))
  }

  def targetJson(rule: ConfigurationAssignmentRule)(row: application.port.ConfigurationRuleTargetRow): Json = Json.obj(
    "resource" -> Json.obj("id" -> Json.fromString(row.resourceId.toString), "name" -> Json.fromString(row.resourceName),
      "code" -> Json.fromString(row.resourceCode), "active" -> Json.fromBoolean(row.resourceActive)),
    "project" -> Json.obj("id" -> Json.fromString(row.projectId.toString), "name" -> Json.fromString(row.projectName)),
    "environment" -> Json.obj("id" -> Json.fromString(row.environmentId.toString), "name" -> Json.fromString(row.environmentName)),
    "matchesSelector" -> Json.fromBoolean(row.matches),
    "excluded" -> Json.fromBoolean(row.excluded),
    "assignment" -> row.assignmentId.fold(Json.Null)(id => Json.obj("id" -> Json.fromString(id.toString),
      "version" -> row.assignmentVersion.fold(Json.Null)(Json.fromInt),
      "revision" -> row.assignmentRevision.fold(Json.Null)(Json.fromInt),
      "managedByRule" -> Json.fromBoolean(row.assignmentSourceRuleId.contains(rule.id)),
      "sourceRuleId" -> row.assignmentSourceRuleId.fold(Json.Null)(id => Json.fromString(id.toString)))),
    "status" -> Json.fromString(ConfigurationRuleTargets.status(rule.id, row.matches, row.excluded,
      row.assignmentSourceRuleId, row.issueCode).code),
    "issue" -> row.issueCode.fold(Json.Null)(code => Json.obj("code" -> Json.fromString(code.code),
      "variableName" -> row.issueVariable.fold(Json.Null)(Json.fromString),
      "conflictingAssignmentId" -> row.conflictingAssignmentId.fold(Json.Null)(id => Json.fromString(id.toString)))))
}

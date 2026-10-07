package ru.bitec.app.ops
package infrastructure.http

import application.provisioning.{ProvisioningError,ServerProfileError,ServerProfilePlan,ServerProfiles}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.provisioning._
import infrastructure.http.dto.{ApiErrorResponse,HttpJsonCodecs}
import io.circe.{Json,parser}
import org.http4s.{HttpRoutes,Request,Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

/** Tenant-scoped server profile APIs. Preview and observe are explicit remote operations; list/detail are DB-only. */
final class ServerProfileRoutes[Tx[_]](profiles: ServerProfiles[IO,Tx], authorization: OrganizationAuthorization,
  logger: Logger[IO]) {
  import HttpJsonCodecs._
  private val bad = ApiErrorResponse("INVALID_REQUEST","Invalid server profile request")
  private val bodyLimit = 128 * 1024L
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "server-profiles" =>
      read(request) { ctx =>
        val params=request.uri.query.params
        val archived=params.get("archived").map(_.toBooleanOption).getOrElse(Some(false))
        val limit=params.get("limit").map(_.toIntOption.filter(n => n>=1 && n<=200)).getOrElse(Some(100))
        (archived,limit) match {
          case (Some(a),Some(n)) if params.keySet.subsetOf(Set("archived","limit")) =>
          safe(request,ctx,"server-profile.list") {
            profiles.list(ctx.organizationId,a,n).flatMap { xs =>
              val items = xs.map(x => Json.obj("profile" -> profileJson(x.profile),"assignmentCount" -> Json.fromInt(x.assignments)))
              Ok(Json.obj("items" -> Json.fromValues(items)))
            }
          }
          case _ => BadRequest(bad)
        }
      }
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "server-profiles" =>
      manage(request) { ctx => withBody(request) { json =>
        (fieldString(json,"code"),fieldString(json,"name"),fieldOptionString(json,"description"),json.hcursor.downField("content").focus) match {
          case (Right(code),Right(name),Right(desc),Some(contentJson)) if shape(json,Set("code","name","content"),Set("description")) => ServerProfileContent.parse(contentJson.noSpaces) match {
            case Left(_) => BadRequest(bad)
            case Right(content) => safe(request,ctx,"server-profile.create")(profiles.create(ctx.actor,code,name,desc,content).flatMap { case (p,r) => Created(Json.obj("profile" -> profileJson(p),"revision" -> revisionJson(r))) })
          }
          case _ => BadRequest(bad)
        }
      } }
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "server-profiles" / id =>
      read(request) { ctx => parseId(id).fold[IO[Response[IO]]](BadRequest(bad))(profileId => safe(request,ctx,"server-profile.detail")(profiles.detail(ctx.organizationId,profileId).flatMap { case (p,revs,assignments) =>
        Ok(Json.obj("profile" -> profileJson(p),"revisions" -> Json.fromValues(revs.map(revisionJson)),"assignments" -> Json.fromValues(assignments.map(assignmentViewJson)))) })) }
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "server-profiles" / id / "revisions" =>
      manage(request) { ctx => parseId(id).fold[IO[Response[IO]]](BadRequest(bad))(profileId => withBody(request) { json =>
        json.hcursor.downField("content").focus.flatMap(j => ServerProfileContent.parse(j.noSpaces).toOption) match {
          case None => BadRequest(bad)
          case Some(content) if shape(json,Set("content")) => safe(request,ctx,"server-profile.revision")(profiles.appendRevision(ctx.actor,profileId,content).flatMap(r => Created(revisionJson(r))))
          case _ => BadRequest(bad)
        }
      }) }
    case request @ DELETE -> Root / "api" / "v1" / "organizations" / _ / "server-profiles" / id =>
      manage(request) { ctx => parseId(id).fold[IO[Response[IO]]](BadRequest(bad))(profileId =>
        safe(request,ctx,"server-profile.archive")(profiles.archive(ctx.actor,profileId).flatMap(p => Ok(profileJson(p))))) }
    case request @ PUT -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "server-profile" =>
      manage(request) { ctx => parseId(resource).fold[IO[Response[IO]]](BadRequest(bad)) { resourceId => withBody(request) { j =>
        (fieldString(j,"profileId").toOption.flatMap(parseId),j.hcursor.downField("revisionNumber").focus.flatMap(_.asNumber).flatMap(_.toInt)) match {
          case (Some(profileId),Some(number)) if number>0 && shape(j,Set("profileId","revisionNumber")) => safe(request,ctx,"server-profile.assign")(profiles.assign(ctx.actor,resourceId,profileId,number).flatMap(a => Ok(assignmentJson(a))))
          case _ => BadRequest(bad)
        }
      } } }
    case request @ DELETE -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "server-profile" =>
      manage(request) { ctx => parseId(resource).fold[IO[Response[IO]]](BadRequest(bad))(resourceId => safe(request,ctx,"server-profile.unassign")(profiles.unassign(ctx.actor,resourceId).flatMap(_ => NoContent()))) }
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "server-profile" / "automation" =>
      read(request) { ctx => parseId(resource).fold[IO[Response[IO]]](BadRequest(bad))(resourceId => safe(request,ctx,"server-profile.automation")(profiles.automation(ctx.organizationId,resourceId).flatMap(a => Ok(automationJson(a))))) }
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "server-profile" / "observe" =>
      execute(request) { ctx => parseId(resource).fold[IO[Response[IO]]](BadRequest(bad))(resourceId => safe(request,ctx,"server-profile.observe")(profiles.observe(ctx.actor,resourceId).flatMap(o => Ok(observationJson(o))))) }
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "server-profile" / "preview" =>
      execute(request) { ctx => parseId(resource).fold[IO[Response[IO]]](BadRequest(bad))(resourceId => safe(request,ctx,"server-profile.preview")(profiles.preview(ctx.actor,resourceId).flatMap(p => Ok(planJson(p))))) }
  }

  private def read(req: Request[IO])(f: OrganizationAccessContext => IO[Response[IO]]) = authorization.require(req,OrganizationPermission.ReadOrganization)(f)
  private def manage(req: Request[IO])(f: OrganizationAccessContext => IO[Response[IO]]) = authorization.require(req,OrganizationPermission.ManageConfigurations)(f)
  private def execute(req: Request[IO])(f: OrganizationAccessContext => IO[Response[IO]]) = manage(req)(_ => authorization.require(req,OrganizationPermission.ExecuteOperations)(f))
  private def parseId(s: String): Option[UUID] = Try(UUID.fromString(s)).toOption
  private def withBody(req: Request[IO])(f: Json => IO[Response[IO]]): IO[Response[IO]] = req.body.take(bodyLimit+1).compile.toVector.flatMap { bytes =>
    if(bytes.length>bodyLimit) BadRequest(bad) else parser.parse(new String(bytes.toArray,StandardCharsets.UTF_8)).fold(_ => BadRequest(bad),f)
  }
  private def fieldString(j: Json,key: String): Either[Unit,String] = j.hcursor.get[String](key).leftMap(_ => ())
  private def shape(j: Json,required: Set[String],optional: Set[String]=Set.empty): Boolean =
    j.asObject.exists(o => required.subsetOf(o.keys.toSet) && o.keys.toSet.subsetOf(required ++ optional))
  private def fieldOptionString(j: Json,key: String): Either[Unit,Option[String]] = j.hcursor.get[Option[String]](key).leftMap(_ => ())
  private def safe(req: Request[IO],ctx: OrganizationAccessContext,op: String)(action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case e: ServerProfileError =>
      val response = ApiErrorResponse(e.code,e.getMessage)
      e.code match {
        case "SERVER_PROFILE_NOT_FOUND" | "SERVER_PROFILE_REVISION_NOT_FOUND" | "SERVER_PROFILE_RESOURCE_NOT_FOUND" => NotFound(response)
        case "PROVISIONING_DISABLED" => ServiceUnavailable(response)
        case _ => Conflict(response)
      }
    case e: ProvisioningError => Conflict(ApiErrorResponse(e.code,e.getMessage))
    case e => ReadModelHttp.failedAs(logger,"server_profile.request_failed",req,op,"organizationId" -> ctx.organizationId,"actorUserId" -> ctx.user.id)(e)
  }
  private def profileJson(p: ServerProfile) = Json.obj("id" -> Json.fromString(p.id.toString),"organizationId" -> Json.fromString(p.organizationId.toString),"code" -> Json.fromString(p.code),"name" -> Json.fromString(p.name),"description" -> p.description.fold(Json.Null)(Json.fromString),"archived" -> Json.fromBoolean(p.archived),"latestRevision" -> Json.fromInt(p.latestRevision),"createdAt" -> instantEncoder(p.createdAt),"updatedAt" -> instantEncoder(p.updatedAt))
  private def revisionJson(r: ServerProfileRevision) = Json.obj("id" -> Json.fromString(r.id.toString),"profileId" -> Json.fromString(r.profileId.toString),"number" -> Json.fromInt(r.number),"contentHash" -> Json.fromString(r.contentHash),"content" -> r.content.json,"createdAt" -> instantEncoder(r.createdAt))
  private def assignmentJson(a: ServerProfileAssignment) = Json.obj("id" -> Json.fromString(a.id.toString),"resourceId" -> Json.fromString(a.resourceId.toString),"profileId" -> Json.fromString(a.profileId.toString),"revisionId" -> Json.fromString(a.revisionId.toString),"revisionNumber" -> Json.fromInt(a.revisionNumber),"version" -> Json.fromLong(a.version))
  private def assignmentViewJson(v: application.port.ServerProfileAssignmentView) = assignmentJson(v.assignment).deepMerge(Json.obj("resourceName" -> Json.fromString(v.resourceName),"resourceActive" -> Json.fromBoolean(v.resourceActive)))
  private def observationJson(o: ServerProfileObservation) = Json.obj("id" -> Json.fromString(o.id.toString),"content" -> o.content,"contentHash" -> Json.fromString(o.contentHash),"observedAt" -> instantEncoder(o.observedAt))
  private def assessmentJson(a: ServerProfileAssessment) = Json.obj("compliant" -> Json.fromBoolean(a.compliant),"modules" -> Json.fromValues(a.modules.map { case (name,ok) => Json.obj("module" -> Json.fromString(name),"compliant" -> Json.fromBoolean(ok)) }),"changes" -> Json.fromValues(a.changes.map(c => Json.obj("module" -> Json.fromString(c.module),"code" -> Json.fromString(c.code),"detail" -> c.detail.fold(Json.Null)(Json.fromString),"before" -> c.before.fold(Json.Null)(Json.fromString),"after" -> c.after.fold(Json.Null)(Json.fromString)))))
  private def automationJson(a: application.provisioning.ServerProfileAutomation) = Json.obj("state" -> Json.fromString(a.state),"operationsBlocked" -> Json.fromBoolean(a.operationsBlocked),"assignment" -> a.assignment.fold(Json.Null)(assignmentJson),"profile" -> a.profile.fold(Json.Null)(profileJson),"revision" -> a.revision.fold(Json.Null)(revisionJson),"observation" -> a.observation.fold(Json.Null)(observationJson),"assessment" -> a.assessment.fold(Json.Null)(assessmentJson),"activeRun" -> a.activeRun.fold(Json.Null)(r => Json.obj("id" -> Json.fromString(r.id.toString),"state" -> Json.fromString(r.state.code))))
  private def planJson(p: ServerProfilePlan) = Json.obj(
    "run" -> Json.obj("id" -> Json.fromString(p.run.id.toString),"state" -> Json.fromString(p.run.state.code)),
    "connectionName" -> Json.fromString(p.connectionName), "resourceKind" -> Json.fromString(p.run.input.resourceKind),
    "profileName" -> Json.fromString(p.profileName),
    "revisionNumber" -> p.run.input.profileApply.fold(Json.Null)(s => Json.fromInt(s.revisionNumber)),
    "steps" -> Json.fromValues(p.run.input.steps.zipWithIndex.map { case (kind,position) =>
      Json.obj("kind" -> Json.fromString(kind.code),"position" -> Json.fromInt(position)) }),
    "dependencyPackages" -> Json.fromValues(p.dependencyPackages.map(Json.fromString)),
    "packageFindings" -> Json.fromValues(p.packageFindings.map(_.json)),
    "endpoint" -> p.run.input.profileApply.flatMap(s => Option.when(s.content.caddy.enabled)(s.content.caddy)
      .flatMap(c => c.domain.map(d => s"https://$d:${c.localHttpsPort}"))).fold(Json.Null)(Json.fromString),
    "assessment" -> assessmentJson(p.assessment), "warnings" -> Json.fromValues(p.warnings.map(Json.fromString)),
    "blockingProblems" -> Json.fromValues(p.blockingProblems.map(Json.fromString)))
}

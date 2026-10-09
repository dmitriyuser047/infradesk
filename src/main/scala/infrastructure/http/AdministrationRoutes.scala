package ru.bitec.app.ops
package infrastructure.http

import application.administration._
import application.port._
import cats.effect.{IO,SyncIO}
import cats.syntax.all._
import domain.auth.AuthenticatedSession
import infrastructure.http.dto.{ApiErrorResponse,HttpJsonCodecs}
import io.circe.{Decoder,Encoder,Json}
import org.http4s.{HttpRoutes,Request,Response}
import org.http4s.circe.{CirceEntityDecoder,CirceEntityEncoder}
import org.http4s.dsl.io._
import org.typelevel.vault.Key
import java.time.Instant
import java.util.UUID

/** The authentication boundary attaches this only after validating the actual session. */
object AuthenticatedRequest {
  private val key = Key.newKey[SyncIO,AuthenticatedSession].unsafeRunSync()
  def attach(request: Request[IO],session: AuthenticatedSession): Request[IO] = request.withAttribute(key,session)
  def userId(request: Request[IO]): Option[UUID] = request.attributes.lookup(key).map(_.user.id)
}

final class AdministrationRoutes[Tx[_]](service: Administration[Tx],runner: TransactionRunner[IO,Tx]) {
  import HttpJsonCodecs._
  import CirceEntityDecoder._
  import CirceEntityEncoder._
  import AdministrationScope._
  private implicit val createOrganizationDecoder: Decoder[CreateOrganizationCommand] = Decoder.forProduct3("requestId","code","name")(CreateOrganizationCommand.apply)
  private implicit val createUserDecoder: Decoder[CreateAdministrationUserCommand] = Decoder.forProduct7(
    "requestId","email","displayName","password","organizationId","role","isAdministrator")(CreateAdministrationUserCommand.apply)
  private implicit val membershipDecoder: Decoder[ChangeMembershipCommand] = Decoder.forProduct3("role","isActive","expectedUpdatedAt")(ChangeMembershipCommand.apply)
  private implicit val statusDecoder: Decoder[ChangeUserStatusCommand] = Decoder.forProduct3("isActive","isAdministrator","expectedUpdatedAt")(ChangeUserStatusCommand.apply)
  private implicit val userEncoder: Encoder[AdministrationUser] = Encoder.forProduct6(
    "id","email","displayName","isActive","isAdministrator","updatedAt")(u => (u.id,u.email,u.displayName,u.isActive,u.isAdministrator,u.updatedAt))
  private implicit val orgEncoder: Encoder[AdministrationOrganization] = Encoder.forProduct3("id","code","name")(o => (o.id,o.code,o.name))
  private implicit val memberEncoder: Encoder[AdministrationMember] = Encoder.forProduct6("user","organizationId","role","isActive","updatedAt","organizationName")(
    m => (m.user,m.organizationId,m.role.code,m.isActive,m.updatedAt,m.organizationName))
  private implicit val eventEncoder: Encoder[AdministrationAudit] = Encoder.forProduct6("id","actorId","action","targetId","organizationId","occurredAt")(
    e => (e.id,e.actorId,e.action,e.targetId,e.organizationId,e.occurredAt))

  private def page[A: Encoder](values: List[A],limit: Int,id: A => UUID): Json = Json.obj(
    "items" -> Json.arr(values.take(limit).map(Encoder[A].apply):_*),
    "nextCursor" -> (if(values.length>limit) Json.fromString(id(values(limit-1)).toString) else Json.Null))
  private def respond(result: IO[Response[IO]]): IO[Response[IO]] = result.handleErrorWith {
    case e: AdministrationError =>
      val body=ApiErrorResponse(e.code,e.getMessage)
      e.code match {
        case "FORBIDDEN" => Forbidden(body)
        case "ORGANIZATION_NOT_FOUND" | "USER_NOT_FOUND" => NotFound(body)
        case "INVALID_REQUEST" | "PASSWORD_TOO_SHORT" | "PASSWORD_TOO_LONG" | "PASSWORD_INVALID_CHARACTERS" => BadRequest(body)
        case _ => Conflict(body)
      }
    case _ => InternalServerError(ApiErrorResponse("INTERNAL_ERROR","Internal server error"))
  }
  private def actor(request: Request[IO]): IO[UUID] =
    AuthenticatedRequest.userId(request).orElse(OrganizationAuthorization.contextOf(request).map(_.user.id))
      .liftTo[IO](AdministrationError("FORBIDDEN","Operation is not allowed"))
  private def uuid(value: String): IO[UUID] = IO.fromEither(scala.util.Try(UUID.fromString(value)).toEither
    .leftMap(_ => AdministrationError("INVALID_REQUEST","Invalid identifier")))
  private def after(request: Request[IO]): IO[Option[UUID]] = request.params.get("after").traverse(uuid)
  private def body[A: Decoder](request: Request[IO]): IO[A] = request.as[A].handleErrorWith(_ =>
    IO.raiseError(AdministrationError("INVALID_REQUEST","Invalid request")))
  private def run[A](program: Tx[A]): IO[A] = runner.run(program)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" => respond(for {
      id <- actor(request); input <- body[CreateOrganizationCommand](request)
      result <- run(service.createOrganization(id,input)); response <- Created(result)
    } yield response)
    case request @ GET -> Root / "api" / "v1" / "administration" / "users" => respond(for {
      id <- actor(request); cursor <- after(request); result <- run(service.listUsers(id,cursor))
      response <- Ok(page(result,50,(u: AdministrationUser) => u.id))
    } yield response)
    case request @ POST -> Root / "api" / "v1" / "administration" / "users" => respond(for {
      id <- actor(request); input <- body[CreateAdministrationUserCommand](request)
      result <- service.createUser(id,Installation,input); response <- Created(result)
    } yield response)
    case request @ GET -> Root / "api" / "v1" / "administration" / "users" / target => respond(for {
      id <- actor(request); userId <- uuid(target); result <- run(service.user(id,userId)); response <- Ok(result)
    } yield response)
    case request @ PATCH -> Root / "api" / "v1" / "administration" / "users" / target => respond(for {
      id <- actor(request); userId <- uuid(target); input <- body[ChangeUserStatusCommand](request)
      result <- run(service.changeUserStatus(id,userId,input)); response <- Ok(result)
    } yield response)
    case request @ GET -> Root / "api" / "v1" / "administration" / "users" / target / "memberships" => respond(for {
      id <- actor(request); userId <- uuid(target); cursor <- after(request); result <- run(service.userMemberships(id,userId,cursor))
      response <- Ok(page(result,50,(m: AdministrationMember) => m.organizationId))
    } yield response)
    case request @ GET -> Root / "api" / "v1" / "administration" / "organizations" => respond(for {
      id <- actor(request); cursor <- after(request); result <- run(service.listOrganizations(id,cursor))
      response <- Ok(page(result,100,(o: AdministrationOrganization) => o.id))
    } yield response)
    case request @ GET -> Root / "api" / "v1" / "administration" / "audit" => respond(for {
      id <- actor(request); cursor <- after(request); result <- run(service.events(id,cursor))
      response <- Ok(page(result,50,(e: AdministrationAudit) => e.id))
    } yield response)
    case request @ GET -> Root / "api" / "v1" / "administration" / "organizations" / org / "members" / target => respond(for {
      id <- actor(request); orgId <- uuid(org); userId <- uuid(target)
      result <- run(service.membership(id,orgId,userId)); response <- Ok(result)
    } yield response)
    case request @ PUT -> Root / "api" / "v1" / "administration" / "organizations" / org / "members" / target => respond(for {
      id <- actor(request); orgId <- uuid(org); userId <- uuid(target); input <- body[ChangeMembershipCommand](request)
      result <- run(service.changeMembership(id,Installation,orgId,userId,input)); response <- Ok(result)
    } yield response)
    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "members" => respond(for {
      id <- actor(request); orgId <- uuid(org); cursor <- after(request)
      result <- run(service.listMembers(id,Organization(orgId),orgId,cursor))
      response <- Ok(page(result,50,(m: AdministrationMember) => m.user.id))
    } yield response)
    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "members" / "users" => respond(for {
      id <- actor(request); orgId <- uuid(org); input <- body[CreateAdministrationUserCommand](request)
      result <- service.createUser(id,Organization(orgId),input); response <- Created(result)
    } yield response)
    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "members" => respond(for {
      id <- actor(request); orgId <- uuid(org); json <- body[Json](request)
      email <- IO.fromEither(json.hcursor.get[String]("email").leftMap(_ => AdministrationError("INVALID_REQUEST","Invalid email")))
      input <- IO.fromEither(membershipDecoder.decodeJson(json).leftMap(_ => AdministrationError("INVALID_REQUEST","Invalid role")))
      result <- run(service.addMemberByEmail(id,orgId,email,input)); response <- Created(result)
    } yield response)
    case request @ PUT -> Root / "api" / "v1" / "organizations" / org / "members" / target => respond(for {
      id <- actor(request); orgId <- uuid(org); userId <- uuid(target); input <- body[ChangeMembershipCommand](request)
      result <- run(service.changeMembership(id,Organization(orgId),orgId,userId,input)); response <- Ok(result)
    } yield response)
  }
}

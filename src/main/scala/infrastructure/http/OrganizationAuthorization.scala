package ru.bitec.app.ops
package infrastructure.http

import application.auth.ActorContext
import cats.effect.{IO, SyncIO}
import domain.auth.{
  AuthenticatedUser,
  OrganizationAuthorizationPolicy,
  OrganizationPermission,
  OrganizationRole
}
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.http4s.{Request, Response}
import org.typelevel.log4cats.Logger
import org.typelevel.vault.Key

import java.util.UUID

/** An authenticated user inside one organization, together with the role the membership lookup
  * returned. Permissions follow from the role; no further query is needed.
  */
final case class OrganizationAccessContext(
  user: AuthenticatedUser,
  organizationId: UUID,
  role: OrganizationRole
) {
  def allows(permission: OrganizationPermission): Boolean =
    OrganizationAuthorizationPolicy.allows(role, permission)

  def actor: ActorContext = ActorContext(user.id, organizationId)
}

/** The single place where an organization-scoped handler states what it requires.
  *
  * A route either names its permission here or never sees the access context it needs, so a new
  * privileged route cannot silently inherit the access of an ordinary member.
  */
final class OrganizationAuthorization(logger: Logger[IO]) {
  import HttpJsonCodecs._

  private val forbidden = ApiErrorResponse("FORBIDDEN", "Operation is not allowed")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  def require(request: Request[IO], permission: OrganizationPermission)(
    next: OrganizationAccessContext => IO[Response[IO]]
  ): IO[Response[IO]] =
    OrganizationAuthorization.contextOf(request) match {
      case Some(context) =>
        OrganizationAuthorizationPolicy.require(context.role, permission) match {
          case Right(_) => next(context)
          case Left(error) => denied(request, context, error.permission) *> Forbidden(forbidden)
        }
      // Only the authentication boundary attaches the context; its absence is a wiring error.
      case None =>
        log(logger.error(
          s"authorization.context.missing method=${request.method.name} path=${safePath(request)}"
        )) *> InternalServerError(internalError)
    }

  private def denied(
    request: Request[IO],
    context: OrganizationAccessContext,
    permission: OrganizationPermission
  ): IO[Unit] =
    log(logger.warn(
      s"authorization.denied userId=${context.user.id} organizationId=${context.organizationId} " +
        s"role=${context.role.code} permission=${permission.code} " +
        s"method=${request.method.name} path=${safePath(request)}"
    ))

  /** The path without the query string, which may carry filters but never belongs in a log line
    * together with anything the client sent in headers or in the body.
    */
  private def safePath(request: Request[IO]): String = request.uri.path.renderString

  private def log(effect: IO[Unit]): IO[Unit] = effect.handleErrorWith(_ => IO.unit)
}

object OrganizationAuthorization {

  private val ContextKey: Key[OrganizationAccessContext] =
    Key.newKey[SyncIO, OrganizationAccessContext].unsafeRunSync()

  def withContext(request: Request[IO], context: OrganizationAccessContext): Request[IO] =
    request.withAttribute(ContextKey, context)

  def contextOf(request: Request[IO]): Option[OrganizationAccessContext] =
    request.attributes.lookup(ContextKey)
}

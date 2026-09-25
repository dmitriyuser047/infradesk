package ru.bitec.app.ops
package infrastructure.http

import application.notification.{
  GetNotificationChannel,
  ListNotificationChannels,
  NotificationChannelError,
  NotificationChannelManagement
}
import application.port.TransactionRunner
import cats.effect.IO
import domain.auth.OrganizationPermission
import domain.notification.NotificationChannel
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs, SaveNotificationChannelRequest}
import infrastructure.http.mapper.NotificationChannelHttpMapper
import org.http4s.{HttpRoutes, Response}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

/** Notification channels as configuration of an organization.
  *
  * Every route here requires the same capability, including the listing: a channel holds a
  * credential and decides who is told about an incident, so it is settings rather than
  * reporting. The tenant of the path is never trusted on its own — the access context decides
  * which organization is read, which is what makes a guessed identifier of another tenant come
  * back as "not found".
  */
final class NotificationChannelRoutes[Tx[_]](
  listChannels: ListNotificationChannels[Tx],
  getChannel: GetNotificationChannel[Tx],
  management: NotificationChannelManagement[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization
) {
  import CirceEntityDecoder._
  import CirceEntityEncoder._
  import HttpJsonCodecs._

  private val invalidRequest = ApiErrorResponse("INVALID_REQUEST", "Invalid notification channel")
  private val notFound =
    ApiErrorResponse("NOTIFICATION_CHANNEL_NOT_FOUND", "Notification channel was not found")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "notification-channels" =>
      authorization.require(request, OrganizationPermission.ManageNotifications) { context =>
        withOrganization(org, context.organizationId) { organizationId =>
          respond(runner.run(listChannels.execute(organizationId))
            .flatMap(channels => Ok(channels.map(NotificationChannelHttpMapper.toResponse))))
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "notification-channels" / id =>
      authorization.require(request, OrganizationPermission.ManageNotifications) { context =>
        withChannel(org, context.organizationId, id) { (organizationId, channelId) =>
          respond(runner.run(getChannel.execute(organizationId, channelId)).flatMap {
            case Some(channel) => ok(channel)
            case None => NotFound(notFound)
          })
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "notification-channels" =>
      authorization.require(request, OrganizationPermission.ManageNotifications) { context =>
        withOrganization(org, context.organizationId) { _ =>
          withCommand(request) { command =>
            respond(runner.run(management.create(context.actor, command))
              .flatMap(channel => Created(NotificationChannelHttpMapper.toResponse(channel))))
          }
        }
      }

    case request @ PUT -> Root / "api" / "v1" / "organizations" / org / "notification-channels" / id =>
      authorization.require(request, OrganizationPermission.ManageNotifications) { context =>
        withChannel(org, context.organizationId, id) { (_, channelId) =>
          withCommand(request) { command =>
            respond(runner.run(management.update(context.actor, channelId, command)).flatMap(ok))
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "notification-channels" / id / "enable" =>
      authorization.require(request, OrganizationPermission.ManageNotifications) { context =>
        withChannel(org, context.organizationId, id) { (_, channelId) =>
          respond(runner.run(management.setEnabled(context.actor, channelId, enabled = true))
            .flatMap(ok))
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "notification-channels" / id / "disable" =>
      authorization.require(request, OrganizationPermission.ManageNotifications) { context =>
        withChannel(org, context.organizationId, id) { (_, channelId) =>
          respond(runner.run(management.setEnabled(context.actor, channelId, enabled = false))
            .flatMap(ok))
        }
      }
  }

  private def ok(channel: NotificationChannel): IO[Response[IO]] =
    Ok(NotificationChannelHttpMapper.toResponse(channel))

  private def withCommand(request: org.http4s.Request[IO])(
    next: application.notification.NotificationChannelCommand => IO[Response[IO]]
  ): IO[Response[IO]] =
    request.as[SaveNotificationChannelRequest].attempt.flatMap {
      case Left(_) => BadRequest(invalidRequest)
      case Right(body) => NotificationChannelHttpMapper.toCommand(body)
        .fold(_ => BadRequest(invalidRequest), next)
    }

  /** The organization of the path has to be the organization the caller was admitted to. */
  private def withOrganization(raw: String, contextOrganizationId: UUID)(
    next: UUID => IO[Response[IO]]
  ): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption match {
      case Some(id) if id == contextOrganizationId => next(id)
      case Some(_) => NotFound(notFound)
      case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid organizationId"))
    }

  private def withChannel(raw: String, contextOrganizationId: UUID, rawChannelId: String)(
    next: (UUID, UUID) => IO[Response[IO]]
  ): IO[Response[IO]] =
    withOrganization(raw, contextOrganizationId) { organizationId =>
      Try(UUID.fromString(rawChannelId)).toOption match {
        case Some(channelId) => next(organizationId, channelId)
        case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid notificationChannelId"))
      }
    }

  /** Business errors answer by their code; anything else is an internal error without detail. */
  private def respond(action: IO[Response[IO]]): IO[Response[IO]] =
    action.handleErrorWith {
      case error: NotificationChannelError => error.code match {
        case "NOTIFICATION_CHANNEL_NOT_FOUND" => NotFound(notFound)
        case code => BadRequest(ApiErrorResponse(code, error.getMessage))
      }
      case _ => InternalServerError(internalError)
    }
}

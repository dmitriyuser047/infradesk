package ru.bitec.app.ops
package infrastructure.http

import application.integration.{IntegrationError,OnboardingJson,RemnawaveOnboardingApi}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration.{OnboardingInput,PanelSourceMode,NodeAddressMode,RemnawaveProtocol,NodeTlsMaterial,NodeTlsHttp01}
import infrastructure.http.dto.{ApiErrorResponse,HttpJsonCodecs}
import io.circe.Json
import org.http4s.{HttpRoutes,Request,Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction,StandardCharsets}
import scala.util.Try

final class RemnawaveOnboardingRoutes[Tx[_]](service: RemnawaveOnboardingApi,authorization: OrganizationAuthorization,
  logger: Logger[IO]) {
  import HttpJsonCodecs._
  private val invalid = ApiErrorResponse("INVALID_REQUEST","Invalid Remnawave onboarding request")
  private val MaxBytes = 16384
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / "remnawave-node-onboarding" / "certificates" =>
      mutate(req)(ctx => withId(integration)(id => body(req,Set("resourceId","domain","certificatePem","privateKeyPem"),limit=65536) { json =>
        val c=json.hcursor
        val decoded=for {
          resource <- c.get[String]("resourceId").toOption.flatMap(uuid)
          domain <- c.get[String]("domain").toOption
          certificate <- c.get[String]("certificatePem").toOption
          key <- c.get[String]("privateKeyPem").toOption
        } yield (resource,domain,new NodeTlsMaterial(certificate,key))
        decoded.fold[IO[Response[IO]]](BadRequest(invalid)) { case(resource,domain,material) =>
          respond(req,ctx)(service.importCertificate(ctx.actor,id,resource,domain,material).flatMap(Created(_)))
        }
      }))
    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / "remnawave-node-onboarding" / "options" =>
      authorization.require(req,OrganizationPermission.ManageIntegrations)(ctx => withId(integration)(id => respond(req,ctx)(service.options(ctx.organizationId,id).flatMap(Ok(_)))))
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / "remnawave-node-onboarding" / "preview" =>
      mutate(req)(ctx => withId(integration)(id => body(req,Set("resourceId","nodeName","address","nodePort","desiredState"),Set("configProfileId","activeInboundIds","protocol","tlsCertificateId","tlsHttp01","panelCidrs","panelSourceMode","nodeAddressMode")) { json =>
        val c=json.hcursor
        val decoded=for {
          resource <- c.get[String]("resourceId").toOption.flatMap(uuid)
          name <- c.get[String]("nodeName").toOption
          address <- c.get[String]("address").toOption
          addressMode <- c.get[Option[String]]("nodeAddressMode").toOption.flatMap {
            case None => Some(NodeAddressMode.PublicIp)
            case Some(value) => NodeAddressMode.fromCode(value).filter(_!=NodeAddressMode.Legacy)
          }
          port <- c.get[Int]("nodePort").toOption
          protocol <- c.downField("protocol").focus.fold[Option[Option[RemnawaveProtocol]]](Some(None))(
            j => RemnawaveProtocol.decode(j,port).toOption.map(Some(_)))
          profile <- if(protocol.nonEmpty && !c.downField("configProfileId").succeeded) Some(OnboardingInput.GeneratedProfileId)
            else c.get[String]("configProfileId").toOption.flatMap(uuid)
          rawInbounds <- if(protocol.nonEmpty && !c.downField("activeInboundIds").succeeded) Some(List.empty[String])
            else c.get[List[String]]("activeInboundIds").toOption.filter(_.size<=256)
          inbounds <- rawInbounds.traverse(uuid)
          cidrs <- c.get[Option[List[String]]]("panelCidrs").toOption.map(_.getOrElse(Nil)).filter(_.size<=32)
          mode <- c.get[Option[String]]("panelSourceMode").toOption.flatMap {
            case Some(value) => PanelSourceMode.fromCode(value)
            case None => Some(if(cidrs.nonEmpty) PanelSourceMode.Manual else PanelSourceMode.Auto)
          }
          _ <- Option.when(mode != PanelSourceMode.Auto || cidrs.isEmpty)(())
          _ <- Option.when(mode != PanelSourceMode.Manual || OnboardingInput.canonicalCidrs(cidrs).contains(cidrs.sorted))(())
          desired <- c.get[String]("desiredState").toOption.filter(_=="ENABLED")
          certificate <- c.get[Option[String]]("tlsCertificateId").toOption.flatMap(_.traverse(uuid))
          http <- c.downField("tlsHttp01").focus.fold[Option[Option[NodeTlsHttp01]]](Some(None)) { j =>
            for {
              _ <- Option.when(j.asObject.exists(_.keys.toSet==Set("certificateId","email","agreeTerms")))(())
              id <- j.hcursor.get[String]("certificateId").toOption.flatMap(uuid)
              email <- j.hcursor.get[String]("email").toOption
              _ <- Option.when(j.hcursor.get[Boolean]("agreeTerms").contains(true))(())
              request=NodeTlsHttp01(id,email)
              _ <- Option.when(request.valid)(())
            } yield Some(request)
          }
        } yield OnboardingInput(resource,name,address,port,profile,inbounds,cidrs,desired,mode,addressMode,protocol,certificate,http)
        decoded.fold[IO[Response[IO]]](BadRequest(invalid))(input => respond(req,ctx)(service.preview(ctx.actor,id,input).flatMap(Ok(_))))
      }))
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / "remnawave-node-onboarding" / "runs" =>
      mutate(req)(ctx => withId(integration)(id => body(req,Set("planId","requestId"),Set("confirmRecreate")) { json =>
        val ids=for { p <- json.hcursor.get[String]("planId").toOption.flatMap(uuid); r <- json.hcursor.get[String]("requestId").toOption.flatMap(uuid) } yield p -> r
        val confirmed=json.hcursor.get[Option[Boolean]]("confirmRecreate").toOption
        if(confirmed.isEmpty) BadRequest(invalid) else ids.fold[IO[Response[IO]]](BadRequest(invalid)) { case(p,r) => respond(req,ctx)(service.start(ctx.actor,id,p,r,confirmed.flatten.getOrElse(false)).flatMap(run => Accepted(OnboardingJson.run(run)))) }
      }))
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / "remnawave-node-onboarding" / "runs" / run / "reconcile" =>
      mutate(req)(ctx => withId(integration)(id => withId(run)(r => body(req,Set("action")) { json =>
        json.hcursor.get[String]("action").toOption.filter(Set("RECOVER","DELETE_RECREATE")).fold[IO[Response[IO]]](BadRequest(invalid))(
          action => respond(req,ctx)(service.reconcile(ctx.actor,id,r,action).flatMap(Ok(_))))
      })))
    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / "remnawave-node-onboarding" / "runs" / run =>
      authorization.require(req,OrganizationPermission.ReadOrganization)(ctx => withId(integration)(id => withId(run)(r =>
        respond(req,ctx)(service.detail(ctx.organizationId,id,r).flatMap(Ok(_))))))
    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / "remnawave-node-onboarding" / "runs" =>
      authorization.require(req,OrganizationPermission.ReadOrganization)(ctx => withId(integration)(id =>
        respond(req,ctx)(service.history(ctx.organizationId,id).flatMap(Ok(_)))))
  }
  private def mutate(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(req,OrganizationPermission.ManageIntegrations)(_ =>
      authorization.require(req,OrganizationPermission.ManageConfigurations)(_ =>
        authorization.require(req,OrganizationPermission.ExecuteOperations)(next)))
  private def uuid(s: String): Option[UUID] = Try(UUID.fromString(s)).toOption.filter(_.toString==s)
  private def withId(s: String)(f: UUID => IO[Response[IO]]): IO[Response[IO]] = uuid(s).fold[IO[Response[IO]]](BadRequest(invalid))(f)
  private def body(req: Request[IO],keys: Set[String],optional: Set[String] = Set.empty,limit: Int = MaxBytes)(f: Json => IO[Response[IO]]): IO[Response[IO]] =
    req.body.take(limit+1L).compile.to(Array).flatMap { bytes =>
      val decoded=Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString).toOption
      val json=decoded.flatMap(io.circe.parser.parse(_).toOption).filter(j => j.asObject.exists(o => keys.subsetOf(o.keys.toSet) && o.keys.toSet.subsetOf(keys++optional)))
      if(bytes.length>limit || json.isEmpty) BadRequest(invalid) else f(json.get)
    }
  private def respond(req: Request[IO],ctx: OrganizationAccessContext)(action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case e: IntegrationError if e.code=="INTEGRATION_NOT_FOUND" || e.code=="REMNAWAVE_ONBOARDING_NOT_FOUND" => NotFound(ApiErrorResponse(e.code,e.getMessage))
    case e: IntegrationError if e.code=="REMNAWAVE_ONBOARDING_INVALID_INPUT" || e.code=="REMNAWAVE_ONBOARDING_CIDR_INVALID" => BadRequest(ApiErrorResponse(e.code,e.getMessage))
    case e: IntegrationError => Conflict(ApiErrorResponse(e.code,e.getMessage))
    case e => ReadModelHttp.failedAs(logger,"remnawave.onboarding.request_failed",req,"onboarding",
      "organizationId" -> ctx.organizationId,"actorUserId" -> ctx.user.id)(e)
  }
}

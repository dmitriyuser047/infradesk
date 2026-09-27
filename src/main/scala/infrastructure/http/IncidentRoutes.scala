package ru.bitec.app.ops
package infrastructure.http
import application.incident.{GetIncidentDetail,ListIncidents}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.incident.IncidentStatus
import infrastructure.http.dto.{ApiErrorResponse,HttpJsonCodecs,InfrastructureContextResponses}
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityEncoder._
import java.util.UUID
import scala.util.Try
/** The incident list and detail. Both carry each incident's infrastructure context — resource,
  * environment, project, rule and source connections — read in one statement.
  */
final class IncidentRoutes[Tx[_]](
 get:GetIncidentDetail[Tx],
 list:ListIncidents[Tx],
 runner:TransactionRunner[IO,Tx],
 authorization: OrganizationAuthorization
) {
 import HttpJsonCodecs._
 import InfrastructureContextResponses.Codecs._
 private def uuid(s:String,n:String)=Try(UUID.fromString(s)).toEither.leftMap(_=>ApiErrorResponse("INVALID_REQUEST",s"Invalid $n"))
 private val nf=ApiErrorResponse("INCIDENT_NOT_FOUND","Incident was not found")
 val routes=HttpRoutes.of[IO]{
  case req @ GET -> Root / "api" / "v1" / "organizations" / org / "incidents" => authorization.require(req, OrganizationPermission.ReadOrganization) { _ =>
   uuid(org,"organizationId") match { case Left(e)=>BadRequest(e); case Right(o)=>
   req.uri.query.params.get("status").traverse(IncidentStatus.fromCode).fold(_=>BadRequest(ApiErrorResponse("INVALID_REQUEST","Invalid status")), s=>runner.run(list.execute(o,s)).attempt.flatMap{case Right(v)=>Ok(v.map(InfrastructureContextResponses.incident));case Left(_)=>InternalServerError(ApiErrorResponse("INTERNAL_ERROR","Internal server error"))}) } }
  case req @ GET -> Root / "api" / "v1" / "organizations" / org / "incidents" / id => authorization.require(req, OrganizationPermission.ReadOrganization) { _ =>
   (uuid(org,"organizationId"),uuid(id,"incidentId")) match {case(Left(e),_)=>BadRequest(e);case(_,Left(e))=>BadRequest(e);case(Right(o),Right(i))=>runner.run(get.execute(o,i)).attempt.flatMap{case Right(Some(v))=>Ok(InfrastructureContextResponses.incident(v));case Right(None)=>NotFound(nf);case Left(_)=>InternalServerError(ApiErrorResponse("INTERNAL_ERROR","Internal server error"))}} }
 }
}

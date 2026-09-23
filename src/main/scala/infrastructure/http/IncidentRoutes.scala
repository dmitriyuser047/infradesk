package ru.bitec.app.ops
package infrastructure.http
import application.incident.{GetIncident,ListIncidents}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.incident.{Incident,IncidentStatus}
import infrastructure.http.dto.{ApiErrorResponse,HttpJsonCodecs,IncidentResponse}
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityEncoder._
import java.util.UUID
import java.time.Instant
import scala.util.Try
final class IncidentRoutes[Tx[_]](get:GetIncident[Tx], list:ListIncidents[Tx], runner:TransactionRunner[IO,Tx]) {
 import HttpJsonCodecs._
 private def uuid(s:String,n:String)=Try(UUID.fromString(s)).toEither.leftMap(_=>ApiErrorResponse("INVALID_REQUEST",s"Invalid $n"))
 private def map(i:Incident)=IncidentResponse(i.id,i.monitorRuleId,i.resourceId,i.status.code,i.reason.code,i.startedAt,i.openedAt,i.resolvedAt,i.createdAt,i.updatedAt)
 private val nf=ApiErrorResponse("INCIDENT_NOT_FOUND","Incident was not found")
 val routes=HttpRoutes.of[IO]{
  case req @ GET -> Root / "api" / "v1" / "organizations" / org / "incidents" => uuid(org,"organizationId") match { case Left(e)=>BadRequest(e); case Right(o)=>
   req.uri.query.params.get("status").traverse(IncidentStatus.fromCode).fold(_=>BadRequest(ApiErrorResponse("INVALID_REQUEST","Invalid status")), s=>runner.run(list.execute(o,s)).attempt.flatMap{case Right(v)=>Ok(v.map(map));case Left(_)=>InternalServerError(ApiErrorResponse("INTERNAL_ERROR","Internal server error"))}) }
  case GET -> Root / "api" / "v1" / "organizations" / org / "incidents" / id => (uuid(org,"organizationId"),uuid(id,"incidentId")) match {case(Left(e),_)=>BadRequest(e);case(_,Left(e))=>BadRequest(e);case(Right(o),Right(i))=>runner.run(get.execute(o,i)).attempt.flatMap{case Right(Some(v))=>Ok(map(v));case Right(None)=>NotFound(nf);case Left(_)=>InternalServerError(ApiErrorResponse("INTERNAL_ERROR","Internal server error"))}}
 }
}

package ru.bitec.app.ops
package infrastructure.http
import application.monitor.{CreateMonitorRule,ListMonitorRules,UpdateMonitorRule}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.metric.MetricCode
import domain.monitor.MonitorOperator
import infrastructure.http.dto.{ApiErrorResponse,HttpJsonCodecs,CreateMonitorRuleRequest,UpdateMonitorRuleRequest}
import infrastructure.http.mapper.MonitorRuleHttpMapper
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.circe._
import java.util.UUID
import scala.util.Try
final class MonitorRuleRoutes[Tx[_]](list:ListMonitorRules[Tx],create:CreateMonitorRule[Tx],update:UpdateMonitorRule[Tx],runner:TransactionRunner[IO,Tx]){
 import HttpJsonCodecs._
 private def uid(s:String,n:String)=Try(UUID.fromString(s)).toEither.leftMap(_=>ApiErrorResponse("INVALID_REQUEST",s"Invalid $n"))
 private def valid(m:String,o:String,f:Long)=for{x<-MetricCode.fromCode(m);y<-MonitorOperator.fromCode(o);_<-Either.cond(f>=0,(),new IllegalArgumentException("forSeconds"))}yield(x,y)
 private val resourceNf=ApiErrorResponse("RESOURCE_NOT_FOUND","Resource was not found");private val ruleNf=ApiErrorResponse("MONITOR_RULE_NOT_FOUND","Monitor rule was not found");private val internal=ApiErrorResponse("INTERNAL_ERROR","Internal server error")
 val routes=HttpRoutes.of[IO]{
  case GET -> Root / "api" / "v1" / "organizations" / org / "resources" / res / "monitor-rules" => (uid(org,"organizationId"),uid(res,"resourceId")) match{case(Right(o),Right(r))=>runner.run(list.execute(o,r)).attempt.flatMap{case Right(Some(v))=>Ok(v.map(MonitorRuleHttpMapper.toResponse));case Right(None)=>NotFound(resourceNf);case Left(_)=>InternalServerError(internal)};case(Left(e),_)=>BadRequest(e);case(_,Left(e))=>BadRequest(e)}
  case req @ POST -> Root / "api" / "v1" / "organizations" / org / "resources" / res / "monitor-rules" => (uid(org,"organizationId"),uid(res,"resourceId")) match{case(Right(o),Right(r))=>req.asJsonDecode[CreateMonitorRuleRequest].attempt.flatMap{case Right(x)=>valid(x.metricCode,x.operator,x.forSeconds).fold(_=>BadRequest(ApiErrorResponse("INVALID_REQUEST","Invalid monitor rule")),v=>runner.run(create.execute(o,r,v._1,v._2,x.threshold,x.forSeconds,x.enabled)).attempt.flatMap{case Right(Some(z))=>Created(MonitorRuleHttpMapper.toResponse(z));case Right(None)=>NotFound(resourceNf);case Left(_)=>InternalServerError(internal)});case Left(_)=>BadRequest(ApiErrorResponse("INVALID_REQUEST","Invalid request"))};case(Left(e),_)=>BadRequest(e);case(_,Left(e))=>BadRequest(e)}
  case req @ PUT -> Root / "api" / "v1" / "organizations" / org / "monitor-rules" / id => (uid(org,"organizationId"),uid(id,"monitorRuleId")) match{case(Right(o),Right(i))=>req.asJsonDecode[UpdateMonitorRuleRequest].attempt.flatMap{case Right(x)=>valid(x.metricCode,x.operator,x.forSeconds).fold(_=>BadRequest(ApiErrorResponse("INVALID_REQUEST","Invalid monitor rule")),v=>runner.run(update.execute(o,i,v._1,v._2,x.threshold,x.forSeconds,x.enabled)).attempt.flatMap{case Right(Some(z))=>Ok(MonitorRuleHttpMapper.toResponse(z));case Right(None)=>NotFound(ruleNf);case Left(_)=>InternalServerError(internal)});case Left(_)=>BadRequest(ApiErrorResponse("INVALID_REQUEST","Invalid request"))};case(Left(e),_)=>BadRequest(e);case(_,Left(e))=>BadRequest(e)}
 }
}

package ru.bitec.app.ops
package infrastructure.http

import application.incident.{GetIncident, ListIncidents}
import application.port.{IncidentListItem, IncidentListQuery, IncidentRepository, IncidentResourceReference, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import java.time.Instant
import java.util.UUID

final class IncidentRoutesSpec extends FunSuite {
  test("lists and filters incidents") {
    val f=fixture(List(open,resolved)); val all=run(f,s"/api/v1/organizations/$Org/incidents"); val onlyOpen=run(f,s"/api/v1/organizations/$Org/incidents?status=OPEN"); val onlyResolved=run(f,s"/api/v1/organizations/$Org/incidents?status=RESOLVED")
    assertEquals(all._1.status,Status.Ok); assertEquals(all._2.asArray.map(_.size),Some(2)); assertEquals(onlyOpen._2.asArray.map(_.size),Some(1)); assertEquals(onlyResolved._2.asArray.map(_.size),Some(1))
  }
  test("each listed incident names its resource, read once for the whole list") {
    val f=fixture(List(open,resolved,noData)); val all=run(f,s"/api/v1/organizations/$Org/incidents")
    val first=all._2.asArray.flatMap(_.headOption).getOrElse(fail("no incident")).hcursor
    assertEquals(first.downField("resource").get[String]("name"),Right(s"node-${open.resourceId.toString.take(4)}"))
    assertEquals(first.downField("resource").get[String]("resourceTypeCode"),Right("NODE"))
    assertEquals(first.downField("resource").get[UUID]("id"),first.get[UUID]("resourceId"))
    // The resource carries identity only: nothing of its spec or status reaches the list.
    assertEquals(first.downField("resource").keys.map(_.toSet),Some(Set("id","name","resourceTypeCode")))
    assertEquals(f._2.listCalls,1); assertEquals(f._3.calls,1)
  }
  test("validates list status and returns empty list") {
    val invalid=run(fixture(List.empty),s"/api/v1/organizations/$Org/incidents?status=BAD"); val empty=run(fixture(List.empty),s"/api/v1/organizations/$Org/incidents")
    assertEquals(invalid._1.status,Status.BadRequest); assertEquals(invalid._2.hcursor.get[String]("code"),Right("INVALID_REQUEST")); assertEquals(empty._2.asArray,Some(Vector.empty))
  }
  test("exposes the incident reason") {
    val f=fixture(List(open,noData)); val all=run(f,s"/api/v1/organizations/$Org/incidents")
    val reasons=all._2.asArray.getOrElse(Vector.empty).flatMap(_.hcursor.get[String]("reason").toOption)
    assertEquals(reasons.toSet,Set("THRESHOLD","NO_DATA"))
  }
  test("returns detail and hides foreign incident") {
    val f=fixture(List(open)); val ok=run(f,s"/api/v1/organizations/$Org/incidents/${open.id}"); val foreign=run(f,s"/api/v1/organizations/$Other/incidents/${open.id}"); val absent=run(f,s"/api/v1/organizations/$Org/incidents/$OtherId")
    assertEquals(ok._1.status,Status.Ok); assertEquals(foreign._1.status,Status.NotFound); assertEquals(absent._2.hcursor.get[String]("code"),Right("INCIDENT_NOT_FOUND"))
  }
  test("validates ids and sanitizes failures") {
    val badOrg=run(fixture(List.empty),s"/api/v1/organizations/bad/incidents"); val badId=run(fixture(List.empty),s"/api/v1/organizations/$Org/incidents/bad"); val failed=run(fixture(List.empty,Some(new IllegalStateException("sql secret"))),s"/api/v1/organizations/$Org/incidents")
    assertEquals(badOrg._1.status,Status.BadRequest);assertEquals(badId._1.status,Status.BadRequest);assertEquals(failed._1.status,Status.InternalServerError);assert(!failed._2.noSpaces.contains("sql secret"))
  }
  private def fixture(values:List[Incident], failure:Option[Throwable]=None)={val r=new Repo(values,failure);val t=new Runner; (new IncidentRoutes[IO](GetIncident(r),ListIncidents(r:IncidentListQuery[IO]),t,support.AuthorizationFixtures.authorization),r,t)}
  private def run(f:(IncidentRoutes[IO],Repo,Runner), path:String)={val r=support.AuthorizationFixtures.authorized(f._1.routes.orNotFound).run(Request[IO](Method.GET,Uri.unsafeFromString(path))).unsafeRunSync();(r,r.as[Json].unsafeRunSync())}
  private final class Runner extends TransactionRunner[IO,IO]{var calls=0;def run[A](p:IO[A])=IO{calls+=1}*>p}
  private final class Repo(values:List[Incident], failure:Option[Throwable]) extends IncidentRepository[IO] with IncidentListQuery[IO] {
    var listCalls=0
    def list(o:UUID,s:Option[IncidentStatus])=IO{listCalls+=1}*>findByOrganization(o,s).map(_.map(i=>IncidentListItem(i,IncidentResourceReference(i.resourceId,s"node-${i.resourceId.toString.take(4)}","NODE"))))
    def findOpenByRule(o:UUID,r:UUID)=IO.pure(values.find(x=>x.organizationId==o&&x.monitorRuleId==r&&x.status==IncidentStatus.Open))
    def findById(o:UUID,id:UUID)=failure match {case Some(e)=>IO.raiseError[Option[Incident]](e);case None=>IO.pure(values.find(x=>x.organizationId==o&&x.id==id))}
    def findByOrganization(o:UUID,s:Option[IncidentStatus])=failure match {case Some(e)=>IO.raiseError[List[Incident]](e);case None=>IO.pure(values.filter(x=>x.organizationId==o&&s.forall(_==x.status)))}
    def save(i:Incident)=IO.unit
    def saveAll(i:List[Incident])=IO.unit
  }
  private val Org=UUID.fromString("20000000-0000-0000-0000-000000000001");private val Other=UUID.fromString("20000000-0000-0000-0000-000000000002");private val OtherId=UUID.randomUUID();private val Now=Instant.parse("2026-09-22T10:00:00Z")
  private val open=Incident(UUID.randomUUID(),Org,UUID.randomUUID(),UUID.randomUUID(),IncidentStatus.Open,IncidentReason.ThresholdViolation,Now,Now,None,Now,Now);private val resolved=open.copy(id=UUID.randomUUID(),status=IncidentStatus.Resolved,resolvedAt=Some(Now.plusSeconds(1)));private val noData=open.copy(id=UUID.randomUUID(),reason=IncidentReason.NoData)
}

package ru.bitec.app.ops
package infrastructure.http

import application.incident.{GetIncidentDetail, ListIncidents}
import application.port.{EnvironmentReference, IncidentCursor, IncidentListItem, IncidentListQuery, IncidentRepository, IncidentResourceReference, InfrastructureLocation, MonitorConditionView, ProjectReference, SourceConnectionReference, TransactionRunner}
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
  test("each incident carries its infrastructure context and the condition of its rule") {
    val f=fixture(List(open)); val detail=run(f,s"/api/v1/organizations/$Org/incidents/${open.id}")._2.hcursor
    assertEquals(detail.downField("project").get[String]("name"),Right("SvinPeak"))
    assertEquals(detail.downField("environment").get[String]("kind"),Right("PROD"))
    assertEquals(detail.downField("monitorRule").get[String]("operator"),Right("GREATER_THAN"))
    assertEquals(detail.downField("monitorRule").get[BigDecimal]("threshold"),Right(BigDecimal(85)))
    assertEquals(detail.downField("sourceConnections").downArray.get[String]("name"),Right("Finnish Node"))
    assert(detail.downField("parentResource").focus.exists(_.isNull))
    // Identity only: nothing of a connection's configuration or credentials.
    assertEquals(detail.downField("sourceConnections").downArray.keys.map(_.toSet),Some(Set("id","name","connectorType","active")))
    val listed=run(f,s"/api/v1/organizations/$Org/incidents")._2.asArray.flatMap(_.headOption).getOrElse(fail("no incident")).hcursor
    assertEquals(listed.downField("environment").get[String]("name"),Right("Production"))
  }
  test("lists one keyset page and validates its cursor and size") {
    val f=fixture(List(open,resolved,noData))
    val first=run(f,s"/api/v1/organizations/$Org/incidents?limit=2")
    assertEquals(first._2.asArray.map(_.size),Some(2))
    val next=run(f,s"/api/v1/organizations/$Org/incidents?status=OPEN&limit=1&beforeOpenedAt=$Now&beforeId=${open.id}")
    assertEquals(next._1.status,Status.Ok)
    assertEquals(f._2.pages,List(None->2,Some(IncidentCursor(Now,open.id))->1))
    // Without a limit the default page applies: never the whole history.
    run(f,s"/api/v1/organizations/$Org/incidents")
    assertEquals(f._2.pages.last,None->50)
    assertEquals(run(f,s"/api/v1/organizations/$Org/incidents?limit=201")._1.status,Status.BadRequest)
    assertEquals(run(f,s"/api/v1/organizations/$Org/incidents?beforeId=${open.id}")._1.status,Status.BadRequest)
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
  private def fixture(values:List[Incident], failure:Option[Throwable]=None)={val r=new Repo(values,failure);val t=new Runner; (new IncidentRoutes[IO](GetIncidentDetail(r:IncidentListQuery[IO]),ListIncidents(r:IncidentListQuery[IO]),t,support.AuthorizationFixtures.authorization,org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("test.incidents")),r,t)}
  private def run(f:(IncidentRoutes[IO],Repo,Runner), path:String)={val r=support.AuthorizationFixtures.authorized(f._1.routes.orNotFound).run(Request[IO](Method.GET,Uri.unsafeFromString(path))).unsafeRunSync();(r,r.as[Json].unsafeRunSync())}
  private final class Runner extends TransactionRunner[IO,IO]{var calls=0;def run[A](p:IO[A])=IO{calls+=1}*>p}
  private final class Repo(values:List[Incident], failure:Option[Throwable]) extends IncidentRepository[IO] with IncidentListQuery[IO] {
    var listCalls=0
    var pages=List.empty[(Option[IncidentCursor],Int)]
    def list(o:UUID,s:Option[IncidentStatus],b:Option[IncidentCursor],l:Int)=IO{listCalls+=1;pages=pages:+(b->l)}*>findByOrganization(o,s).map(_.take(l).map(item))
    def listByConnection(o:UUID,c:UUID,s:Option[IncidentStatus],b:Option[IncidentCursor],l:Int)=IO.pure(List.empty[IncidentListItem])
    def listByResource(o:UUID,r:UUID,s:Option[IncidentStatus],b:Option[IncidentCursor],l:Int)=IO.pure(List.empty[IncidentListItem])
    def find(o:UUID,id:UUID)=findById(o,id).map(_.map(item))
    private def item(i:Incident)=IncidentListItem(i,IncidentResourceReference(i.resourceId,s"node-${i.resourceId.toString.take(4)}","NODE"),
      InfrastructureLocation(ProjectReference(ProjectId,"SvinPeak"),EnvironmentReference(EnvironmentId,"Production","PROD")),
      MonitorConditionView(i.monitorRuleId,"CPU_USAGE_PERCENT","GREATER_THAN",BigDecimal(85),300,900),None,
      List(SourceConnectionReference(ConnectionId,"Finnish Node","SSH",active=true)))
    def findOpenByRule(o:UUID,r:UUID)=IO.pure(values.find(x=>x.organizationId==o&&x.monitorRuleId==r&&x.status==IncidentStatus.Open))
    def findById(o:UUID,id:UUID)=failure match {case Some(e)=>IO.raiseError[Option[Incident]](e);case None=>IO.pure(values.find(x=>x.organizationId==o&&x.id==id))}
    def findByOrganization(o:UUID,s:Option[IncidentStatus])=failure match {case Some(e)=>IO.raiseError[List[Incident]](e);case None=>IO.pure(values.filter(x=>x.organizationId==o&&s.forall(_==x.status)))}
    def save(i:Incident)=IO.unit
    def saveAll(i:List[Incident])=IO.unit
  }
  private val Org=UUID.fromString("20000000-0000-0000-0000-000000000001");private val Other=UUID.fromString("20000000-0000-0000-0000-000000000002");private val OtherId=UUID.randomUUID();private val ProjectId=UUID.randomUUID();private val EnvironmentId=UUID.randomUUID();private val ConnectionId=UUID.randomUUID();private val Now=Instant.parse("2026-09-22T10:00:00Z")
  private val open=Incident(UUID.randomUUID(),Org,UUID.randomUUID(),UUID.randomUUID(),IncidentStatus.Open,IncidentReason.ThresholdViolation,Now,Now,None,Now,Now);private val resolved=open.copy(id=UUID.randomUUID(),status=IncidentStatus.Resolved,resolvedAt=Some(Now.plusSeconds(1)));private val noData=open.copy(id=UUID.randomUUID(),reason=IncidentReason.NoData)
}

package ru.bitec.app.ops
package infrastructure.http

import application.monitor.{CreateMonitorRule, ListMonitorRules, UpdateMonitorRule}
import application.port.{IdGenerator, IncidentRepository, MonitorRuleRepository, MonitorRuleStateRepository, ResourceRepository, TimeProvider, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.metric.MetricCode
import domain.incident.{Incident, IncidentStatus}
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleState, MonitorRuleStatus}
import domain.resource.Resource
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._

import java.time.Instant
import java.util.UUID

final class MonitorRuleRoutesSpec extends FunSuite {
  test("lists enabled and disabled rules for a resource in creation order") {
    val fixture = buildFixture(List(disabledRule, enabledRule))

    val response = run(fixture, getRules(OrganizationId, ResourceId))

    assertEquals(response._1.status, Status.Ok)
    assertEquals(response._2.asArray.map(_.size), Some(2))
    assertEquals(response._2.asArray.flatMap(_.head.hcursor.get[Boolean]("enabled").toOption), Some(false))
    assertEquals(response._2.asArray.flatMap(_.last.hcursor.get[Boolean]("enabled").toOption), Some(true))
    assertEquals(fixture.resourceRepository.requests, List(OrganizationId -> ResourceId))
    assertEquals(fixture.monitorRuleRepository.findByResourceRequests, List(OrganizationId -> ResourceId))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  test("exposes the no-data timeout and the last evaluated status of each rule") {
    val fixture = buildFixture(
      List(enabledRule),
      states = List(MonitorRuleState(OrganizationId, enabledRule.id, MonitorRuleStatus.NoData, None, Now))
    )

    val response = run(fixture, getRules(OrganizationId, ResourceId))
    val rule = response._2.asArray.flatMap(_.headOption).getOrElse(Json.Null).hcursor

    assertEquals(response._1.status, Status.Ok)
    assertEquals(rule.get[Long]("noDataSeconds"), Right(600L))
    assertEquals(rule.get[String]("operator"), Right("LESS_THAN"))
    assertEquals(rule.get[Option[String]]("status"), Right(Some("NO_DATA")))
  }

  test("reports no status for a rule that was never evaluated") {
    val fixture = buildFixture(List(enabledRule))

    val response = run(fixture, getRules(OrganizationId, ResourceId))
    val rule = response._2.asArray.flatMap(_.headOption).getOrElse(Json.Null).hcursor

    assertEquals(rule.get[Option[String]]("status"), Right(None))
  }

  test("returns an empty list for an existing resource without monitor rules") {
    val fixture = buildFixture(List.empty)

    val response = run(fixture, getRules(OrganizationId, ResourceId))

    assertEquals(response._1.status, Status.Ok)
    assertEquals(response._2.asArray, Some(Vector.empty))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  test("hides a resource from another organization") {
    val fixture = buildFixture(List(enabledRule))

    val response = run(fixture, getRules(OtherOrganizationId, ResourceId))

    assertEquals(response._1.status, Status.NotFound)
    assertEquals(response._2.hcursor.get[String]("code"), Right("RESOURCE_NOT_FOUND"))
    assertEquals(fixture.resourceRepository.requests, List(OtherOrganizationId -> ResourceId))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  test("creates a rule with the injected id and one timestamp for createdAt and updatedAt") {
    val fixture = buildFixture(List.empty)

    val response = run(fixture, postRule(OrganizationId, ResourceId, validBody))
    val saved = fixture.monitorRuleRepository.rules

    assertEquals(response._1.status, Status.Created)
    assertEquals(response._2.hcursor.get[String]("id"), Right(GeneratedRuleId.toString))
    assertEquals(response._2.hcursor.get[String]("metricCode"), Right("CPU_USAGE_PERCENT"))
    assertEquals(saved.map(_.id), List(GeneratedRuleId))
    assertEquals(saved.head.createdAt, Now)
    assertEquals(saved.head.updatedAt, Now)
    assertEquals(fixture.idGenerator.calls, 1)
    assertEquals(fixture.transactionRunner.calls, 1)
    // The creation is journalled in the same transaction, against the authenticated actor.
    assertEquals(
      fixture.auditEvents.recorded.map(event =>
        (event.action.code, event.targetType.code, event.targetId, event.actorUserId)),
      List(("MONITOR_RULE_CREATED", "MONITOR_RULE", Some(GeneratedRuleId),
        support.AuthorizationFixtures.ActorUserId))
    )
  }

  test("rejects unknown metric, unknown operator, negative duration, and malformed JSON") {
    val unknownMetric = validBody.deepMerge(Json.obj("metricCode" -> Json.fromString("DISK_USAGE_PERCENT")))
    val unknownOperator = validBody.deepMerge(Json.obj("operator" -> Json.fromString("BETWEEN")))
    val negativeDuration = validBody.deepMerge(Json.obj("forSeconds" -> Json.fromLong(-1)))
    val negativeNoData = validBody.deepMerge(Json.obj("noDataSeconds" -> Json.fromLong(-1)))
    val thresholdAbovePercent = validBody.deepMerge(Json.obj("threshold" -> Json.fromBigDecimal(BigDecimal("100.5"))))
    val missingNoData = Json.obj(
      "metricCode" -> Json.fromString("CPU_USAGE_PERCENT"),
      "operator" -> Json.fromString("GREATER_THAN"),
      "threshold" -> Json.fromBigDecimal(BigDecimal(90)),
      "forSeconds" -> Json.fromLong(0),
      "enabled" -> Json.fromBoolean(true)
    )

    List(
      postRule(OrganizationId, ResourceId, unknownMetric),
      postRule(OrganizationId, ResourceId, unknownOperator),
      postRule(OrganizationId, ResourceId, negativeDuration),
      postRule(OrganizationId, ResourceId, negativeNoData),
      postRule(OrganizationId, ResourceId, thresholdAbovePercent),
      postRule(OrganizationId, ResourceId, missingNoData),
      putRule(OrganizationId, enabledRule.id, negativeNoData),
      putRule(OrganizationId, enabledRule.id, thresholdAbovePercent),
      Request[IO](Method.POST, rulesUri(OrganizationId, ResourceId)).withEntity("{")
    ).foreach { request =>
      val response = run(buildFixture(List.empty), request)
      assertEquals(response._1.status, Status.BadRequest)
      assertEquals(response._2.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
    }
  }

  test("refuses a rule for a resource type monitoring does not support") {
    val fixture = buildFixture(List.empty)

    val response = run(fixture, postRule(OrganizationId, ContainerResourceId, validBody))

    assertEquals(response._1.status, Status.BadRequest)
    assertEquals(response._2.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
    assertEquals(fixture.monitorRuleRepository.rules, List.empty)
  }

  test("returns not found when creating for an absent resource") {
    val fixture = buildFixture(List.empty)

    val response = run(fixture, postRule(OrganizationId, UnknownResourceId, validBody))

    assertEquals(response._1.status, Status.NotFound)
    assertEquals(response._2.hcursor.get[String]("code"), Right("RESOURCE_NOT_FOUND"))
    assertEquals(fixture.transactionRunner.calls, 1)
    // Nothing happened, so nothing is journalled.
    assertEquals(fixture.auditEvents.recorded, List.empty)
  }

  test("updates mutable fields while preserving resource id and createdAt") {
    val fixture = buildFixture(List(enabledRule))
    val body = Json.obj(
      "metricCode" -> Json.fromString("MEMORY_USAGE_PERCENT"),
      "operator" -> Json.fromString("GREATER_THAN_OR_EQUAL"),
      "threshold" -> Json.fromBigDecimal(BigDecimal(80)),
      "forSeconds" -> Json.fromLong(300),
      "noDataSeconds" -> Json.fromLong(1200),
      "enabled" -> Json.fromBoolean(false)
    )

    val response = run(fixture, putRule(OrganizationId, enabledRule.id, body))
    val saved = fixture.monitorRuleRepository.rules.head

    assertEquals(response._1.status, Status.Ok)
    assertEquals(saved.id, enabledRule.id)
    assertEquals(saved.organizationId, enabledRule.organizationId)
    assertEquals(saved.resourceId, enabledRule.resourceId)
    assertEquals(saved.createdAt, enabledRule.createdAt)
    assertEquals(saved.metricCode, MetricCode.MemoryUsagePercent)
    assertEquals(saved.threshold, BigDecimal(80))
    assertEquals(saved.forSeconds, 300L)
    assertEquals(saved.noDataSeconds, 1200L)
    assertEquals(saved.operator, MonitorOperator.GreaterThanOrEqual)
    assertEquals(saved.enabled, false)
    assertEquals(saved.updatedAt, Now)
    assertEquals(fixture.transactionRunner.calls, 1)
    assertEquals(fixture.auditEvents.recorded.map(event => (event.action.code, event.targetId)),
      List(("MONITOR_RULE_UPDATED", Some(enabledRule.id))))
  }

  test("returns not found for unknown or foreign monitor rule updates") {
    val unknownFixture = buildFixture(List.empty)
    val foreignFixture = buildFixture(List(enabledRule))

    val unknown = run(unknownFixture, putRule(OrganizationId, UnknownRuleId, validBody))
    val foreign = run(foreignFixture, putRule(OtherOrganizationId, enabledRule.id, validBody))

    assertEquals(unknown._1.status, Status.NotFound)
    assertEquals(foreign._1.status, Status.NotFound)
    assertEquals(unknown._2.hcursor.get[String]("code"), Right("MONITOR_RULE_NOT_FOUND"))
    assertEquals(foreign._2.hcursor.get[String]("code"), Right("MONITOR_RULE_NOT_FOUND"))
    assertEquals(unknownFixture.transactionRunner.calls, 1)
    assertEquals(foreignFixture.transactionRunner.calls, 1)
  }

  test("sanitizes repository failures") {
    val fixture = buildFixture(List.empty, failure = Some(new IllegalStateException("database password")))

    val response = run(fixture, getRules(OrganizationId, ResourceId))

    assertEquals(response._1.status, Status.InternalServerError)
    assertEquals(response._2.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assertEquals(response._2.hcursor.get[String]("message"), Right("Internal server error"))
    assert(!response._2.noSpaces.contains("database password"))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  private def buildFixture(
    initialRules: List[MonitorRule],
    failure: Option[Throwable] = None,
    states: List[MonitorRuleState] = List.empty
  ): RouteFixture = {
    val resourceRepository = new InMemoryResourceRepository(
      Map(ResourceId -> resource, ContainerResourceId -> containerResource)
    )
    val monitorRuleRepository = new InMemoryMonitorRuleRepository(initialRules, failure)
    val monitorRuleStateRepository = new InMemoryMonitorRuleStateRepository(states)
    val incidentRepository = new InMemoryIncidentRepository
    val idGenerator = new FixedIdGenerator(GeneratedRuleId)
    val timeProvider = new FixedTimeProvider(Now)
    val transactionRunner = new RecordingTransactionRunner
    val (auditEvents, auditRecorder) = support.TestAuditRecorder.recording
    val routes = new MonitorRuleRoutes[IO](
      ListMonitorRules(resourceRepository, monitorRuleRepository, monitorRuleStateRepository),
      CreateMonitorRule(resourceRepository, monitorRuleRepository, idGenerator, timeProvider,
        auditRecorder),
      UpdateMonitorRule(monitorRuleRepository, monitorRuleStateRepository, incidentRepository,
        new application.notification.RecordNotificationDeliveries[IO](
          new NoNotificationDeliveryRepository, idGenerator, timeProvider, List.empty),
        auditRecorder,
        support.TestHistoryRecorder.recording._2,
        timeProvider),
      transactionRunner,
      support.AuthorizationFixtures.authorization
    )

    RouteFixture(support.AuthorizationFixtures.authorized(routes.routes.orNotFound),
      resourceRepository, monitorRuleRepository, idGenerator, transactionRunner, auditEvents)
  }

  private def run(fixture: RouteFixture, request: Request[IO]): (org.http4s.Response[IO], Json) = {
    val response = fixture.app.run(request).unsafeRunSync()
    response -> response.as[Json].unsafeRunSync()
  }

  private def getRules(organizationId: UUID, resourceId: UUID): Request[IO] =
    Request[IO](Method.GET, rulesUri(organizationId, resourceId))

  private def postRule(organizationId: UUID, resourceId: UUID, body: Json): Request[IO] =
    Request[IO](Method.POST, rulesUri(organizationId, resourceId)).withEntity(body)

  private def putRule(organizationId: UUID, monitorRuleId: UUID, body: Json): Request[IO] =
    Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/monitor-rules/$monitorRuleId"))
      .withEntity(body)

  private def rulesUri(organizationId: UUID, resourceId: UUID): Uri =
    Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/resources/$resourceId/monitor-rules")

  private final case class RouteFixture(
    app: org.http4s.HttpApp[IO],
    resourceRepository: InMemoryResourceRepository,
    monitorRuleRepository: InMemoryMonitorRuleRepository,
    idGenerator: FixedIdGenerator,
    transactionRunner: RecordingTransactionRunner,
    auditEvents: support.RecordingAuditEventRepository
  )

  private final class RecordingTransactionRunner extends TransactionRunner[IO, IO] {
    var calls: Int = 0

    override def run[A](program: IO[A]): IO[A] =
      IO { calls += 1 } *> program
  }

  private final class FixedIdGenerator(id: UUID) extends IdGenerator[IO] {
    var calls: Int = 0

    override def nextId: IO[UUID] = IO { calls += 1 }.as(id)
  }

  private final class FixedTimeProvider(value: Instant) extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(value)
  }

  private final class InMemoryResourceRepository(initial: Map[UUID, Resource]) extends ResourceRepository[IO] {
    var requests: List[(UUID, UUID)] = List.empty

    override def findById(organizationId: UUID, id: UUID): IO[Option[Resource]] =
      IO { requests = requests :+ (organizationId -> id) } *>
        IO.pure(initial.get(id).filter(_.organizationId == organizationId))

    override def findActiveByEnvironment(organizationId: UUID, environmentId: UUID): IO[List[Resource]] = IO.pure(List.empty)

    override def save(resource: Resource): IO[Unit] = IO.unit

    override def deactivateIfExclusiveToConnection(
      organizationId: UUID,
      id: UUID,
      connectionId: UUID,
      now: Instant
    ): IO[Boolean] = IO.pure(false)
  }

  private final class InMemoryMonitorRuleStateRepository(initial: List[MonitorRuleState])
    extends MonitorRuleStateRepository[IO] {
    var findByResourceRequests: List[(UUID, UUID)] = List.empty
    var deleted: List[(UUID, UUID)] = List.empty

    override def findByRuleId(organizationId: UUID, monitorRuleId: UUID): IO[Option[MonitorRuleState]] =
      IO.pure(initial.find(state => state.organizationId == organizationId && state.monitorRuleId == monitorRuleId))

    override def findByResource(organizationId: UUID, resourceId: UUID): IO[List[MonitorRuleState]] =
      IO { findByResourceRequests = findByResourceRequests :+ (organizationId -> resourceId) } *>
        IO.pure(initial.filter(_.organizationId == organizationId))

    override def deleteByRuleId(organizationId: UUID, monitorRuleId: UUID): IO[Unit] =
      IO { deleted = deleted :+ ((organizationId, monitorRuleId)) }

    override def save(state: MonitorRuleState): IO[Unit] = IO.unit

    override def saveAll(states: List[MonitorRuleState]): IO[Unit] = IO.unit
  }

  /** Notifications are off in these route tests: the outbox has its own coverage. */
  private final class NoNotificationDeliveryRepository
    extends application.port.NotificationDeliveryRepository[IO] {
    override def saveAll(deliveries: List[domain.notification.NotificationDelivery]): IO[Unit] = IO.unit
    override def findById(organizationId: UUID, id: UUID): IO[Option[domain.notification.NotificationDelivery]] =
      IO.pure(None)
    override def claimPending(claimedBy: UUID, limit: Int, leaseSeconds: Long): IO[List[domain.notification.NotificationDelivery]] =
      IO.pure(List.empty)
    override def markSent(organizationId: UUID, id: UUID, claimedBy: UUID, sentAt: Instant): IO[Boolean] =
      IO.pure(true)
    override def reschedule(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      nextAttemptAt: Instant, errorCode: String, updatedAt: Instant): IO[Boolean] = IO.pure(true)
    override def markDead(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      errorCode: String, updatedAt: Instant): IO[Boolean] = IO.pure(true)
  }

  private final class InMemoryIncidentRepository extends IncidentRepository[IO] {
    var saved: List[Incident] = List.empty

    override def findOpenByRule(organizationId: UUID, monitorRuleId: UUID): IO[Option[Incident]] = IO.pure(None)
    override def findById(organizationId: UUID, incidentId: UUID): IO[Option[Incident]] = IO.pure(None)
    override def findByOrganization(organizationId: UUID, status: Option[IncidentStatus]): IO[List[Incident]] =
      IO.pure(List.empty)
    override def save(incident: Incident): IO[Unit] = saveAll(List(incident))
    override def saveAll(incidents: List[Incident]): IO[Unit] = IO { saved = saved ++ incidents }
  }

  private final class InMemoryMonitorRuleRepository(
    initialRules: List[MonitorRule],
    failure: Option[Throwable]
  ) extends MonitorRuleRepository[IO] {
    var rules: List[MonitorRule] = initialRules
    var findByResourceRequests: List[(UUID, UUID)] = List.empty

    override def findById(organizationId: UUID, id: UUID): IO[Option[MonitorRule]] =
      operation(rules.find(rule => rule.organizationId == organizationId && rule.id == id))

    override def findEnabledByResource(organizationId: UUID, resourceId: UUID): IO[List[MonitorRule]] =
      operation(rules.filter(rule => rule.organizationId == organizationId && rule.resourceId == resourceId && rule.enabled))

    override def findByResource(organizationId: UUID, resourceId: UUID): IO[List[MonitorRule]] =
      IO { findByResourceRequests = findByResourceRequests :+ (organizationId -> resourceId) } *>
        operation(
          rules
            .filter(rule => rule.organizationId == organizationId && rule.resourceId == resourceId)
            .sortBy(rule => (rule.createdAt, rule.id.toString))
        )

    override def save(rule: MonitorRule): IO[Unit] =
      operation(()) *> IO { rules = rules.filterNot(_.id == rule.id) :+ rule }

    private def operation[A](value: => A): IO[A] =
      failure.fold(IO(value))(IO.raiseError)
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val ContainerResourceId = UUID.fromString("70000000-0000-0000-0000-000000000002")
  private val UnknownResourceId = UUID.fromString("70000000-0000-0000-0000-000000000099")
  private val ResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val GeneratedRuleId = UUID.fromString("80000000-0000-0000-0000-000000000001")
  private val UnknownRuleId = UUID.fromString("80000000-0000-0000-0000-000000000099")
  private val Now = Instant.parse("2026-09-22T10:00:00Z")
  private val Earlier = Now.minusSeconds(60)

  private val resource = Resource(
    ResourceId,
    OrganizationId,
    EnvironmentId,
    ResourceTypeId,
    None,
    "node-1",
    "node-1",
    isActive = true,
    Earlier,
    Earlier,
    "NODE"
  )

  private val containerResource = resource.copy(
    id = ContainerResourceId,
    code = "container-1",
    name = "container-1",
    resourceTypeCode = "CONTAINER"
  )

  private val disabledRule = MonitorRule(
    UUID.fromString("80000000-0000-0000-0000-000000000002"),
    OrganizationId,
    ResourceId,
    MetricCode.MemoryUsagePercent,
    MonitorOperator.GreaterThan,
    BigDecimal(80),
    60,
    900,
    enabled = false,
    Earlier,
    Earlier
  )

  private val enabledRule = MonitorRule(
    UUID.fromString("80000000-0000-0000-0000-000000000003"),
    OrganizationId,
    ResourceId,
    MetricCode.CpuUsagePercent,
    MonitorOperator.LessThan,
    BigDecimal(90),
    0,
    600,
    enabled = true,
    Now,
    Now
  )

  private val validBody = Json.obj(
    "metricCode" -> Json.fromString("CPU_USAGE_PERCENT"),
    "operator" -> Json.fromString("GREATER_THAN"),
    "threshold" -> Json.fromBigDecimal(BigDecimal(90)),
    "forSeconds" -> Json.fromLong(0),
    "noDataSeconds" -> Json.fromLong(900),
    "enabled" -> Json.fromBoolean(true)
  )
}

package ru.bitec.app.ops
package persistence.postgres

import application.context.{GetConnectionInfrastructureSummary, GetResourceContext, ListConnectionResources}
import application.incident.{GetIncidentDetail, IncidentPageRequest, ListConnectionIncidents, ListResourceIncidents}
import application.port.{IncidentCursor, SourceConnectionReference}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.incident.IncidentStatus
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeSpec, NodeStatus}
import domain.resource.{Resource, ResourceData}
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.util.log.{LogEvent, LogHandler}
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The infrastructure read models against a real database: `external_ref` is the only relation
  * between a connection and a resource, every read stays inside its organization, a resource
  * known to several connections keeps all of them, and no read costs a statement per row.
  */
final class InfrastructureContextIntegrationSpec extends FunSuite {

  private val Now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val NodeType = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ContainerType = UUID.fromString("10000000-0000-0000-0000-000000000002")

  test("a connection lists the resources it discovered once each, roots first, and nothing else") {
    withWorld { world =>
      for {
        finnish <- world.connection("Finnish Node")
        german <- world.connection("German Node")
        node <- world.node("fin-prod-01")
        backend <- world.container("backend", parent = Some(node))
        worker <- world.node("worker")
        _ <- world.discover(finnish, node)
        _ <- world.discover(finnish, backend)
        // A second reference of the same connection to the same resource must not duplicate it.
        _ <- world.discover(finnish, backend)
        _ <- world.discover(german, worker)
        listed <- world.run(world.connectionResources.execute(world.org, finnish))
        germanResources <- world.run(world.connectionResources.execute(world.org, german))
      } yield {
        assertEquals(listed.map(_.map(_.resource.name)), Some(List("fin-prod-01", "backend")))
        assertEquals(listed.map(_.map(_.environment.name).distinct), Some(List("Production")))
        assertEquals(germanResources.map(_.map(_.resource.name)), Some(List("worker")))
      }
    }
  }

  test("a connection of another organization is not found, even by its identifier") {
    withWorld { world =>
      for {
        foreign <- world.connection("Theirs", organization = world.foreignOrg)
        resources <- world.run(world.connectionResources.execute(world.org, foreign))
        incidents <- world.run(world.connectionIncidents.execute(world.org, foreign, page()))
        summary <- world.run(world.summary.execute(world.org, foreign))
      } yield {
        assertEquals(resources, None)
        assertEquals(incidents, None)
        assertEquals(summary, None)
      }
    }
  }

  test("connection incidents carry their resource, environment, project and rule; the status filter holds") {
    withWorld { world =>
      for {
        finnish <- world.connection("Finnish Node")
        backend <- world.container("backend")
        redis <- world.container("redis")
        _ <- world.discover(finnish, backend)
        _ <- world.discover(finnish, redis)
        cpu <- world.incident(backend, open = true, Now.minusSeconds(60))
        old <- world.incident(redis, open = false, Now.minusSeconds(600))
        all <- world.run(world.connectionIncidents.execute(world.org, finnish, page()))
        open <- world.run(world.connectionIncidents.execute(world.org, finnish, page(Some(IncidentStatus.Open))))
      } yield {
        assertEquals(all.map(_.map(_.incident.id)), Some(List(cpu, old)))
        assertEquals(open.map(_.map(_.incident.id)), Some(List(cpu)))
        val first = all.flatMap(_.headOption).getOrElse(fail("no incident"))
        assertEquals(first.resource.name, "backend")
        assertEquals(first.location.project.name, "Project")
        assertEquals(first.location.environment.name, "Production")
        assertEquals(first.monitorRule.operator, "GREATER_THAN")
        assertEquals(first.monitorRule.threshold, BigDecimal(90))
        assertEquals(first.sourceConnections.map(_.name), List("Finnish Node"))
      }
    }
  }

  test("pages continue after one exact row even when incidents opened at the same instant") {
    withWorld { world =>
      for {
        finnish <- world.connection("Finnish Node")
        backend <- world.container("backend")
        _ <- world.discover(finnish, backend)
        ids <- (1 to 5).toList.traverse(_ => world.incident(backend, open = false, Now))
        first <- world.run(world.connectionIncidents.execute(world.org, finnish, page(limit = 2)))
        last = first.flatMap(_.lastOption).getOrElse(fail("empty page"))
        second <- world.run(world.connectionIncidents.execute(world.org, finnish,
          page(limit = 10, before = Some(IncidentCursor(last.incident.openedAt, last.incident.id)))))
      } yield {
        val seen = first.toList.flatten.map(_.incident.id) ++ second.toList.flatten.map(_.incident.id)
        assertEquals(seen, ids.sortBy(_.toString).reverse)
      }
    }
  }

  test("open incidents of a connection page past any ceiling, once each, whatever the source fan-out") {
    withWorld { world =>
      def walk(connection: UUID, limit: Int): IO[List[UUID]] = {
        def loop(before: Option[IncidentCursor], seen: List[UUID]): IO[List[UUID]] =
          world.run(world.connectionIncidents.execute(world.org, connection,
            page(Some(IncidentStatus.Open), limit, before))).flatMap { result =>
            val rows = result.getOrElse(fail("connection not found"))
            val ids = seen ++ rows.map(_.incident.id)
            rows.lastOption match {
              case Some(last) if rows.size == limit => loop(Some(IncidentCursor(last.incident.openedAt, last.incident.id)), ids)
              case _ => IO.pure(ids)
            }
          }
        loop(None, List.empty)
      }

      for {
        finnish <- world.connection("Finnish Node")
        prometheus <- world.connection("Prometheus")
        backend <- world.container("backend")
        // Two sources: the lateral join must not turn one incident into two rows.
        _ <- world.discover(finnish, backend)
        _ <- world.discover(prometheus, backend)
        open <- (1 to 237).toList.traverse(index => world.incident(backend, open = true, if (index % 4 == 0) Now.minusSeconds(index.toLong) else Now))
        _ <- world.incident(backend, open = false, Now)
        seen <- walk(finnish, 50)
        first <- world.run(world.connectionIncidents.execute(world.org, finnish, page(Some(IncidentStatus.Open), 50)))
      } yield {
        assertEquals(seen.size, 237)
        assertEquals(seen.distinct.size, 237)
        assertEquals(seen.toSet, open.toSet)
        assertEquals(first.toList.flatten.head.sourceConnections.map(_.name), List("Finnish Node", "Prometheus"))
      }
    }
  }

  test("a resource context names its place, its parent and every source connection") {
    withWorld { world =>
      for {
        ssh <- world.connection("Finnish Node")
        prometheus <- world.connection("Prometheus")
        node <- world.node("fin-prod-01")
        backend <- world.container("backend", parent = Some(node))
        _ <- world.container("postgres", parent = Some(node))
        _ <- world.discover(ssh, backend)
        _ <- world.discover(prometheus, backend)
        _ <- world.incident(backend, open = true, Now)
        context <- world.run(world.resourceContext.execute(world.org, backend))
        nodeContext <- world.run(world.resourceContext.execute(world.org, node))
      } yield {
        val value = context.getOrElse(fail("no context"))
        assertEquals(value.location.environment.name, "Production")
        assertEquals(value.parent.map(_.name), Some("fin-prod-01"))
        assertEquals(value.sourceConnections.map(_.name), List("Finnish Node", "Prometheus"))
        assertEquals(value.openIncidentCount, 1L)
        val parent = nodeContext.getOrElse(fail("no context"))
        assertEquals(parent.children.map(_.name), List("backend", "postgres"))
        assertEquals(parent.activeChildCount, 2L)
        // A resource no connection discovered has no source, not an error.
        assertEquals(parent.sourceConnections, List.empty[SourceConnectionReference])
      }
    }
  }

  test("resource incidents and incident detail stay inside the resource and the organization") {
    withWorld { world =>
      for {
        backend <- world.container("backend")
        redis <- world.container("redis")
        mine <- world.incident(backend, open = true, Now)
        _ <- world.incident(redis, open = true, Now)
        foreignNode <- world.node("theirs", organization = world.foreignOrg)
        foreign <- world.incident(foreignNode, open = true, Now, organization = world.foreignOrg)
        listed <- world.run(world.resourceIncidents.execute(world.org, backend, page()))
        crossTenant <- world.run(world.resourceIncidents.execute(world.org, foreignNode, page()))
        detail <- world.run(world.incidentDetail.execute(world.org, mine))
        foreignDetail <- world.run(world.incidentDetail.execute(world.org, foreign))
        foreignContext <- world.run(world.resourceContext.execute(world.org, foreignNode))
      } yield {
        assertEquals(listed.map(_.map(_.incident.id)), Some(List(mine)))
        assertEquals(crossTenant, None)
        assertEquals(detail.map(_.resource.name), Some("backend"))
        assertEquals(foreignDetail, None)
        assertEquals(foreignContext, None)
      }
    }
  }

  test("the summary counts what the connection discovered and previews it") {
    withWorld { world =>
      for {
        finnish <- world.connection("Finnish Node")
        node <- world.node("fin-prod-01")
        backend <- world.container("backend", parent = Some(node))
        retired <- world.container("retired", active = false)
        _ <- List(node, backend, retired).traverse_(world.discover(finnish, _))
        _ <- world.incident(backend, open = true, Now)
        _ <- world.incident(backend, open = false, Now.minusSeconds(60))
        summary <- world.run(world.summary.execute(world.org, finnish))
      } yield {
        val value = summary.getOrElse(fail("no summary"))
        assertEquals(value.counts.activeResources, 2L)
        assertEquals(value.counts.inactiveResources, 1L)
        assertEquals(value.counts.openIncidents, 1L)
        assertEquals(value.counts.activeByType.map(count => (count.resourceTypeCode, count.count)),
          List(("CONTAINER", 1L), ("NODE", 1L)))
        assertEquals(value.openIncidents.size, 1)
        assertEquals(value.resources.map(_.resource.name), List("fin-prod-01", "backend"))
      }
    }
  }

  test("organization connection counts and environment sources are one statement each") {
    withWorld { world =>
      for {
        finnish <- world.connection("Finnish Node")
        empty <- world.connection("Empty")
        backend <- world.container("backend")
        _ <- world.discover(finnish, backend)
        _ <- world.discover(finnish, backend)
        _ <- world.incident(backend, open = true, Now)
        _ <- world.incident(backend, open = true, Now.minusSeconds(5))
        counts <- world.run(world.query.organizationConnectionCounts(world.org))
        sources <- world.run(world.query.environmentResourceSources(world.org, world.environment))
      } yield {
        val byId = counts.map(value => value.connectionId -> (value.activeResourceCount, value.openIncidentCount)).toMap
        assertEquals(byId.get(finnish), Some((1L, 2L)))
        assertEquals(byId.get(empty), Some((0L, 0L)))
        assertEquals(sources.map(value => (value.resourceId, value.sourceConnections.map(_.name))),
          List((backend, List("Finnish Node"))))
      }
    }
  }

  test("no read costs a statement per row") {
    withWorld { world =>
      def statements[A](program: ConnectionIO[A]): IO[Int] =
        Ref.of[IO, Int](0).flatMap { counter =>
          val handler = new LogHandler[IO] {
            override def run(event: LogEvent): IO[Unit] = counter.update(_ + 1)
          }
          val xa = Transactor.fromDriverManager[IO](
            "org.postgresql.Driver", world.config.url, world.config.user, world.config.password, Some(handler))
          new DoobieTransactionRunner(xa).run(program) *> counter.get
        }

      def costs(connection: UUID, node: UUID, incident: UUID): IO[List[Int]] =
        List(
          statements(world.summary.execute(world.org, connection)),
          statements(world.connectionResources.execute(world.org, connection)),
          statements(world.connectionIncidents.execute(world.org, connection, page())),
          statements(world.resourceContext.execute(world.org, node)),
          statements(world.resourceIncidents.execute(world.org, node, page())),
          statements(world.incidentDetail.execute(world.org, incident))
        ).sequence

      for {
        finnish <- world.connection("Finnish Node")
        node <- world.node("fin-prod-01")
        _ <- world.discover(finnish, node)
        incident <- world.incident(node, open = true, Now)
        small <- costs(finnish, node, incident)
        _ <- (1 to 20).toList.traverse_ { index =>
          for {
            other <- world.connection(s"Other $index")
            container <- world.container(s"container-$index", parent = Some(node))
            _ <- world.discover(finnish, container)
            _ <- world.discover(other, container)
            _ <- world.incident(container, open = index % 2 == 0, Now.minusSeconds(index.toLong))
          } yield ()
        }
        large <- costs(finnish, node, incident)
      } yield {
        assertEquals(small, List(5, 2, 2, 3, 2, 1))
        assertEquals(large, small)
      }
    }
  }

  private def page(
    status: Option[IncidentStatus] = None,
    limit: Int = IncidentPageRequest.DefaultLimit,
    before: Option[IncidentCursor] = None
  ): IncidentPageRequest = IncidentPageRequest(status, before, limit)

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private def withWorld(body: World => IO[Unit]): Unit = {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    val config = PostgresTestDatabase.config
    PostgresTestDatabase.transactor(config).use { xa =>
      val world = new World(xa, config)
      (world.setUp *> body(world)).guarantee(world.cleanUp)
    }.unsafeRunSync()
  }

  private final class World(
    xa: Transactor[IO],
    val config: infrastructure.database.DatabaseConfig
  ) {
    private val runner = new DoobieTransactionRunner(xa)
    private val resources = ProductionResourceCodec.resourceRepository
    private val suffix = UUID.randomUUID().toString.take(8)

    val org: UUID = UUID.randomUUID()
    val foreignOrg: UUID = UUID.randomUUID()
    private val environments = Map(org -> UUID.randomUUID(), foreignOrg -> UUID.randomUUID())
    val environment: UUID = environments(org)

    val query = new PostgresInfrastructureContextQuery(ProductionResourceCodec.codec)
    private val incidents = new PostgresIncidentListQuery
    val summary = GetConnectionInfrastructureSummary[ConnectionIO](query, incidents)
    val connectionResources = ListConnectionResources[ConnectionIO](query)
    val connectionIncidents = ListConnectionIncidents[ConnectionIO](query, incidents)
    val resourceContext = GetResourceContext[ConnectionIO](query)
    val resourceIncidents = ListResourceIncidents[ConnectionIO](query, incidents)
    val incidentDetail = GetIncidentDetail[ConnectionIO](incidents)

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def setUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
      val project = UUID.randomUUID()
      val environment = environments(id)
      for {
        _ <- sql"insert into organization (id, code, name) values ($id, ${s"context-$suffix-${id.toString.take(4)}"}, 'Context')".update.run
        _ <- sql"insert into project (id, organization_id, code, name) values ($project, $id, 'p', 'Project')".update.run
        _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($environment, $id, $project, 'e', 'Production', 'PROD')".update.run
      } yield ()
    })

    def cleanUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
      for {
        _ <- sql"delete from incident where organization_id = $id".update.run
        _ <- sql"delete from monitor_rule where organization_id = $id".update.run
        _ <- sql"delete from external_ref where organization_id = $id".update.run
        _ <- sql"delete from resource where organization_id = $id and parent_resource_id is not null".update.run
        _ <- sql"delete from resource where organization_id = $id".update.run
        _ <- sql"delete from connection where organization_id = $id".update.run
        _ <- sql"delete from environment where organization_id = $id".update.run
        _ <- sql"delete from project where organization_id = $id".update.run
        _ <- sql"delete from organization where id = $id".update.run
      } yield ()
    })

    def connection(name: String, organization: UUID = org): IO[UUID] = {
      val id = UUID.randomUUID()
      run(sql"""
        insert into connection (id, organization_id, scope_type, connector_type, code, name)
        values ($id, $organization, 'ORGANIZATION', 'SSH', ${id.toString.take(32)}, $name)
      """.update.run).as(id)
    }

    def discover(connection: UUID, resource: UUID): IO[Unit] =
      run(sql"""
        insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id)
        values (${UUID.randomUUID()}, $org, $connection, 'NODE', ${UUID.randomUUID().toString}, $resource)
      """.update.run).void

    def node(name: String, organization: UUID = org): IO[UUID] =
      resource(organization, NodeType, "NODE", name, None, active = true, ResourceData(
        Some(NodeSpec(name, None, None, None, None)), Some(NodeStatus(online = true, None, None, None))))

    def container(name: String, parent: Option[UUID] = None, active: Boolean = true): IO[UUID] =
      resource(org, ContainerType, "CONTAINER", name, parent, active,
        ResourceData(Some(ContainerSpec(Some("nginx"))), Some(ContainerStatus(Some("running")))))

    /** Through the production repository and codecs, so the stored rows are the real ones. */
    private def resource(organization: UUID, typeId: UUID, typeCode: String, name: String, parent: Option[UUID],
                         active: Boolean, data: ResourceData): IO[UUID] = {
      val id = UUID.randomUUID()
      run(resources.save(Resource(id, organization, environments(organization), typeId, parent, name, name,
        active, Now, Now, typeCode, data))).as(id)
    }

    def incident(resource: UUID, open: Boolean, openedAt: Instant, organization: UUID = org): IO[UUID] = {
      val rule = UUID.randomUUID()
      val id = UUID.randomUUID()
      val status = if (open) "OPEN" else "RESOLVED"
      val resolvedAt = Option.when(!open)(openedAt.plusSeconds(60))
      run(for {
        _ <- sql"""
          insert into monitor_rule (id, organization_id, resource_id, metric_code, operator, threshold,
            for_seconds, no_data_seconds, enabled, created_at, updated_at)
          values ($rule, $organization, $resource, 'CPU_USAGE_PERCENT', 'GREATER_THAN', 90, 0, 900, true, $Now, $Now)
        """.update.run
        _ <- sql"""
          insert into incident (id, organization_id, monitor_rule_id, resource_id, status, reason,
            started_at, opened_at, resolved_at, created_at, updated_at)
          values ($id, $organization, $rule, $resource, $status, 'THRESHOLD', $openedAt, $openedAt, $resolvedAt, $Now, $Now)
        """.update.run
      } yield id)
    }
  }
}

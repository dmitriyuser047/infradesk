package ru.bitec.app.ops
package persistence.postgres

import application.incident.{IncidentPageRequest, ListIncidents}
import application.port.IncidentCursor
import application.port.IncidentResourceReference
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

/** The incident list read projection against a real database: every row carries the identity of
  * its resource, the tenant boundary holds, and the list costs one statement however many
  * resources it mentions — no read per row.
  */
final class IncidentListIntegrationSpec extends FunSuite {

  private val Now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val NodeType = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ContainerType = UUID.fromString("10000000-0000-0000-0000-000000000002")

  test("every incident carries the name and type of its resource, newest first") {
    withWorld { world =>
      for {
        node <- world.node("web-1")
        container <- world.container("nginx")
        retired <- world.node("retired", active = false)
        older <- world.incident(node, open = true, Now.minusSeconds(300))
        newer <- world.incident(container, open = true, Now.minusSeconds(60))
        resolved <- world.incident(retired, open = false, Now.minusSeconds(600))
        all <- world.list(None)
        open <- world.list(Some(IncidentStatus.Open))
        closed <- world.list(Some(IncidentStatus.Resolved))
      } yield {
        assertEquals(all.map(_.incident.id), List(newer, older, resolved))
        assertEquals(all.map(_.resource), List(
          IncidentResourceReference(container, "nginx", "CONTAINER"),
          IncidentResourceReference(node, "web-1", "NODE"),
          // A resource inventory deactivated still names its incidents.
          IncidentResourceReference(retired, "retired", "NODE")))
        assertEquals(open.map(_.incident.id), List(newer, older))
        assertEquals(closed.map(_.incident.id), List(resolved))
      }
    }
  }

  test("another organization's incidents and resources never appear") {
    withWorld { world =>
      for {
        node <- world.node("mine")
        mine <- world.incident(node, open = true, Now)
        foreignNode <- world.node("theirs", organization = world.foreignOrg)
        _ <- world.incident(foreignNode, open = true, Now, organization = world.foreignOrg)
        listed <- world.list(None)
      } yield {
        assertEquals(listed.map(_.incident.id), List(mine))
        assertEquals(listed.map(_.resource.name), List("mine"))
      }
    }
  }

  test("the list costs one statement whatever the number of distinct resources") {
    withWorld { world =>
      def statements: IO[(Int, Int)] =
        Ref.of[IO, Int](0).flatMap { counter =>
          val handler = new LogHandler[IO] {
            override def run(event: LogEvent): IO[Unit] = counter.update(_ + 1)
          }
          val xa = Transactor.fromDriverManager[IO](
            "org.postgresql.Driver", world.config.url, world.config.user, world.config.password,
            Some(handler))
          new DoobieTransactionRunner(xa).run(world.useCase.execute(world.org, IncidentPageRequest(None, None, IncidentPageRequest.MaxLimit)))
            .flatMap(items => counter.get.map(count => (count, items.size)))
        }

      for {
        first <- world.node("node-0")
        _ <- world.incident(first, open = true, Now)
        small <- statements
        _ <- (1 to 30).toList.traverse_ { index =>
          for {
            node <- world.node(s"node-$index")
            _ <- world.incident(node, open = index % 2 == 0, Now.minusSeconds(index.toLong))
            container <- world.container(s"container-$index")
            _ <- world.incident(container, open = true, Now.minusSeconds(index.toLong))
          } yield ()
        }
        large <- statements
      } yield {
        assertEquals(small, (1, 1))
        assertEquals(large, (1, 61))
      }
    }
  }

  test("pages through every incident without loss or duplicate, even at one instant") {
    withWorld { world =>
      // Walks the whole list a page at a time, the way the incident list does.
      def walk(status: Option[IncidentStatus], limit: Int): IO[List[UUID]] = {
        def loop(before: Option[IncidentCursor], seen: List[UUID]): IO[List[UUID]] =
          world.page(status, before, limit).flatMap { page =>
            val ids = seen ++ page.map(_.incident.id)
            page.lastOption match {
              case Some(last) if page.size == limit => loop(Some(IncidentCursor(last.incident.openedAt, last.incident.id)), ids)
              case _ => IO.pure(ids)
            }
          }
        loop(None, List.empty)
      }

      for {
        node <- world.node("busy")
        // More open incidents than any old ceiling, most of them opened at the very same instant.
        open <- (1 to 230).toList.traverse(index => world.incident(node, open = true, if (index % 3 == 0) Now.minusSeconds(index.toLong) else Now))
        resolved <- (1 to 30).toList.traverse(_ => world.incident(node, open = false, Now))
        foreignNode <- world.node("theirs", organization = world.foreignOrg)
        _ <- (1 to 5).toList.traverse_(_ => world.incident(foreignNode, open = true, Now, organization = world.foreignOrg))
        openIds <- walk(Some(IncidentStatus.Open), 50)
        resolvedIds <- walk(Some(IncidentStatus.Resolved), 7)
        allIds <- walk(None, 50)
      } yield {
        assertEquals(openIds.size, 230)
        assertEquals(openIds.distinct.size, 230)
        assertEquals(openIds.toSet, open.toSet)
        assertEquals(resolvedIds.toSet, resolved.toSet)
        assertEquals(resolvedIds.distinct.size, 30)
        // Another organization's incidents are never on any page.
        assertEquals(allIds.toSet, (open ++ resolved).toSet)
        assertEquals(allIds.distinct.size, 260)
      }
    }
  }

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

    val useCase = ListIncidents[ConnectionIO](new PostgresIncidentListQuery)

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def list(status: Option[IncidentStatus]) = page(status, None, IncidentPageRequest.MaxLimit)

    def page(status: Option[IncidentStatus], before: Option[IncidentCursor], limit: Int) =
      run(useCase.execute(org, IncidentPageRequest(status, before, limit)))

    def setUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
      val project = UUID.randomUUID()
      val environment = environments(id)
      for {
        _ <- sql"insert into organization (id, code, name) values ($id, ${s"incidents-$suffix-${id.toString.take(4)}"}, 'Incidents')".update.run
        _ <- sql"insert into project (id, organization_id, code, name) values ($project, $id, 'p', 'Project')".update.run
        _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($environment, $id, $project, 'e', 'Env', 'PROD')".update.run
      } yield ()
    })

    def cleanUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
      for {
        _ <- sql"delete from incident where organization_id = $id".update.run
        _ <- sql"delete from monitor_rule where organization_id = $id".update.run
        _ <- sql"delete from resource where organization_id = $id".update.run
        _ <- sql"delete from environment where organization_id = $id".update.run
        _ <- sql"delete from project where organization_id = $id".update.run
        _ <- sql"delete from organization where id = $id".update.run
      } yield ()
    })

    def node(name: String, active: Boolean = true, organization: UUID = org): IO[UUID] =
      resource(organization, NodeType, "NODE", name, active, ResourceData(
        Some(NodeSpec(name, None, None, None, None)), Some(NodeStatus(online = true, None, None, None))))

    def container(name: String): IO[UUID] =
      resource(org, ContainerType, "CONTAINER", name, active = true,
        ResourceData(Some(ContainerSpec(Some("nginx"))), Some(ContainerStatus(Some("running")))))

    /** Through the production repository and codecs, so the stored rows are the real ones. */
    private def resource(organization: UUID, typeId: UUID, typeCode: String, name: String, active: Boolean,
                         data: ResourceData): IO[UUID] = {
      val id = UUID.randomUUID()
      run(resources.save(Resource(id, organization, environments(organization), typeId, None, name, name,
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

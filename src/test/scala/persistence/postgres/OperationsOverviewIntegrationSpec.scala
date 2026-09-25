package ru.bitec.app.ops
package persistence.postgres

import application.overview.{GetOperationsOverview, OperationsOverview, OperationsOverviewPolicy}
import application.port._
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.ConnectionScope
import domain.history.{HistoryEvent, HistoryEventSource, HistoryEventType}
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeSpec, NodeStatus}
import domain.resource.{Resource, ResourceData}
import infrastructure.database.{DoobieReadOnlySnapshotRunner, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.util.log.{LogEvent, LogHandler}
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The operations overview against a real database: what it counts, what it reports as a current
  * problem and in which order, how it respects the hierarchy and the tenant, and what it costs.
  *
  * Every test builds its own organization, so counts are exact and nothing leaks between tests.
  */
final class OperationsOverviewIntegrationSpec extends FunSuite {

  private val Now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val NodeType = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ContainerType = UUID.fromString("10000000-0000-0000-0000-000000000002")

  test("an empty organization is all zeros, with nothing to attend to and no activity") {
    withWorld { world =>
      world.overview(ConnectionScope.Organization).map { overview =>
        val summary = overview.summary
        assertEquals(summary.nodes, NodeSummary(0, 0, 0))
        assertEquals(summary.containers, ContainerSummary(0, 0, 0))
        assertEquals(summary.connections, ConnectionSummary(0, 0, 0, 0))
        assertEquals(summary.incidents, IncidentSummary(0, 0, 0))
        assertEquals(summary.operations, OperationSummary(0, 0))
        assertEquals(overview.attention, AttentionPage(Nil, 0))
        assertEquals(overview.recentActivity, Nil)
      }
    }
  }

  test("nodes and containers are counted by the state inventory stored, inactive ones not at all") {
    withWorld { world =>
      for {
        _ <- world.node(world.env1, "online", online = true)
        _ <- world.node(world.env1, "offline", online = false)
        _ <- world.node(world.env1, "gone", online = true, active = false)
        _ <- world.container(world.env1, "web", Some("running"))
        _ <- world.container(world.env1, "worker", Some("Running"))
        _ <- world.container(world.env1, "job", Some("exited"))
        _ <- world.container(world.env1, "broken", Some("dead"))
        _ <- world.container(world.env1, "paused", Some("paused"))
        _ <- world.container(world.env1, "old", Some("running"), active = false)
        overview <- world.overview(ConnectionScope.Organization)
      } yield {
        assertEquals(overview.summary.nodes, NodeSummary(total = 2, online = 1, offline = 1))
        assertEquals(overview.summary.containers, ContainerSummary(total = 5, running = 2, stopped = 2))
      }
    }
  }

  test("only NODE or only CONTAINER inventory leaves the other type at zero") {
    withWorld { world =>
      for {
        _ <- world.node(world.env1, "only-node", online = true)
        nodesOnly <- world.overview(ConnectionScope.Organization)
        _ <- world.run(sql"update resource set is_active = false where organization_id = ${world.org}".update.run)
        _ <- world.container(world.env1, "only-container", Some("running"))
        containersOnly <- world.overview(ConnectionScope.Organization)
      } yield {
        assertEquals(nodesOnly.summary.nodes, NodeSummary(1, 1, 0))
        assertEquals(nodesOnly.summary.containers, ContainerSummary(0, 0, 0))
        assertEquals(containersOnly.summary.nodes, NodeSummary(0, 0, 0))
        assertEquals(containersOnly.summary.containers, ContainerSummary(1, 1, 0))
      }
    }
  }

  test("open incidents are current problems with their reason; resolved ones are not") {
    withWorld { world =>
      for {
        node <- world.node(world.env1, "db", online = true)
        threshold <- world.incident(node, "THRESHOLD", open = true, openedAt = Now.minusSeconds(60))
        noData <- world.incident(node, "NO_DATA", open = true, openedAt = Now.minusSeconds(30))
        _ <- world.incident(node, "THRESHOLD", open = false, openedAt = Now.minusSeconds(600))
        overview <- world.overview(ConnectionScope.Organization)
      } yield {
        assertEquals(overview.summary.incidents, IncidentSummary(open = 2, threshold = 1, noData = 1))
        val incidents = overview.attention.items.collect { case value: IncidentAttention => value }
        assertEquals(incidents.map(_.id), List(noData, threshold))
        assertEquals(incidents.map(_.reason), List("NO_DATA", "THRESHOLD"))
        assertEquals(incidents.head.resource.name, "db")
        assertEquals(incidents.head.resource.environmentId, world.env1)
        assertEquals(incidents.head.metricCode, "CPU_USAGE_PERCENT")
      }
    }
  }

  test("a connection is judged by its latest finished synchronization, and appears once") {
    withWorld { world =>
      for {
        recovered <- world.connection("recovered", ConnectionScope.Environment(world.project1, world.env1))
        _ <- world.sync(recovered, "FAILED", Now.minusSeconds(600))
        _ <- world.sync(recovered, "COMPLETED", Now.minusSeconds(300))
        broken <- world.connection("broken", ConnectionScope.Environment(world.project1, world.env1))
        _ <- (1 to 10).toList.traverse_(minute => world.sync(broken, "FAILED", Now.minusSeconds(60L * minute)))
        retrying <- world.connection("retrying", ConnectionScope.Project(world.project1))
        latestFailure <- world.sync(retrying, "FAILED", Now.minusSeconds(120))
        _ <- world.sync(retrying, "RUNNING", Now.minusSeconds(10))
        _ <- world.connection("fresh", ConnectionScope.Organization)
        removed <- world.connection("removed", ConnectionScope.Organization, active = false)
        _ <- world.sync(removed, "FAILED", Now.minusSeconds(10))
        overview <- world.overview(ConnectionScope.Organization)
      } yield {
        assertEquals(overview.summary.connections,
          ConnectionSummary(total = 4, healthy = 1, failing = 2, neverSynced = 1))
        val syncs = overview.attention.items.collect { case value: SyncFailureAttention => value }
        // Ten failed sessions of one connection are one current problem; history keeps the rest.
        assertEquals(syncs.map(_.connection.name).sorted, List("broken", "retrying"))
        assertEquals(syncs.find(_.connection.id == retrying).map(_.id), Some(latestFailure))
        assertEquals(syncs.head.errorCode, Some("SSH_HOST_KEY_MISMATCH"))
      }
    }
  }

  test("an operation is a problem only while it is the latest outcome inside the horizon") {
    withWorld { world =>
      for {
        failed <- world.container(world.env1, "failed", Some("running"))
        failedOp <- world.operation(failed, "FAILED", Now.minusSeconds(300))
        unknown <- world.container(world.env1, "unknown", Some("running"))
        unknownOp <- world.operation(unknown, "UNKNOWN", Now.minusSeconds(200))
        retried <- world.container(world.env1, "retried", Some("running"))
        _ <- world.operation(retried, "FAILED", Now.minusSeconds(900))
        _ <- world.operation(retried, "SUCCEEDED", Now.minusSeconds(600))
        running <- world.container(world.env1, "running", Some("running"))
        _ <- world.operation(running, "FAILED", Now.minusSeconds(900))
        _ <- world.operation(running, "RUNNING", Now.minusSeconds(5))
        stale <- world.container(world.env1, "stale", Some("exited"))
        _ <- world.operation(stale, "FAILED",
          Now.minus(OperationsOverviewPolicy.OperationsHorizon).minusSeconds(60))
        overview <- world.overview(ConnectionScope.Organization)
      } yield {
        assertEquals(overview.summary.operations, OperationSummary(failed = 1, unknown = 1))
        val operations = overview.attention.items.collect { case value: OperationAttention => value }
        assertEquals(operations.map(value => value.id -> value.kind),
          List[(UUID, AttentionKind)](unknownOp -> AttentionKind.OperationUnknown, failedOp -> AttentionKind.OperationFailed))
        assertEquals(operations.map(_.resource.name), List("unknown", "failed"))
        assertEquals(operations.head.errorCode, Some("OPERATION_RESULT_UNKNOWN"))
        assertEquals(operations.head.operationCode, "CONTAINER_RESTART")
      }
    }
  }

  test("a deactivated resource keeps an unknown operation result, but not a failure") {
    withWorld { world =>
      for {
        goneFailed <- world.container(world.env1, "gone-failed", Some("exited"), active = false)
        _ <- world.operation(goneFailed, "FAILED", Now.minusSeconds(100))
        goneUnknown <- world.container(world.env1, "gone-unknown", Some("running"), active = false)
        unknownOp <- world.operation(goneUnknown, "UNKNOWN", Now.minusSeconds(200))
        liveFailed <- world.container(world.env1, "live-failed", Some("running"))
        failedOp <- world.operation(liveFailed, "FAILED", Now.minusSeconds(300))
        overview <- world.overview(ConnectionScope.Organization)
        environment <- world.overview(ConnectionScope.Environment(world.project1, world.env1))
      } yield {
        List(overview, environment).foreach { value =>
          // The summary follows the same policy as the list, so the card and the list agree.
          assertEquals(value.summary.operations, OperationSummary(failed = 1, unknown = 1))
          val operations = value.attention.items.collect { case item: OperationAttention => item }
          assertEquals(operations.map(item => item.id -> item.resource.name), List(
            unknownOp -> "gone-unknown",
            failedOp -> "live-failed"
          ))
          assertEquals(value.attention.total, 2)
        }
      }
    }
  }

  test("attention is ordered by priority, then newest first, and a bounded page carries the total") {
    withWorld { world =>
      for {
        node <- world.node(world.env1, "node", online = false)
        _ <- world.incident(node, "THRESHOLD", open = true, openedAt = Now.minusSeconds(3000))
        connection <- world.connection("ssh", ConnectionScope.Environment(world.project1, world.env1))
        _ <- world.sync(connection, "FAILED", Now.minusSeconds(1))
        failed <- world.container(world.env1, "failed", Some("running"))
        _ <- world.operation(failed, "FAILED", Now.minusSeconds(2))
        unknown <- world.container(world.env1, "unknown", Some("running"))
        _ <- world.operation(unknown, "UNKNOWN", Now.minusSeconds(5000))
        ordered <- world.overview(ConnectionScope.Organization)
        _ <- (1 to 25).toList.traverse_ { index =>
          world.container(world.env2, s"batch-$index", Some("running"))
            .flatMap(id => world.operation(id, "FAILED", Now.minusSeconds(index.toLong)))
        }
        bounded <- world.overview(ConnectionScope.Organization)
      } yield {
        assertEquals(ordered.attention.items.map(_.kind), List[AttentionKind](
          AttentionKind.OperationUnknown,
          AttentionKind.Incident,
          AttentionKind.NodeOffline,
          AttentionKind.SyncFailed,
          AttentionKind.OperationFailed
        ))
        assertEquals(ordered.attention.total, 5)
        assertEquals(bounded.attention.items.size, OperationsOverviewPolicy.AttentionLimit)
        assertEquals(bounded.attention.total, 30)
        val failures = bounded.attention.items.collect {
          case value: OperationAttention if value.kind == AttentionKind.OperationFailed => value.occurredAt
        }
        assertEquals(failures, failures.sorted(Ordering[Instant].reverse))
      }
    }
  }

  test("project and environment scopes count only what lives inside them") {
    withWorld { world =>
      for {
        inEnv1 <- world.node(world.env1, "env1-node", online = true)
        inEnv2 <- world.container(world.env2, "env2-container", Some("exited"))
        inProject2 <- world.node(world.env3, "project2-node", online = false)
        _ <- world.incident(inEnv1, "NO_DATA", open = true, openedAt = Now.minusSeconds(10))
        _ <- world.operation(inEnv2, "FAILED", Now.minusSeconds(10))
        _ <- world.incident(inProject2, "THRESHOLD", open = true, openedAt = Now.minusSeconds(10))
        env1Connection <- world.connection("env1", ConnectionScope.Environment(world.project1, world.env1))
        _ <- world.sync(env1Connection, "FAILED", Now.minusSeconds(10))
        projectConnection <- world.connection("project1", ConnectionScope.Project(world.project1))
        _ <- world.sync(projectConnection, "COMPLETED", Now.minusSeconds(10))
        _ <- world.connection("organization", ConnectionScope.Organization)
        organization <- world.overview(ConnectionScope.Organization)
        project <- world.overview(ConnectionScope.Project(world.project1))
        environment <- world.overview(ConnectionScope.Environment(world.project1, world.env1))
        otherProject <- world.overview(ConnectionScope.Project(world.project2))
      } yield {
        assertEquals(organization.summary.nodes.total, 2)
        assertEquals(organization.summary.connections.total, 3)
        assertEquals(organization.attention.total, 5)

        assertEquals(project.summary.nodes, NodeSummary(1, 1, 0))
        assertEquals(project.summary.containers, ContainerSummary(1, 0, 1))
        assertEquals(project.summary.incidents, IncidentSummary(1, 0, 1))
        assertEquals(project.summary.connections, ConnectionSummary(2, 1, 1, 0))
        assertEquals(project.attention.items.map(_.kind).toSet,
          Set[AttentionKind](AttentionKind.Incident, AttentionKind.SyncFailed, AttentionKind.OperationFailed))

        assertEquals(environment.summary.nodes, NodeSummary(1, 1, 0))
        assertEquals(environment.summary.containers, ContainerSummary(0, 0, 0))
        assertEquals(environment.summary.operations, OperationSummary(0, 0))
        assertEquals(environment.summary.connections, ConnectionSummary(1, 0, 1, 0))
        assertEquals(environment.attention.items.map(_.kind).toSet,
          Set[AttentionKind](AttentionKind.Incident, AttentionKind.SyncFailed))

        assertEquals(otherProject.summary.nodes, NodeSummary(1, 0, 1))
        assertEquals(otherProject.summary.connections.total, 0)
        assertEquals(otherProject.attention.items.map(_.kind),
          List[AttentionKind](AttentionKind.Incident, AttentionKind.NodeOffline))
      }
    }
  }

  test("a project or environment of another organization, or of another project, is not found") {
    withWorld { world =>
      for {
        foreignProject <- world.foreignProject
        foreignEnvironment <- world.foreignEnvironment(foreignProject)
        project <- world.execute(ConnectionScope.Project(foreignProject))
        environment <- world.execute(ConnectionScope.Environment(foreignProject, foreignEnvironment))
        mismatched <- world.execute(ConnectionScope.Environment(world.project2, world.env1))
        spoofed <- world.execute(ConnectionScope.Environment(world.project1, foreignEnvironment))
        own <- world.execute(ConnectionScope.Environment(world.project1, world.env1))
      } yield {
        assertEquals(project, None)
        assertEquals(environment, None)
        assertEquals(mismatched, None)
        assertEquals(spoofed, None)
        assert(own.isDefined)
      }
    }
  }

  test("another organization's infrastructure never reaches the overview") {
    withWorld { world =>
      for {
        foreignProject <- world.foreignProject
        foreignEnvironment <- world.foreignEnvironment(foreignProject)
        foreignNode <- world.foreignNode(foreignEnvironment)
        _ <- world.run(sql"""
          insert into history_event (id, organization_id, event_type, source, resource_id,
            occurred_at, created_at)
          values (${UUID.randomUUID()}, ${world.foreignOrg}, 'RESOURCE_DISCOVERED', 'SYSTEM',
            $foreignNode, $Now, $Now)
        """.update.run)
        overview <- world.overview(ConnectionScope.Organization)
      } yield {
        assertEquals(overview.summary.nodes.total, 0)
        assertEquals(overview.attention.total, 0)
        assertEquals(overview.recentActivity, Nil)
      }
    }
  }

  test("recent activity is the scoped, bounded, newest-first slice of the timeline") {
    withWorld { world =>
      for {
        env1Node <- world.node(world.env1, "env1", online = true)
        env3Node <- world.node(world.env3, "env3", online = true)
        env1Connection <- world.connection("env1", ConnectionScope.Environment(world.project1, world.env1))
        project2Connection <- world.connection("p2", ConnectionScope.Project(world.project2))
        env1Events = (1 to 20).toList.map(index =>
          world.event(HistoryEventType.ResourceDiscovered, Now.minusSeconds(index.toLong), resource = Some(env1Node)))
        tied = List(
          world.event(HistoryEventType.ResourceDeactivated, Now, resource = Some(env1Node)),
          world.event(HistoryEventType.ResourceDeactivated, Now, resource = Some(env1Node))
        )
        syncEvent = world.event(HistoryEventType.SyncFailed, Now.plusSeconds(1), connection = Some(env1Connection))
        otherSync = world.event(HistoryEventType.SyncFailed, Now.plusSeconds(2), connection = Some(project2Connection))
        otherNode = world.event(HistoryEventType.ResourceDiscovered, Now.plusSeconds(3), resource = Some(env3Node))
        _ <- world.run(new PostgresHistoryEventRepository().saveAll(
          env1Events ++ tied ++ List(syncEvent, otherSync, otherNode)))
        organization <- world.overview(ConnectionScope.Organization)
        environment <- world.overview(ConnectionScope.Environment(world.project1, world.env1))
        project2 <- world.overview(ConnectionScope.Project(world.project2))
      } yield {
        assertEquals(organization.recentActivity.size, OperationsOverviewPolicy.ActivityLimit)
        assertEquals(organization.recentActivity.take(2).map(_.id), List(otherNode.id, otherSync.id))

        assertEquals(environment.recentActivity.size, OperationsOverviewPolicy.ActivityLimit)
        assertEquals(environment.recentActivity.head.id, syncEvent.id)
        // Two facts of one instant keep a stable order: id descending.
        assertEquals(environment.recentActivity.slice(1, 3).map(_.id), tied.map(_.id).sortBy(_.toString).reverse)
        assert(!environment.recentActivity.exists(event => Set(otherSync.id, otherNode.id).contains(event.id)))

        assertEquals(project2.recentActivity.map(_.id), List(otherNode.id, otherSync.id))
      }
    }
  }

  test("one overview costs a bounded number of statements whatever the fleet size") {
    withWorld { world =>
      def statements(scope: ConnectionScope): IO[Int] =
        Ref.of[IO, Int](0).flatMap { counter =>
          val handler = new LogHandler[IO] {
            override def run(event: LogEvent): IO[Unit] = counter.update(_ + 1)
          }
          val xa = Transactor.fromDriverManager[IO](
            "org.postgresql.Driver", world.config.url, world.config.user, world.config.password,
            Some(handler))
          new DoobieReadOnlySnapshotRunner(xa).run(world.useCase.execute(world.org, scope)) *> counter.get
        }

      for {
        small <- statements(ConnectionScope.Environment(world.project1, world.env1))
        _ <- (1 to 40).toList.traverse_ { index =>
          for {
            node <- world.node(world.env1, s"node-$index", online = index % 2 == 0)
            _ <- world.incident(node, "THRESHOLD", open = true, openedAt = Now.minusSeconds(index.toLong))
            container <- world.container(world.env1, s"container-$index", Some("exited"))
            _ <- world.operation(container, "FAILED", Now.minusSeconds(index.toLong))
          } yield ()
        }
        large <- statements(ConnectionScope.Environment(world.project1, world.env1))
        organization <- statements(ConnectionScope.Organization)
      } yield {
        // Snapshot setup, the environment check, summary, attention and activity.
        assertEquals(small, 5)
        assertEquals(large, small)
        // The organization scope needs no check.
        assertEquals(organization, 4)
      }
    }
  }

  test("the snapshot runner refuses writes") {
    withWorld { world =>
      new DoobieReadOnlySnapshotRunner(world.xa)
        .run(sql"update organization set name = 'changed' where id = ${world.org}".update.run)
        .attempt
        .map(result => assert(result.isLeft, "a write succeeded in a read-only snapshot"))
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
    val xa: Transactor[IO],
    val config: infrastructure.database.DatabaseConfig
  ) {
    private val runner = new DoobieTransactionRunner(xa)
    private val resources = ProductionResourceCodec.resourceRepository
    private val suffix = UUID.randomUUID().toString.take(8)

    val org: UUID = UUID.randomUUID()
    val foreignOrg: UUID = UUID.randomUUID()
    val project1: UUID = UUID.randomUUID()
    val project2: UUID = UUID.randomUUID()
    val env1: UUID = UUID.randomUUID()
    val env2: UUID = UUID.randomUUID()
    val env3: UUID = UUID.randomUUID()
    private val actor: UUID = UUID.randomUUID()

    val useCase = new GetOperationsOverview[ConnectionIO](
      new PostgresOperationsOverviewQuery,
      new PostgresHistoryEventQuery,
      new PostgresProjectRepository,
      new PostgresNavigationQueryRepository,
      new TimeProvider[ConnectionIO] { override def now: ConnectionIO[Instant] = Now.pure[ConnectionIO] }
    )

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def execute(scope: ConnectionScope): IO[Option[OperationsOverview]] =
      new DoobieReadOnlySnapshotRunner(xa).run(useCase.execute(org, scope))

    def overview(scope: ConnectionScope): IO[OperationsOverview] =
      execute(scope).map(_.getOrElse(fail(s"scope $scope was not found")))

    def setUp: IO[Unit] = run(for {
      _ <- sql"insert into organization (id, code, name) values ($org, ${s"overview-$suffix"}, 'Overview')".update.run
      _ <- sql"insert into organization (id, code, name) values ($foreignOrg, ${s"foreign-$suffix"}, 'Foreign')".update.run
      _ <- sql"insert into project (id, organization_id, code, name) values ($project1, $org, 'p1', 'Project one')".update.run
      _ <- sql"insert into project (id, organization_id, code, name) values ($project2, $org, 'p2', 'Project two')".update.run
      _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($env1, $org, $project1, 'e1', 'One', 'PROD')".update.run
      _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($env2, $org, $project1, 'e2', 'Two', 'TEST')".update.run
      _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($env3, $org, $project2, 'e3', 'Three', 'DEV')".update.run
      _ <- sql"insert into user_account (id, email, password_hash, display_name, created_at, updated_at) values ($actor, ${s"$actor@example.test"}, 'x', 'Actor', current_timestamp, current_timestamp)".update.run
    } yield ())

    def cleanUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
      for {
        _ <- sql"delete from history_event where organization_id = $id".update.run
        _ <- sql"delete from operation_execution where organization_id = $id".update.run
        _ <- sql"delete from incident where organization_id = $id".update.run
        _ <- sql"delete from monitor_rule where organization_id = $id".update.run
        _ <- sql"delete from sync_session where organization_id = $id".update.run
        _ <- sql"delete from resource where organization_id = $id".update.run
        _ <- sql"delete from connection where organization_id = $id".update.run
        _ <- sql"delete from environment where organization_id = $id".update.run
        _ <- sql"delete from project where organization_id = $id".update.run
        _ <- sql"delete from organization where id = $id".update.run
      } yield ()
    } *> sql"delete from user_account where id = $actor".update.run.void)

    def node(environment: UUID, name: String, online: Boolean, active: Boolean = true): IO[UUID] =
      resource(environment, NodeType, "NODE", name, active, ResourceData(
        Some(NodeSpec(name, None, None, None, None)), Some(NodeStatus(online, None, None, None))))

    def container(environment: UUID, name: String, state: Option[String], active: Boolean = true): IO[UUID] =
      resource(environment, ContainerType, "CONTAINER", name, active,
        ResourceData(Some(ContainerSpec(Some("nginx"))), Some(ContainerStatus(state))))

    /** Through the production repository and codecs, so the stored JSON is the real one. */
    private def resource(
      environment: UUID,
      typeId: UUID,
      typeCode: String,
      name: String,
      active: Boolean,
      data: ResourceData
    ): IO[UUID] = {
      val id = UUID.randomUUID()
      run(resources.save(Resource(id, org, environment, typeId, None, name, name, active, Now, Now,
        typeCode, data))).as(id)
    }

    def incident(resource: UUID, reason: String, open: Boolean, openedAt: Instant): IO[UUID] = {
      val rule = UUID.randomUUID()
      val id = UUID.randomUUID()
      val status = if (open) "OPEN" else "RESOLVED"
      val resolvedAt = Option.when(!open)(openedAt.plusSeconds(60))
      run(for {
        _ <- sql"""
          insert into monitor_rule (id, organization_id, resource_id, metric_code, operator, threshold,
            for_seconds, no_data_seconds, enabled, created_at, updated_at)
          values ($rule, $org, $resource, 'CPU_USAGE_PERCENT', 'GREATER_THAN', 90, 0, 900, true, $Now, $Now)
        """.update.run
        _ <- sql"""
          insert into incident (id, organization_id, monitor_rule_id, resource_id, status, reason,
            started_at, opened_at, resolved_at, created_at, updated_at)
          values ($id, $org, $rule, $resource, $status, $reason, $openedAt, $openedAt, $resolvedAt, $Now, $Now)
        """.update.run
      } yield id)
    }

    def connection(name: String, scope: ConnectionScope, active: Boolean = true): IO[UUID] = {
      val id = UUID.randomUUID()
      val (projectId, environmentId) = scope match {
        case ConnectionScope.Organization => (None, None)
        case ConnectionScope.Project(project) => (Some(project), None)
        case ConnectionScope.Environment(project, environment) => (Some(project), Some(environment))
      }
      run(sql"""
        insert into connection (id, organization_id, scope_type, project_id, environment_id,
          connector_type, code, name, is_active)
        values ($id, $org, ${scope.code}, $projectId, $environmentId, 'SSH', ${s"$name-$suffix"}, $name, $active)
      """.update.run).as(id)
    }

    def sync(connection: UUID, status: String, startedAt: Instant): IO[UUID] = {
      val id = UUID.randomUUID()
      val finishedAt = Option.when(status != "RUNNING")(startedAt.plusSeconds(5))
      val errorCode = Option.when(status == "FAILED")("SSH_HOST_KEY_MISMATCH")
      val errorMessage = Option.when(status == "FAILED")("SSH host key does not match")
      run(sql"""
        insert into sync_session (id, organization_id, connection_id, started_at, finished_at, status,
          error_code, error_message, recover_after_at)
        values ($id, $org, $connection, $startedAt, $finishedAt, $status, $errorCode, $errorMessage,
          ${startedAt.plusSeconds(900)})
      """.update.run).as(id)
    }

    def operation(resource: UUID, status: String, startedAt: Instant): IO[UUID] = {
      val id = UUID.randomUUID()
      val finishedAt = Option.when(status != "RUNNING")(startedAt.plusSeconds(3))
      val errorCode = status match {
        case "FAILED" => Some("OPERATION_EXECUTION_FAILED")
        case "UNKNOWN" => Some("OPERATION_RESULT_UNKNOWN")
        case _ => None
      }
      val errorMessage = errorCode.map(_ => "Operation execution failed")
      run(sql"""
        insert into operation_execution (id, organization_id, resource_id, actor_user_id, operation,
          target_connection_id, target_external_type, target_external_id, status, started_at,
          finished_at, error_code, error_message, created_at, updated_at, recover_after_at)
        values ($id, $org, $resource, $actor, 'CONTAINER_RESTART', ${UUID.randomUUID()}, 'CONTAINER',
          'abc', $status, $startedAt, $finishedAt, $errorCode, $errorMessage, $startedAt, $startedAt,
          ${startedAt.plusSeconds(600)})
      """.update.run).as(id)
    }

    def event(
      eventType: HistoryEventType,
      occurredAt: Instant,
      resource: Option[UUID] = None,
      connection: Option[UUID] = None
    ): HistoryEvent =
      HistoryEvent(UUID.randomUUID(), org, eventType, HistoryEventSource.System, resource, connection,
        None, None, None, None, occurredAt, occurredAt)

    def foreignProject: IO[UUID] = {
      val id = UUID.randomUUID()
      run(sql"insert into project (id, organization_id, code, name) values ($id, $foreignOrg, 'fp', 'Foreign')"
        .update.run).as(id)
    }

    def foreignEnvironment(project: UUID): IO[UUID] = {
      val id = UUID.randomUUID()
      run(sql"""
        insert into environment (id, organization_id, project_id, code, name, kind)
        values ($id, $foreignOrg, $project, 'fe', 'Foreign', 'PROD')
      """.update.run).as(id)
    }

    def foreignNode(environment: UUID): IO[UUID] = {
      val id = UUID.randomUUID()
      run(resources.save(Resource(id, foreignOrg, environment, NodeType, None, "foreign", "foreign",
        isActive = true, Now, Now, "NODE",
        ResourceData(Some(NodeSpec("foreign", None, None, None, None)),
          Some(NodeStatus(online = false, None, None, None)))))).as(id)
    }
  }
}

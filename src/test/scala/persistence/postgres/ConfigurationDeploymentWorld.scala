package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.configuration._
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.configuration._
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import integration.ssh.{SshjClient, SshjConfigurationTransport}
import munit.Assertions._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import support.{AuthorizationFixtures, RemoteConfigurationServer}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** A tenant with SSH-reachable nodes, a configuration profile and the real deployment services, for
  * PostgreSQL integration specs of deployments and rollouts. Nodes are real resources whose source
  * connection points at a real in-process SSH/SFTP server.
  */
private[postgres] final class ConfigurationDeploymentWorld(val runner: DoobieTransactionRunner,
                                                           val remote: RemoteConfigurationServer) {
  val org: UUID = UUID.randomUUID()
  val foreignOrg: UUID = UUID.randomUUID()
  private val environments = Map(org -> UUID.randomUUID(), foreignOrg -> UUID.randomUUID())
  val actor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, org)
  val foreignActor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, foreignOrg)
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.configuration.deployment")

  val settings: ConfigurationDeploymentSettings = ConfigurationDeploymentSettings(
    leaseDuration = 30.seconds, healthTimeout = 2.seconds, healthPollInterval = 100.millis,
    transientBackoff = 1.second, maxTransientAttempts = 2, maxRemoteFileBytes = 64 * 1024,
    validatorTimeout = 5.seconds, activationTimeout = 5.seconds)

  val transport: RemoteConfigurationTransport[IO] =
    new SshjConfigurationTransport(new SshjClient[IO], RemoteConfigurationServer.credentials, 10.seconds)

  val deploymentRepository = new PostgresConfigurationDeploymentRepository
  val rolloutRepository = new PostgresConfigurationRolloutRepository
  val assignmentRepository = new PostgresConfigurationAssignmentRepository
  val assignmentQuery = new PostgresConfigurationAssignmentQuery
  val profileQuery = new PostgresConfigurationProfileQuery
  val sources = new PostgresConfigurationDeploymentSourceQuery
  val ids = new ConnectionIOIdGenerator
  val time = new ConnectionIOTimeProvider
  val audit = new AuditRecorder[ConnectionIO](new PostgresAuditEventRepository, ids, time)

  val profiles = new ConfigurationProfileManagement[ConnectionIO](new PostgresConfigurationProfileRepository, ids, time, audit)
  val assignments = new ConfigurationAssignments[IO, ConnectionIO](assignmentRepository, new PostgresConfigurationTargetQuery,
    profileQuery, ids, time, audit, runner, runner)

  def deploymentsWith(auditRecorder: AuditRecorder[ConnectionIO] = audit,
                      remoteTransport: RemoteConfigurationTransport[IO] = transport): ConfigurationDeployments[IO, ConnectionIO] =
    new ConfigurationDeployments[IO, ConnectionIO](assignmentRepository, assignmentQuery, profileQuery, sources,
      deploymentRepository, remoteTransport, ids, time, auditRecorder, runner, runner, settings)

  val deployments: ConfigurationDeployments[IO, ConnectionIO] = deploymentsWith()
  val promotions = new ConfigurationPromotions[IO, ConnectionIO](new PostgresConfigurationPromotionRepository,
    profileQuery, time, audit, runner, runner)
  val rollouts = new ConfigurationRollouts[IO, ConnectionIO](assignmentRepository, assignmentQuery, profileQuery,
    sources, deployments, rolloutRepository, ids, time, audit, runner, runner, settings)

  def worker(owner: UUID = UUID.randomUUID(), clock: IO[Instant] = IO.realTimeInstant,
             remoteTransport: RemoteConfigurationTransport[IO] = transport,
             workerSettings: ConfigurationDeploymentSettings = settings): ConfigurationDeploymentWorker[ConnectionIO] =
    new ConfigurationDeploymentWorker[ConnectionIO](deploymentRepository, assignmentRepository, profileQuery,
      sources, remoteTransport, runner, owner, workerSettings, logger, clock, Some(org))

  def rolloutWorker(owner: UUID = UUID.randomUUID(), clock: IO[Instant] = IO.realTimeInstant): ConfigurationRolloutWorker[ConnectionIO] =
    new ConfigurationRolloutWorker[ConnectionIO](rolloutRepository, deploymentRepository, ids, runner, owner, logger,
      clock = clock, scope = Some(org))

  def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

  type Node = ConfigurationDeploymentWorld.Node
  private val Node = ConfigurationDeploymentWorld.Node

  def node(name: String, organization: UUID = org): IO[Node] = {
    val resource = UUID.randomUUID()
    val connection = UUID.randomUUID()
    val config = io.circe.Json.fromFields(remote.connectionConfig.values.map { case (k, v) => k -> io.circe.Json.fromString(v) })
      .noSpaces
    run(for {
      _ <- sql"""insert into resource (id, organization_id, environment_id, resource_type_id, code, name, is_active)
                 values ($resource, $organization, ${environments(organization)}, $NodeType, $name, $name, true)""".update.run
      _ <- sql"""insert into connection (id, organization_id, scope_type, connector_type, code, name, config)
                 values ($connection, $organization, 'ORGANIZATION', 'SSH', ${s"ssh-$name"}, ${s"ssh $name"}, $config::jsonb)""".update.run
      _ <- sql"""insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id)
                 values (${UUID.randomUUID()}, $organization, $connection, 'NODE', $name, $resource)""".update.run
    } yield Node(resource, connection, name))
  }

  /** A second SSH connection of the organization that is not a source of any node. */
  def unrelatedConnection(): IO[UUID] = {
    val id = UUID.randomUUID()
    run(sql"""insert into connection (id, organization_id, scope_type, connector_type, code, name)
              values ($id, $org, 'ORGANIZATION', 'SSH', ${s"other-$id"}, 'other')""".update.run).as(id)
  }

  def touchConnection(id: UUID): IO[Unit] =
    run(sql"update connection set updated_at = updated_at + interval '1 second' where id = $id".update.run).void

  val Template = "server_name {{ domain }};\nmode {{ mode }};\n"

  def content(template: String = Template, variables: List[ConfigurationVariableDefinition] = DefaultVariables): ValidatedConfiguration =
    ConfigurationValidation.validate(template, variables).fold(errors => throw new IllegalStateException(errors.toString), identity)

  val DefaultVariables: List[ConfigurationVariableDefinition] = List(
    ConfigurationVariableDefinition("domain", ConfigurationValueType.StringType, required = true, None, None),
    ConfigurationVariableDefinition("mode", ConfigurationValueType.StringType, required = false, Some("ok"), None))

  def profile(code: String = s"app-${UUID.randomUUID().toString.take(8)}", by: ActorContext = actor): IO[UUID] =
    run(profiles.create(by, CreateConfigurationProfileCommand(code, ConfigurationProfileMetadata(s"Profile $code", None), content())))
      .map(_._1.id)

  def revision(profile: UUID, template: String = Template,
               variables: List[ConfigurationVariableDefinition] = DefaultVariables): IO[Int] =
    run(profiles.appendRevision(actor, profile, content(template, variables))).map(_.revisionNumber)

  def assign(node: Node, profile: UUID, path: String, values: (String, String)*): IO[UUID] =
    assignments.create(actor, node.resourceId, profile, ConfigurationAssignmentDraft(1, path,
      values.toList.map { case (name, value) => ConfigurationVariableValue(name, value) }))

  def assignment(id: UUID): IO[ConfigurationAssignment] =
    run(assignmentRepository.find(org, id)).map(_.getOrElse(fail("assignment is missing")))

  /** Preview, then request exactly what the preview showed. */
  def deploy(assignmentId: UUID, node: Node, policy: ConfigurationExecutionPolicy = ConfigurationExecutionPolicy.Default,
             requestId: UUID = UUID.randomUUID()): IO[UUID] = for {
    current <- assignment(assignmentId)
    preview <- deployments.preview(org, assignmentId, current.version, node.connectionId)
    id <- deployments.request(actor, assignmentId, current.version, node.connectionId,
      ExpectedRemoteState.of(preview.remoteSha256), policy, requestId)
  } yield id

  def deployment(id: UUID): IO[ConfigurationDeployment] =
    run(deploymentRepository.find(org, id)).map(_.getOrElse(fail("deployment is missing")))

  def events(id: UUID): IO[List[String]] = run(deploymentRepository.events(org, id)).map(_.map(_.eventType))

  /** Runs the deployment worker until nothing is queued or running, within a bound. */
  def drain(worker: ConfigurationDeploymentWorker[ConnectionIO] = this.worker(), rounds: Int = 20): IO[Unit] =
    (1 to rounds).toList.foldLeft(IO.pure(false)) { (done, _) =>
      done.flatMap { finished =>
        if (finished) IO.pure(true)
        else worker.tick *> run(sql"""select count(*) from configuration_deployment
               where organization_id = $org and state in ('QUEUED','RUNNING')""".query[Long].unique).map(_ == 0)
      }
    }.void

  def journal(targetType: String): IO[List[(String, Option[UUID])]] =
    run(sql"""select action, target_id from audit_event where organization_id = $org and target_type = $targetType
              order by occurred_at, created_at""".query[(String, Option[UUID])].to[List])

  def setUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
    val project = UUID.randomUUID()
    for {
      _ <- sql"insert into organization (id, code, name) values ($id, ${s"deploy-${id.toString.take(8)}"}, 'Deployments')".update.run
      _ <- sql"insert into project (id, organization_id, code, name) values ($project, $id, 'p', 'Project')".update.run
      _ <- sql"""insert into environment (id, organization_id, project_id, code, name, kind)
                 values (${environments(id)}, $id, $project, 'prod', 'Production', 'PROD')""".update.run
    } yield ()
  })

  def cleanUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
    for {
      _ <- sql"update configuration_deployment set rollout_id = null, rollout_item_id = null, backup_retained = false where organization_id = $id".update.run
      _ <- sql"delete from configuration_rollout_item_value where organization_id = $id".update.run
      _ <- sql"delete from configuration_rollout_item_validator_arg where organization_id = $id".update.run
      _ <- sql"delete from configuration_deployment_event where organization_id = $id".update.run
      _ <- sql"delete from configuration_deployment_value where organization_id = $id".update.run
      _ <- sql"delete from configuration_deployment_validator_arg where organization_id = $id".update.run
      _ <- sql"update configuration_rollout_item set deployment_id = null where organization_id = $id".update.run
      _ <- sql"delete from configuration_deployment where organization_id = $id".update.run
      _ <- sql"delete from configuration_rollout_item where organization_id = $id".update.run
      _ <- sql"delete from configuration_rollout where organization_id = $id".update.run
      _ <- sql"delete from audit_event where organization_id = $id".update.run
      _ <- sql"delete from configuration_assignment_value where organization_id = $id".update.run
      _ <- sql"delete from configuration_assignment where organization_id = $id".update.run
      _ <- sql"delete from configuration_revision_variable where organization_id = $id".update.run
      _ <- sql"delete from configuration_revision where organization_id = $id".update.run
      _ <- sql"delete from configuration_profile where organization_id = $id".update.run
      _ <- sql"delete from external_ref where organization_id = $id".update.run
      _ <- sql"delete from connection where organization_id = $id".update.run
      _ <- sql"delete from resource where organization_id = $id".update.run
      _ <- sql"delete from environment where organization_id = $id".update.run
      _ <- sql"delete from project where organization_id = $id".update.run
      _ <- sql"delete from organization where id = $id".update.run
    } yield ()
  })

  private val NodeType = UUID.fromString("10000000-0000-0000-0000-000000000001")
}

private[postgres] object ConfigurationDeploymentWorld {
  /** A NODE resource with its own SSH source connection to the fixture server. */
  final case class Node(resourceId: UUID, connectionId: UUID, name: String)

  def enabled: Boolean = sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true")

  /** A fresh world per test: its own tenant, its own SSH server, always cleaned up. */
  def run(body: ConfigurationDeploymentWorld => IO[Unit]): Unit = {
    assume(enabled, "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")
    val remote = RemoteConfigurationServer.start()
    try PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val world = new ConfigurationDeploymentWorld(new DoobieTransactionRunner(xa), remote)
      (world.setUp *> body(world)).guarantee(world.cleanUp)
    }.unsafeRunSync()
    finally remote.stop()
  }
}

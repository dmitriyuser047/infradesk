package ru.bitec.app.ops
package application.connection

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection, ConnectionSchedule}
import domain.enviroment.Environment
import domain.project.Project
import infrastructure.http.dto._
import infrastructure.http.SshConnectionMutationRoutes
import integration.ssh._
import munit.FunSuite
import io.circe.{Json, parser}
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}

import java.time.Instant
import java.util.{Base64, UUID}

final class SshConnectionManagementSpec extends FunSuite {
  import CirceEntityDecoder._
  import CirceEntityEncoder._
  private val org = UUID.randomUUID()
  private val key = Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7))
  private val cipher = ConnectionSecretCipher.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key)).toOption.get
  private val request = SaveSshConnectionRequest("SSH", "prod-vps", "Production VPS",
    ConnectionScopeRequest("ORGANIZATION", None, None),
    SshSettingsRequest("example.org", Some(22), "root"),
    Some(PasswordCredentialsRequest("PASSWORD", "secret")),
    ConnectionScheduleRequest(true, 60))

  test("create probes outside transaction, saves encrypted credential and due schedule") {
    val f = new ManagementFixture
    val result = f.management.create(org, request).unsafeRunSync()
    assertEquals(f.probes, 1)
    assertEquals(f.probedInTransaction, false)
    assertEquals(result.connection.secretRef.exists(_.startsWith("db:")), true)
    assertEquals(result.connection.config.get("hostKeyFingerprint"), Some("SHA256:test"))
    assertEquals(f.savedSchedule.get.enabled, true)
    assertEquals(f.savedSchedule.get.nextRunAt, result.connection.createdAt)
    assertEquals(cipher.decrypt(f.savedSecret.get), "secret")
    assert(!new String(f.savedSecret.get.ciphertext, "UTF-8").contains("secret"))
  }

  test("metadata-only update does not probe or replace secret, and deactivate retains it") {
    val f = new ManagementFixture
    val created = f.management.create(org, request).unsafeRunSync().connection
    val originalSecret = f.savedSecret.get
    val changed = f.management.update(org, created.id, request.copy(name = "Renamed", credentials = None)).unsafeRunSync()
    assertEquals(changed.connection.name, "Renamed")
    assertEquals(f.probes, 1)
    assertEquals(f.savedSecret.get.id, originalSecret.id)
    f.management.deactivate(org, created.id).unsafeRunSync()
    f.management.deactivate(org, created.id).unsafeRunSync()
    assertEquals(f.savedConnection.get.isActive, false)
    assertEquals(f.savedSchedule.get.enabled, false)
    assertEquals(f.savedSecret.get.id, originalSecret.id)
  }

  test("failed SSH probe leaves connection and secret unchanged") {
    val f = new ManagementFixture
    val created = f.management.create(org, request).unsafeRunSync().connection
    val originalSecret = f.savedSecret.get
    f.failProbe = true
    val error = intercept[ConnectionManagementError] {
      f.management.update(org, created.id, request.copy(ssh = request.ssh.copy(host = "other.example.org"),
        credentials = None)).unsafeRunSync()
    }
    assertEquals(error.code, "SSH_CONNECTION_FAILED")
    assertEquals(f.savedConnection.get, created)
    assertEquals(f.savedSecret.get.id, originalSecret.id)
  }

  test("mutation routes return safe 201, 422 for probe failure and 404 for unknown ID") {
    val f = new ManagementFixture
    val app = new SshConnectionMutationRoutes[IO](f.management).routes.orNotFound
    val body = parser.parse(s"""{
      "connectorType":"SSH","code":"prod-vps","name":"Production VPS",
      "scope":{"type":"ORGANIZATION"},
      "ssh":{"host":"example.org","port":22,"username":"root"},
      "credentials":{"type":"PASSWORD","password":"secret"},
      "schedule":{"enabled":true,"intervalSeconds":60}
    }""").toOption.get
    val created = app.run(Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/organizations/$org/connections"))
      .withEntity(body)).unsafeRunSync()
    assertEquals(created.status, Status.Created)
    val json = created.as[Json].unsafeRunSync()
    assertEquals(json.hcursor.get[String]("secretRef").isLeft, true)
    assertEquals(json.hcursor.get[String]("ssh").isLeft, true)
    assertEquals(json.hcursor.downField("ssh").get[String]("host"), Right("example.org"))
    assert(!json.noSpaces.contains("secret"))

    f.failProbe = true
    val testBody = parser.parse("""{"host":"example.org","port":22,"username":"root","credentials":{"type":"PASSWORD","password":"secret"}}""").toOption.get
    val failed = app.run(Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/organizations/$org/connections/ssh/test"))
      .withEntity(testBody)).unsafeRunSync()
    assertEquals(failed.status, Status.UnprocessableEntity)
    assertEquals(failed.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("SSH_CONNECTION_FAILED"))

    val missing = app.run(Request[IO](Method.DELETE,
      Uri.unsafeFromString(s"/api/v1/organizations/$org/connections/${UUID.randomUUID()}"))).unsafeRunSync()
    assertEquals(missing.status, Status.NotFound)
  }

  private final class ManagementFixture {
    var savedConnection: Option[Connection] = None
    var savedSchedule: Option[ConnectionSchedule] = None
    var savedSecret: Option[ConnectionSecret] = None
    var probes = 0
    var probedInTransaction = false
    var inTransaction = false
    var failProbe = false

    val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] =
        IO { inTransaction = true } *> program.guarantee(IO { inTransaction = false })
    }
    val connections = new ConnectionRepository[IO] {
      override def findById(organizationId: UUID, id: UUID): IO[Option[Connection]] =
        IO.pure(savedConnection.filter(c => c.organizationId == organizationId && c.id == id))
      override def findByOrganization(organizationId: UUID): IO[List[Connection]] =
        IO.pure(savedConnection.filter(_.organizationId == organizationId).toList)
      override def save(value: Connection): IO[Unit] = IO { savedConnection = Some(value) }
    }
    val schedules = new ConnectionScheduleRepository[IO] {
      override def save(value: ConnectionSchedule): IO[Unit] = IO { savedSchedule = Some(value) }
      override def findByConnection(organizationId: UUID, connectionId: UUID): IO[Option[ConnectionSchedule]] =
        IO.pure(savedSchedule.filter(s => s.organizationId == organizationId && s.connectionId == connectionId))
      override def findDue(now: Instant, limit: Int): IO[List[ConnectionSchedule]] = IO.pure(Nil)
      override def scheduleNext(organizationId: UUID, connectionId: UUID, nextRunAt: Instant): IO[Unit] = IO.unit
    }
    val secrets = new ConnectionSecretRepository[IO] {
      override def save(value: ConnectionSecret): IO[Unit] = IO { savedSecret = Some(value) }
      override def find(organizationId: UUID, id: UUID): IO[Option[ConnectionSecret]] =
        IO.pure(savedSecret.filter(s => s.organizationId == organizationId && s.id == id))
      override def delete(organizationId: UUID, id: UUID): IO[Unit] = IO {
        if (savedSecret.exists(s => s.organizationId == organizationId && s.id == id)) savedSecret = None
      }
    }
    val projects = new ProjectRepository[IO] {
      override def findActiveByOrganization(organizationId: UUID): IO[List[Project]] = IO.pure(Nil)
      override def findActiveById(organizationId: UUID, projectId: UUID): IO[Option[Project]] = IO.pure(None)
    }
    val environments = new EnvironmentRepository[IO] {
      override def findActiveByProject(organizationId: UUID, projectId: UUID): IO[List[Environment]] = IO.pure(Nil)
    }
    val client = new SshClient[IO] {
      override def execute(config: SshConnectionConfig, authentication: SshAuthentication, command: String): IO[SshCommandResult] =
        IO {
          probes += 1
          probedInTransaction ||= inTransaction
          if (failProbe) throw new IllegalStateException("simulated failure")
          SshCommandResult(0, "infradesk-ok\n", "", "SHA256:test")
        }
    }
    val auth = new SshAuthenticationProvider[IO] {
      override def resolve(connection: Connection): IO[SshAuthentication] =
        IO.pure(SshAuthentication.Password("secret"))
    }
    val management = new SshConnectionManagement[IO](connections, schedules, secrets,
      projects, environments, runner, client, auth, cipher)
  }
}

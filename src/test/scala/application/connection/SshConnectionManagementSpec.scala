package ru.bitec.app.ops
package application.connection

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection, ConnectionSchedule, ConnectionScope, SshAuthenticationType, SshConnectionSettings, SshCredential}
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
  private val cipher = ConnectionSecretCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key)).toOption.get)
  private val request = CreateSshConnectionCommand("prod-vps", "Production VPS",
    ConnectionScope.Organization,
    // The host has been probed and its identity confirmed before the connection is created.
    SshConnectionSettings("example.org", 22, "root", Some("SHA256:test"), 10, 30),
    SshCredential.Password("secret"), SshScheduleCommand(true, 600))
  private val updateRequest = UpdateSshConnectionCommand(request.code, request.name, request.scope,
    request.ssh, None, request.schedule)

  test("create probes outside transaction and schedules first sync after the interval") {
    val f = new ManagementFixture
    val result = f.management.create(support.AuthorizationFixtures.actor(org), request).unsafeRunSync()
    assertEquals(f.probes, 1)
    assertEquals(f.probedInTransaction, false)
    assertEquals(result.connection.secretRef.exists(_.startsWith("db:")), true)
    assertEquals(result.connection.config.get("hostKeyFingerprint"), Some("SHA256:test"))
    assertEquals(f.savedSchedule.get.enabled, true)
    assertEquals(f.savedSchedule.get.nextRunAt, result.connection.createdAt.plusSeconds(600))
    assertEquals(f.savedSchedule.get.consecutiveFailures, 0L)
    assertEquals(cipher.decrypt(f.savedSecret.get), SshCredential.Password("secret"))
    assertEquals(result.connection.config.get("authenticationType"), Some("PASSWORD"))
    assert(!new String(f.savedSecret.get.ciphertext, "UTF-8").contains("secret"))
  }

  test("metadata-only update does not probe or replace secret, and deactivate retains it") {
    val f = new ManagementFixture
    val created = f.management.create(support.AuthorizationFixtures.actor(org), request).unsafeRunSync().connection
    val originalSecret = f.savedSecret.get
    val originalNextRun = f.savedSchedule.get.nextRunAt
    f.savedSchedule = f.savedSchedule.map(_.copy(consecutiveFailures = 2L))
    val changed = f.management.update(support.AuthorizationFixtures.actor(org), created.id, updateRequest.copy(name = "Renamed")).unsafeRunSync()
    assertEquals(changed.connection.name, "Renamed")
    assertEquals(f.probes, 1)
    assertEquals(f.credentialResolutions, 0)
    assertEquals(f.savedSecret.get.id, originalSecret.id)
    assertEquals(changed.schedule.get.nextRunAt, originalNextRun)
    assertEquals(changed.schedule.get.consecutiveFailures, 2L)
    f.management.deactivate(support.AuthorizationFixtures.actor(org), created.id).unsafeRunSync()
    f.management.deactivate(support.AuthorizationFixtures.actor(org), created.id).unsafeRunSync()
    assertEquals(f.savedConnection.get.isActive, false)
    assertEquals(f.savedSchedule.get.enabled, false)
    assertEquals(f.savedSchedule.get.consecutiveFailures, 2L)
    assertEquals(f.savedSecret.get.id, originalSecret.id)
    // Create, update and the first deactivation are journalled; the second changes nothing but is
    // still an action the owner performed.
    assertEquals(f.auditEvents.recorded.map(event => (event.action.code, event.targetId)), List(
      ("CONNECTION_CREATED", Some(created.id)),
      ("CONNECTION_UPDATED", Some(created.id)),
      ("CONNECTION_DELETED", Some(created.id)),
      ("CONNECTION_DELETED", Some(created.id))
    ))
    assertEquals(f.auditEvents.recorded.map(_.targetType.code).distinct, List("CONNECTION"))
    assertEquals(f.auditEvents.recorded.map(_.organizationId).distinct, List(org))
  }

  test("PRIVATE_KEY HTTP update without credentials keeps the stored key and authentication type") {
    val f = new ManagementFixture
    val keyCredential = SshCredential.PrivateKey("-----BEGIN PRIVATE KEY-----\nopaque\n", Some(" unlock "))
    val created = f.management.create(support.AuthorizationFixtures.actor(org),
      request.copy(ssh = request.ssh.copy(authenticationType = SshAuthenticationType.PrivateKey),
        credential = keyCredential)).unsafeRunSync().connection
    val secretBefore = f.savedSecret.get
    val app = support.AuthorizationFixtures.authorized(
      new SshConnectionMutationRoutes[IO](f.management, support.AuthorizationFixtures.authorization)
        .routes.orNotFound)
    val body = parser.parse(s"""{
      "connectorType":"SSH","code":"prod-vps","name":"Renamed key connection",
      "scope":{"type":"ORGANIZATION"},
      "ssh":{"host":"example.org","port":22,"username":"root","authenticationType":"PRIVATE_KEY","hostKeyFingerprint":"SHA256:test"},
      "schedule":{"enabled":true,"intervalSeconds":600}
    }""").toOption.get

    val response = app.run(Request[IO](Method.PUT,
      Uri.unsafeFromString(s"/api/v1/organizations/$org/connections/${created.id}"))
      .withEntity(body)).unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assertEquals(f.savedConnection.get.name, "Renamed key connection")
    assertEquals(f.savedConnection.get.config.get("authenticationType"), Some("PRIVATE_KEY"))
    assertEquals(f.savedConnection.get.secretRef, created.secretRef)
    assertEquals(f.savedSecret.get.id, secretBefore.id)
    assertEquals(cipher.decrypt(f.savedSecret.get), keyCredential)
  }

  test("password bytes are opaque and are not trimmed by the HTTP mapper") {
    val f = new ManagementFixture
    val app = support.AuthorizationFixtures.authorized(
      new SshConnectionMutationRoutes[IO](f.management, support.AuthorizationFixtures.authorization)
        .routes.orNotFound)
    val body = parser.parse(s"""{
      "connectorType":"SSH","code":"spaced-password","name":"Spaced password",
      "scope":{"type":"ORGANIZATION"},
      "ssh":{"host":"example.org","port":22,"username":"root","authenticationType":"PASSWORD","hostKeyFingerprint":"SHA256:test"},
      "credentials":{"type":"PASSWORD","password":" secret "},
      "schedule":{"enabled":true,"intervalSeconds":600}
    }""").toOption.get

    val response = app.run(Request[IO](Method.POST,
      Uri.unsafeFromString(s"/api/v1/organizations/$org/connections"))
      .withEntity(body)).unsafeRunSync()

    assertEquals(response.status, Status.Created)
    assertEquals(cipher.decrypt(f.savedSecret.get), SshCredential.Password(" secret "))
    assertEquals(f.probedCredentials.last, SshCredential.Password(" secret "))
  }

  test("an update cannot commit after another owner changes its connection snapshot") {
    val f = new ManagementFixture
    val created = f.management.create(support.AuthorizationFixtures.actor(org), request)
      .unsafeRunSync().connection
    var concurrent: Option[Connection] = None
    f.beforeVerified = () => {
      val changed = f.savedConnection.get.copy(name = "Concurrent winner",
        config = f.savedConnection.get.config.updated("hostKeyFingerprint", "SHA256:concurrent"),
        secretRef = Some("db:00000000-0000-0000-0000-000000000099"),
        updatedAt = created.updatedAt.plusSeconds(1))
      f.savedConnection = Some(changed)
      concurrent = Some(changed)
    }

    val error = intercept[ConnectionManagementError] {
      f.management.update(support.AuthorizationFixtures.actor(org), created.id,
        updateRequest.copy(name = "Stale loser",
          ssh = updateRequest.ssh.copy(username = "changed-user"))).unsafeRunSync()
    }

    assertEquals(error.code, "CONNECTION_MODIFIED")
    assertEquals(f.savedConnection, concurrent)
  }

  test("a failed SSH probe records neither a connection nor a journal entry") {
    val f = new ManagementFixture
    f.failProbe = true

    val outcome = f.management.create(support.AuthorizationFixtures.actor(org), request)
      .attempt.unsafeRunSync()

    assert(outcome.isLeft)
    assertEquals(f.savedConnection, None)
    assertEquals(f.auditEvents.recorded, List.empty)
  }

  test("failed SSH probe leaves connection and secret unchanged") {
    val f = new ManagementFixture
    val created = f.management.create(support.AuthorizationFixtures.actor(org), request).unsafeRunSync().connection
    val originalSecret = f.savedSecret.get
    f.failProbe = true
    val error = intercept[ConnectionManagementError] {
      f.management.update(support.AuthorizationFixtures.actor(org), created.id, updateRequest.copy(ssh = request.ssh.copy(host = "other.example.org"))).unsafeRunSync()
    }
    assertEquals(error.code, "SSH_CONNECTION_FAILED")
    assertEquals(f.savedConnection.get, created)
    assertEquals(f.savedSecret.get.id, originalSecret.id)
  }

  test("changing an enabled interval schedules the next run after the new interval") {
    val f = new ManagementFixture
    val created = f.management.create(support.AuthorizationFixtures.actor(org), request.copy(schedule = SshScheduleCommand(true, 3600))).unsafeRunSync().connection
    val oldNextRun = Instant.now().plusSeconds(3300)
    f.savedSchedule = f.savedSchedule.map(_.copy(nextRunAt = oldNextRun))
    f.savedSchedule = f.savedSchedule.map(_.copy(consecutiveFailures = 2L))
    val changed = f.management.update(support.AuthorizationFixtures.actor(org), created.id,
      updateRequest.copy(schedule = SshScheduleCommand(true, 900))).unsafeRunSync()
    assertEquals(changed.schedule.get.intervalSeconds, 900L)
    assertEquals(changed.schedule.get.nextRunAt, changed.connection.updatedAt.plusSeconds(900))
    assertEquals(changed.schedule.get.consecutiveFailures, 2L)
    assertEquals(f.probes, 1)
  }

  test("re-enabling a schedule avoids its old overdue run and keeps failures") {
    val f = new ManagementFixture
    val created = f.management.create(support.AuthorizationFixtures.actor(org), request).unsafeRunSync().connection
    f.savedSchedule = f.savedSchedule.map(_.copy(enabled = false, nextRunAt = Instant.EPOCH,
      consecutiveFailures = 3L))

    val changed = f.management.update(support.AuthorizationFixtures.actor(org), created.id, updateRequest).unsafeRunSync()

    assertEquals(changed.schedule.get.nextRunAt, changed.connection.updatedAt.plusSeconds(600))
    assertEquals(changed.schedule.get.consecutiveFailures, 3L)
  }

  test("rejects SSH intervals below 300 seconds on create and update") {
    val f = new ManagementFixture
    val invalid = request.copy(schedule = SshScheduleCommand(true, 299))
    val createError = intercept[ConnectionManagementError] {
      f.management.create(support.AuthorizationFixtures.actor(org), invalid).unsafeRunSync()
    }
    assertEquals(createError.code, "INVALID_REQUEST")
    assertEquals(f.probes, 0)

    val created = f.management.create(support.AuthorizationFixtures.actor(org), request.copy(schedule = SshScheduleCommand(true, 300)))
      .unsafeRunSync().connection
    val updateError = intercept[ConnectionManagementError] {
      f.management.update(support.AuthorizationFixtures.actor(org), created.id,
        updateRequest.copy(schedule = SshScheduleCommand(true, 299))).unsafeRunSync()
    }
    assertEquals(updateError.code, "INVALID_REQUEST")
    assertEquals(f.savedSchedule.get.intervalSeconds, 300L)
  }

  test("a private key connection stores a typed credential and its authentication method") {
    val f = new ManagementFixture
    val credential = SshCredential.PrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----", Some("unlock"))

    val result = f.management.create(support.AuthorizationFixtures.actor(org),
      request.copy(ssh = request.ssh.copy(authenticationType = SshAuthenticationType.PrivateKey),
        credential = credential)).unsafeRunSync()

    assertEquals(cipher.decrypt(f.savedSecret.get), credential)
    assertEquals(result.connection.config.get("authenticationType"), Some("PRIVATE_KEY"))
    // The stored configuration holds no part of the credential.
    assert(!result.connection.config.values.values.mkString(" ").contains("BEGIN OPENSSH"))
    assert(!result.connection.config.values.values.mkString(" ").contains("unlock"))
    assertEquals(f.probedCredentials, List(credential))
  }

  test("an unconfirmed host is never offered a credential") {
    val f = new ManagementFixture

    val error = intercept[ConnectionManagementError] {
      f.management.create(support.AuthorizationFixtures.actor(org),
        request.copy(ssh = request.ssh.copy(hostKeyFingerprint = None))).unsafeRunSync()
    }

    assertEquals(error.code, "SSH_HOST_KEY_NOT_TRUSTED")
    // Nothing was sent anywhere: no authenticated probe, no stored secret, no connection.
    assertEquals(f.probes, 0)
    assertEquals(f.probedCredentials, List.empty[SshCredential])
    assertEquals(f.savedSecret, None)
    assertEquals(f.savedConnection, None)
  }

  test("changing the authentication method requires the credential of the new one") {
    val f = new ManagementFixture
    val created = f.management.create(support.AuthorizationFixtures.actor(org), request)
      .unsafeRunSync().connection

    val error = intercept[ConnectionManagementError] {
      f.management.update(support.AuthorizationFixtures.actor(org), created.id,
        updateRequest.copy(ssh = updateRequest.ssh.copy(
          hostKeyFingerprint = Some("SHA256:test"),
          authenticationType = SshAuthenticationType.PrivateKey))).unsafeRunSync()
    }

    assertEquals(error.code, "INVALID_REQUEST")
    // The password connection is untouched: no half-switched state is ever stored.
    assertEquals(cipher.decrypt(f.savedSecret.get), SshCredential.Password("secret"))
    assertEquals(f.savedConnection.get.config.get("authenticationType"), Some("PASSWORD"))

    val switched = f.management.update(support.AuthorizationFixtures.actor(org), created.id,
      updateRequest.copy(
        ssh = updateRequest.ssh.copy(hostKeyFingerprint = Some("SHA256:test"),
          authenticationType = SshAuthenticationType.PrivateKey),
        credential = Some(SshCredential.PrivateKey("-----BEGIN-----", None)))).unsafeRunSync()

    assertEquals(switched.connection.config.get("authenticationType"), Some("PRIVATE_KEY"))
    assertEquals(cipher.decrypt(f.savedSecret.get), SshCredential.PrivateKey("-----BEGIN-----", None))
  }

  test("confirming a rotated host key is an explicit update, not an automatic one") {
    val f = new ManagementFixture
    val created = f.management.create(support.AuthorizationFixtures.actor(org), request)
      .unsafeRunSync().connection
    val secretBefore = f.savedSecret.get

    // The caller supplies the new fingerprint it has seen and accepted.
    f.probeFingerprint = "SHA256:rotated"
    val rotated = f.management.update(support.AuthorizationFixtures.actor(org), created.id,
      updateRequest.copy(ssh = updateRequest.ssh.copy(hostKeyFingerprint = Some("SHA256:rotated"))))
      .unsafeRunSync()

    assertEquals(rotated.connection.config.get("hostKeyFingerprint"), Some("SHA256:rotated"))
    // Rotating trust does not rotate the credential.
    assertEquals(f.savedSecret.get.id, secretBefore.id)
    assertEquals(f.auditEvents.recorded.map(_.action.code),
      List("CONNECTION_CREATED", "CONNECTION_UPDATED"))
  }

  test("mutation routes return safe 201, 422 for probe failure and 404 for unknown ID") {
    val f = new ManagementFixture
    val app = support.AuthorizationFixtures.authorized(
      new SshConnectionMutationRoutes[IO](f.management, support.AuthorizationFixtures.authorization)
        .routes.orNotFound)
    val body = parser.parse(s"""{
      "connectorType":"SSH","code":"prod-vps","name":"Production VPS",
      "scope":{"type":"ORGANIZATION"},
      "ssh":{"host":"example.org","port":22,"username":"root","authenticationType":"PASSWORD","hostKeyFingerprint":"SHA256:test"},
      "credentials":{"type":"PASSWORD","password":"secret"},
      "schedule":{"enabled":true,"intervalSeconds":600}
    }""").toOption.get
    val untrustedBody = parser.parse(s"""{
      "connectorType":"SSH","code":"unconfirmed","name":"Unconfirmed host",
      "scope":{"type":"ORGANIZATION"},
      "ssh":{"host":"example.org","port":22,"username":"root","authenticationType":"PASSWORD"},
      "credentials":{"type":"PASSWORD","password":"secret"},
      "schedule":{"enabled":true,"intervalSeconds":600}
    }""").toOption.get
    val created = app.run(Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/organizations/$org/connections"))
      .withEntity(body)).unsafeRunSync()
    assertEquals(created.status, Status.Created)
    val json = created.as[Json].unsafeRunSync()
    assertEquals(json.hcursor.get[String]("secretRef").isLeft, true)
    assertEquals(json.hcursor.get[String]("ssh").isLeft, true)
    assertEquals(json.hcursor.downField("ssh").get[String]("host"), Right("example.org"))
    assert(!json.noSpaces.contains("secret"))

    // A host nobody confirmed is refused before any credential is offered to it.
    val untrusted = app.run(Request[IO](Method.POST,
      Uri.unsafeFromString(s"/api/v1/organizations/$org/connections"))
      .withEntity(untrustedBody)).unsafeRunSync()
    assertEquals(untrusted.status, Status.UnprocessableEntity)
    assertEquals(untrusted.as[Json].unsafeRunSync().hcursor.get[String]("code"),
      Right("SSH_HOST_KEY_NOT_TRUSTED"))
    assertEquals(f.probedCredentials.size, 1)

    // Asking a host who it is carries no credential field at all.
    val probed = app.run(Request[IO](Method.POST,
      Uri.unsafeFromString(s"/api/v1/organizations/$org/connections/ssh/host-key"))
      .withEntity(parser.parse(
        """{"host":"example.org","port":22,"username":"root"}""").toOption.get)).unsafeRunSync()
    assertEquals(probed.status, Status.Ok)
    assertEquals(probed.as[Json].unsafeRunSync().hcursor.get[String]("hostKeyFingerprint"),
      Right("SHA256:test"))
    assertEquals(f.hostProbes, 1)
    assertEquals(f.probedCredentials.size, 1)

    f.failProbe = true
    val testBody = parser.parse("""{"host":"example.org","port":22,"username":"root","hostKeyFingerprint":"SHA256:test","credentials":{"type":"PASSWORD","password":"secret"}}""").toOption.get
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
    var hostProbes = 0
    var credentialResolutions = 0
    var probeFingerprint = "SHA256:test"
    var probedCredentials = List.empty[SshCredential]
    var beforeVerified: () => Unit = () => ()

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
      override def saveIfUnmodified(value: Connection, expectedUpdatedAt: Instant): IO[Boolean] = IO {
        if (savedConnection.exists(_.updatedAt == expectedUpdatedAt)) {
          savedConnection = Some(value)
          true
        } else false
      }
    }
    val schedules = new ConnectionScheduleRepository[IO] {
      override def save(value: ConnectionSchedule): IO[Unit] = IO { savedSchedule = Some(value) }
      override def findByConnection(organizationId: UUID, connectionId: UUID): IO[Option[ConnectionSchedule]] =
        IO.pure(savedSchedule.filter(s => s.organizationId == organizationId && s.connectionId == connectionId))
      override def claimDue(claimedBy: UUID, limit: Int, leaseSeconds: Long) = IO.pure(Nil)
      override def completeClaimedRun(organizationId: UUID, connectionId: UUID, claimedBy: UUID, nextRunAt: Instant, consecutiveFailures: Long) = IO.pure(true)
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
      override def tryCreate(project: Project): IO[Boolean] = IO.pure(false)
      override def findActiveByOrganization(organizationId: UUID): IO[List[Project]] = IO.pure(Nil)
      override def findActiveById(organizationId: UUID, projectId: UUID): IO[Option[Project]] = IO.pure(None)
    }
    val environments = new EnvironmentRepository[IO] {
      override def tryCreate(environment: Environment): IO[Boolean] = IO.pure(false)
      override def findActiveByProject(organizationId: UUID, projectId: UUID): IO[List[Environment]] = IO.pure(Nil)
    }
    val probe = new SshConnectionProbe[IO] {
      override def probeHostKey(settings: SshConnectionSettings): IO[String] = IO {
        hostProbes += 1
        probeFingerprint
      }
      override def verify(config: SshConnectionSettings, credential: SshCredential): IO[String] =
        IO {
          probes += 1
          probedCredentials = probedCredentials :+ credential
          probedInTransaction ||= inTransaction
          if (failProbe) throw SshProbeError.ConnectionFailed
          beforeVerified()
          probeFingerprint
        }
    }
    val auth = new SshCredentialResolver[IO] {
      override def resolveCredential(connection: Connection): IO[SshCredential] =
        IO { credentialResolutions += 1; SshCredential.Password("secret") }
    }
    val (auditEvents, auditRecorder) = support.TestAuditRecorder.recording
    val management = new SshConnectionManagement[IO](connections, schedules, secrets,
      projects, environments, runner, probe, auth, cipher, auditRecorder)
  }
}

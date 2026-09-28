package ru.bitec.app.ops
package application.auth

import application.port.{MyOrganization, OrganizationMembershipRepository, OrganizationProvisioning, OrganizationRepository, TransactionRunner, UserAccountRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.{OrganizationMembership, UserAccount}
import domain.organization.Organization
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class BootstrapAdminSpec extends FunSuite {
  private val orgId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val now = Instant.parse("2026-09-22T12:00:00Z")
  private val config = BootstrapConfig("admin@example.com", "initial-password", orgId, "Admin")

  test("bootstrap is optional and validates complete configuration") {
    assertEquals(BootstrapConfig.fromEnvironment(Map.empty), Right(None))
    assertEquals(BootstrapConfig.fromEnvironment(Map(
      "INFRADESK_BOOTSTRAP_EMAIL" -> "",
      "INFRADESK_BOOTSTRAP_PASSWORD" -> " ",
      "INFRADESK_BOOTSTRAP_ORGANIZATION_ID" -> "",
      "INFRADESK_BOOTSTRAP_DISPLAY_NAME" -> ""
    )), Right(None))
    assert(BootstrapConfig.fromEnvironment(Map("INFRADESK_BOOTSTRAP_EMAIL" -> "admin@example.com")).isLeft)
  }

  test("bootstrap creates one owner without overwriting an existing password") {
    val users = new Users
    val memberships = new Memberships
    val service = new BootstrapAdmin[IO](users, memberships, new Organizations(true), new Provisioning, runner, new BCryptPasswordHasher)

    service.run(Some(config)).unsafeRunSync()
    assertEquals(users.values.length, 1)
    assertEquals(memberships.values.length, 1)
    val originalHash = users.values.head.passwordHash
    assert(new BCryptPasswordHasher().verify(config.password, originalHash).unsafeRunSync())
    service.run(Some(config.copy(password = "different-password"))).unsafeRunSync()
    assertEquals(users.values.length, 1)
    assertEquals(users.values.head.passwordHash, originalHash)
    assertEquals(memberships.values.length, 1)
    assertEquals(memberships.values.head.role.code, "OWNER")
  }

  test("bootstrap rejects a missing organization before creating a user") {
    val users = new Users
    val memberships = new Memberships
    val service = new BootstrapAdmin[IO](users, memberships, new Organizations(false), new Provisioning, runner, new BCryptPasswordHasher)
    intercept[IllegalStateException](service.run(Some(config)).unsafeRunSync())
    assertEquals(users.values.length, 0)
    assertEquals(memberships.values.length, 0)
  }

  test("bootstrap creates the named organization once, then the owner inside it") {
    val users = new Users
    val memberships = new Memberships
    val provisioning = new Provisioning
    val organizations = new OrganizationRepository[IO] {
      override def findActiveById(id: UUID): IO[Option[Organization]] =
        IO(provisioning.values.find(_._1 == id).map { case (_, code, name) => Organization(id, code, name, true, now, now) })
    }
    val service = new BootstrapAdmin[IO](users, memberships, organizations, provisioning, runner, new BCryptPasswordHasher)
    val named = config.copy(organizationName = Some("Default organization"))
    service.run(Some(named)).unsafeRunSync()
    service.run(Some(named)).unsafeRunSync()
    assertEquals(provisioning.values, List((orgId, "default", "Default organization")))
    assertEquals((users.values.length, memberships.values.map(_.organizationId)), (1, List(orgId)))
    assertEquals(BootstrapConfig.fromEnvironment(Map(
      "INFRADESK_BOOTSTRAP_EMAIL" -> "admin@example.com", "INFRADESK_BOOTSTRAP_PASSWORD" -> "initial-password",
      "INFRADESK_BOOTSTRAP_ORGANIZATION_ID" -> orgId.toString, "INFRADESK_BOOTSTRAP_DISPLAY_NAME" -> "Admin",
      "INFRADESK_BOOTSTRAP_ORGANIZATION_NAME" -> " Default organization ")).map(_.flatMap(_.organizationName)),
      Right(Some("Default organization")))
    assert(!named.toString.contains("initial-password"))
  }

  private final class Provisioning extends OrganizationProvisioning[IO] {
    var values: List[(UUID, String, String)] = Nil
    override def createIfMissing(id: UUID, code: String, name: String, at: Instant): IO[Unit] = IO {
      if (!values.exists(value => value._1 == id || value._2 == code)) values = values :+ ((id, code, name))
    }
  }

  private val runner = new TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }

  private final class Users extends UserAccountRepository[IO] {
    var values: List[UserAccount] = Nil
    override def findByEmail(email: String): IO[Option[UserAccount]] = IO(values.find(_.email == email))
    override def findActiveById(id: UUID): IO[Option[UserAccount]] = IO(values.find(user => user.id == id && user.isActive))
    override def createIfMissing(user: UserAccount): IO[Unit] = IO {
      if (!values.exists(_.email == user.email)) values = user :: values
    }
    override def compareAndSetPasswordHash(id: UUID, expectedPasswordHash: String, newPasswordHash: String, updatedAt: java.time.Instant): IO[Boolean] =
      IO.pure(false)
    override def updateDisplayName(id: UUID, displayName: String, updatedAt: java.time.Instant): IO[Boolean] =
      IO.pure(false)
  }

  private final class Memberships extends OrganizationMembershipRepository[IO] {
    override def findActiveRole(userId: UUID, organizationId: UUID): IO[Option[domain.auth.OrganizationRole]] = IO.pure(None)
    var values: List[OrganizationMembership] = Nil
    override def hasActiveMembership(userId: UUID, organizationId: UUID): IO[Boolean] = IO.pure(false)
    override def listActiveOrganizations(userId: UUID): IO[List[MyOrganization]] = IO.pure(Nil)
    override def createIfMissing(membership: OrganizationMembership): IO[Unit] = IO {
      if (!values.exists(value => value.userId == membership.userId && value.organizationId == membership.organizationId)) {
        values = membership :: values
      }
    }
  }

  private final class Organizations(exists: Boolean) extends OrganizationRepository[IO] {
    override def findActiveById(id: UUID): IO[Option[Organization]] = IO.pure {
      if (exists && id == orgId) Some(Organization(orgId, "ORG", "Organization", true, now, now))
      else None
    }
  }
}

package ru.bitec.app.ops
package application.auth

import application.port.{OrganizationMembershipRepository, OrganizationRepository, TransactionRunner, UserAccountRepository}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.{OrganizationMembership, OrganizationRole, UserAccount}

import java.time.Instant
import java.util.UUID

final case class BootstrapConfig(email: String, password: String, organizationId: UUID, displayName: String) {
  override def toString: String = s"BootstrapConfig(email=$email, password=<redacted>, organizationId=$organizationId, displayName=$displayName)"
}

object BootstrapConfig {
  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, Option[BootstrapConfig]] = {
    val keys = List(
      "INFRADESK_BOOTSTRAP_EMAIL", "INFRADESK_BOOTSTRAP_PASSWORD",
      "INFRADESK_BOOTSTRAP_ORGANIZATION_ID", "INFRADESK_BOOTSTRAP_DISPLAY_NAME"
    )
    val configured = keys.filter(key => values.get(key).exists(_.trim.nonEmpty))
    // Compose commonly supplies optional variables as empty strings. All-empty means disabled;
    // once one value is present the contract remains fail-fast and requires the whole group.
    if (configured.isEmpty) Right(None)
    else if (keys.exists(key => values.get(key).forall(_.trim.isEmpty)))
      Left(new IllegalArgumentException(s"Invalid ${keys.find(key => values.get(key).forall(_.trim.isEmpty)).get}: all bootstrap settings must be configured together"))
    else {
      scala.util.Try(UUID.fromString(values("INFRADESK_BOOTSTRAP_ORGANIZATION_ID")))
        .toEither.leftMap(_ => new IllegalArgumentException("Invalid INFRADESK_BOOTSTRAP_ORGANIZATION_ID: expected UUID"))
        .map(id => Some(BootstrapConfig(
          UserAccount.normalizeEmail(values("INFRADESK_BOOTSTRAP_EMAIL")),
          values("INFRADESK_BOOTSTRAP_PASSWORD"), id,
          values("INFRADESK_BOOTSTRAP_DISPLAY_NAME").trim
        )))
    }
  }
}

final class BootstrapAdmin[Tx[_]](
  users: UserAccountRepository[Tx],
  memberships: OrganizationMembershipRepository[Tx],
  organizations: OrganizationRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  passwords: PasswordHasher
) {
  def run(config: Option[BootstrapConfig]): IO[Unit] = config match {
    case None => IO.unit
    case Some(value) =>
      for {
        organization <- runner.run(organizations.findActiveById(value.organizationId))
        _ <- IO.raiseWhen(organization.isEmpty)(new IllegalStateException("Bootstrap organization was not found or is inactive"))
        existing <- runner.run(users.findByEmail(value.email))
        user <- existing match {
          case Some(account) => IO.pure(account)
          case None =>
            for {
              hash <- passwords.hash(value.password)
              now <- IO(Instant.now())
              proposed = UserAccount(UUID.randomUUID(), value.email, hash, value.displayName, true, now, now)
              _ <- runner.run(users.createIfMissing(proposed))
              actual <- runner.run(users.findByEmail(value.email)).flatMap {
                case Some(account) => IO.pure(account)
                case None => IO.raiseError[UserAccount](new IllegalStateException("Bootstrap user creation failed"))
              }
            } yield actual
        }
        now <- IO(Instant.now())
        _ <- runner.run(memberships.createIfMissing(
          OrganizationMembership(user.id, value.organizationId, OrganizationRole.Owner, true, now, now)
        ))
      } yield ()
  }
}

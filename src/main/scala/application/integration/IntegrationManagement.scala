package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{IdGenerator, IntegrationCryptography, IntegrationRepository, IntegrationSecret,
  IntegrationSecretRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.integration.{Integration, IntegrationBaseUrl, IntegrationProviderType, RemnawaveCredential}
import java.util.UUID

final case class IntegrationError(code: String, override val getMessage: String)
  extends RuntimeException(getMessage)

final case class CreateIntegrationCommand(name: String, providerType: IntegrationProviderType,
  baseUrl: String, credential: RemnawaveCredential)
final case class UpdateIntegrationCommand(name: String, baseUrl: String,
  credential: Option[RemnawaveCredential])

/** Database-only lifecycle. Routes run each method as one transaction; the network test runs later. */
final class IntegrationManagement[Tx[_]: MonadThrow](
  integrations: IntegrationRepository[Tx], secrets: IntegrationSecretRepository[Tx],
  ids: IdGenerator[Tx], time: TimeProvider[Tx], cipher: IntegrationCryptography,
  audit: AuditRecorder[Tx]
) {
  def list(organizationId: UUID): Tx[List[Integration]] = integrations.listByOrganization(organizationId)
  def get(organizationId: UUID, id: UUID): Tx[Option[Integration]] = integrations.findById(organizationId, id)

  def create(actor: ActorContext, command: CreateIntegrationCommand): Tx[Integration] = for {
    name <- validName(command.name).liftTo[Tx]
    url <- IntegrationBaseUrl.parse(command.baseUrl).leftMap(_ => invalid).liftTo[Tx]
    _ <- validCredential(command.credential).liftTo[Tx]
    _ <- Either.cond(command.providerType == command.credential.providerType, (), invalid).liftTo[Tx]
    id <- ids.nextId
    secretId <- ids.nextId
    now <- time.now
    integration = Integration(id, actor.organizationId, name, command.providerType, url,
      enabled = false, secretId, command.credential.caddyApiKey.nonEmpty, now, now)
    _ <- secrets.save(cipher.encrypt(secretId, actor.organizationId, command.credential))
    _ <- integrations.save(integration)
    _ <- audit.record(actor, AuditAction.IntegrationCreated, AuditTargetType.Integration, Some(id))
  } yield integration

  def update(actor: ActorContext, id: UUID, command: UpdateIntegrationCommand): Tx[Integration] = for {
    name <- validName(command.name).liftTo[Tx]
    url <- IntegrationBaseUrl.parse(command.baseUrl).leftMap(_ => invalid).liftTo[Tx]
    _ <- command.credential.traverse_(validCredential).liftTo[Tx]
    stored <- load(actor.organizationId, id)
    replacement <- command.credential.traverse(credential =>
      ids.nextId.map(secretId => cipher.encrypt(secretId, actor.organizationId, credential)))
    _ <- replacement.traverse_(secrets.save)
    now <- time.now
    next = stored.copy(name = name, baseUrl = url,
      secretId = replacement.map(_.id).getOrElse(stored.secretId),
      caddyApiKeyConfigured = command.credential.map(_.caddyApiKey.nonEmpty)
        .getOrElse(stored.caddyApiKeyConfigured), updatedAt = now)
    _ <- integrations.save(next)
    _ <- replacement.traverse_(_ => secrets.delete(actor.organizationId, stored.secretId))
    _ <- audit.record(actor, AuditAction.IntegrationUpdated, AuditTargetType.Integration, Some(id))
  } yield next

  def setEnabled(actor: ActorContext, id: UUID, enabled: Boolean): Tx[Integration] =
    load(actor.organizationId, id).flatMap { stored =>
      if (stored.enabled == enabled) stored.pure[Tx]
      else for {
        now <- time.now
        next = stored.copy(enabled = enabled, updatedAt = now)
        _ <- integrations.save(next)
        action = if (enabled) AuditAction.IntegrationEnabled else AuditAction.IntegrationDisabled
        _ <- audit.record(actor, action, AuditTargetType.Integration, Some(id))
      } yield next
    }

  def delete(actor: ActorContext, id: UUID): Tx[Unit] = for {
    stored <- load(actor.organizationId, id)
    _ <- integrations.delete(actor.organizationId, id)
    _ <- secrets.delete(actor.organizationId, stored.secretId)
    _ <- audit.record(actor, AuditAction.IntegrationDeleted, AuditTargetType.Integration, Some(id))
  } yield ()

  /** Test intent commits before decrypting or contacting the provider. */
  def prepareTest(actor: ActorContext, id: UUID): Tx[(Integration, IntegrationSecret)] = for {
    integration <- integrations.findById(actor.organizationId, id).flatMap(_.liftTo[Tx](notFound))
    secret <- secrets.find(actor.organizationId, integration.secretId).flatMap(
      _.liftTo[Tx](IntegrationError("INTEGRATION_CREDENTIAL_MISSING", "Integration credential is missing")))
    _ <- audit.record(actor, AuditAction.IntegrationTestRequested, AuditTargetType.Integration, Some(id))
  } yield (integration, secret)

  private def load(org: UUID, id: UUID): Tx[Integration] =
    integrations.findByIdForUpdate(org, id).flatMap(_.liftTo[Tx](notFound))
  private def notFound = IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found")
  private def invalid = IntegrationError("INVALID_REQUEST", "Invalid integration configuration")
  private def validName(value: String): Either[IntegrationError, String] =
    Either.cond(value.trim.nonEmpty && value.trim.length <= 255, value.trim, invalid)
  private def validCredential(value: RemnawaveCredential): Either[IntegrationError, Unit] =
    Either.cond(value.valid, (), IntegrationError("INVALID_REQUEST", "Invalid integration credential"))
}

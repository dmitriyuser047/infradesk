package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{IdGenerator, IntegrationActionRepository, IntegrationConfigProfileRepository, IntegrationCryptography,
  IntegrationInventoryRepository, IntegrationRepository, IntegrationSecret, IntegrationSecretRepository,
  IntegrationSyncStateRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.integration.{Integration, IntegrationBaseUrl, IntegrationManagementMode, IntegrationProviderType,
  RemnawaveCredential}
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
  audit: AuditRecorder[Tx], syncState: IntegrationSyncStateRepository[Tx],
  actions: IntegrationActionRepository[Tx], inventory: IntegrationInventoryRepository[Tx],
  configProfiles: IntegrationConfigProfileRepository[Tx]
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
    // Every integration has a schedule; it is only claimed while the integration is enabled.
    _ <- syncState.ensure(actor.organizationId, id, now)
    _ <- audit.record(actor, AuditAction.IntegrationCreated, AuditTargetType.Integration, Some(id))
  } yield integration

  def update(actor: ActorContext, id: UUID, command: UpdateIntegrationCommand): Tx[Integration] = for {
    name <- validName(command.name).liftTo[Tx]
    url <- IntegrationBaseUrl.parse(command.baseUrl).leftMap(_ => invalid).liftTo[Tx]
    _ <- command.credential.traverse_(validCredential).liftTo[Tx]
    stored <- load(actor.organizationId, id)
    targetChanged = stored.baseUrl != url || command.credential.nonEmpty
    // A desired state belongs to the panel it was set for: it never follows the integration to
    // another endpoint or credential. Return to OBSERVE first, which removes the intents.
    _ <- Either.cond(!(targetChanged && managed(stored)), (), IntegrationError(
      IntegrationDesiredStates.ManagementActive, "Endpoint and credentials cannot change while nodes are managed"))
      .liftTo[Tx]
    _ <- if (targetChanged) requireNoActive(actor.organizationId, id) else ().pure[Tx]
    replacement <- command.credential.traverse(credential =>
      ids.nextId.map(secretId => cipher.encrypt(secretId, actor.organizationId, credential)))
    _ <- replacement.traverse_(secrets.save)
    now <- time.now
    next = stored.copy(name = name, baseUrl = url,
      secretId = replacement.map(_.id).getOrElse(stored.secretId),
      caddyApiKeyConfigured = command.credential.map(_.caddyApiKey.nonEmpty)
        .getOrElse(stored.caddyApiKeyConfigured), updatedAt = now)
    _ <- integrations.save(next)
    _ <- if (targetChanged) configProfiles.detachAll(actor.organizationId, id, now) else ().pure[Tx]
    _ <- if (targetChanged) inventory.deactivateAll(actor.organizationId, id, now).void else ().pure[Tx]
    _ <- replacement.traverse_(_ => secrets.delete(actor.organizationId, stored.secretId))
    _ <- audit.record(actor, AuditAction.IntegrationUpdated, AuditTargetType.Integration, Some(id))
  } yield next

  def setEnabled(actor: ActorContext, id: UUID, enabled: Boolean): Tx[Integration] =
    load(actor.organizationId, id).flatMap { stored =>
      if (stored.enabled == enabled) stored.pure[Tx]
      // Desired state cannot be kept without fresh observations.
      else if (!enabled && managed(stored)) IntegrationError(IntegrationDesiredStates.RequiresSync,
        "Automatic synchronization cannot be disabled while nodes are managed").raiseError[Tx, Integration]
      else for {
        now <- time.now
        next = stored.copy(enabled = enabled, updatedAt = now)
        _ <- integrations.save(next)
        // Enabling turns automatic observation on: the first run is due right away.
        _ <- if (enabled) syncState.scheduleAt(actor.organizationId, id, now) else ().pure[Tx]
        action = if (enabled) AuditAction.IntegrationEnabled else AuditAction.IntegrationDisabled
        _ <- audit.record(actor, action, AuditTargetType.Integration, Some(id))
      } yield next
    }

  def delete(actor: ActorContext, id: UUID): Tx[Unit] = for {
    stored <- load(actor.organizationId, id)
    _ <- requireNoActive(actor.organizationId, id)
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
  private def requireNoActive(org: UUID, id: UUID): Tx[Unit] =
    actions.hasActive(org, id).flatMap(active => Either.cond(!active, (),
      IntegrationError("INTEGRATION_ACTION_ALREADY_RUNNING", "An action is already active"))
      .liftTo[Tx])
  private def managed(value: Integration): Boolean =
    value.managementMode == IntegrationManagementMode.ManagedSelected
  private def notFound = IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found")
  private def invalid = IntegrationError("INVALID_REQUEST", "Invalid integration configuration")
  private def validName(value: String): Either[IntegrationError, String] =
    Either.cond(value.trim.nonEmpty && value.trim.length <= 255, value.trim, invalid)
  private def validCredential(value: RemnawaveCredential): Either[IntegrationError, Unit] =
    Either.cond(value.valid, (), IntegrationError("INVALID_REQUEST", "Invalid integration credential"))
}

package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.configuration.{ConfigurationProfileValidation, ConfigurationError}
import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration.{CanonicalJson, ConfigurationDiff, ConfigurationLimits,
  ConfigurationLineDiff, ConfigurationProfile, ConfigurationProfileKind, ConfigurationRevision}
import domain.integration._
import io.circe.Json
import io.circe.parser.parse
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

final case class AdoptIntegrationConfigProfile(code: String, name: String, description: Option[String])
final case class IntegrationConfigPreview(revisionNumber: Int, localSha256: String,
  remoteSha256: String, remoteUpdatedAt: Option[Instant], changed: Boolean, diff: ConfigurationDiff)
final case class ManagedIntegrationConfigProfile(binding: IntegrationConfigProfileBinding,
  profile: ConfigurationProfile, latestSha256: String, remoteSha256: Option[String],
  latestDeployedSha256: Option[String], latestDeployment: Option[IntegrationConfigDeployment],
  status: IntegrationConfigStatus, nodesUsingProfile: Int)

/** Explicit single-profile operations. Network reads are always outside database transactions. */
final class IntegrationConfigProfiles[Tx[_]: MonadThrow](integrations: IntegrationRepository[Tx],
  inventory: IntegrationInventoryRepository[Tx], secrets: IntegrationSecretRepository[Tx],
  credentialCipher: IntegrationCryptography, providers: IntegrationProviderRegistry[IO],
  profiles: ConfigurationProfileRepository[Tx], profileQuery: ConfigurationProfileQuery[Tx],
  configs: IntegrationConfigProfileRepository[Tx], deployments: IntegrationConfigDeploymentRepository[Tx],
  ids: IdGenerator[Tx], time: TimeProvider[Tx], audit: AuditRecorder[Tx],
  runner: TransactionRunner[IO, Tx], syncState: IntegrationSyncStateRepository[Tx], logger: Logger[IO]) {

  private def log(value: String): IO[Unit] = logger.info(value).handleErrorWith(_ => IO.unit)

  private def fail[A](code: String, message: String): Tx[A] =
    IntegrationError(code, message).raiseError[Tx, A]
  private val notFound = IntegrationError("INTEGRATION_CONFIG_PROFILE_NOT_FOUND", "Config profile was not found")

  private def checkedObject(org: UUID, integrationId: UUID, objectId: UUID,
    forUpdate: Boolean): Tx[IntegrationInventoryObject] =
    inventory.findObject(org, integrationId, objectId, forUpdate).flatMap(_.liftTo[Tx](notFound)).flatMap { obj =>
      if (obj.objectType == IntegrationObjectType.ConfigProfile) obj.pure[Tx]
      else fail("INTEGRATION_CONFIG_PROFILE_UNSUPPORTED", "Object is not a config profile")
    }

  private def integration(org: UUID, id: UUID, forUpdate: Boolean): Tx[Integration] =
    (if (forUpdate) integrations.findByIdForUpdate(org, id) else integrations.findById(org, id))
      .flatMap(_.liftTo[Tx](IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found")))

  private def transport(value: Integration): IO[IntegrationConfigProfileTransport[IO]] =
    providers.find(value.providerType).flatMap(_.configProfiles)
      .liftTo[IO](IntegrationError("INTEGRATION_CONFIG_PROFILE_UNSUPPORTED", "Provider does not manage config profiles"))

  private def context(value: Integration, secret: IntegrationSecret): IO[IntegrationRuntimeContext] =
    IO.delay(credentialCipher.decrypt(secret)).handleErrorWith(_ =>
      IO.raiseError(IntegrationError("INTEGRATION_CREDENTIAL_INVALID", "Integration credential is invalid")))
      .map(credential => IntegrationRuntimeContext(value.id, value.organizationId, value.baseUrl, credential))

  private def secret(value: Integration): Tx[IntegrationSecret] =
    secrets.find(value.organizationId, value.secretId).flatMap(_.liftTo[Tx](
      IntegrationError("INTEGRATION_CREDENTIAL_MISSING", "Integration credential is missing")))

  private def canonical(config: Json): Either[IntegrationError, String] = {
    val result = if (config.isObject) Some(CanonicalJson.render(config)) else None
    result.filter(_.getBytes(StandardCharsets.UTF_8).length <= ConfigurationLimits.MaxTemplateBytes)
      .toRight(IntegrationError("INVALID_INTEGRATION_CONFIG", "Configuration must be a bounded JSON object"))
  }

  /** TX1 snapshot, remote GET, TX2 recheck and atomic profile/revision/payload/binding/audit. */
  def adopt(actor: ActorContext, integrationId: UUID, objectId: UUID,
    command: AdoptIntegrationConfigProfile): IO[ConfigurationProfile] = for {
    _ <- log(s"integration.config.adopt.started organizationId=${actor.organizationId} integrationId=$integrationId inventoryObjectId=$objectId")
    code <- ConfigurationProfileValidation.code(command.code).liftTo[IO]
    metadata <- ConfigurationProfileValidation.metadata(command.name, command.description).liftTo[IO]
    before <- runner.run(for {
      i <- integration(actor.organizationId, integrationId, forUpdate = false)
      obj <- checkedObject(actor.organizationId, integrationId, objectId, forUpdate = false)
      _ <- MonadThrow[Tx].raiseUnless(obj.isActive)(IntegrationError(
        "INTEGRATION_CONFIG_PROFILE_INACTIVE", "Config profile is inactive"))
      bound <- configs.binding(actor.organizationId, integrationId, objectId)
      _ <- MonadThrow[Tx].raiseWhen(bound.nonEmpty)(IntegrationError(
        "INTEGRATION_CONFIG_PROFILE_ALREADY_MANAGED", "Config profile is already managed"))
      s <- secret(i)
    } yield (i, obj, s))
    (initialIntegration, initialObject, initialSecret) = before
    runtime <- context(initialIntegration, initialSecret)
    provider <- transport(initialIntegration)
    remote <- provider.fetchConfigProfile(runtime, initialObject.externalId)
    contents <- canonical(remote.config).liftTo[IO]
    result <- runner.run(for {
      current <- integration(actor.organizationId, integrationId, forUpdate = true)
      obj <- checkedObject(actor.organizationId, integrationId, objectId, forUpdate = true)
      _ <- MonadThrow[Tx].raiseUnless(current.baseUrl == initialIntegration.baseUrl &&
        current.secretId == initialIntegration.secretId && obj.externalId == initialObject.externalId &&
        obj.isActive && remote.externalId == obj.externalId)(IntegrationError(
          "INTEGRATION_CONFIG_PROFILE_CHANGED", "Config profile changed during adoption"))
      bound <- configs.binding(actor.organizationId, integrationId, objectId)
      _ <- MonadThrow[Tx].raiseWhen(bound.nonEmpty)(IntegrationError(
        "INTEGRATION_CONFIG_PROFILE_ALREADY_MANAGED", "Config profile is already managed"))
      profileId <- ids.nextId
      revisionId <- ids.nextId
      bindingId <- ids.nextId
      now <- time.now
      profile = ConfigurationProfile(profileId, actor.organizationId, code, metadata.name,
        metadata.description, archived = false, latestRevisionNumber = 1, now, now,
        ConfigurationProfileKind.RemnawaveConfig)
      revision = ConfigurationRevision(revisionId, actor.organizationId, profileId, 1, "", Nil, actor.userId, now)
      inserted <- profiles.insertProfile(profile)
      _ <- MonadThrow[Tx].raiseUnless(inserted)(ConfigurationError.codeExists)
      _ <- profiles.insertRevision(revision)
      _ <- configs.insertSecureRevision(revisionId, actor.organizationId, profileId, contents, now)
      created <- configs.insertBinding(IntegrationConfigProfileBinding(bindingId, actor.organizationId,
        integrationId, objectId, profileId, actor.userId, now, now))
      _ <- MonadThrow[Tx].raiseUnless(created)(IntegrationError(
        "INTEGRATION_CONFIG_PROFILE_ALREADY_MANAGED", "Config profile is already managed"))
      _ <- audit.record(actor, AuditAction.IntegrationConfigProfileAdopted,
        AuditTargetType.ConfigurationProfile, Some(profileId))
      _ <- if (current.enabled) syncState.scheduleAt(actor.organizationId, integrationId, now)
        else ().pure[Tx]
    } yield profile)
    _ <- log(s"integration.config.adopt.completed organizationId=${actor.organizationId} integrationId=$integrationId inventoryObjectId=$objectId configurationProfileId=${result.id}")
  } yield result

  def appendRevision(actor: ActorContext, integrationId: UUID, objectId: UUID,
    config: Json): IO[Int] = for {
    contents <- canonical(config).liftTo[IO]
    number <- runner.run(for {
      binding <- configs.binding(actor.organizationId, integrationId, objectId).flatMap(_.liftTo[Tx](notFound))
      profile <- profiles.findForUpdate(actor.organizationId, binding.configurationProfileId)
        .flatMap(_.liftTo[Tx](notFound))
      _ <- MonadThrow[Tx].raiseUnless(profile.kind == ConfigurationProfileKind.RemnawaveConfig && !profile.archived)(
        IntegrationError("INTEGRATION_CONFIG_PROFILE_UNAVAILABLE", "Managed profile is unavailable"))
      id <- ids.nextId
      now <- time.now
      next = profile.latestRevisionNumber + 1
      _ <- profiles.insertRevision(ConfigurationRevision(id, actor.organizationId, profile.id,
        next, "", Nil, actor.userId, now))
      _ <- configs.insertSecureRevision(id, actor.organizationId, profile.id, contents, now)
      _ <- profiles.updateProfile(profile.copy(latestRevisionNumber = next, updatedAt = now))
      _ <- audit.record(actor, AuditAction.IntegrationConfigRevisionCreated,
        AuditTargetType.ConfigurationProfile, Some(profile.id))
    } yield next)
    _ <- log(s"integration.config.revision.created organizationId=${actor.organizationId} integrationId=$integrationId inventoryObjectId=$objectId revisionNumber=$number")
  } yield number

  def revisionContent(org: UUID, integrationId: UUID, objectId: UUID,
    revisionNumber: Int): IO[IntegrationSecureRevision] = runner.run(for {
    binding <- configs.binding(org, integrationId, objectId).flatMap(_.liftTo[Tx](notFound))
    revision <- configs.secureRevision(org, binding.configurationProfileId, revisionNumber)
      .flatMap(_.liftTo[Tx](notFound))
  } yield revision)

  def revisions(org: UUID, integrationId: UUID, objectId: UUID,
    before: Option[Int], limit: Int): IO[List[ConfigurationRevisionSummary]] = runner.run(for {
    binding <- configs.binding(org, integrationId, objectId).flatMap(_.liftTo[Tx](notFound))
    rows <- profileQuery.listRevisions(org, binding.configurationProfileId, before, limit)
  } yield rows)

  def preview(org: UUID, integrationId: UUID, objectId: UUID,
    revisionNumber: Int): IO[IntegrationConfigPreview] = for {
    loaded <- runner.run(for {
      i <- integration(org, integrationId, forUpdate = false)
      obj <- checkedObject(org, integrationId, objectId, forUpdate = false)
      binding <- configs.binding(org, integrationId, objectId).flatMap(_.liftTo[Tx](notFound))
      revision <- configs.secureRevision(org, binding.configurationProfileId, revisionNumber)
        .flatMap(_.liftTo[Tx](notFound))
      s <- secret(i)
    } yield (i, obj, revision, s))
    (i, obj, revision, s) = loaded
    runtime <- context(i, s)
    provider <- transport(i)
    remote <- provider.fetchConfigProfile(runtime, obj.externalId)
    _ <- Either.cond(remote.externalId == obj.externalId, (), IntegrationError(
      "INTEGRATION_CONFIG_PROFILE_CHANGED", "Config profile changed during preview")).liftTo[IO]
    remoteText <- canonical(remote.config).liftTo[IO]
    // The same bounded diff as file deployment; its text is returned only to this authorized caller.
    before = parse(remoteText).toOption.get.spaces2
    after = parse(revision.canonicalJson).toOption.get.spaces2
  } yield IntegrationConfigPreview(revisionNumber, revision.contentSha256,
    CanonicalJson.sha256(remoteText), remote.updatedAt,
    revision.contentSha256 != CanonicalJson.sha256(remoteText), ConfigurationLineDiff.between(before, after))

  def requestDeploy(actor: ActorContext, integrationId: UUID, objectId: UUID,
    revisionNumber: Int, requestId: UUID): IO[IntegrationConfigDeployment] = runner.run(
    deployments.findByRequest(actor.organizationId, requestId).flatMap {
      case Some(existing) =>
        if (existing.integrationId == integrationId && existing.inventoryObjectId == objectId &&
          existing.revisionNumber == revisionNumber) existing.pure[Tx]
        else fail[IntegrationConfigDeployment]("CONFIG_DEPLOYMENT_REQUEST_ID_CONFLICT", "Request ID was already used")
      case None => for {
        _ <- integration(actor.organizationId, integrationId, forUpdate = true)
        obj <- checkedObject(actor.organizationId, integrationId, objectId, forUpdate = true)
        _ <- MonadThrow[Tx].raiseUnless(obj.isActive)(IntegrationError(
          "INTEGRATION_CONFIG_PROFILE_INACTIVE", "Config profile is inactive"))
        observed <- obj.summary match {
          case profile: RemnawaveConfigProfileSummary => profile.configSha256
            .liftTo[Tx](IntegrationError("INTEGRATION_CONFIG_REQUIRES_REFRESH", "Synchronize before deploying"))
          case _ => fail[String]("INTEGRATION_CONFIG_PROFILE_UNSUPPORTED", "Config profile observation is invalid")
        }
        binding <- configs.binding(actor.organizationId, integrationId, objectId).flatMap(_.liftTo[Tx](notFound))
        latest <- deployments.recent(actor.organizationId, integrationId, objectId, 1)
        _ <- MonadThrow[Tx].raiseWhen(latest.headOption.exists(d =>
          d.status == IntegrationConfigDeploymentStatus.Unknown &&
          d.finishedAt.exists(!obj.lastSeenAt.isAfter(_))))(IntegrationError(
          "INTEGRATION_CONFIG_REQUIRES_REFRESH", "Synchronize before deploying again"))
        profile <- profileQuery.find(actor.organizationId, binding.configurationProfileId)
          .flatMap(_.liftTo[Tx](notFound))
        _ <- MonadThrow[Tx].raiseUnless(profile.kind == ConfigurationProfileKind.RemnawaveConfig && !profile.archived)(
          IntegrationError("INTEGRATION_CONFIG_PROFILE_UNAVAILABLE", "Managed profile is unavailable"))
        revision <- profileQuery.findRevision(actor.organizationId, profile.id, revisionNumber)
          .flatMap(_.liftTo[Tx](notFound))
        desiredHash <- configs.revisionHash(actor.organizationId, profile.id, revisionNumber)
          .flatMap(_.liftTo[Tx](notFound))
        id <- ids.nextId
        now <- time.now
        value = IntegrationConfigDeployment(id, actor.organizationId, integrationId, objectId, binding.id,
          profile.id, revision.revision.id, revisionNumber, requestId, actor.userId,
          IntegrationConfigDeploymentStatus.Queued, observed, desiredHash, now)
        result <- deployments.insertOrFind(value)
        (stored, created) = result
        _ <- MonadThrow[Tx].raiseUnless(created || (stored.integrationId == integrationId &&
          stored.inventoryObjectId == objectId && stored.revisionNumber == revisionNumber))(
          IntegrationError("CONFIG_DEPLOYMENT_REQUEST_ID_CONFLICT", "Request ID was already used"))
        _ <- if (created) audit.record(actor, AuditAction.IntegrationConfigDeploymentRequested,
          AuditTargetType.Integration, Some(integrationId)) else ().pure[Tx]
      } yield stored
    }).flatTap(value => log(s"integration.config.deploy.requested organizationId=${actor.organizationId} integrationId=$integrationId inventoryObjectId=$objectId deploymentId=${value.id} revisionNumber=$revisionNumber"))

  def managed(org: UUID, integrationId: UUID, objectId: UUID): IO[Option[ManagedIntegrationConfigProfile]] =
    runner.run(configs.binding(org, integrationId, objectId).flatMap(_.traverse { binding => for {
      obj <- checkedObject(org, integrationId, objectId, forUpdate = false)
      profile <- profileQuery.find(org, binding.configurationProfileId).flatMap(_.liftTo[Tx](notFound))
      latestHash <- configs.revisionHash(org, profile.id, profile.latestRevisionNumber).flatMap(_.liftTo[Tx](notFound))
      importedHash <- configs.revisionHash(org, profile.id, 1).flatMap(_.liftTo[Tx](notFound))
      latest <- deployments.recent(org, integrationId, objectId, 1)
      succeeded <- deployments.latestSucceededHash(org, integrationId, objectId)
      summary <- obj.summary match {
        case value: RemnawaveConfigProfileSummary => value.pure[Tx]
        case _ => fail[RemnawaveConfigProfileSummary]("INTEGRATION_CONFIG_PROFILE_UNSUPPORTED",
          "Config profile observation is invalid")
      }
      status = if (obj.isActive && !obj.lastSeenAt.isAfter(binding.createdAt))
        IntegrationConfigStatus.WaitingRefresh
      else IntegrationConfigStatus.derive(obj.isActive, obj.lastSeenAt, summary.configSha256,
        latestHash, succeeded.orElse(Some(importedHash)), latest.headOption)
    } yield ManagedIntegrationConfigProfile(binding, profile, latestHash, summary.configSha256,
      succeeded, latest.headOption, status, summary.nodeUuids.size) }))

  def history(org: UUID, integrationId: UUID, objectId: UUID,
    limit: Int): IO[List[IntegrationConfigDeployment]] =
    runner.run(configs.binding(org, integrationId, objectId).flatMap(_.liftTo[Tx](notFound))) *>
      runner.run(deployments.recent(org, integrationId, objectId, limit))
}

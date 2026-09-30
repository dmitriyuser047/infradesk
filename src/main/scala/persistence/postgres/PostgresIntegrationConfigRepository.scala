package ru.bitec.app.ops
package persistence.postgres

import application.integration.IntegrationError
import application.port.{IntegrationConfigDeploymentRepository, IntegrationConfigProfileRepository,
  IntegrationConfigRolloutRepository, IntegrationSecureRevision}
import cats.effect.Sync
import cats.syntax.all._
import domain.integration.{IntegrationConfigDeployment, IntegrationConfigDeploymentStatus,
  IntegrationConfigDeploymentSource, IntegrationConfigProfileBinding, IntegrationConfigRollout,
  IntegrationConfigRolloutInspection, IntegrationConfigRolloutNodeHealth, IntegrationConfigRolloutPreview,
  IntegrationConfigRolloutStatus}
import integration.secret.{RemnawaveConfigCipher, RemnawaveSecurePayload}
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresIntegrationConfigProfileRepository(cipher: RemnawaveConfigCipher)
  extends IntegrationConfigProfileRepository[ConnectionIO] {

  override def binding(org: UUID, integrationId: UUID, objectId: UUID):
    ConnectionIO[Option[IntegrationConfigProfileBinding]] =
    sql"""select id, organization_id, integration_id, inventory_object_id, configuration_profile_id,
        created_by_user_id, created_at, updated_at from integration_config_profile_binding
        where organization_id = $org and integration_id = $integrationId and inventory_object_id = $objectId
          and detached_at is null"""
      .query[IntegrationConfigProfileBinding].option

  override def detachAll(org: UUID, integrationId: UUID, at: Instant): ConnectionIO[Unit] =
    sql"""update integration_config_profile_binding set detached_at = $at, updated_at = $at
      where organization_id = $org and integration_id = $integrationId and detached_at is null"""
      .update.run.void

  override def insertBinding(value: IntegrationConfigProfileBinding): ConnectionIO[Boolean] =
    sql"""insert into integration_config_profile_binding (id, organization_id, integration_id,
        inventory_object_id, configuration_profile_id, created_by_user_id, created_at, updated_at)
        values (${value.id}, ${value.organizationId}, ${value.integrationId}, ${value.inventoryObjectId},
        ${value.configurationProfileId}, ${value.createdByUserId}, ${value.createdAt}, ${value.updatedAt})
        on conflict do nothing""".update.run.map(_ == 1)

  override def insertSecureRevision(revisionId: UUID, org: UUID, profileId: UUID,
    canonicalJson: String, at: Instant): ConnectionIO[Unit] =
    Sync[ConnectionIO].delay(cipher.encrypt(revisionId, org, profileId, canonicalJson)).flatMap { payload =>
      sql"""insert into configuration_revision_secure_payload
          (revision_id, organization_id, profile_id, purpose, nonce, ciphertext, content_sha256, created_at)
          values (${payload.revisionId}, ${payload.organizationId}, ${payload.profileId}, ${payload.purpose},
          ${payload.nonce}, ${payload.ciphertext}, ${payload.contentSha256}, $at)""".update.run.void
    }

  override def secureRevision(org: UUID, profileId: UUID,
    revisionNumber: Int): ConnectionIO[Option[IntegrationSecureRevision]] =
    sql"""select r.id, r.revision_number, s.purpose, s.nonce, s.ciphertext, s.content_sha256
        from configuration_revision r
        join configuration_revision_secure_payload s on s.revision_id = r.id
          and s.profile_id = r.profile_id and s.organization_id = r.organization_id
        where r.organization_id = $org and r.profile_id = $profileId and r.revision_number = $revisionNumber"""
      .query[(UUID, Int, String, Array[Byte], Array[Byte], String)].option.flatMap(_.traverse {
        case (id, number, purpose, nonce, ciphertext, hash) =>
          Sync[ConnectionIO].delay {
            val payload = RemnawaveSecurePayload(id, org, profileId, purpose, nonce, ciphertext, hash)
            IntegrationSecureRevision(id, org, profileId, number, hash, cipher.decrypt(payload))
          }
      })

  override def revisionHash(org: UUID, profileId: UUID, revisionNumber: Int): ConnectionIO[Option[String]] =
    sql"""select s.content_sha256 from configuration_revision r
        join configuration_revision_secure_payload s on s.revision_id = r.id
          and s.profile_id = r.profile_id and s.organization_id = r.organization_id
        where r.organization_id = $org and r.profile_id = $profileId and r.revision_number = $revisionNumber"""
      .query[String].option
}

private[postgres] object IntegrationConfigDeploymentRows {
  final case class Row(id: UUID, organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID,
    bindingId: UUID, configurationProfileId: UUID, configurationRevisionId: UUID,
    revisionNumber: Int, requestId: UUID, requestedByUserId: UUID, status: String,
    expectedRemoteSha256: String, desiredSha256: String, createdAt: Instant,
    startedAt: Option[Instant], recoverAfterAt: Option[Instant], finishedAt: Option[Instant],
    claimedBy: Option[UUID], claimToken: Option[UUID], errorCode: Option[String], errorMessage: Option[String],
    source: String, rolloutId: Option[UUID]) {
    def toDomain: Either[IllegalArgumentException, IntegrationConfigDeployment] = for {
      s <- IntegrationConfigDeploymentStatus.fromCode(status)
      src <- IntegrationConfigDeploymentSource.fromCode(source)
    } yield IntegrationConfigDeployment(id, organizationId,
        integrationId, inventoryObjectId, bindingId, configurationProfileId, configurationRevisionId,
        revisionNumber, requestId, requestedByUserId, s, expectedRemoteSha256, desiredSha256,
        createdAt, startedAt, recoverAfterAt, finishedAt, claimedBy, claimToken, errorCode, errorMessage,
        src, rolloutId)
  }
  val columns: Fragment = fr"""d.id, d.organization_id, d.integration_id, d.inventory_object_id,
    d.binding_id, d.configuration_profile_id, d.configuration_revision_id, d.revision_number,
    d.request_id, d.requested_by_user_id, d.status, d.expected_remote_sha256, d.desired_sha256,
    d.created_at, d.started_at, d.recover_after_at, d.finished_at, d.claimed_by, d.claim_token,
    d.error_code, d.error_message, d.source, d.rollout_id"""
}

final class PostgresIntegrationConfigDeploymentRepository extends IntegrationConfigDeploymentRepository[ConnectionIO] {
  import IntegrationConfigDeploymentRows._

  override def findByRequest(org: UUID, requestId: UUID): ConnectionIO[Option[IntegrationConfigDeployment]] =
    (fr"select" ++ columns ++ fr"from integration_config_deployment d where d.organization_id = $org and d.request_id = $requestId")
      .query[Row].option.flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  override def insertOrFind(value: IntegrationConfigDeployment): ConnectionIO[(IntegrationConfigDeployment, Boolean)] =
    sql"""insert into integration_config_deployment
      (id, organization_id, integration_id, inventory_object_id, binding_id,
       configuration_profile_id, configuration_revision_id, revision_number,
       request_id, requested_by_user_id, status, expected_remote_sha256, desired_sha256, created_at,
       source, rollout_id)
      values (${value.id}, ${value.organizationId}, ${value.integrationId}, ${value.inventoryObjectId},
        ${value.bindingId}, ${value.configurationProfileId}, ${value.configurationRevisionId},
        ${value.revisionNumber}, ${value.requestId}, ${value.requestedByUserId}, 'QUEUED',
        ${value.expectedRemoteSha256}, ${value.desiredSha256}, ${value.createdAt},
        ${value.source.code}, ${value.rolloutId})
      on conflict do nothing""".update.run.flatMap {
      case 1 => (value, true).pure[ConnectionIO]
      case _ => findByRequest(value.organizationId, value.requestId).flatMap {
        case Some(existing) => (existing, false).pure[ConnectionIO]
        case None => IntegrationError("INTEGRATION_CONFIG_DEPLOYMENT_ALREADY_RUNNING",
          "A configuration deployment is already active").raiseError[ConnectionIO, (IntegrationConfigDeployment, Boolean)]
      }
    }

  override def recent(org: UUID, integrationId: UUID, objectId: UUID,
    limit: Int): ConnectionIO[List[IntegrationConfigDeployment]] =
    (fr"select" ++ columns ++ fr"""from integration_config_deployment d
      where d.organization_id = $org and d.integration_id = $integrationId and d.inventory_object_id = $objectId
      order by d.created_at desc, d.id desc limit $limit""")
      .query[Row].to[List].flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  override def recentForBinding(org: UUID, bindingId: UUID,
    limit: Int): ConnectionIO[List[IntegrationConfigDeployment]] =
    (fr"select" ++ columns ++ fr"""from integration_config_deployment d
      where d.organization_id = $org and d.binding_id = $bindingId
      order by d.created_at desc, d.id desc limit $limit""")
      .query[Row].to[List].flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  override def latestSucceededHash(org: UUID, bindingId: UUID): ConnectionIO[Option[String]] =
    sql"""select desired_sha256 from integration_config_deployment
      where organization_id = $org and binding_id = $bindingId
        and status = 'SUCCEEDED' order by finished_at desc, id desc limit 1""".query[String].option

  override def hasActive(org: UUID, integrationId: UUID): ConnectionIO[Boolean] =
    sql"""select exists(select 1 from integration_config_deployment where organization_id = $org
      and integration_id = $integrationId and status in ('QUEUED','RUNNING'))""".query[Boolean].unique

  override def recoverAndClaim(owner: UUID, token: UUID, at: Instant,
    recoverAfter: Instant, limit: Int): ConnectionIO[(List[(UUID, UUID)], List[IntegrationConfigDeployment])] = for {
    recovered <- sql"""with stale as (
        select d.id from integration_config_deployment d
        join integration_inventory_object o on o.id = d.inventory_object_id
          and o.integration_id = d.integration_id and o.organization_id = d.organization_id
        where d.status = 'RUNNING' and d.recover_after_at < $at
        order by d.recover_after_at, d.id for update of d, o skip locked limit $limit
      ) update integration_config_deployment d
      set status = 'UNKNOWN', finished_at = $at, claimed_by = null, claim_token = null,
        error_code = 'INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN', error_message = 'Remote result is unknown'
      from stale where d.id = stale.id returning d.organization_id, d.integration_id"""
      .query[(UUID, UUID)].to[List]
    claimed <- (fr"""with due as (
      select id from integration_config_deployment where status = 'QUEUED'
      order by created_at, id for update skip locked limit $limit)
      update integration_config_deployment d set status = 'RUNNING', started_at = $at,
        recover_after_at = $recoverAfter, claimed_by = $owner, claim_token = $token
      from due where d.id = due.id returning""" ++ columns).query[Row].to[List]
      .flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
  } yield (recovered, claimed)

  override def complete(value: IntegrationConfigDeployment, token: UUID, at: Instant,
    status: String, errorCode: Option[String], errorMessage: Option[String]): ConnectionIO[Boolean] =
    sql"""update integration_config_deployment
      set status = $status, finished_at = $at, claimed_by = null, claim_token = null,
        error_code = $errorCode, error_message = $errorMessage
      where id = ${value.id} and organization_id = ${value.organizationId}
        and status = 'RUNNING' and claim_token = $token""".update.run.map(_ == 1)
}

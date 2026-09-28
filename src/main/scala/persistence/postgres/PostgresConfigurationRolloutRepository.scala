package ru.bitec.app.ops
package persistence.postgres

import application.port.{ConfigurationRolloutInsert, ConfigurationRolloutRepository}
import cats.data.NonEmptyList
import cats.syntax.all._
import domain.configuration._
import org.typelevel.doobie.{ConnectionIO, Fragment, Fragments, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresConfigurationRolloutRepository extends ConfigurationRolloutRepository[ConnectionIO] {
  import PostgresConfigurationRolloutRepository._

  override def insert(rollout: ConfigurationRollout,
                      items: List[ConfigurationRolloutItem]): ConnectionIO[ConfigurationRolloutInsert] =
    sql"""insert into configuration_rollout (
      id, organization_id, request_id, profile_id, profile_revision_number, state,
      canary_count, batch_size, pause_seconds, stop_on_failure, rollback_mode,
      created_by_user_id, created_at)
      values (${rollout.id}, ${rollout.organizationId}, ${rollout.requestId}, ${rollout.profileId},
        ${rollout.profileRevisionNumber}, 'QUEUED', ${rollout.strategy.canaryCount},
        ${rollout.strategy.batchSize}, ${rollout.strategy.pauseSeconds},
        ${rollout.strategy.stopOnFailure}, ${rollout.strategy.rollbackMode.code},
        ${rollout.actorUserId}, ${rollout.createdAt}) on conflict do nothing""".update.run.flatMap {
      case 1 => items.traverse_(insertItem).as(ConfigurationRolloutInsert.Written: ConfigurationRolloutInsert)
      case _ => sql"""select id from configuration_rollout where organization_id = ${rollout.organizationId}
        and request_id = ${rollout.requestId}""".query[UUID].option.map {
        case Some(id) => ConfigurationRolloutInsert.Repeated(id): ConfigurationRolloutInsert
        case None => throw new IllegalStateException("Rollout insert conflict without request match")
      }
    }

  private def insertItem(item: ConfigurationRolloutItem): ConnectionIO[Unit] = {
    val expected = item.expectedRemoteState match {
      case ExpectedRemoteState.Missing => (None: Option[String], true)
      case ExpectedRemoteState.Sha256(hash) => (Some(hash), false)
    }
    for {
      _ <- sql"""insert into configuration_rollout_item (
        id, organization_id, rollout_id, position, assignment_id, assignment_version,
        resource_id, target_path, connection_id, connection_updated_at, desired_sha256,
        expected_remote_sha256, remote_expected_missing, activation, unit_name,
        validator_executable, new_file_mode, state, created_at, updated_at)
        values (${item.id}, ${item.organizationId}, ${item.rolloutId}, ${item.position},
          ${item.assignmentId}, ${item.assignmentVersion}, ${item.resourceId}, ${item.targetPath},
          ${item.connectionId}, ${item.connectionUpdatedAt}, ${item.desiredSha256},
          ${expected._1}, ${expected._2}, ${item.policy.activation.code}, ${item.policy.unitName},
          ${item.policy.validator.map(_.executable)}, ${item.policy.newFileMode}, 'PENDING',
          ${item.createdAt}, ${item.updatedAt})""".update.run
      _ <- Update[(UUID, UUID, String, String)]("""insert into configuration_rollout_item_value
        (item_id, organization_id, name, value) values (?, ?, ?, ?)""")
        .updateMany(item.values.map(value => (item.id, item.organizationId, value.name, value.value)))
      _ <- Update[(UUID, UUID, Int, String)]("""insert into configuration_rollout_item_validator_arg
        (item_id, organization_id, position, argument) values (?, ?, ?, ?)""")
        .updateMany(item.policy.validator.toList.flatMap(_.args.zipWithIndex.map { case (arg, position) =>
          (item.id, item.organizationId, position, arg)
        }))
    } yield ()
  }

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationRollout]] =
    (select ++ fr"where organization_id = $organizationId and id = $id")
      .query[RolloutRow].option.map(_.map(_.toDomain))

  override def findRequest(organizationId: UUID, requestId: UUID): ConnectionIO[Option[ConfigurationRollout]] =
    (select ++ fr"where organization_id = $organizationId and request_id = $requestId")
      .query[RolloutRow].option.map(_.map(_.toDomain))

  override def history(organizationId: UUID, before: Option[(Instant, UUID)],
                       limit: Int): ConnectionIO[List[ConfigurationRollout]] = {
    val cursor = before.fold(fr"") { case (created, id) => fr"and (created_at, id) < ($created, $id)" }
    (select ++ fr"where organization_id = $organizationId" ++ cursor ++
      fr"order by created_at desc, id desc limit $limit").query[RolloutRow].to[List].map(_.map(_.toDomain))
  }

  override def items(organizationId: UUID, rolloutId: UUID): ConnectionIO[List[ConfigurationRolloutItem]] =
    (itemSelect ++ fr"where organization_id = $organizationId and rollout_id = $rolloutId order by position")
      .query[(ItemCoreRow, ItemStateRow)].to[List].flatMap { rows =>
        NonEmptyList.fromList(rows.map(_._1.id)) match {
          case None => List.empty[ConfigurationRolloutItem].pure[ConnectionIO]
          case Some(ids) =>
            val values = (fr"select item_id, name, value from configuration_rollout_item_value where organization_id = $organizationId and" ++
              Fragments.in(fr"item_id", ids)).query[(UUID, String, String)].to[List]
            val args = (fr"select item_id, argument from configuration_rollout_item_validator_arg where organization_id = $organizationId and" ++
              Fragments.in(fr"item_id", ids) ++ fr"order by item_id, position").query[(UUID, String)].to[List]
            (values, args).mapN { (allValues, allArgs) =>
              val groupedValues = allValues.groupMap(_._1)(row => ConfigurationVariableValue(row._2, row._3))
              val groupedArgs = allArgs.groupMap(_._1)(_._2)
              rows.map { case (core, state) => core.toDomain(state,
                groupedValues.getOrElse(core.id, List.empty), groupedArgs.getOrElse(core.id, List.empty)) }
            }
        }
      }

  override def claim(owner: UUID, token: UUID, now: Instant,
                     until: Instant): ConnectionIO[Option[ConfigurationRollout]] =
    sql"""with next as (
      select id from configuration_rollout where
        state = 'QUEUED' or
        (state in ('RUNNING','ROLLING_BACK') and (lease_expires_at is null or lease_expires_at <= $now)) or
        (state = 'PAUSED' and next_action_at <= $now and (lease_expires_at is null or lease_expires_at <= $now))
      order by created_at, id limit 1 for update skip locked
    ) update configuration_rollout r set
      state = case when r.state in ('QUEUED','PAUSED') then 'RUNNING' else r.state end,
      lease_owner = $owner, lease_token = $token, lease_expires_at = $until,
      started_at = coalesce(started_at, $now), next_action_at = null
      from next where r.id = next.id returning r.organization_id, r.id"""
      .query[(UUID, UUID)].option.flatMap(_.traverse { case (org, id) =>
        find(org, id).map(_.getOrElse(throw new IllegalStateException("Claimed rollout disappeared")))
      })

  override def renew(organizationId: UUID, id: UUID, token: UUID, now: Instant,
                     until: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_rollout set lease_expires_at = $until
      where organization_id = $organizationId and id = $id and lease_token = $token
        and state in ('RUNNING','ROLLING_BACK') and lease_expires_at > $now""".update.run.map(_ == 1)

  override def setItem(organizationId: UUID, rolloutId: UUID, token: UUID, itemId: UUID,
                       expected: ConfigurationRolloutItemState, next: ConfigurationRolloutItemState,
                       deploymentId: Option[UUID], now: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_rollout_item i set state = ${next.code},
        deployment_id = coalesce($deploymentId, i.deployment_id), updated_at = $now
      from configuration_rollout r
      where i.organization_id = $organizationId and i.rollout_id = $rolloutId and i.id = $itemId
        and i.state = ${expected.code} and r.id = i.rollout_id and r.organization_id = i.organization_id
        and r.lease_token = $token and r.lease_expires_at > $now""".update.run.map(_ == 1)

  override def setState(organizationId: UUID, id: UUID, token: UUID, state: ConfigurationRolloutState,
                        now: Instant, nextActionAt: Option[Instant], lastPausedPosition: Option[Int]): ConnectionIO[Boolean] =
    sql"""update configuration_rollout set state = ${state.code},
      finished_at = case when ${state.terminal} then $now else finished_at end,
      next_action_at = $nextActionAt,
      last_paused_position = coalesce($lastPausedPosition, last_paused_position),
      lease_owner = null, lease_token = null, lease_expires_at = null
      where organization_id = $organizationId and id = $id and lease_token = $token
        and lease_expires_at > $now and state in ('RUNNING','ROLLING_BACK')""".update.run.map(_ == 1)

  override def cancel(organizationId: UUID, id: UUID): ConnectionIO[Boolean] =
    sql"""update configuration_rollout set cancel_requested = true
      where organization_id = $organizationId and id = $id
        and state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')""".update.run.map(_ == 1)
}

object PostgresConfigurationRolloutRepository {
  private val select: Fragment = fr"""select id, organization_id, request_id, profile_id,
    profile_revision_number, state, canary_count, batch_size, pause_seconds, stop_on_failure,
    rollback_mode, lease_owner, lease_token, lease_expires_at, cancel_requested,
    created_by_user_id, created_at, started_at, finished_at, next_action_at,
    last_paused_position from configuration_rollout"""

  private final case class RolloutRow(
    id: UUID, organizationId: UUID, requestId: UUID, profileId: UUID, revision: Int,
    state: String, canaryCount: Int, batchSize: Int, pauseSeconds: Int, stopOnFailure: Boolean,
    rollbackMode: String, leaseOwner: Option[UUID], leaseToken: Option[UUID], leaseExpiresAt: Option[Instant],
    cancelRequested: Boolean, actorUserId: UUID, createdAt: Instant,
    startedAt: Option[Instant], finishedAt: Option[Instant], nextActionAt: Option[Instant],
    lastPausedPosition: Int
  ) {
    def toDomain: ConfigurationRollout = ConfigurationRollout(id, organizationId, requestId, profileId, revision,
      ConfigurationRolloutState.fromCode(state), ConfigurationRolloutStrategy(canaryCount, batchSize, pauseSeconds,
        stopOnFailure, ConfigurationRollbackMode.fromCode(rollbackMode)), leaseOwner, leaseToken,
      leaseExpiresAt, cancelRequested, actorUserId, createdAt, startedAt, finishedAt,
      nextActionAt, lastPausedPosition)
  }

  private val itemSelect: Fragment = fr"""select
    id, organization_id, rollout_id, position, assignment_id, assignment_version, resource_id,
    target_path, connection_id, connection_updated_at, desired_sha256,
    expected_remote_sha256, remote_expected_missing, activation, unit_name,
    validator_executable, new_file_mode, created_at,
    state, deployment_id, updated_at from configuration_rollout_item"""

  private final case class ItemCoreRow(
    id: UUID, organizationId: UUID, rolloutId: UUID, position: Int, assignmentId: UUID,
    assignmentVersion: Int, resourceId: UUID, targetPath: String, connectionId: UUID,
    connectionUpdatedAt: Instant, desiredSha256: String, expectedRemoteSha256: Option[String],
    remoteExpectedMissing: Boolean, activation: String, unitName: Option[String],
    validatorExecutable: Option[String], newFileMode: Int, createdAt: Instant
  ) {
    def toDomain(state: ItemStateRow, values: List[ConfigurationVariableValue], args: List[String]): ConfigurationRolloutItem =
      ConfigurationRolloutItem(id, organizationId, rolloutId, position, assignmentId, assignmentVersion,
        resourceId, targetPath, connectionId, connectionUpdatedAt, desiredSha256,
        expectedRemoteSha256.fold[ExpectedRemoteState](ExpectedRemoteState.Missing)(ExpectedRemoteState.Sha256.apply),
        ConfigurationExecutionPolicy(ConfigurationActivation.fromCode(activation).fold(throw _, identity),
          unitName, validatorExecutable.map(ConfigurationValidator(_, args)), newFileMode),
        values, ConfigurationRolloutItemState.fromCode(state.state), state.deploymentId, createdAt, state.updatedAt)
  }

  private final case class ItemStateRow(state: String, deploymentId: Option[UUID], updatedAt: Instant)
}

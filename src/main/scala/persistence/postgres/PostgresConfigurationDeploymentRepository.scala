package ru.bitec.app.ops
package persistence.postgres

import application.port.{ConfigurationDeploymentInsert, ConfigurationDeploymentRepository}
import cats.syntax.all._
import cats.data.NonEmptyList
import domain.configuration._
import org.typelevel.doobie.{ConnectionIO, Fragment, Fragments, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresConfigurationDeploymentRepository extends ConfigurationDeploymentRepository[ConnectionIO] {
  import PostgresConfigurationDeploymentRepository._

  override def insert(deployment: ConfigurationDeployment): ConnectionIO[ConfigurationDeploymentInsert] = {
    val expected = deployment.expectedRemoteState match {
      case ExpectedRemoteState.Missing => (None: Option[String], true)
      case ExpectedRemoteState.Sha256(hash) => (Some(hash), false)
    }
    val validator = deployment.policy.validator
    sql"""insert into configuration_deployment (
        id, organization_id, request_id, assignment_id, assignment_version, resource_id,
        profile_id, profile_revision_number, target_path, connection_id, connection_updated_at,
        desired_sha256, expected_remote_sha256, remote_expected_missing, activation, unit_name,
        validator_executable, new_file_mode, state, phase, created_by_user_id, created_at,
        rollout_id, rollout_item_id)
      values (${deployment.id}, ${deployment.organizationId}, ${deployment.requestId},
        ${deployment.assignmentId}, ${deployment.assignmentVersion}, ${deployment.resourceId},
        ${deployment.profileId}, ${deployment.profileRevisionNumber}, ${deployment.targetPath},
        ${deployment.connectionId}, ${deployment.connectionUpdatedAt}, ${deployment.desiredSha256},
        ${expected._1}, ${expected._2}, ${deployment.policy.activation.code}, ${deployment.policy.unitName},
        ${validator.map(_.executable)}, ${deployment.policy.newFileMode}, 'QUEUED', 'PRECHECK',
        ${deployment.actorUserId}, ${deployment.createdAt}, ${deployment.rolloutId}, ${deployment.rolloutItemId})
      on conflict do nothing""".update.run.flatMap {
      case 1 =>
        for {
          _ <- Update[(UUID, UUID, String, String)]("""insert into configuration_deployment_value
            (deployment_id, organization_id, name, value) values (?, ?, ?, ?)""")
            .updateMany(deployment.values.map(v => (deployment.id, deployment.organizationId, v.name, v.value)))
          _ <- Update[(UUID, UUID, Int, String)]("""insert into configuration_deployment_validator_arg
            (deployment_id, organization_id, position, argument) values (?, ?, ?, ?)""")
            .updateMany(validator.toList.flatMap(_.args.zipWithIndex.map { case (arg, position) =>
              (deployment.id, deployment.organizationId, position, arg)
            }))
          _ <- event(deployment.organizationId, deployment.id, "QUEUED", deployment.createdAt)
        } yield ConfigurationDeploymentInsert.Written: ConfigurationDeploymentInsert
      case _ =>
        sql"""select id from configuration_deployment
               where organization_id = ${deployment.organizationId} and request_id = ${deployment.requestId}"""
          .query[UUID].option.map {
            case Some(id) => ConfigurationDeploymentInsert.Repeated(id): ConfigurationDeploymentInsert
            case None => ConfigurationDeploymentInsert.AlreadyActive: ConfigurationDeploymentInsert
          }
    }
  }

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationDeployment]] =
    (select ++ fr"where d.organization_id = $organizationId and d.id = $id")
      .query[(CoreRow, StateRow)].option.flatMap(_.traverse { case (core, state) => hydrate(core, state) })

  override def findRequest(organizationId: UUID, requestId: UUID): ConnectionIO[Option[ConfigurationDeployment]] =
    (select ++ fr"where d.organization_id = $organizationId and d.request_id = $requestId")
      .query[(CoreRow, StateRow)].option.flatMap(_.traverse { case (core, state) => hydrate(core, state) })

  override def claim(owner: UUID, token: UUID, now: Instant, until: Instant,
                     limit: Int): ConnectionIO[List[ConfigurationDeployment]] =
    sql"""with next as (
        select id from configuration_deployment
        where state = 'QUEUED' or (state = 'RUNNING' and lease_expires_at <= $now)
        order by created_at, id limit $limit for update skip locked
      ) update configuration_deployment d
        set state = 'RUNNING', lease_owner = $owner, lease_token = $token,
            lease_expires_at = $until, started_at = coalesce(started_at, $now)
      from next where d.id = next.id returning d.organization_id, d.id"""
      .query[(UUID, UUID)].to[List].flatMap(_.traverse { case (org, id) =>
        event(org, id, "CLAIMED", now) *> find(org, id).map(_.getOrElse(
          throw new IllegalStateException("Claimed deployment disappeared")))
      })

  override def renew(organizationId: UUID, id: UUID, token: UUID, now: Instant,
                     until: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_deployment set lease_expires_at = $until
      where organization_id = $organizationId and id = $id and lease_token = $token
        and state = 'RUNNING' and lease_expires_at > $now""".update.run.map(_ == 1)

  override def advance(organizationId: UUID, id: UUID, token: UUID,
                       expected: ConfigurationDeploymentPhase, next: ConfigurationDeploymentPhase,
                       now: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_deployment set phase = ${next.code}
      where organization_id = $organizationId and id = $id and lease_token = $token
        and state = 'RUNNING' and phase = ${expected.code} and lease_expires_at > $now"""
      .update.run.flatMap {
        case 1 => event(organizationId, id, eventFor(next), now).as(true)
        case _ => false.pure[ConnectionIO]
      }

  override def finish(organizationId: UUID, id: UUID, token: UUID,
                      state: ConfigurationDeploymentState, failureCode: Option[String],
                      now: Instant): ConnectionIO[Boolean] =
    if (!state.terminal) false.pure[ConnectionIO]
    else sql"""update configuration_deployment
        set state = ${state.code}, failure_code = $failureCode, finished_at = $now,
            lease_owner = null, lease_token = null, lease_expires_at = null
        where organization_id = $organizationId and id = $id and lease_token = $token
          and state = 'RUNNING' and lease_expires_at > $now""".update.run.flatMap {
      case 1 => event(organizationId, id,
        if (state == ConfigurationDeploymentState.RolledBack) "ROLLBACK_SUCCEEDED" else state.code, now).as(true)
      case _ => false.pure[ConnectionIO]
    }

  override def cancel(organizationId: UUID, id: UUID, now: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_deployment
      set state = case when state = 'QUEUED' then 'CANCELLED' else state end,
          finished_at = case when state = 'QUEUED' then $now else finished_at end,
          cancel_requested = true
      where organization_id = $organizationId and id = $id and state in ('QUEUED','RUNNING')
      returning state""".query[String].option.flatMap {
        case Some("CANCELLED") => event(organizationId, id, "CANCELLED", now).as(true)
        case Some(_) => true.pure[ConnectionIO]
        case None => false.pure[ConnectionIO]
      }

  override def queueRollback(organizationId: UUID, id: UUID, rolloutId: UUID,
                             now: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_deployment set state = 'QUEUED', phase = 'ROLLBACK',
      finished_at = null, failure_code = null, cancel_requested = false
      where organization_id = $organizationId and id = $id and rollout_id = $rolloutId
        and state = 'SUCCEEDED'""".update.run.attemptSql.map {
      case Right(1) => true
      case Right(_) => false
      case Left(error: org.postgresql.util.PSQLException)
        if error.getSQLState == "23505" => false
      case Left(error) => throw error
    }

  override def history(organizationId: UUID, profileId: Option[UUID], before: Option[(Instant, UUID)],
                       limit: Int): ConnectionIO[List[ConfigurationDeployment]] = {
    val profile = profileId.fold(fr"")(id => fr"and d.profile_id = $id")
    val cursor = before.fold(fr"") { case (createdAt, id) => fr"and (d.created_at, d.id) < ($createdAt, $id)" }
    (select ++ fr"where d.organization_id = $organizationId" ++ profile ++ cursor ++
      fr"order by d.created_at desc, d.id desc limit $limit")
      .query[(CoreRow, StateRow)].to[List].flatMap { rows =>
        // A single batched value/argument read for a whole history page, never one query per row.
        val ids = rows.map(_._1.id)
        if (ids.isEmpty) List.empty[ConfigurationDeployment].pure[ConnectionIO]
        else {
          val nonEmptyIds = NonEmptyList.fromListUnsafe(ids)
          val values =
            (fr"select deployment_id, name, value from configuration_deployment_value where organization_id = $organizationId and" ++
              Fragments.in(fr"deployment_id", nonEmptyIds))
              .query[(UUID, String, String)].to[List]
          val args =
            (fr"select deployment_id, argument from configuration_deployment_validator_arg where organization_id = $organizationId and" ++
              Fragments.in(fr"deployment_id", nonEmptyIds) ++ fr"order by deployment_id, position")
              .query[(UUID, String)].to[List]
          (values, args).mapN { (allValues, allArgs) =>
            val groupedValues = allValues.groupMap(_._1)(row => ConfigurationVariableValue(row._2, row._3))
            val groupedArgs = allArgs.groupMap(_._1)(_._2)
            rows.map { case (core, state) => core.toDomain(state,
              groupedValues.getOrElse(core.id, List.empty), groupedArgs.getOrElse(core.id, List.empty)) }
          }
        }
      }
  }

  private def hydrate(core: CoreRow, state: StateRow): ConnectionIO[ConfigurationDeployment] =
    (sql"""select name, value from configuration_deployment_value
      where organization_id = ${core.organizationId} and deployment_id = ${core.id} order by name"""
      .query[(String, String)].to[List],
      sql"""select argument from configuration_deployment_validator_arg
        where organization_id = ${core.organizationId} and deployment_id = ${core.id} order by position"""
        .query[String].to[List]).mapN { (values, args) =>
      core.toDomain(state, values.map { case (name, value) => ConfigurationVariableValue(name, value) }, args)
    }

  private def event(org: UUID, id: UUID, kind: String, at: Instant): ConnectionIO[Unit] =
    sql"""insert into configuration_deployment_event(deployment_id, organization_id, sequence, event_type, occurred_at)
      select $id, $org, coalesce(max(sequence), 0) + 1, $kind, $at
      from configuration_deployment_event where deployment_id = $id""".update.run.void

  private def eventFor(phase: ConfigurationDeploymentPhase): String = phase match {
    case ConfigurationDeploymentPhase.Upload => "REMOTE_PRECHECK_OK"
    case ConfigurationDeploymentPhase.Validate => "UPLOADED"
    case ConfigurationDeploymentPhase.Replace => "VALIDATED"
    case ConfigurationDeploymentPhase.Activate => "REPLACED"
    case ConfigurationDeploymentPhase.Verify => "ACTIVATED"
    case ConfigurationDeploymentPhase.Cleanup => "VERIFIED"
    case ConfigurationDeploymentPhase.Precheck => "CLAIMED"
    case ConfigurationDeploymentPhase.Rollback => "ROLLBACK_STARTED"
  }
}

object PostgresConfigurationDeploymentRepository {
  private val select: Fragment = fr"""select
    d.id, d.organization_id, d.request_id, d.assignment_id, d.assignment_version,
    d.resource_id, d.profile_id, d.profile_revision_number, d.target_path,
    d.connection_id, d.connection_updated_at, d.desired_sha256,
    d.expected_remote_sha256, d.remote_expected_missing, d.activation, d.unit_name,
    d.validator_executable, d.new_file_mode, d.created_by_user_id, d.created_at,
    d.rollout_id, d.rollout_item_id,
    d.state, d.phase, d.lease_owner, d.lease_token, d.lease_expires_at,
    d.started_at, d.finished_at, d.failure_code, d.cancel_requested
    from configuration_deployment d"""

  private final case class CoreRow(
    id: UUID, organizationId: UUID, requestId: UUID, assignmentId: UUID, assignmentVersion: Int,
    resourceId: UUID, profileId: UUID, profileRevisionNumber: Int, targetPath: String,
    connectionId: UUID, connectionUpdatedAt: Instant, desiredSha256: String,
    expectedRemoteSha256: Option[String], remoteExpectedMissing: Boolean,
    activation: String, unitName: Option[String], validatorExecutable: Option[String], newFileMode: Int,
    actorUserId: UUID, createdAt: Instant, rolloutId: Option[UUID], rolloutItemId: Option[UUID]
  ) {
    def toDomain(state: StateRow, values: List[ConfigurationVariableValue], args: List[String]): ConfigurationDeployment =
      ConfigurationDeployment(id, organizationId, requestId, assignmentId, assignmentVersion,
        resourceId, profileId, profileRevisionNumber, targetPath, values, desiredSha256,
        connectionId, connectionUpdatedAt,
        expectedRemoteSha256.fold[ExpectedRemoteState](ExpectedRemoteState.Missing)(ExpectedRemoteState.Sha256.apply),
        ConfigurationExecutionPolicy(ConfigurationActivation.fromCode(activation).fold(throw _, identity),
          unitName, validatorExecutable.map(ConfigurationValidator(_, args)), newFileMode),
        actorUserId, createdAt,
        ConfigurationDeploymentState.fromCode(state.state).fold(throw _, identity),
        ConfigurationDeploymentPhase.fromCode(state.phase).fold(throw _, identity),
        state.leaseOwner, state.leaseToken, state.leaseExpiresAt, state.startedAt, state.finishedAt,
        state.failureCode, state.cancelRequested, rolloutId, rolloutItemId)
  }

  private final case class StateRow(
    state: String, phase: String, leaseOwner: Option[UUID], leaseToken: Option[UUID],
    leaseExpiresAt: Option[Instant], startedAt: Option[Instant], finishedAt: Option[Instant],
    failureCode: Option[String], cancelRequested: Boolean
  )
}

package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  ConfigurationBackupCleanup,
  ConfigurationDeploymentEvent,
  ConfigurationDeploymentInsert,
  ConfigurationDeploymentListItem,
  ConfigurationDeploymentRepository,
  ConfigurationDeploymentSummary
}
import cats.data.NonEmptyList
import cats.syntax.all._
import domain.configuration._
import org.typelevel.doobie.{ConnectionIO, FC, Fragment, Fragments, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Deployment snapshots and their lifecycle. Every worker-side update is fenced on the lease token,
  * the expected state and a live lease; the database, not this process, decides who owns a row.
  */
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
        rollout_id, rollout_item_id, retry_of_deployment_id)
      values (${deployment.id}, ${deployment.organizationId}, ${deployment.requestId},
        ${deployment.assignmentId}, ${deployment.assignmentVersion}, ${deployment.resourceId},
        ${deployment.profileId}, ${deployment.profileRevisionNumber}, ${deployment.targetPath},
        ${deployment.connectionId}, ${deployment.connectionUpdatedAt}, ${deployment.desiredSha256},
        ${expected._1}, ${expected._2}, ${deployment.policy.activation.code}, ${deployment.policy.unitName},
        ${validator.map(_.executable)}, ${deployment.policy.newFileMode}, 'QUEUED', 'PRECHECK',
        ${deployment.actorUserId}, ${deployment.createdAt}, ${deployment.rolloutId}, ${deployment.rolloutItemId},
        ${deployment.retryOfDeploymentId})
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
        // Nothing was inserted: either this request was already accepted, or the resource already
        // has an active deployment (the partial unique index).
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

  override def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int,
                     perOrganization: Int, scope: Option[UUID]): ConnectionIO[List[ConfigurationDeployment]] =
    // Candidates are ranked per organization against what that organization already runs, then
    // locked with SKIP LOCKED; the lock query re-checks claimability, so a row another worker took
    // in between is never taken twice.
    sql"""with running as (
        select organization_id, count(*) as busy from configuration_deployment
        where state = 'RUNNING' and lease_expires_at > $now group by organization_id
      ), ranked as (
        select d.id, row_number() over (partition by d.organization_id order by d.created_at, d.id) as rank,
               coalesce(r.busy, 0) as busy
        from configuration_deployment d left join running r on r.organization_id = d.organization_id
        where (d.state = 'QUEUED' or (d.state = 'RUNNING' and d.lease_expires_at <= $now))
          and ($scope::uuid is null or d.organization_id = $scope)
      ), eligible as (
        select id from ranked where busy + rank <= $perOrganization
      ), next as (
        select d.id from configuration_deployment d join eligible e on e.id = d.id
        where d.state = 'QUEUED' or (d.state = 'RUNNING' and d.lease_expires_at <= $now)
        order by d.created_at, d.id limit $limit for update of d skip locked
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
    sql"""update configuration_deployment set phase = ${next.code}, transient_attempts = 0
      where organization_id = $organizationId and id = $id and lease_token = $token
        and state = 'RUNNING' and phase = ${expected.code} and lease_expires_at > $now"""
      .update.run.flatMap {
        case 1 => event(organizationId, id, eventFor(next), now).as(true)
        case _ => false.pure[ConnectionIO]
      }

  override def enterRollback(organizationId: UUID, id: UUID, token: UUID, expected: ConfigurationDeploymentPhase,
                             failureCode: String, now: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_deployment
      set phase = 'ROLLBACK', rollback_from_phase = ${expected.code}, failure_code = $failureCode,
          transient_attempts = 0
      where organization_id = $organizationId and id = $id and lease_token = $token
        and state = 'RUNNING' and phase = ${expected.code} and phase <> 'ROLLBACK'
        and lease_expires_at > $now""".update.run.flatMap {
      case 1 => event(organizationId, id, "ROLLBACK_STARTED", now).as(true)
      case _ => false.pure[ConnectionIO]
    }

  override def postpone(organizationId: UUID, id: UUID, token: UUID, now: Instant,
                        until: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_deployment
      set lease_expires_at = $until, transient_attempts = transient_attempts + 1
      where organization_id = $organizationId and id = $id and lease_token = $token
        and state = 'RUNNING' and lease_expires_at > $now""".update.run.flatMap {
      case 1 => event(organizationId, id, "RETRY_SCHEDULED", now).as(true)
      case _ => false.pure[ConnectionIO]
    }

  override def release(organizationId: UUID, id: UUID, token: UUID, now: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_deployment set lease_expires_at = $now
      where organization_id = $organizationId and id = $id and lease_token = $token
        and state = 'RUNNING' and lease_expires_at > $now""".update.run.flatMap {
      case 1 => event(organizationId, id, "RELEASED", now).as(true)
      case _ => false.pure[ConnectionIO]
    }

  override def finish(organizationId: UUID, id: UUID, token: UUID,
                      state: ConfigurationDeploymentState, failureCode: Option[String],
                      backupRetained: Boolean, now: Instant): ConnectionIO[Boolean] =
    if (!state.terminal) false.pure[ConnectionIO]
    else sql"""update configuration_deployment
        set state = ${state.code}, failure_code = coalesce($failureCode, failure_code), finished_at = $now,
            lease_owner = null, lease_token = null, lease_expires_at = null,
            backup_retained = $backupRetained, backup_cleanup_attempts = 0, backup_cleanup_not_before = null
        where organization_id = $organizationId and id = $id and lease_token = $token
          and state = 'RUNNING' and lease_expires_at > $now""".update.run.flatMap {
      case 1 => event(organizationId, id,
        if (state == ConfigurationDeploymentState.RolledBack) "ROLLBACK_SUCCEEDED" else state.code, now) *>
        (if (backupRetained) event(organizationId, id, "BACKUP_RETAINED", now) else ().pure[ConnectionIO]).as(true)
      case _ => false.pure[ConnectionIO]
    }

  override def cancel(organizationId: UUID, id: UUID, now: Instant): ConnectionIO[Boolean] =
    sql"""update configuration_deployment
      set state = case when state = 'QUEUED' and phase = 'PRECHECK' then 'CANCELLED' else state end,
          finished_at = case when state = 'QUEUED' and phase = 'PRECHECK' then $now else finished_at end,
          cancel_requested = true
      where organization_id = $organizationId and id = $id and state in ('QUEUED','RUNNING')
      returning state""".query[String].option.flatMap {
        case Some("CANCELLED") => event(organizationId, id, "CANCELLED", now).as(true)
        case Some(_) => event(organizationId, id, "CANCEL_REQUESTED", now).as(true)
        case None => false.pure[ConnectionIO]
      }

  override def queueRollback(organizationId: UUID, id: UUID, rolloutId: UUID,
                             now: Instant): ConnectionIO[Boolean] =
    // A savepoint keeps the surrounding transaction usable when the resource is busy right now:
    // the partial unique index then refuses the row, and the rollout simply tries again later.
    savepoint(sql"""update configuration_deployment set state = 'QUEUED', phase = 'ROLLBACK',
      rollback_from_phase = 'CLEANUP', failure_code = 'CONFIGURATION_ROLLOUT_ROLLBACK',
      finished_at = null, cancel_requested = false, transient_attempts = 0
      where organization_id = $organizationId and id = $id and rollout_id = $rolloutId
        and state = 'SUCCEEDED' and (backup_retained or remote_expected_missing)""".update.run).flatMap {
      case Right(1) => event(organizationId, id, "ROLLBACK_STARTED", now).as(true)
      case Right(_) => false.pure[ConnectionIO]
      case Left(error) if error.getSQLState == "23505" => false.pure[ConnectionIO]
      case Left(error) => error.raiseError[ConnectionIO, Boolean]
    }

  override def view(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationDeploymentListItem]] =
    (listSelect ++ fr"where d.organization_id = $organizationId and d.id = $id")
      .query[(CoreRow, StateRow, Names)].to[List].flatMap(listItems(organizationId, _)).map(_.headOption)

  override def history(organizationId: UUID, profileId: Option[UUID], resourceId: Option[UUID],
                       before: Option[(Instant, UUID)], limit: Int): ConnectionIO[List[ConfigurationDeploymentListItem]] = {
    val profile = profileId.fold(fr"")(id => fr"and d.profile_id = $id")
    val resource = resourceId.fold(fr"")(id => fr"and d.resource_id = $id")
    val cursor = before.fold(fr"") { case (createdAt, id) => fr"and (d.created_at, d.id) < ($createdAt, $id)" }
    (listSelect ++ fr"where d.organization_id = $organizationId" ++ profile ++ resource ++ cursor ++
      fr"order by d.created_at desc, d.id desc limit $limit")
      .query[(CoreRow, StateRow, Names)].to[List].flatMap(listItems(organizationId, _))
  }

  private def listItems(organizationId: UUID,
                        rows: List[(CoreRow, StateRow, Names)]): ConnectionIO[List[ConfigurationDeploymentListItem]] =
    hydrateAll(organizationId, rows.map { case (core, state, _) => core -> state }).map(_.zip(rows).map {
      case (deployment, (_, _, names)) =>
        ConfigurationDeploymentListItem(deployment, names.resource, names.connection, names.actor)
    })

  override def events(organizationId: UUID, id: UUID): ConnectionIO[List[ConfigurationDeploymentEvent]] =
    sql"""select sequence, event_type, occurred_at from configuration_deployment_event
      where organization_id = $organizationId and deployment_id = $id order by sequence limit 500"""
      .query[ConfigurationDeploymentEvent].to[List]

  override def summaries(organizationId: UUID,
                         assignmentIds: List[UUID]): ConnectionIO[List[ConfigurationDeploymentSummary]] =
    NonEmptyList.fromList(assignmentIds.distinct) match {
      case None => List.empty[ConfigurationDeploymentSummary].pure[ConnectionIO]
      case Some(ids) =>
        // One statement for a whole page of assignments: the latest success and the active one.
        (fr"""select a.id, s.id, s.profile_revision_number, s.desired_sha256, s.finished_at,
                     x.id, x.state, x.phase
              from unnest(${ids.toList.toArray[UUID]}) as a(id)
              left join lateral (
                select d.id, d.profile_revision_number, d.desired_sha256, d.finished_at
                from configuration_deployment d
                where d.organization_id = $organizationId and d.assignment_id = a.id and d.state = 'SUCCEEDED'
                order by d.finished_at desc, d.id desc limit 1) s on true
              left join lateral (
                select d.id, d.state, d.phase from configuration_deployment d
                where d.organization_id = $organizationId and d.assignment_id = a.id
                  and d.state in ('QUEUED','RUNNING')
                order by d.created_at desc limit 1) x on true""")
          .query[(UUID, Option[UUID], Option[Int], Option[String], Option[Instant],
            Option[UUID], Option[String], Option[String])].to[List].map(_.map {
            case (assignment, successId, revision, sha, finished, activeId, activeState, activePhase) =>
              ConfigurationDeploymentSummary(assignment,
                (successId, revision, sha, finished).tupled,
                (activeId, activeState, activePhase).tupled.map { case (activeDeployment, state, phase) =>
                  (activeDeployment, ConfigurationDeploymentState.fromCode(state).fold(throw _, identity),
                    ConfigurationDeploymentPhase.fromCode(phase).fold(throw _, identity))
                })
          })
    }

  override def claimBackupCleanup(now: Instant, until: Instant, limit: Int,
                                  scope: Option[UUID]): ConnectionIO[List[ConfigurationBackupCleanup]] =
    // Backups outlive their deployment only while their rollout may still need them.
    sql"""with next as (
        select d.id from configuration_deployment d
        join configuration_rollout r on r.id = d.rollout_id and r.organization_id = d.organization_id
        where d.backup_retained and d.state in ('SUCCEEDED','FAILED','ROLLED_BACK','ROLLBACK_FAILED','CANCELLED')
          and r.state in ('SUCCEEDED','FAILED','ROLLED_BACK','CANCELLED')
          and (d.backup_cleanup_not_before is null or d.backup_cleanup_not_before <= $now)
          and ($scope::uuid is null or d.organization_id = $scope)
        order by d.finished_at, d.id limit $limit for update of d skip locked
      ) update configuration_deployment d set backup_cleanup_not_before = $until
      from next where d.id = next.id
      returning d.organization_id, d.id, d.resource_id, d.target_path, d.connection_id,
        d.connection_updated_at, d.backup_cleanup_attempts"""
      .query[ConfigurationBackupCleanup].to[List]

  override def finishBackupCleanup(organizationId: UUID, id: UUID, removed: Boolean, maxAttempts: Int,
                                   retryAt: Instant, now: Instant): ConnectionIO[Unit] =
    if (removed)
      sql"""update configuration_deployment set backup_retained = false, backup_cleanup_not_before = null
        where organization_id = $organizationId and id = $id and backup_retained""".update.run.flatMap {
        case 1 => event(organizationId, id, "BACKUP_CLEANED", now)
        case _ => ().pure[ConnectionIO]
      }
    else
      // A cleanup that keeps failing is given up with a durable warning; the applied file stays.
      sql"""update configuration_deployment
        set backup_cleanup_attempts = backup_cleanup_attempts + 1,
            backup_retained = backup_cleanup_attempts + 1 < $maxAttempts,
            backup_cleanup_not_before = $retryAt
        where organization_id = $organizationId and id = $id and backup_retained
        returning backup_retained""".query[Boolean].option.flatMap {
        case Some(false) => event(organizationId, id, "BACKUP_CLEANUP_FAILED", now)
        case _ => ().pure[ConnectionIO]
      }

  private def savepoint[A](program: ConnectionIO[A]): ConnectionIO[Either[java.sql.SQLException, A]] =
    for {
      point <- FC.setSavepoint
      result <- program.attemptSql
      _ <- result.fold(_ => FC.rollback(point), _ => FC.releaseSavepoint(point))
    } yield result

  private def hydrate(core: CoreRow, state: StateRow): ConnectionIO[ConfigurationDeployment] =
    hydrateAll(core.organizationId, List(core -> state)).map(_.head)

  /** Values and validator arguments for a whole page in two statements, never one per row. */
  private def hydrateAll(organizationId: UUID,
                         rows: List[(CoreRow, StateRow)]): ConnectionIO[List[ConfigurationDeployment]] =
    NonEmptyList.fromList(rows.map(_._1.id)) match {
      case None => List.empty[ConfigurationDeployment].pure[ConnectionIO]
      case Some(ids) =>
        val values =
          (fr"select deployment_id, name, value from configuration_deployment_value where organization_id = $organizationId and" ++
            Fragments.in(fr"deployment_id", ids) ++ fr"order by deployment_id, name")
            .query[(UUID, String, String)].to[List]
        val args =
          (fr"select deployment_id, argument from configuration_deployment_validator_arg where organization_id = $organizationId and" ++
            Fragments.in(fr"deployment_id", ids) ++ fr"order by deployment_id, position")
            .query[(UUID, String)].to[List]
        (values, args).mapN { (allValues, allArgs) =>
          val groupedValues = allValues.groupMap(_._1)(row => ConfigurationVariableValue(row._2, row._3))
          val groupedArgs = allArgs.groupMap(_._1)(_._2)
          rows.map { case (core, state) => core.toDomain(state,
            groupedValues.getOrElse(core.id, List.empty), groupedArgs.getOrElse(core.id, List.empty)) }
        }
    }

  private def event(org: UUID, id: UUID, kind: String, at: Instant): ConnectionIO[Unit] =
    // The deployment row is locked by the statement that led here, so sequences never collide.
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
    d.rollout_id, d.rollout_item_id, d.retry_of_deployment_id,
    d.state, d.phase, d.lease_owner, d.lease_token, d.lease_expires_at,
    d.started_at, d.finished_at, d.failure_code, d.cancel_requested,
    d.rollback_from_phase, d.transient_attempts, d.backup_retained
    from configuration_deployment d"""

  private final case class Names(resource: String, connection: String, actor: String)

  /** The same columns plus names; tenant-scoped joins, so a row never shows another tenant's name. */
  private val listSelect: Fragment = fr"""select
    d.id, d.organization_id, d.request_id, d.assignment_id, d.assignment_version,
    d.resource_id, d.profile_id, d.profile_revision_number, d.target_path,
    d.connection_id, d.connection_updated_at, d.desired_sha256,
    d.expected_remote_sha256, d.remote_expected_missing, d.activation, d.unit_name,
    d.validator_executable, d.new_file_mode, d.created_by_user_id, d.created_at,
    d.rollout_id, d.rollout_item_id, d.retry_of_deployment_id,
    d.state, d.phase, d.lease_owner, d.lease_token, d.lease_expires_at,
    d.started_at, d.finished_at, d.failure_code, d.cancel_requested,
    d.rollback_from_phase, d.transient_attempts, d.backup_retained,
    r.name, c.name, u.display_name
    from configuration_deployment d
    join resource r on r.id = d.resource_id and r.organization_id = d.organization_id
    join connection c on c.id = d.connection_id and c.organization_id = d.organization_id
    join user_account u on u.id = d.created_by_user_id"""

  private final case class CoreRow(
    id: UUID, organizationId: UUID, requestId: UUID, assignmentId: UUID, assignmentVersion: Int,
    resourceId: UUID, profileId: UUID, profileRevisionNumber: Int, targetPath: String,
    connectionId: UUID, connectionUpdatedAt: Instant, desiredSha256: String,
    expectedRemoteSha256: Option[String], remoteExpectedMissing: Boolean,
    activation: String, unitName: Option[String], validatorExecutable: Option[String], newFileMode: Int,
    actorUserId: UUID, createdAt: Instant, rolloutId: Option[UUID], rolloutItemId: Option[UUID],
    retryOfDeploymentId: Option[UUID]
  ) {
    def toDomain(state: StateRow, values: List[ConfigurationVariableValue], args: List[String]): ConfigurationDeployment =
      ConfigurationDeployment(id, organizationId, requestId, assignmentId, assignmentVersion,
        resourceId, profileId, profileRevisionNumber, targetPath, values, desiredSha256,
        connectionId, connectionUpdatedAt, ExpectedRemoteState.of(expectedRemoteSha256),
        ConfigurationExecutionPolicy(ConfigurationActivation.fromCode(activation).fold(throw _, identity),
          unitName, validatorExecutable.map(ConfigurationValidator(_, args)), newFileMode),
        actorUserId, createdAt,
        ConfigurationDeploymentState.fromCode(state.state).fold(throw _, identity),
        ConfigurationDeploymentPhase.fromCode(state.phase).fold(throw _, identity),
        state.leaseOwner, state.leaseToken, state.leaseExpiresAt, state.startedAt, state.finishedAt,
        state.failureCode, state.cancelRequested, rolloutId, rolloutItemId, retryOfDeploymentId,
        state.rollbackFromPhase.map(code => ConfigurationDeploymentPhase.fromCode(code).fold(throw _, identity)),
        state.transientAttempts, state.backupRetained)
  }

  private final case class StateRow(
    state: String, phase: String, leaseOwner: Option[UUID], leaseToken: Option[UUID],
    leaseExpiresAt: Option[Instant], startedAt: Option[Instant], finishedAt: Option[Instant],
    failureCode: Option[String], cancelRequested: Boolean, rollbackFromPhase: Option[String],
    transientAttempts: Int, backupRetained: Boolean
  )
}

package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.data.NonEmptyList
import cats.syntax.all._
import domain.configuration._
import org.typelevel.doobie.{ConnectionIO, Fragment, Fragments, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import PostgresConfigurationAssignmentRepository.{AssignmentRow, assignmentColumns}

import java.time.Instant
import java.util.UUID

/** Rules, their normalized selectors and their reconciliation state.
  *
  * A selector is evaluated in SQL with parameters only: labels are compared with EXISTS, never
  * interpolated, so no selector can inject SQL and a page of resources costs one statement.
  * Every reconciliation write is fenced on the lease token and the exact rule version.
  */
final class PostgresConfigurationAssignmentRuleRepository
  extends ConfigurationAssignmentRuleRepository[ConnectionIO] with ConfigurationRuleAssignmentRepository[ConnectionIO] {
  import PostgresConfigurationAssignmentRuleRepository._

  override def insert(rule: ConfigurationAssignmentRule): ConnectionIO[Boolean] =
    sql"""insert into configuration_assignment_rule (id, organization_id, code, name, description, profile_id,
            profile_revision_number, target_path, enabled, archived, version, created_by_user_id, created_at,
            updated_at, next_reconcile_at)
          values (${rule.id}, ${rule.organizationId}, ${rule.code}, ${rule.name}, ${rule.description}, ${rule.profileId},
            ${rule.profileRevisionNumber}, ${rule.targetPath}, ${rule.enabled}, false, ${rule.version}, ${rule.createdBy},
            ${rule.createdAt}, ${rule.updatedAt}, ${rule.nextReconcileAt})
          on conflict (organization_id, code) do nothing""".update.run.flatMap {
      case 1 => writeSelector(rule.organizationId, rule.id, rule.selector).as(true)
      case _ => false.pure[ConnectionIO]
    }

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationAssignmentRule]] =
    (ruleSelect ++ fr"where ru.organization_id = $organizationId and ru.id = $id").query[RuleRow].option
      .flatMap(_.traverse(row => selectors(organizationId, List(row.id)).map(all => row.toDomain(all.getOrElse(row.id, EmptySelector)))))

  override def view(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationAssignmentRuleListItem]] =
    listWhere(organizationId, fr"and ru.id = $id", 1).map(_.headOption)

  override def validReferences(organizationId: UUID, projects: List[UUID], environments: List[UUID]): ConnectionIO[Boolean] =
    sql"""select (select count(*) from project where organization_id = $organizationId and id = any(${projects.toArray[UUID]}))
               + (select count(*) from environment where organization_id = $organizationId and id = any(${environments.toArray[UUID]}))"""
      .query[Long].unique.map(_ == (projects.distinct.size + environments.distinct.size).toLong)

  override def resourceExists(organizationId: UUID, resourceId: UUID): ConnectionIO[Boolean] =
    sql"select exists (select 1 from resource where organization_id = $organizationId and id = $resourceId)".query[Boolean].unique

  override def list(organizationId: UUID, profileId: Option[UUID], includeArchived: Boolean,
                    after: Option[(Instant, UUID)], limit: Int): ConnectionIO[List[ConfigurationAssignmentRuleListItem]] = {
    val profile = profileId.fold(fr"")(id => fr"and ru.profile_id = $id")
    val archived = if (includeArchived) fr"" else fr"and not ru.archived"
    val cursor = after.fold(fr"") { case (at, id) => fr"and (ru.created_at, ru.id) > ($at, $id)" }
    listWhere(organizationId, profile ++ archived ++ cursor, limit)
  }

  private def listWhere(organizationId: UUID, filter: Fragment, limit: Int): ConnectionIO[List[ConfigurationAssignmentRuleListItem]] = {
    // Counts are correlated subqueries of the same statement: one statement per page of rules.
    (fr"select" ++ ruleColumns ++ fr""", cp.code, cp.name, cp.archived, cp.latest_revision_number,
        (select count(*) from resource r join resource_type rt on rt.id = r.resource_type_id
           join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
           where """ ++ storedPredicate ++ fr""")::int,
        (select count(*) from configuration_assignment a where a.organization_id = ru.organization_id
           and a.source_rule_id = ru.id and a.removed_at is null)::int,
        (select count(*) from configuration_assignment_rule_issue i where i.organization_id = ru.organization_id
           and i.rule_id = ru.id)::int,
        (select count(*) from configuration_assignment_rule_exclusion x where x.organization_id = ru.organization_id
           and x.rule_id = ru.id)::int
      from configuration_assignment_rule ru
      join configuration_profile cp on cp.id = ru.profile_id and cp.organization_id = ru.organization_id
      where ru.organization_id = $organizationId""" ++ filter ++
      fr"order by ru.created_at, ru.id limit $limit")
      .query[(RuleRow, (String, String, Boolean, Int), (Int, Int, Int, Int))].to[List].flatMap { rows =>
        selectors(organizationId, rows.map(_._1.id)).map { all =>
          rows.map { case (row, (code, name, profileArchived, latest), (matched, managed, issues, excluded)) =>
            ConfigurationAssignmentRuleListItem(row.toDomain(all.getOrElse(row.id, EmptySelector)), code, name,
              profileArchived, latest, matched, managed, issues, excluded)
          }
        }
      }
  }

  override def update(organizationId: UUID, id: UUID, expectedVersion: Int, name: String, description: Option[String],
                      selector: ConfigurationRuleSelector, at: Instant): ConnectionIO[RuleWrite] =
    sql"""update configuration_assignment_rule
          set name = $name, description = $description, version = version + 1, updated_at = $at,
              next_reconcile_at = case when enabled then $at else null end,
              lease_owner = null, lease_token = null, lease_expires_at = null
          where organization_id = $organizationId and id = $id and version = $expectedVersion and not archived"""
      .update.run.flatMap {
        case 1 => (sql"delete from configuration_assignment_rule_project where rule_id = $id".update.run *>
          sql"delete from configuration_assignment_rule_environment where rule_id = $id".update.run *>
          sql"delete from configuration_assignment_rule_label where rule_id = $id".update.run *>
          writeSelector(organizationId, id, selector)).as(RuleWrite.Written: RuleWrite)
        case _ => lost(organizationId, id)
      }

  override def setEnabled(organizationId: UUID, id: UUID, expectedVersion: Int, enabled: Boolean,
                          at: Instant): ConnectionIO[RuleWrite] =
    sql"""update configuration_assignment_rule
          set enabled = $enabled, version = version + 1, updated_at = $at,
              next_reconcile_at = case when $enabled then $at else null end,
              lease_owner = null, lease_token = null, lease_expires_at = null
          where organization_id = $organizationId and id = $id and version = $expectedVersion and not archived"""
      .update.run.flatMap {
        case 1 => (RuleWrite.Written: RuleWrite).pure[ConnectionIO]
        case _ => lost(organizationId, id)
      }

  override def archive(organizationId: UUID, id: UUID, expectedVersion: Int, at: Instant): ConnectionIO[RuleWrite] =
    sql"""update configuration_assignment_rule
          set archived = true, enabled = false, version = version + 1, updated_at = $at, next_reconcile_at = null,
              lease_owner = null, lease_token = null, lease_expires_at = null
          where organization_id = $organizationId and id = $id and version = $expectedVersion and not archived"""
      .update.run.flatMap {
        case 1 => (RuleWrite.Written: RuleWrite).pure[ConnectionIO]
        case _ => lost(organizationId, id)
      }

  override def scheduleReconcile(organizationId: UUID, id: UUID, at: Instant): ConnectionIO[RuleWrite] =
    sql"""update configuration_assignment_rule set next_reconcile_at = $at
          where organization_id = $organizationId and id = $id and enabled and not archived"""
      .update.run.flatMap {
        case 1 => (RuleWrite.Written: RuleWrite).pure[ConnectionIO]
        case _ => sql"select archived from configuration_assignment_rule where organization_id = $organizationId and id = $id"
          .query[Boolean].option.map {
            case None => RuleWrite.Missing
            case Some(true) => RuleWrite.Archived
            case Some(false) => RuleWrite.Disabled
          }
      }

  override def exclude(organizationId: UUID, ruleId: UUID, resourceId: UUID, actor: UUID, at: Instant): ConnectionIO[Boolean] =
    sql"""insert into configuration_assignment_rule_exclusion (rule_id, organization_id, resource_id, created_by_user_id, created_at)
          values ($ruleId, $organizationId, $resourceId, $actor, $at) on conflict do nothing""".update.run.flatMap { count =>
      sql"""delete from configuration_assignment_rule_issue
            where organization_id = $organizationId and rule_id = $ruleId and resource_id = $resourceId""".update.run.as(count == 1)
    }

  override def include(organizationId: UUID, ruleId: UUID, resourceId: UUID, at: Instant): ConnectionIO[Boolean] =
    sql"""delete from configuration_assignment_rule_exclusion
          where organization_id = $organizationId and rule_id = $ruleId and resource_id = $resourceId""".update.run.flatMap {
      case 0 => false.pure[ConnectionIO]
      case _ => sql"""update configuration_assignment_rule set next_reconcile_at = $at
                      where organization_id = $organizationId and id = $ruleId and enabled and not archived""".update.run.as(true)
    }

  override def candidates(organizationId: UUID, selector: ConfigurationRuleSelector, ruleId: Option[UUID],
                          targetPath: String, after: Option[UUID], limit: Int): ConnectionIO[List[RuleCandidate]] =
    (fr"""select r.id,
            exists (select 1 from configuration_assignment_rule_exclusion x
                    where x.rule_id = ${ruleId}::uuid and x.resource_id = r.id),
            a.id, a.source_rule_id, a.profile_id, a.profile_revision_number
          from resource r
          join resource_type rt on rt.id = r.resource_type_id
          join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
          left join configuration_assignment a on a.organization_id = r.organization_id and a.resource_id = r.id
            and a.target_path = $targetPath and a.removed_at is null
          where """ ++ predicate(organizationId, selector) ++
      fr"and (${after}::uuid is null or r.id > ${after}::uuid) order by r.id limit $limit")
      .query[RuleCandidate].to[List]

  override def previewCounts(organizationId: UUID, selector: ConfigurationRuleSelector,
                             targetPath: String): ConnectionIO[(Int, Int, Int, Int)] =
    (fr"""select count(*)::int, (count(*) filter (where a.id is null))::int,
            (count(*) filter (where a.id is not null and a.source_rule_id is null))::int,
            (count(*) filter (where a.source_rule_id is not null))::int
          from resource r join resource_type rt on rt.id = r.resource_type_id
          join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
          left join configuration_assignment a on a.organization_id = r.organization_id and a.resource_id = r.id
            and a.target_path = $targetPath and a.removed_at is null
          where """ ++ predicate(organizationId, selector)).query[(Int, Int, Int, Int)].unique

  override def eligible(organizationId: UUID, rule: ConfigurationAssignmentRule, resourceId: UUID): ConnectionIO[Boolean] =
    (fr"""select exists (select 1 from resource r join resource_type rt on rt.id = r.resource_type_id
          join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
          where r.id = $resourceId and """ ++ predicate(organizationId, rule.selector) ++
      fr"""and not exists (select 1 from configuration_assignment_rule_exclusion x
                           where x.rule_id = ${rule.id} and x.resource_id = r.id))""").query[Boolean].unique

  override def targets(organizationId: UUID, rule: ConfigurationAssignmentRule, after: Option[(String, UUID)],
                       limit: Int): ConnectionIO[List[ConfigurationRuleTargetRow]] = {
    val cursor = after.fold(fr"") { case (name, id) => fr"and (r.name, r.id) > ($name, $id)" }
    (fr"""with matching as (
            select r.id from resource r join resource_type rt on rt.id = r.resource_type_id
            join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
            where """ ++ predicate(organizationId, rule.selector) ++ fr"""
          ), related as (
            select id from matching
            union select a.resource_id from configuration_assignment a
              where a.organization_id = $organizationId and a.source_rule_id = ${rule.id} and a.removed_at is null
            union select x.resource_id from configuration_assignment_rule_exclusion x
              where x.organization_id = $organizationId and x.rule_id = ${rule.id}
            union select i.resource_id from configuration_assignment_rule_issue i
              where i.organization_id = $organizationId and i.rule_id = ${rule.id}
          )
          select r.id, r.name, r.code, r.is_active, p.id, p.name, e.id, e.name,
            r.id in (select id from matching),
            exists (select 1 from configuration_assignment_rule_exclusion x where x.rule_id = ${rule.id} and x.resource_id = r.id),
            a.id, a.version, a.profile_revision_number, a.source_rule_id,
            i.issue_code, i.variable_name, i.conflicting_assignment_id
          from related
          join resource r on r.id = related.id and r.organization_id = $organizationId
          join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
          join project p on p.id = e.project_id and p.organization_id = e.organization_id
          left join configuration_assignment a on a.organization_id = r.organization_id and a.resource_id = r.id
            and a.target_path = ${rule.targetPath} and a.removed_at is null
          left join configuration_assignment_rule_issue i on i.rule_id = ${rule.id} and i.resource_id = r.id
          where true """ ++ cursor ++ fr"order by r.name, r.id limit $limit")
      .query[TargetRow].to[List].map(_.map(_.toDomain))
  }

  // Reconciliation.

  override def claim(owner: UUID, token: UUID, now: Instant, until: Instant,
                     scope: Option[UUID]): ConnectionIO[Option[ClaimedRule]] =
    sql"""with next as (
            select id from configuration_assignment_rule
            where enabled and not archived and next_reconcile_at <= $now
              and (lease_expires_at is null or lease_expires_at <= $now)
              and (${scope}::uuid is null or organization_id = ${scope}::uuid)
            order by next_reconcile_at, id limit 1 for update skip locked
          ) update configuration_assignment_rule ru
            set lease_owner = $owner, lease_token = $token, lease_expires_at = $until
          from next where ru.id = next.id returning ru.organization_id, ru.id"""
      .query[(UUID, UUID)].option.flatMap(_.flatTraverse { case (org, id) =>
        (find(org, id), sql"""select cp.archived from configuration_assignment_rule ru
              join configuration_profile cp on cp.id = ru.profile_id and cp.organization_id = ru.organization_id
              where ru.id = $id""".query[Boolean].unique).mapN((rule, archived) => rule.map(ClaimedRule(_, token, archived)))
      })

  override def renew(claimed: ClaimedRule, now: Instant, until: Instant): ConnectionIO[Boolean] =
    (fr"update configuration_assignment_rule ru set lease_expires_at = $until where" ++ fence(claimed, now))
      .update.run.map(_ == 1)

  override def autoCreate(claimed: ClaimedRule, resourceId: UUID, assignment: ConfigurationAssignment,
                          now: Instant): ConnectionIO[RuleAutoCreate] = {
    val rule = claimed.rule
    // FOR SHARE: a concurrent rule change (which bumps the version) waits for this insert to commit.
    (fr"select 1 from configuration_assignment_rule ru where" ++ fence(claimed, now) ++ fr"for share")
      .query[Int].option.flatMap {
        case None => (RuleAutoCreate.Fenced: RuleAutoCreate).pure[ConnectionIO]
        case Some(_) =>
          sql"""select archived from configuration_profile
                where organization_id = ${rule.organizationId} and id = ${rule.profileId} for share"""
            .query[Boolean].unique.flatMap {
              case true => (RuleAutoCreate.NoLongerEligible: RuleAutoCreate).pure[ConnectionIO]
              case false => eligible(rule.organizationId, rule, resourceId).flatMap {
                case false => (RuleAutoCreate.NoLongerEligible: RuleAutoCreate).pure[ConnectionIO]
                case true =>
                  // The partial unique index stays the final authority over who owns the path.
                  sql"""insert into configuration_assignment (id, organization_id, resource_id, profile_id,
                          profile_revision_number, target_path, version, removed_at, created_at, updated_at, source_rule_id)
                        values (${assignment.id}, ${assignment.organizationId}, ${assignment.resourceId},
                          ${assignment.profileId}, ${assignment.profileRevisionNumber}, ${assignment.targetPath}, 1, null,
                          $now, $now, ${rule.id})
                        on conflict (organization_id, resource_id, target_path) where removed_at is null do nothing"""
                    .update.run.flatMap {
                      case 1 => sql"""delete from configuration_assignment_rule_issue
                                      where rule_id = ${rule.id} and resource_id = $resourceId""".update.run
                        .as(RuleAutoCreate.Created: RuleAutoCreate)
                      case _ => sql"""select id, source_rule_id from configuration_assignment
                                      where organization_id = ${rule.organizationId} and resource_id = $resourceId
                                        and target_path = ${rule.targetPath} and removed_at is null"""
                        .query[(UUID, Option[UUID])].option.map {
                          case Some((id, source)) => RuleAutoCreate.Occupied(id, source)
                          case None => RuleAutoCreate.NoLongerEligible
                        }
                    }
              }
            }
      }
  }

  override def recordIssue(claimed: ClaimedRule, resourceId: UUID, code: ConfigurationRuleIssueCode,
                           variable: Option[String], conflicting: Option[UUID], now: Instant): ConnectionIO[Boolean] =
    (fr"""insert into configuration_assignment_rule_issue (rule_id, organization_id, resource_id, issue_code,
            variable_name, conflicting_assignment_id, observed_at)
          select ${claimed.rule.id}, ${claimed.rule.organizationId}, $resourceId, ${code.code}, $variable, $conflicting, $now
          where exists (select 1 from configuration_assignment_rule ru where""" ++ fence(claimed, now) ++ fr""")
          on conflict (rule_id, resource_id) do update set issue_code = excluded.issue_code,
            variable_name = excluded.variable_name, conflicting_assignment_id = excluded.conflicting_assignment_id,
            observed_at = excluded.observed_at""").update.run.map(_ == 1)

  override def clearIssues(claimed: ClaimedRule, resourceIds: List[UUID]): ConnectionIO[Boolean] =
    if (resourceIds.isEmpty) true.pure[ConnectionIO]
    else sql"""delete from configuration_assignment_rule_issue
               where rule_id = ${claimed.rule.id} and resource_id = any(${resourceIds.toArray[UUID]})"""
      .update.run.as(true)

  override def finishSweep(claimed: ClaimedRule, sweepStartedAt: Instant, now: Instant,
                           next: Instant): ConnectionIO[Boolean] =
    (fr"""update configuration_assignment_rule ru set last_reconciled_at = $now,
            next_reconcile_at = case when ru.next_reconcile_at > $sweepStartedAt then ru.next_reconcile_at else $next end,
            lease_owner = null, lease_token = null, lease_expires_at = null
          where""" ++ fence(claimed, now)).update.run.flatMap {
      case 1 =>
        // Issues of resources the sweep no longer saw (they stopped matching) are not current any more.
        sql"""delete from configuration_assignment_rule_issue
              where rule_id = ${claimed.rule.id} and observed_at < $sweepStartedAt""".update.run.as(true)
      case _ => false.pure[ConnectionIO]
    }

  // Managed assignments.

  override def lockRule(organizationId: UUID, ruleId: UUID): ConnectionIO[Option[(Int, Int, Boolean)]] =
    sql"""select version, profile_revision_number, archived from configuration_assignment_rule
          where organization_id = $organizationId and id = $ruleId for update""".query[(Int, Int, Boolean)].option

  override def detach(organizationId: UUID, ruleId: UUID, assignmentId: UUID, expectedVersion: Int, actor: UUID,
                      at: Instant): ConnectionIO[ManagedAssignmentWrite] =
    managedChange(organizationId, ruleId, assignmentId, expectedVersion) { resourceId =>
      sql"""update configuration_assignment set source_rule_id = null, version = version + 1, updated_at = $at
            where organization_id = $organizationId and id = $assignmentId""".update.run *>
        exclude(organizationId, ruleId, resourceId, actor, at).void
    }

  override def excludeAndRemove(organizationId: UUID, ruleId: UUID, assignmentId: UUID, expectedVersion: Int,
                                actor: UUID, at: Instant): ConnectionIO[ManagedAssignmentWrite] =
    managedChange(organizationId, ruleId, assignmentId, expectedVersion) { resourceId =>
      exclude(organizationId, ruleId, resourceId, actor, at) *>
        sql"""update configuration_assignment set removed_at = $at, version = version + 1, updated_at = $at
              where organization_id = $organizationId and id = $assignmentId""".update.run.void
    }

  override def adopt(organizationId: UUID, rule: ConfigurationAssignmentRule, assignmentId: UUID, expectedVersion: Int,
                     at: Instant): ConnectionIO[ManagedAssignmentWrite] =
    lockRule(organizationId, rule.id).flatMap {
      case Some((version, _, false)) if version == rule.version => lockAssignment(organizationId, assignmentId).flatMap {
        case None => (ManagedAssignmentWrite.Missing: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) if a.removedAt.isDefined => (ManagedAssignmentWrite.Missing: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) if a.version != expectedVersion => (ManagedAssignmentWrite.Stale: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) if a.sourceRuleId.isDefined => (ManagedAssignmentWrite.AlreadyManaged: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) if a.targetPath != rule.targetPath || a.profileId != rule.profileId ||
          a.profileRevisionNumber != rule.profileRevisionNumber =>
          (ManagedAssignmentWrite.Incompatible: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) => for {
          _ <- sql"""update configuration_assignment set source_rule_id = ${rule.id}, version = version + 1, updated_at = $at
                     where organization_id = $organizationId and id = $assignmentId""".update.run
          _ <- sql"""delete from configuration_assignment_rule_exclusion where rule_id = ${rule.id} and resource_id = ${a.resourceId}"""
            .update.run
          _ <- sql"""delete from configuration_assignment_rule_issue where rule_id = ${rule.id} and resource_id = ${a.resourceId}"""
            .update.run
        } yield ManagedAssignmentWrite.Written: ManagedAssignmentWrite
      }
      case Some(_) => (ManagedAssignmentWrite.Stale: ManagedAssignmentWrite).pure[ConnectionIO]
      case None => (ManagedAssignmentWrite.Missing: ManagedAssignmentWrite).pure[ConnectionIO]
    }

  override def managed(organizationId: UUID, ruleId: UUID, limit: Int,
                       lock: Boolean): ConnectionIO[List[ConfigurationPromotionCandidate]] =
    (fr"select" ++ assignmentColumns ++ fr""", r.name from configuration_assignment a
          join resource r on r.id = a.resource_id and r.organization_id = a.organization_id
          where a.organization_id = $organizationId and a.source_rule_id = $ruleId and a.removed_at is null
          order by a.id limit $limit""" ++ (if (lock) fr"for update of a" else fr""))
      .query[(AssignmentRow, String)].to[List].flatMap { rows =>
        val ids = rows.map(_._1.id).toArray[UUID]
        sql"""select assignment_id, name, value from configuration_assignment_value
              where organization_id = $organizationId and assignment_id = any($ids) order by assignment_id, name"""
          .query[(UUID, String, String)].to[List].map { values =>
            val grouped = values.groupMap(_._1)(row => ConfigurationVariableValue(row._2, row._3))
            rows.map { case (row, name) => ConfigurationPromotionCandidate(row.toDomain, name, grouped.getOrElse(row.id, Nil)) }
          }
      }

  override def promote(organizationId: UUID, ruleId: UUID, revisionNumber: Int, assignmentIds: List[UUID],
                       at: Instant): ConnectionIO[List[(UUID, Int)]] =
    for {
      _ <- sql"""update configuration_assignment_rule
                 set profile_revision_number = $revisionNumber, version = version + 1, updated_at = $at,
                     next_reconcile_at = case when enabled then $at else next_reconcile_at end,
                     lease_owner = null, lease_token = null, lease_expires_at = null
                 where organization_id = $organizationId and id = $ruleId""".update.run
      versions <- sql"""update configuration_assignment
                        set profile_revision_number = $revisionNumber, version = version + 1, updated_at = $at
                        where organization_id = $organizationId and source_rule_id = $ruleId and removed_at is null
                          and id = any(${assignmentIds.toArray[UUID]})
                        returning id, version""".query[(UUID, Int)].to[List]
    } yield versions.sortBy(_._1.toString)

  private def managedChange(organizationId: UUID, ruleId: UUID, assignmentId: UUID, expectedVersion: Int)(
    change: UUID => ConnectionIO[Unit]): ConnectionIO[ManagedAssignmentWrite] =
    lockRule(organizationId, ruleId).flatMap {
      case None => (ManagedAssignmentWrite.Missing: ManagedAssignmentWrite).pure[ConnectionIO]
      case Some(_) => lockAssignment(organizationId, assignmentId).flatMap {
        case None => (ManagedAssignmentWrite.Missing: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) if a.removedAt.isDefined => (ManagedAssignmentWrite.Missing: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) if !a.sourceRuleId.contains(ruleId) => (ManagedAssignmentWrite.NotManaged: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) if a.version != expectedVersion => (ManagedAssignmentWrite.Stale: ManagedAssignmentWrite).pure[ConnectionIO]
        case Some(a) => change(a.resourceId).as(ManagedAssignmentWrite.Written: ManagedAssignmentWrite)
      }
    }

  private def lockAssignment(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationAssignment]] =
    (fr"select" ++ assignmentColumns ++ fr"from configuration_assignment a where a.organization_id = $organizationId and a.id = $id for update")
      .query[AssignmentRow].option.map(_.map(_.toDomain))

  private def lost(organizationId: UUID, id: UUID): ConnectionIO[RuleWrite] =
    sql"select archived from configuration_assignment_rule where organization_id = $organizationId and id = $id"
      .query[Boolean].option.map {
        case None => RuleWrite.Missing
        case Some(true) => RuleWrite.Archived
        case Some(false) => RuleWrite.Stale
      }

  private def writeSelector(organizationId: UUID, id: UUID, selector: ConfigurationRuleSelector): ConnectionIO[Unit] =
    Update[(UUID, UUID, UUID)]("insert into configuration_assignment_rule_project (rule_id, organization_id, project_id) values (?, ?, ?)")
      .updateMany(selector.projects.map(project => (id, organizationId, project))) *>
      Update[(UUID, UUID, UUID)]("insert into configuration_assignment_rule_environment (rule_id, organization_id, environment_id) values (?, ?, ?)")
        .updateMany(selector.environments.map(environment => (id, organizationId, environment))) *>
      Update[(UUID, UUID, String, String, String)]("insert into configuration_assignment_rule_label (rule_id, organization_id, kind, key, value) values (?, ?, ?, ?, ?)")
        .updateMany(selector.requiredLabels.map(l => (id, organizationId, "REQUIRED", l.key, l.value)) ++
          selector.excludedLabels.map(l => (id, organizationId, "EXCLUDED", l.key, l.value))).void

  /** Selectors of a page of rules in three statements. */
  private def selectors(organizationId: UUID, ids: List[UUID]): ConnectionIO[Map[UUID, ConfigurationRuleSelector]] =
    NonEmptyList.fromList(ids) match {
      case None => Map.empty[UUID, ConfigurationRuleSelector].pure[ConnectionIO]
      case Some(nel) =>
        val projects = (fr"select rule_id, project_id from configuration_assignment_rule_project where organization_id = $organizationId and" ++
          Fragments.in(fr"rule_id", nel) ++ fr"order by project_id").query[(UUID, UUID)].to[List]
        val environments = (fr"select rule_id, environment_id from configuration_assignment_rule_environment where organization_id = $organizationId and" ++
          Fragments.in(fr"rule_id", nel) ++ fr"order by environment_id").query[(UUID, UUID)].to[List]
        val labels = (fr"select rule_id, kind, key, value from configuration_assignment_rule_label where organization_id = $organizationId and" ++
          Fragments.in(fr"rule_id", nel) ++ fr"order by key, value").query[(UUID, String, String, String)].to[List]
        (projects, environments, labels).mapN { (p, e, l) =>
          ids.map { id =>
            id -> ConfigurationRuleSelector(p.filter(_._1 == id).map(_._2), e.filter(_._1 == id).map(_._2),
              l.filter(row => row._1 == id && row._2 == "REQUIRED").map(row => ResourceLabel(row._3, row._4)),
              l.filter(row => row._1 == id && row._2 == "EXCLUDED").map(row => ResourceLabel(row._3, row._4)))
          }.toMap
        }
    }

  private def fence(claimed: ClaimedRule, now: Instant): Fragment =
    fr"""ru.organization_id = ${claimed.rule.organizationId} and ru.id = ${claimed.rule.id}
        and ru.lease_token = ${claimed.token} and ru.version = ${claimed.rule.version}
        and ru.enabled and not ru.archived and ru.lease_expires_at > $now"""
}

object PostgresConfigurationAssignmentRuleRepository {
  val EmptySelector: ConfigurationRuleSelector = ConfigurationRuleSelector(Nil, Nil, Nil, Nil)

  /** An in-memory selector as a parameterized predicate over `resource r`, `resource_type rt`, `environment e`. */
  def predicate(organizationId: UUID, selector: ConfigurationRuleSelector): Fragment = {
    val base = fr"r.organization_id = $organizationId and r.is_active and rt.code = 'NODE'"
    val projects = if (selector.projects.isEmpty) fr"" else fr"and e.project_id = any(${selector.projects.toArray[UUID]})"
    val environments = if (selector.environments.isEmpty) fr""
      else fr"and r.environment_id = any(${selector.environments.toArray[UUID]})"
    val required = selector.requiredLabels.map(label => fr"""and exists (select 1 from resource_label rl
        where rl.organization_id = r.organization_id and rl.resource_id = r.id
          and rl.key = ${label.key} and rl.value = ${label.value})""")
    val excluded = selector.excludedLabels.map(label => fr"""and not exists (select 1 from resource_label rl
        where rl.organization_id = r.organization_id and rl.resource_id = r.id
          and rl.key = ${label.key} and rl.value = ${label.value})""")
    (base :: projects :: environments :: required ++ excluded).combineAll
  }

  /** The stored selector of rule `ru` as the same predicate, for per-rule counts in one statement. */
  val storedPredicate: Fragment = fr"""r.organization_id = ru.organization_id and r.is_active and rt.code = 'NODE'
    and (not exists (select 1 from configuration_assignment_rule_project sp where sp.rule_id = ru.id)
         or exists (select 1 from configuration_assignment_rule_project sp where sp.rule_id = ru.id and sp.project_id = e.project_id))
    and (not exists (select 1 from configuration_assignment_rule_environment se where se.rule_id = ru.id)
         or exists (select 1 from configuration_assignment_rule_environment se where se.rule_id = ru.id and se.environment_id = r.environment_id))
    and not exists (select 1 from configuration_assignment_rule_label sl where sl.rule_id = ru.id and sl.kind = 'REQUIRED'
         and not exists (select 1 from resource_label rl where rl.organization_id = r.organization_id
           and rl.resource_id = r.id and rl.key = sl.key and rl.value = sl.value))
    and not exists (select 1 from configuration_assignment_rule_label sl join resource_label rl
         on rl.organization_id = r.organization_id and rl.resource_id = r.id and rl.key = sl.key and rl.value = sl.value
         where sl.rule_id = ru.id and sl.kind = 'EXCLUDED')"""

  private val ruleColumns: Fragment = fr"""ru.id, ru.organization_id, ru.code, ru.name, ru.description, ru.profile_id,
    ru.profile_revision_number, ru.target_path, ru.enabled, ru.archived, ru.version, ru.created_by_user_id,
    ru.created_at, ru.updated_at, ru.last_reconciled_at, ru.next_reconcile_at"""
  private val ruleSelect: Fragment = fr"select" ++ ruleColumns ++ fr"from configuration_assignment_rule ru"

  private final case class RuleRow(id: UUID, organizationId: UUID, code: String, name: String, description: Option[String],
                                   profileId: UUID, revision: Int, targetPath: String, enabled: Boolean, archived: Boolean,
                                   version: Int, createdBy: UUID, createdAt: Instant, updatedAt: Instant,
                                   lastReconciledAt: Option[Instant], nextReconcileAt: Option[Instant]) {
    def toDomain(selector: ConfigurationRuleSelector): ConfigurationAssignmentRule =
      ConfigurationAssignmentRule(id, organizationId, code, name, description, profileId, revision, targetPath, selector,
        enabled, archived, version, createdBy, createdAt, updatedAt, lastReconciledAt, nextReconcileAt)
  }

  private final case class TargetRow(resourceId: UUID, name: String, code: String, active: Boolean,
                                     projectId: UUID, projectName: String, environmentId: UUID, environmentName: String,
                                     matches: Boolean, excluded: Boolean, assignmentId: Option[UUID],
                                     assignmentVersion: Option[Int], assignmentRevision: Option[Int],
                                     assignmentSourceRuleId: Option[UUID], issueCode: Option[String],
                                     issueVariable: Option[String], conflicting: Option[UUID]) {
    def toDomain: ConfigurationRuleTargetRow = ConfigurationRuleTargetRow(resourceId, name, code, active, projectId,
      projectName, environmentId, environmentName, matches, excluded, assignmentId, assignmentVersion,
      assignmentRevision, assignmentSourceRuleId, issueCode.map(ConfigurationRuleIssueCode.fromCode), issueVariable, conflicting)
  }
}

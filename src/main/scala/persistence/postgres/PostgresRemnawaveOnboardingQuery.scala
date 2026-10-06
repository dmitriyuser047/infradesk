package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.integration._
import domain.provisioning._
import io.circe.Json
import java.time.Instant
import org.typelevel.doobie.{ConnectionIO,Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.util.UUID

final class PostgresRemnawaveOnboardingQuery extends RemnawaveOnboardingQuery[ConnectionIO] {

  private case class Latest(id: UUID, state: String, finishedAt: Option[Instant])
  private type StatusRow = (UUID,Option[String],Option[Int],Option[UUID],Option[Long],Option[UUID],
    Option[String],Option[String],Option[ServerProfileRepositoryRows.ObservationRow],Option[Latest],Boolean,Boolean)

  def serverStatuses(org: UUID, targets: Map[UUID,Either[String,ProvisioningTarget]]): ConnectionIO[Map[UUID,OnboardingServerStatus]] = {
    if (targets.isEmpty) Map.empty[UUID,OnboardingServerStatus].pure[ConnectionIO]
    else {
      val input = Json.arr(targets.toList.sortBy(_._1.toString).map { case(id,target) => Json.obj(
        "resource_id" -> Json.fromString(id.toString),
        "connection_id" -> target.toOption.fold(Json.Null)(t => Json.fromString(t.connectionId.toString)),
        "connection_updated_at" -> target.toOption.fold(Json.Null)(t => Json.fromString(t.connectionUpdatedAt.toString))) }: _*).noSpaces
      val rows = (sql"""select t.resource_id,p.name,r.revision_number,a.id,a.version,a.revision_id,
        r.content::text,r.content_hash,
        o.id,o.organization_id,o.resource_id,o.source_connection_id,o.source_updated_at,o.assignment_id,o.assignment_version,
        o.revision_id,o.content::text,o.content_hash,o.observed_at,o.verified_run_id,
        latest.id,latest.status,latest.finished_at,""" ++ RemnawaveOnboardingActivitySql.resource(org,fr"t.resource_id") ++ sql""",
        exists(select 1 from integration_resource_binding b join integration_inventory_object n on n.id=b.inventory_object_id
          where b.organization_id=$org and b.resource_id=t.resource_id and n.object_type='NODE')
        from jsonb_to_recordset(cast($input as jsonb)) as t(resource_id uuid,connection_id uuid,connection_updated_at timestamptz)
        left join server_profile_assignment a on a.organization_id=$org and a.resource_id=t.resource_id
        left join server_profile p on p.organization_id=$org and p.id=a.profile_id
        left join server_profile_revision r on r.organization_id=$org and r.id=a.revision_id and r.profile_id=a.profile_id
        left join server_profile_observation o on o.organization_id=$org and o.resource_id=t.resource_id
          and o.source_connection_id=t.connection_id and o.source_updated_at=t.connection_updated_at
          and o.assignment_id=a.id and o.assignment_version=a.version and o.revision_id=a.revision_id
        left join lateral (select pr.id,pr.status,pr.finished_at from provisioning_run pr
          where pr.organization_id=$org and pr.resource_id=t.resource_id and pr.run_kind='SERVER_PROFILE_APPLY'
          and pr.connection_id=t.connection_id and pr.connection_updated_at=t.connection_updated_at and pr.status<>'PLANNED'
          and pr.profile_apply_snapshot->>'assignmentId'=a.id::text
          and (pr.profile_apply_snapshot->>'assignmentVersion')::bigint=a.version
          and pr.profile_apply_snapshot->>'revisionId'=a.revision_id::text
          order by case when pr.status in ('QUEUED','RUNNING') then 0 else 1 end,
            coalesce(pr.started_at,pr.updated_at) desc,pr.id desc limit 1) latest on true""").query[StatusRow].to[List]
      rows.map(_.map { case(id,name,number,assignment,version,revision,content,hash,stored,latest,busy,conflict) =>
        val desired = (content,hash).mapN { (c,h) => ServerProfileContent.parsePersisted(c,h)
          .fold(code => throw new IllegalStateException(code),identity) }
        val observed = stored.map(_.toDomain)
        val linked = latest.exists(l => observed.exists(_.verifiedRunId.contains(l.id)))
        val laterManual = latest.exists(l => observed.exists(o => o.verifiedRunId.isEmpty && l.finishedAt.exists(o.observedAt.isAfter)))
        val compliant = (desired,observed).mapN((d,o) => ServerProfileDiff.assess(d,o.content).compliant).contains(true)
        val state = ServerProfileAutomationState.assess(assignment.nonEmpty && name.nonEmpty && desired.nonEmpty,
          observed.nonEmpty,compliant,latest.map(l => ProvisioningRunState.fromCode(l.state)),linked,laterManual)
        id -> OnboardingServerStatus(name,number,assignment.nonEmpty,state,busy,conflict)
      }.toMap)
    }
  }

  def candidates(org: UUID, limit: Int): ConnectionIO[List[OnboardingServerCandidate]] = sql"""
    select r.id,r.name,coalesce(nullif(r.spec->>'hostname',''),
      (select c.config->>'host' from external_ref x join connection c on c.id=x.connection_id
        where x.organization_id=r.organization_id and x.resource_id=r.id and c.connector_type='SSH' order by c.id limit 1),''),e.name from resource r
    join resource_type t on t.id=r.resource_type_id join environment e on e.id=r.environment_id and e.organization_id=r.organization_id
    where r.organization_id=$org and r.is_active and t.code='NODE' order by r.name,r.id limit $limit
    """.query[OnboardingServerCandidate].to[List]
  def bindingConflict(org: UUID, resource: UUID, expected: Option[UUID]): ConnectionIO[Boolean] = sql"""
    select exists(select 1 from integration_resource_binding b join integration_inventory_object o on o.id=b.inventory_object_id
      where b.organization_id=$org and b.resource_id=$resource and o.object_type='NODE'
        and (cast($expected as uuid) is null or o.external_id<>cast($expected as text) or o.integration_id is distinct from
          (select n.integration_id from remnawave_node_onboarding n where n.organization_id=$org and n.resource_id=$resource
            and n.state<>'PLANNED' and (n.external_node_id=cast($expected as uuid) or
              n.input_snapshot->'recovery'->>'previousExternalNodeId'=cast($expected as text))
            order by n.created_at desc,n.id desc limit 1)))""".query[Boolean].unique
  def baselinePlanParent(org: UUID, planId: UUID): ConnectionIO[Option[UUID]] =
    sql"select onboarding_parent_id from provisioning_run where organization_id=$org and id=$planId"
      .query[Option[UUID]].option.map(_.flatten)
  def externalNode(org: UUID, integration: UUID, external: UUID): ConnectionIO[Option[IntegrationInventoryObject]] = {
    import IntegrationInventoryRows._
    (fr"select" ++ objectColumns ++ fr"from integration_inventory_object o where o.organization_id=$org and o.integration_id=$integration and o.object_type='NODE' and o.external_id=${external.toString}")
      .query[ObjectRow].option.flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
  }
}

private[postgres] object RemnawaveOnboardingActivitySql {
  def resource(org: UUID, resource: Fragment): Fragment = sql"""
    exists(select 1 from remnawave_node_onboarding where organization_id=$org and resource_id=$resource and state in ('QUEUED','RUNNING')) or
    exists(select 1 from provisioning_run where organization_id=$org and resource_id=$resource and status in ('QUEUED','RUNNING')) or
    exists(select 1 from configuration_deployment where organization_id=$org and resource_id=$resource and state in ('QUEUED','RUNNING')) or
    exists(select 1 from operation_execution where organization_id=$org and resource_id=$resource and status='RUNNING') or
    exists(select 1 from integration_action_execution a where a.organization_id=$org and a.status in ('QUEUED','RUNNING') and (
      exists(select 1 from integration_resource_binding b where b.organization_id=$org and b.resource_id=$resource and b.inventory_object_id=a.inventory_object_id) or
      exists(select 1 from remnawave_node_onboarding r where r.organization_id=$org and r.resource_id=$resource and r.integration_id=a.integration_id
        and (r.external_node_id::text=a.external_id_snapshot or r.input_snapshot->'recovery'->>'previousExternalNodeId'=a.external_id_snapshot)))) or
    exists(select 1 from remnawave_fleet_membership m where m.organization_id=$org and m.resource_id=$resource and m.removed_at is null and (
      exists(select 1 from remnawave_fleet_rollout r where r.fleet_id=m.fleet_id and r.state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) or
      exists(select 1 from remnawave_fleet_upgrade_run u where u.fleet_id=m.fleet_id and u.state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))))"""
}

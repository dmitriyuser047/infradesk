package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.integration._
import domain.provisioning.{ServerProfileAssignment, ServerProfileContent, ServerProfileObservation}
import io.circe.parser.parse
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

/** Read models for the fleet dashboard. Every answer comes from stored evidence: nothing here
  * contacts a panel or a server, so opening the dashboard performs no remote call.
  */
final class PostgresRemnawaveFleetQuery extends RemnawaveFleetQuery[ConnectionIO] {
  private val repository = new PostgresRemnawaveFleetRepository

  def configConsumers(org: UUID, integrationId: UUID, externalConfigProfileId: String): ConnectionIO[List[FleetRolloutConfigConsumer]] =
    sql"""select id,external_id,display_name,coalesce((summary->>'isDisabled')::boolean,false),
      coalesce((summary->>'isConnected')::boolean,false),last_seen_at
      from integration_inventory_object where organization_id=$org and integration_id=$integrationId
        and object_type='NODE' and is_active and summary->>'activeConfigProfileUuid'=$externalConfigProfileId
        and exists(select 1 from integration i where i.id=$integrationId and i.organization_id=$org and i.deleted_at is null)
      order by id""".query[FleetRolloutConfigConsumer].to[List]

  def summaries(org: UUID, integrationId: UUID,
    fleetIds: List[UUID]): ConnectionIO[Map[UUID, RemnawaveFleetSummary]] =
    if (fleetIds.isEmpty) Map.empty[UUID, RemnawaveFleetSummary].pure[ConnectionIO] else sql"""
      select f.id,
        (select count(*) from remnawave_fleet_membership m
          where m.fleet_id=f.id and m.organization_id=f.organization_id and m.removed_at is null),
        coalesce(sum(case when a.compliance='COMPLIANT' then 1 else 0 end),0),
        coalesce(sum(case when a.compliance='DRIFTED' then 1 else 0 end),0),
        coalesce(sum(case when a.compliance='UNKNOWN' then 1 else 0 end),0),
        coalesce(sum(case when a.compliance='BLOCKED' then 1 else 0 end),0),
        coalesce(sum(case when a.health='HEALTHY' then 1 else 0 end),0),
        coalesce(sum(case when a.health='DEGRADED' then 1 else 0 end),0),
        coalesce(sum(case when a.health='UNKNOWN' then 1 else 0 end),0),
        count(a.id),
        (select r.number from remnawave_fleet_revision r where r.id=f.desired_revision_id),
        max(a.computed_at),
        least(min(a.inventory_observed_at),min(a.server_observed_at),min(a.local_observed_at))
      from remnawave_fleet f
      left join remnawave_fleet_node_assessment a
        on a.fleet_id=f.id and a.organization_id=f.organization_id
        and a.fleet_revision_id=f.desired_revision_id
        and exists(select 1 from remnawave_fleet_membership m where m.id=a.membership_id and m.removed_at is null)
      where f.organization_id=$org and f.integration_id=$integrationId and f.id=any(${fleetIds.map(_.toString)}::uuid[])
      group by f.id,f.desired_revision_id,f.organization_id"""
      .query[(UUID, Int, Int, Int, Int, Int, Int, Int, Int, Int, Option[Int], Option[Instant], Option[Instant])]
      .to[List].map(_.map { case (id, total, compliant, drifted, unknown, blocked, healthy, degraded,
        healthUnknown, assessed, revisionNumber, lastAt, oldest) =>
        // Members without an assessment of the current desired revision are not yet known.
        id -> RemnawaveFleetSummary(total, compliant, drifted, unknown + (total - assessed).max(0), blocked,
          healthy, degraded, healthUnknown + (total - assessed).max(0), assessed, revisionNumber, lastAt, oldest)
      }.toMap)

  private val memberSelect = fr"""
    select m.id,m.organization_id,m.fleet_id,m.integration_id,m.inventory_node_id,m.resource_id,m.version,
      m.created_by,m.created_at,m.removed_at,
      o.display_name,o.summary::text,o.is_active,o.last_seen_at,
      r.name,r.is_active,
      sp.name,sa.revision_number,
      cp.display_name,cp.external_id,
      ds.desired_state,
      exists(select 1 from remnawave_node_onboarding ob where ob.organization_id=m.organization_id
        and ob.integration_id=m.integration_id and ob.resource_id=m.resource_id and ob.state='SUCCEEDED'
        and ob.external_node_id::text=o.external_id)
    from remnawave_fleet_membership m
    join integration_inventory_object o on o.id=m.inventory_node_id and o.organization_id=m.organization_id
    join resource r on r.id=m.resource_id and r.organization_id=m.organization_id
    left join server_profile_assignment sa on sa.organization_id=m.organization_id and sa.resource_id=m.resource_id
    left join server_profile sp on sp.id=sa.profile_id and sp.organization_id=m.organization_id
    left join integration_inventory_object cp on cp.organization_id=m.organization_id
      and cp.integration_id=m.integration_id and cp.object_type='CONFIG_PROFILE'
      and cp.external_id=(o.summary->>'activeConfigProfileUuid')
    left join integration_desired_state ds on ds.organization_id=m.organization_id
      and ds.integration_id=m.integration_id and ds.inventory_object_id=m.inventory_node_id"""

  private type MemberTuple = (UUID, UUID, UUID, UUID, UUID, UUID, Long, UUID, Instant, Option[Instant],
    String, String, Boolean, Instant, String, Boolean, Option[String], Option[Int], Option[String],
    Option[String], Option[String], Boolean)

  def memberRows(org: UUID, fleetId: UUID): ConnectionIO[List[FleetMemberRow]] = memberRowsSelected(org, fleetId, None)
  def memberRowsBatch(org: UUID, fleetId: UUID, ids: List[UUID]): ConnectionIO[List[FleetMemberRow]] =
    memberRowsSelected(org, fleetId, Some(ids))
  private def memberRowsSelected(org: UUID, fleetId: UUID, ids: Option[List[UUID]]): ConnectionIO[List[FleetMemberRow]] = for {
    rows <- (memberSelect ++ fr"where m.organization_id=$org and m.fleet_id=$fleetId and m.removed_at is null" ++
      ids.fold(fr"")(v => fr"and m.id=any(${v.toArray[UUID]})") ++
      fr"order by o.display_name,m.id").query[MemberTuple].to[List]
    stored <- ids.fold(repository.assessments(org, fleetId))(repository.assessmentsBatch(org, fleetId, _))
  } yield {
    val byMembership = stored.map(value => value.membershipId -> value).toMap
    rows.map { case (id, organizationId, fleet, integration, node, resource, version, createdBy, createdAt,
      removedAt, nodeName, summaryText, nodeActive, lastSeenAt, resourceName, resourceActive, profileName,
      revisionNumber, configName, configExternalId, desiredState, managed) =>
      val summary = parse(summaryText).toOption.flatMap(json =>
        serialization.integration.IntegrationSummaryJson.decode(IntegrationObjectType.Node, json).toOption)
        .collect { case value: RemnawaveNodeSummary => value }
      FleetMemberRow(
        RemnawaveFleetMembership(id, organizationId, fleet, integration, node, resource, version, createdBy,
          createdAt, removedAt),
        nodeName, summary.map(_.address).getOrElse(""), summary.map(_.countryCode).filter(_.nonEmpty),
        summary.exists(_.isConnected), summary.exists(_.isDisabled), resourceName, resourceActive && nodeActive,
        profileName, revisionNumber, configName, configExternalId, summary.flatMap(_.activeInboundIds),
        desiredState.flatMap(IntegrationDesiredNodeState.fromCode), managed, byMembership.get(id))
    }
  }

  /** Every bound node of the integration, with the one reason it cannot join a fleet when it cannot. */
  def candidates(org: UUID, integrationId: UUID, limit: Int): ConnectionIO[List[FleetCandidate]] = sql"""
    select o.id,o.external_id,o.display_name,b.resource_id,r.name,o.is_active,r.is_active,
      t.code,
      (select f.name from remnawave_fleet_membership m
        join remnawave_fleet f on f.id=m.fleet_id and f.organization_id=m.organization_id
        where m.organization_id=o.organization_id and m.integration_id=o.integration_id
          and m.inventory_node_id=o.id and m.removed_at is null limit 1)
    from integration_inventory_object o
    join integration i on i.id=o.integration_id and i.organization_id=o.organization_id and i.deleted_at is null
    join integration_resource_binding b on b.inventory_object_id=o.id and b.organization_id=o.organization_id
    join resource r on r.id=b.resource_id and r.organization_id=o.organization_id
    join resource_type t on t.id=r.resource_type_id
    where o.organization_id=$org and o.integration_id=$integrationId and o.object_type='NODE'
    order by o.display_name,o.id limit $limit"""
    .query[(UUID, String, String, UUID, String, Boolean, Boolean, String, Option[String])].to[List]
    .map(_.map { case (node, externalId, name, resource, resourceName, nodeActive, resourceActive, typeCode,
      fleetName) =>
      val blocker =
        if (fleetName.nonEmpty) Some("REMNAWAVE_FLEET_NODE_ALREADY_MEMBER")
        else if (!nodeActive) Some("REMNAWAVE_FLEET_NODE_INACTIVE")
        else if (!resourceActive || typeCode != "NODE") Some("REMNAWAVE_FLEET_RESOURCE_UNAVAILABLE")
        else None
      FleetCandidate(node, externalId, name, resource, resourceName, blocker.isEmpty, blocker, fleetName)
    })

  def storedEvidence(org: UUID, membershipId: UUID,
    revision: RemnawaveFleetRevision): ConnectionIO[Option[FleetStoredEvidence]] = {
    storedEvidenceBatch(org, List(membershipId), revision).map(_.get(membershipId))
  }

  def storedEvidenceBatch(org: UUID, membershipIds: List[UUID],
    revision: RemnawaveFleetRevision): ConnectionIO[Map[UUID, FleetStoredEvidence]] = {
    val content = revision.content
    sql"""
      select m.id,m.organization_id,m.fleet_id,m.integration_id,m.inventory_node_id,m.resource_id,m.version,
        m.created_by,m.created_at,m.removed_at,
        (select b.resource_id from integration_resource_binding b where b.organization_id=m.organization_id
          and b.integration_id=m.integration_id and b.inventory_object_id=m.inventory_node_id),
        r.is_active, o.summary::text, o.is_active, o.last_seen_at,
        (select cp.summary->>'configSha256' from integration_inventory_object cp
          where cp.organization_id=m.organization_id and cp.integration_id=m.integration_id
            and cp.id=${content.inventoryConfigProfileId} and cp.is_active),
        exists(select 1 from integration_inventory_object cp where cp.organization_id=m.organization_id
          and cp.integration_id=m.integration_id and cp.id=${content.inventoryConfigProfileId}
          and cp.object_type='CONFIG_PROFILE' and cp.is_active),
        exists(select 1 from server_profile sp join server_profile_revision sr
            on sr.profile_id=sp.id and sr.organization_id=sp.organization_id
          where sp.organization_id=m.organization_id and sp.id=${content.serverProfileId}
            and not sp.archived and sr.id=${content.serverProfileRevisionId}),
        (select sr.content::text from server_profile_revision sr where sr.organization_id=m.organization_id
          and sr.id=${content.serverProfileRevisionId}),
        sa.id,sa.profile_id,sa.revision_id,sa.revision_number,sa.version,sa.assigned_by_user_id,sa.assigned_at,
        so.id,so.source_connection_id,so.source_updated_at,so.assignment_id,so.assignment_version,
        so.revision_id,so.content::text,so.content_hash,so.observed_at,so.verified_run_id,
        ds.id,ds.desired_state,ds.version,ds.set_by_user_id,ds.created_at,ds.updated_at,
        ds.last_attempt_observation_at,ds.last_action_execution_id,
        exists(select 1 from provisioning_run pr where pr.organization_id=m.organization_id
            and pr.resource_id=m.resource_id and pr.status in ('QUEUED','RUNNING'))
          or exists(select 1 from configuration_deployment cd where cd.organization_id=m.organization_id
            and cd.resource_id=m.resource_id and cd.state in ('QUEUED','RUNNING'))
          or exists(select 1 from operation_execution oe where oe.organization_id=m.organization_id
            and oe.resource_id=m.resource_id and oe.status='RUNNING')
          or exists(select 1 from remnawave_node_onboarding ob where ob.organization_id=m.organization_id
            and ob.resource_id=m.resource_id and ob.state in ('QUEUED','RUNNING'))
          or exists(select 1 from integration_config_deployment icd where icd.organization_id=m.organization_id
            and icd.integration_id=m.integration_id and icd.inventory_object_id=m.inventory_node_id
            and icd.status in ('QUEUED','RUNNING'))
      from remnawave_fleet_membership m
      join integration i on i.id=m.integration_id and i.organization_id=m.organization_id and i.deleted_at is null
      join integration_inventory_object o on o.id=m.inventory_node_id and o.organization_id=m.organization_id
      join resource r on r.id=m.resource_id and r.organization_id=m.organization_id
      left join server_profile_assignment sa on sa.organization_id=m.organization_id and sa.resource_id=m.resource_id
      left join server_profile_observation so on so.organization_id=m.organization_id and so.resource_id=m.resource_id
      left join integration_desired_state ds on ds.organization_id=m.organization_id
        and ds.integration_id=m.integration_id and ds.inventory_object_id=m.inventory_node_id
      where m.organization_id=$org and m.id=any(${membershipIds.toArray[UUID]}) and m.fleet_id=${revision.fleetId} and m.removed_at is null"""
      .query[StoredEvidenceRow].to[List].flatMap { rows =>
        provenanceBatch(org, membershipIds).map(proofs => rows.iterator.map(row => row.id -> row.toDomain(proofs.get(row.id))).toMap)
      }
  }

  private case class StoredEvidenceRow(id: UUID, org: UUID, fleet: UUID, integration: UUID, node: UUID,
    resource: UUID, version: Long, createdBy: UUID, createdAt: Instant, removedAt: Option[Instant],
    boundResource: Option[UUID], resourceActive: Boolean, summary: String, nodeActive: Boolean,
    lastSeenAt: Instant, remoteConfigSha: Option[String], configAvailable: Boolean, profileAvailable: Boolean,
    desiredContent: Option[String], assignmentId: Option[UUID], assignmentProfile: Option[UUID],
    assignmentRevision: Option[UUID], assignmentNumber: Option[Int], assignmentVersion: Option[Long],
    assignedBy: Option[UUID], assignedAt: Option[Instant], observationId: Option[UUID],
    observationConnection: Option[UUID], observationSourceAt: Option[Instant],
    observationAssignment: Option[UUID], observationAssignmentVersion: Option[Long],
    observationRevision: Option[UUID], observationContent: Option[String], observationHash: Option[String],
    observedAt: Option[Instant], verifiedRun: Option[UUID], desiredStateId: Option[UUID],
    desiredStateCode: Option[String], desiredStateVersion: Option[Long], desiredStateBy: Option[UUID],
    desiredStateCreated: Option[Instant], desiredStateUpdated: Option[Instant],
    desiredStateAttempt: Option[Instant], desiredStateAction: Option[UUID], busy: Boolean) {

    def toDomain(found: Option[FleetLocalProvenance]): FleetStoredEvidence = FleetStoredEvidence(
      membership = RemnawaveFleetMembership(id, org, fleet, integration, node, resource, version, createdBy,
        createdAt, removedAt),
      bindingPresent = boundResource.nonEmpty,
      bindingResourceId = boundResource,
      resourceActive = resourceActive,
      node = parse(summary).toOption.flatMap(json =>
        serialization.integration.IntegrationSummaryJson.decode(IntegrationObjectType.Node, json).toOption)
        .collect { case value: RemnawaveNodeSummary => value },
      inventoryActive = nodeActive,
      inventoryObservedAt = Some(lastSeenAt),
      remoteConfigSha256 = remoteConfigSha,
      configProfileAvailable = configAvailable,
      serverProfileAvailable = profileAvailable,
      desiredServerContent = desiredContent.flatMap(ServerProfileContent.parse(_).toOption),
      assignment = for {
        value <- assignmentId
        profile <- assignmentProfile
        revision <- assignmentRevision
        number <- assignmentNumber
        assignmentV <- assignmentVersion
        by <- assignedBy
        at <- assignedAt
      } yield ServerProfileAssignment(value, org, resource, profile, revision, number, assignmentV, by, at),
      serverObservation = for {
        value <- observationId
        connection <- observationConnection
        sourceAt <- observationSourceAt
        json <- observationContent.flatMap(parse(_).toOption)
        hash <- observationHash
        at <- observedAt
      } yield ServerProfileObservation(value, org, resource, connection, sourceAt, observationAssignment,
        observationAssignmentVersion, observationRevision, json, hash, at, verifiedRun),
      desiredStateRecord = for {
        value <- desiredStateId
        code <- desiredStateCode
        state <- IntegrationDesiredNodeState.fromCode(code)
        desiredV <- desiredStateVersion
        by <- desiredStateBy
        created <- desiredStateCreated
        updated <- desiredStateUpdated
      } yield IntegrationDesiredState(value, org, integration, node, state, desiredV, by, created, updated,
        desiredStateAttempt, desiredStateAction),
      provenance = found,
      busy = busy)
  }

  /** Stage25C's own record of the installation it created, matched on identity and never on a name
    * or a directory, so one onboarding's proof is never read as another installation's.
    */
  def provenance(org: UUID, integrationId: UUID, resourceId: UUID,
    inventoryNodeId: UUID): ConnectionIO[Option[FleetLocalProvenance]] = sql"""
    select case when ob.input_snapshot->'recovery'->>'action'='RECOVER'
      then (ob.input_snapshot->'recovery'->>'installationOwnerId')::uuid else ob.id end,
      ob.external_node_id, ob.input_snapshot->>'imageReference',
      coalesce(ob.input_snapshot->>'apiGeneration','') <> '' and ob.input_snapshot->>'compatibilityBlocker' is null,
      ob.finished_at
    from remnawave_node_onboarding ob
    join integration_inventory_object o on o.organization_id=ob.organization_id
      and o.integration_id=ob.integration_id and o.external_id=ob.external_node_id::text
    where ob.organization_id=$org and ob.integration_id=$integrationId and ob.resource_id=$resourceId
      and ob.state='SUCCEEDED' and ob.external_node_id is not null and o.id=$inventoryNodeId
    order by ob.finished_at desc nulls last, ob.id desc limit 1"""
    .query[(UUID, UUID, Option[String], Boolean, Option[Instant])].option.map(_.flatMap {
      case (id, externalId, Some(image), ready, Some(at)) if image.nonEmpty =>
        Some(FleetLocalProvenance(id, externalId, image, ready, at))
      case _ => None
    })

  private def provenanceBatch(org: UUID, ids: List[UUID]): ConnectionIO[Map[UUID, FleetLocalProvenance]] =
    sql"""select distinct on (m.id) m.id,
      case when ob.input_snapshot->'recovery'->>'action'='RECOVER'
        then (ob.input_snapshot->'recovery'->>'installationOwnerId')::uuid else ob.id end,
      ob.external_node_id,ob.input_snapshot->>'imageReference',
      coalesce(ob.input_snapshot->>'apiGeneration','') <> '' and ob.input_snapshot->>'compatibilityBlocker' is null,
      ob.finished_at
      from remnawave_fleet_membership m
      join integration_inventory_object o on o.organization_id=m.organization_id and o.id=m.inventory_node_id
      join remnawave_node_onboarding ob on ob.organization_id=m.organization_id and ob.integration_id=m.integration_id
        and ob.resource_id=m.resource_id and o.integration_id=ob.integration_id and o.external_id=ob.external_node_id::text
      where m.organization_id=$org and m.id=any(${ids.toArray[UUID]}) and m.removed_at is null
        and ob.state='SUCCEEDED' and ob.external_node_id is not null
      order by m.id,ob.finished_at desc nulls last,ob.id desc"""
      .query[(UUID,UUID,UUID,Option[String],Boolean,Option[Instant])].to[List].map(_.flatMap {
        case (member,id,external,Some(image),ready,Some(at)) if image.nonEmpty =>
          Some(member -> FleetLocalProvenance(id,external,image,ready,at))
        case _ => None
      }.toMap)

  def serverProfileName(org: UUID, profileId: UUID): ConnectionIO[Option[String]] =
    sql"select name from server_profile where organization_id=$org and id=$profileId".query[String].option

  def configurationProfileName(org: UUID, profileId: UUID): ConnectionIO[Option[String]] =
    sql"select name from configuration_profile where organization_id=$org and id=$profileId".query[String].option

  def lastSuccessfulSyncAt(org: UUID, integrationId: UUID): ConnectionIO[Option[Instant]] =
    sql"""select max(finished_at) from integration_sync_session
      where organization_id=$org and integration_id=$integrationId and status='COMPLETED'"""
      .query[Option[Instant]].unique
}

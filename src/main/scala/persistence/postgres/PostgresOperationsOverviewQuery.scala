package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.connection.ConnectionScope
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

import PostgresOperationsOverviewQuery._

/** The operations overview as two bounded statements over the tables that own the state.
  *
  * The summary is one aggregate row; attention is at most `limit` rows plus their total. Neither
  * reads a row per resource into the JVM, and neither statement locks anything.
  *
  * Resource state is read from the JSON the NODE and CONTAINER codecs persist (`status.online`,
  * `status.state`). Those field names are a stored contract the codecs keep unchanged; the
  * integration tests write resources through the production codecs, so a rename fails them.
  */
final class PostgresOperationsOverviewQuery extends OperationsOverviewQuery[ConnectionIO] {

  override def summary(
    organizationId: UUID,
    scope: ConnectionScope,
    operationsSince: Instant
  ): ConnectionIO[OperationsOverviewSummary] =
    (fr"with" ++ scopedResources(organizationId, scope) ++ fr"," ++
      latestOperations(organizationId, operationsSince) ++ fr"," ++
      latestFinishedSyncs(organizationId, scope) ++
      fr"""
      select
        count(*) filter (where r.type_code = 'NODE' and r.is_active),
        count(*) filter (where r.type_code = 'NODE' and r.is_active and r.status ->> 'online' = 'true'),
        count(*) filter (where r.type_code = 'NODE' and r.is_active and r.status ->> 'online' = 'false'),
        count(*) filter (where r.type_code = 'CONTAINER' and r.is_active),
        count(*) filter (where r.type_code = 'CONTAINER' and r.is_active
          and lower(r.status ->> 'state') = 'running'),
        count(*) filter (where r.type_code = 'CONTAINER' and r.is_active
          and lower(r.status ->> 'state') in ('exited', 'dead')),
        (select count(*) from latest_sync),
        (select count(*) from latest_sync where status = 'COMPLETED'),
        (select count(*) from latest_sync where status = 'FAILED'),
        (select count(*) from latest_sync where status is null),
        (select count(*) from incident i join scoped_resource ir on ir.id = i.resource_id
          where i.organization_id = $organizationId and i.status = 'OPEN'),
        (select count(*) from incident i join scoped_resource ir on ir.id = i.resource_id
          where i.organization_id = $organizationId and i.status = 'OPEN' and i.reason = 'THRESHOLD'),
        (select count(*) from incident i join scoped_resource ir on ir.id = i.resource_id
          where i.organization_id = $organizationId and i.status = 'OPEN' and i.reason = 'NO_DATA'),
        (select count(*) from latest_operation where status = 'FAILED' and resource_active),
        (select count(*) from latest_operation where status = 'UNKNOWN')
      from scoped_resource r
    """).query[SummaryRow].unique.map(_.toSummary)

  override def attention(
    organizationId: UUID,
    scope: ConnectionScope,
    operationsSince: Instant,
    limit: Int
  ): ConnectionIO[AttentionPage] =
    (fr"with" ++ scopedResources(organizationId, scope) ++ fr"," ++
      latestOperations(organizationId, operationsSince) ++ fr"," ++
      latestFinishedSyncs(organizationId, scope) ++
      fr"""
      , item as (
        select ${AttentionKind.Incident.code} as kind, ${AttentionKind.Incident.rank} as priority,
          i.opened_at as occurred_at, i.id as subject_id,
          r.id as resource_id, r.name as resource_name, r.type_code, r.environment_id,
          null::uuid as connection_id, null::varchar as connection_name,
          i.reason as detail_code, mr.metric_code as metric_code,
          null::varchar as error_code, null::varchar as error_message
        from incident i
        join scoped_resource r on r.id = i.resource_id
        join monitor_rule mr on mr.id = i.monitor_rule_id and mr.organization_id = i.organization_id
        where i.organization_id = $organizationId and i.status = 'OPEN'

        union all

        select
          case when o.status = 'UNKNOWN' then ${AttentionKind.OperationUnknown.code}
            else ${AttentionKind.OperationFailed.code} end,
          case when o.status = 'UNKNOWN' then ${AttentionKind.OperationUnknown.rank}
            else ${AttentionKind.OperationFailed.rank} end,
          o.finished_at, o.id,
          r.id, r.name, r.type_code, r.environment_id,
          null::uuid, null::varchar,
          o.operation, null::varchar, o.error_code, o.error_message
        from latest_operation o
        join scoped_resource r on r.id = o.resource_id
        where o.status = 'UNKNOWN' or (o.status = 'FAILED' and o.resource_active)

        union all

        select ${AttentionKind.NodeOffline.code}, ${AttentionKind.NodeOffline.rank},
          r.updated_at, r.id,
          r.id, r.name, r.type_code, r.environment_id,
          null::uuid, null::varchar,
          null::varchar, null::varchar, null::varchar, null::varchar
        from scoped_resource r
        where r.type_code = 'NODE' and r.is_active and r.status ->> 'online' = 'false'

        union all

        select ${AttentionKind.SyncFailed.code}, ${AttentionKind.SyncFailed.rank},
          s.finished_at, s.session_id,
          null::uuid, null::varchar, null::varchar, null::uuid,
          s.connection_id, s.connection_name,
          null::varchar, null::varchar, s.error_code, s.error_message
        from latest_sync s
        where s.status = 'FAILED'
      )
      select kind, priority, occurred_at, subject_id,
        resource_id, resource_name, type_code, environment_id,
        connection_id, connection_name, detail_code, metric_code, error_code, error_message,
        count(*) over ()
      from item
      order by priority, occurred_at desc, subject_id desc
      limit $limit
    """).query[AttentionRow].to[List].flatMap { rows =>
      rows.traverse(_.toItem.liftTo[ConnectionIO])
        .map(items => AttentionPage(items, rows.headOption.fold(0)(_.total)))
    }

  /** Every resource of the scope, active or not: incidents and operations stay facts about a
    * resource after inventory deactivated it, exactly as the incident and operation pages show.
    */
  private def scopedResources(organizationId: UUID, scope: ConnectionScope): Fragment = {
    val environmentFilter = scope match {
      case ConnectionScope.Organization => fr""
      case ConnectionScope.Project(projectId) =>
        fr"""and r.environment_id in (
          select e.id from environment e
          where e.organization_id = $organizationId and e.project_id = $projectId)"""
      case ConnectionScope.Environment(_, environmentId) => fr"and r.environment_id = $environmentId"
    }

    fr"""
      scoped_resource as (
        select r.id, r.name, r.environment_id, r.is_active, r.status, r.updated_at,
          rt.code as type_code
        from resource r
        join resource_type rt on rt.id = r.resource_type_id
        where r.organization_id = $organizationId
    """ ++ environmentFilter ++ fr")"
  }

  /** The newest operation of each resource inside the horizon. A newer attempt — running or
    * succeeded — replaces an older failure, so one resource contributes at most one row.
    *
    * Consumers apply one policy to it: a FAILED operation is a current problem only while its
    * resource is active — once inventory deactivated the container there is nothing left to act
    * on. An UNKNOWN one stays a problem either way: whether the command changed the host is still
    * unknown and needs a person to check.
    */
  private def latestOperations(organizationId: UUID, since: Instant): Fragment =
    fr"""
      latest_operation as (
        select distinct on (oe.resource_id)
          oe.id, oe.resource_id, oe.operation, oe.status, oe.finished_at,
          oe.error_code, oe.error_message, sr.is_active as resource_active
        from operation_execution oe
        join scoped_resource sr on sr.id = oe.resource_id
        where oe.organization_id = $organizationId and oe.started_at >= $since
        order by oe.resource_id, oe.started_at desc, oe.id desc
      )
    """

  /** Each active connection of the scope with the outcome of its latest finished session.
    *
    * A running session does not hide the last known outcome: a connection that keeps failing is
    * still failing while the next attempt runs. A connection is part of a project or an
    * environment when it is attached to it or below it.
    */
  private def latestFinishedSyncs(organizationId: UUID, scope: ConnectionScope): Fragment = {
    val connectionFilter = scope match {
      case ConnectionScope.Organization => fr""
      case ConnectionScope.Project(projectId) => fr"and c.project_id = $projectId"
      case ConnectionScope.Environment(_, environmentId) => fr"and c.environment_id = $environmentId"
    }

    fr"""
      latest_sync as (
        select c.id as connection_id, c.name as connection_name,
          s.id as session_id, s.status, s.finished_at, s.error_code, s.error_message
        from connection c
        left join lateral (
          select ss.id, ss.status, ss.finished_at, ss.error_code, ss.error_message
          from sync_session ss
          where ss.organization_id = c.organization_id and ss.connection_id = c.id
            and ss.status <> 'RUNNING'
          order by ss.started_at desc, ss.id desc
          limit 1
        ) s on true
        where c.organization_id = $organizationId and c.is_active
    """ ++ connectionFilter ++ fr")"
  }
}

object PostgresOperationsOverviewQuery {

  private[postgres] final case class SummaryRow(
    nodes: Int,
    nodesOnline: Int,
    nodesOffline: Int,
    containers: Int,
    containersRunning: Int,
    containersStopped: Int,
    connections: Int,
    connectionsHealthy: Int,
    connectionsFailing: Int,
    connectionsNeverSynced: Int,
    incidentsOpen: Int,
    incidentsThreshold: Int,
    incidentsNoData: Int,
    operationsFailed: Int,
    operationsUnknown: Int
  ) {
    def toSummary: OperationsOverviewSummary =
      OperationsOverviewSummary(
        NodeSummary(nodes, nodesOnline, nodesOffline),
        ContainerSummary(containers, containersRunning, containersStopped),
        ConnectionSummary(connections, connectionsHealthy, connectionsFailing, connectionsNeverSynced),
        IncidentSummary(incidentsOpen, incidentsThreshold, incidentsNoData),
        OperationSummary(operationsFailed, operationsUnknown)
      )
  }

  private[postgres] final case class AttentionRow(
    kind: String,
    priority: Int,
    occurredAt: Instant,
    subjectId: UUID,
    resourceId: Option[UUID],
    resourceName: Option[String],
    resourceTypeCode: Option[String],
    environmentId: Option[UUID],
    connectionId: Option[UUID],
    connectionName: Option[String],
    detailCode: Option[String],
    metricCode: Option[String],
    errorCode: Option[String],
    errorMessage: Option[String],
    total: Int
  ) {
    def toItem: Either[IllegalArgumentException, AttentionItem] =
      AttentionKind.fromCode(kind).flatMap {
        case AttentionKind.Incident =>
          (resource, detailCode, metricCode).tupled
            .map { case (target, reason, metric) => IncidentAttention(subjectId, occurredAt, reason, metric, target) }
            .toRight(incomplete)
        case operation @ (AttentionKind.OperationUnknown | AttentionKind.OperationFailed) =>
          (resource, detailCode).tupled
            .map { case (target, code) =>
              OperationAttention(operation, subjectId, occurredAt, code, errorCode, errorMessage, target)
            }
            .toRight(incomplete)
        case AttentionKind.NodeOffline =>
          resource.map(NodeOfflineAttention(occurredAt, _)).toRight(incomplete)
        case AttentionKind.SyncFailed =>
          (connectionId, connectionName).tupled
            .map { case (id, name) =>
              SyncFailureAttention(subjectId, occurredAt, errorCode, errorMessage, OverviewConnectionView(id, name))
            }
            .toRight(incomplete)
      }

    private def resource: Option[OverviewResourceView] =
      (resourceId, resourceName, resourceTypeCode, environmentId).tupled.map {
        case (id, name, typeCode, environment) => OverviewResourceView(id, name, typeCode, environment)
      }

    private def incomplete: IllegalArgumentException =
      new IllegalArgumentException(s"Attention row '$kind' $subjectId is incomplete")
  }
}

package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.integration._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.util.UUID

final class PostgresRemnawaveOnboardingQuery extends RemnawaveOnboardingQuery[ConnectionIO] {
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

package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  ConfigurationPromotionCandidate,
  ConfigurationPromotionRepository,
  ConfigurationPromotionSelection,
  ConfigurationPromotionWrite
}
import cats.syntax.all._
import domain.configuration.ConfigurationVariableValue
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import PostgresConfigurationAssignmentRepository.{AssignmentRow, assignmentColumns}

import java.time.Instant
import java.util.UUID

/** Bulk promotion is one short transaction: rows are locked in UUID order, so two promotions over
  * overlapping selections never deadlock, and either every row moves exactly once or none does.
  */
final class PostgresConfigurationPromotionRepository extends ConfigurationPromotionRepository[ConnectionIO] {

  override def load(organizationId: UUID, ids: List[UUID]): ConnectionIO[List[ConfigurationPromotionCandidate]] =
    if (ids.isEmpty) List.empty[ConfigurationPromotionCandidate].pure[ConnectionIO]
    else {
      val array = ids.distinct.toArray[UUID]
      for {
        rows <- (fr"select" ++ assignmentColumns ++ fr""", r.name
            from configuration_assignment a
            join resource r on r.id = a.resource_id and r.organization_id = a.organization_id
            where a.organization_id = $organizationId and a.id = any($array)""")
          .query[(AssignmentRow, String)].to[List]
        values <- sql"""select assignment_id, name, value from configuration_assignment_value
            where organization_id = $organizationId and assignment_id = any($array) order by assignment_id, name"""
          .query[(UUID, String, String)].to[List]
      } yield {
        val grouped = values.groupMap(_._1)(row => ConfigurationVariableValue(row._2, row._3))
        rows.map { case (row, name) => ConfigurationPromotionCandidate(row.toDomain, name, grouped.getOrElse(row.id, Nil)) }
      }
    }

  override def promote(organizationId: UUID, profileId: UUID, revisionNumber: Int,
                       selections: List[ConfigurationPromotionSelection],
                       at: Instant): ConnectionIO[ConfigurationPromotionWrite] = {
    val ids = selections.map(_.assignmentId).distinct.toArray[UUID]
    val expected = selections.map(selection => selection.assignmentId -> selection.expectedVersion).toMap
    sql"""select id, version, profile_id, removed_at is null from configuration_assignment
        where organization_id = $organizationId and id = any($ids) order by id for update"""
      .query[(UUID, Int, UUID, Boolean)].to[List].flatMap { locked =>
        val valid = locked.size == expected.size && locked.forall { case (id, version, profile, active) =>
          active && profile == profileId && expected.get(id).contains(version)
        }
        if (!valid) (ConfigurationPromotionWrite.Conflict: ConfigurationPromotionWrite).pure[ConnectionIO]
        else sql"""update configuration_assignment
            set profile_revision_number = $revisionNumber, version = version + 1, updated_at = $at
            where organization_id = $organizationId and id = any($ids) and removed_at is null
            returning id, version"""
          .query[(UUID, Int)].to[List].flatMap { updated =>
            if (updated.size == expected.size)
              (ConfigurationPromotionWrite.Promoted(updated.sortBy(_._1.toString)): ConfigurationPromotionWrite).pure[ConnectionIO]
            else new IllegalStateException("Locked promotion rows changed").raiseError[ConnectionIO, ConfigurationPromotionWrite]
          }
      }
  }
}

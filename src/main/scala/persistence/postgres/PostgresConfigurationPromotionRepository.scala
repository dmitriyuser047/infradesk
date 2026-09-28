package ru.bitec.app.ops
package persistence.postgres

import application.port.{ConfigurationPromotionRepository, ConfigurationPromotionSelection}
import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresConfigurationPromotionRepository extends ConfigurationPromotionRepository[ConnectionIO] {
  override def promote(organizationId: UUID, profileId: UUID, revisionNumber: Int,
                       selections: List[ConfigurationPromotionSelection], at: Instant): ConnectionIO[Boolean] = {
    val ordered = selections.sortBy(_.assignmentId.toString)
    ordered.traverse { selection =>
      sql"""select version, profile_id, removed_at is null from configuration_assignment
        where organization_id = $organizationId and id = ${selection.assignmentId} for update"""
        .query[(Int, UUID, Boolean)].option.map(_.exists { case (version, profile, active) =>
          version == selection.expectedVersion && profile == profileId && active
        })
    }.flatMap { valid =>
      if (!valid.forall(identity)) false.pure[ConnectionIO]
      else ordered.traverse_ { selection =>
        sql"""update configuration_assignment set profile_revision_number = $revisionNumber,
          version = version + 1, updated_at = $at
          where organization_id = $organizationId and id = ${selection.assignmentId}
          and version = ${selection.expectedVersion}""".update.run.flatMap { count =>
          if (count == 1) ().pure[ConnectionIO]
          else new IllegalStateException("Locked promotion row changed").raiseError[ConnectionIO, Unit]
        }
      }.as(true)
    }
  }
}

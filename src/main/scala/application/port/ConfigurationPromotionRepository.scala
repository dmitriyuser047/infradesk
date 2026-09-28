package ru.bitec.app.ops
package application.port

import java.time.Instant
import java.util.UUID

final case class ConfigurationPromotionSelection(assignmentId: UUID, expectedVersion: Int)

/** Locks selected assignment rows in UUID order and changes every row or none in one transaction. */
trait ConfigurationPromotionRepository[F[_]] {
  def promote(organizationId: UUID, profileId: UUID, revisionNumber: Int,
              selections: List[ConfigurationPromotionSelection], at: Instant): F[Boolean]
}

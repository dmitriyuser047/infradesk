package ru.bitec.app.ops
package application.port

import domain.configuration.{ConfigurationAssignment, ConfigurationVariableValue}

import java.time.Instant
import java.util.UUID

final case class ConfigurationPromotionSelection(assignmentId: UUID, expectedVersion: Int)

/** A selected assignment as promotion sees it: the row, its resource's name and its explicit values. */
final case class ConfigurationPromotionCandidate(
  assignment: ConfigurationAssignment,
  resourceName: String,
  values: List[ConfigurationVariableValue]
)

sealed trait ConfigurationPromotionWrite
object ConfigurationPromotionWrite {
  /** Every selected assignment moved; each with its new version. */
  final case class Promoted(versions: List[(UUID, Int)]) extends ConfigurationPromotionWrite
  /** At least one assignment was missing, removed, of another profile or at another version: nothing moved. */
  case object Conflict extends ConfigurationPromotionWrite
}

trait ConfigurationPromotionRepository[F[_]] {
  /** The selected assignments of this organization with their values, in two statements. */
  def load(organizationId: UUID, ids: List[UUID]): F[List[ConfigurationPromotionCandidate]]

  /** Locks every selected row in UUID order, checks each one, and moves all of them or none. */
  def promote(organizationId: UUID, profileId: UUID, revisionNumber: Int,
              selections: List[ConfigurationPromotionSelection], at: Instant): F[ConfigurationPromotionWrite]
}

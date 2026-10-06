package ru.bitec.app.ops
package domain.integration

/** Fresh local ownership evidence, independent of Panel presence and inventory health. */
sealed abstract class LocalInstallationState(val code: String, val repairable: Boolean = false)
object LocalInstallationState {
  case object Absent extends LocalInstallationState("ABSENT", true)
  case object OwnedComplete extends LocalInstallationState("OWNED_COMPLETE", true)
  case object OwnedPartial extends LocalInstallationState("OWNED_PARTIAL", true)
  case object OwnedDamaged extends LocalInstallationState("OWNED_DAMAGED", true)
  case object Foreign extends LocalInstallationState("FOREIGN")
  case object PortConflict extends LocalInstallationState("PORT_CONFLICT")
  case object Unknown extends LocalInstallationState("UNKNOWN")
  lazy val all: List[LocalInstallationState] = List(Absent, OwnedComplete, OwnedPartial, OwnedDamaged, Foreign, PortConflict, Unknown)
  def fromCode(code: String): LocalInstallationState = all.find(_.code == code).getOrElse(Unknown)
}

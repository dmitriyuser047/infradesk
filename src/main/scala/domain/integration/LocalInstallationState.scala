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

/** Closed, content-free reasons: never store SSH output or credential material. */
sealed abstract class LocalInstallationDiagnosis(val code: String)
object LocalInstallationDiagnosis {
  case object SshUnavailable extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_SSH_UNAVAILABLE")
  case object ObservationTimeout extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_OBSERVATION_TIMEOUT")
  case object OutputTruncated extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_OUTPUT_TRUNCATED")
  case object ComposeUnreadable extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_COMPOSE_UNREADABLE")
  case object OwnerUnproven extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_OWNER_UNPROVEN")
  case object ContainerStateUnknown extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_CONTAINER_STATE_UNKNOWN")
  case object FirewallStateUnknown extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_FIREWALL_STATE_UNKNOWN")
  case object PortStateUnknown extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_PORT_STATE_UNKNOWN")
  case object StateUnknown extends LocalInstallationDiagnosis("REMNAWAVE_LOCAL_INSTALLATION_STATE_UNKNOWN")
  val all: List[LocalInstallationDiagnosis] = List(SshUnavailable,ObservationTimeout,OutputTruncated,ComposeUnreadable,
    OwnerUnproven,ContainerStateUnknown,FirewallStateUnknown,PortStateUnknown,StateUnknown)
  def fromCode(code: String): Option[LocalInstallationDiagnosis] = all.find(_.code==code)
}
final case class LocalInstallationObservation(state: LocalInstallationState, diagnosis: Option[LocalInstallationDiagnosis] = None) {
  require(state!=LocalInstallationState.Unknown || diagnosis.nonEmpty)
  def remediation: String = state match {
    case LocalInstallationState.Foreign | LocalInstallationState.PortConflict => "MANUAL_ONLY"
    case LocalInstallationState.Unknown => "DIAGNOSIS_ONLY"
    case LocalInstallationState.Absent => "RECREATE"
    case _ => "CONTROLLED_RETIREMENT"
  }
  def blocker: Option[String] = state match {
    case LocalInstallationState.Unknown => diagnosis.map(_.code)
    case LocalInstallationState.Foreign => Some("PROVISIONING_NODE_INSTALLATION_UNMANAGED")
    case LocalInstallationState.PortConflict => Some("PROVISIONING_NODE_PORT_OCCUPIED")
    case _ => None
  }
}
object LocalInstallationObservation {
  def fromState(state: LocalInstallationState): LocalInstallationObservation = LocalInstallationObservation(state,
    Option.when(state==LocalInstallationState.Unknown)(LocalInstallationDiagnosis.StateUnknown))
  def unknown(reason: LocalInstallationDiagnosis): LocalInstallationObservation =
    LocalInstallationObservation(LocalInstallationState.Unknown,Some(reason))
}

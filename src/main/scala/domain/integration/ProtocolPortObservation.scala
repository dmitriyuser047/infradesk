package ru.bitec.app.ops
package domain.integration

/** Public, read-only evidence. Process identifiers and command output never leave the adapter. */
sealed abstract class ProtocolPortState(val code: String)
object ProtocolPortState {
  case object Free extends ProtocolPortState("FREE")
  case object OwnedExpected extends ProtocolPortState("OWNED_EXPECTED")
  case object ForeignListener extends ProtocolPortState("FOREIGN_LISTENER")
  case object FirewallConflict extends ProtocolPortState("FIREWALL_CONFLICT")
  case object ObservationUnknown extends ProtocolPortState("OBSERVATION_UNKNOWN")
  val all = List(Free, OwnedExpected, ForeignListener, FirewallConflict, ObservationUnknown)
}
final case class ProtocolPortObservation(port: Int, transport: String, state: ProtocolPortState) {
  require(port >= 1 && port <= 65535 && Set("tcp", "udp")(transport))
  def blocker: Option[String] = state match {
    case ProtocolPortState.Free | ProtocolPortState.OwnedExpected => None
    case ProtocolPortState.ForeignListener => Some("REMNAWAVE_PROTOCOL_PORT_OCCUPIED")
    case ProtocolPortState.FirewallConflict => Some("REMNAWAVE_CLIENT_FIREWALL_CONFLICT")
    case ProtocolPortState.ObservationUnknown => Some("REMNAWAVE_PROTOCOL_PORT_OBSERVATION_UNKNOWN")
  }
  def json: io.circe.Json = io.circe.Json.obj("port" -> io.circe.Json.fromInt(port),
    "transport" -> io.circe.Json.fromString(transport), "state" -> io.circe.Json.fromString(state.code))
}
object ProtocolPortObservation {
  def blockers(protocol: RemnawaveProtocol, observed: List[ProtocolPortObservation]): List[String] = {
    val expected=if(protocol.transport=="UDP") List("udp") else List("tcp","udp")
    if(observed.map(_.transport).sorted!=expected.sorted || observed.exists(_.port!=protocol.port))
      List("REMNAWAVE_PROTOCOL_PORT_OBSERVATION_UNKNOWN")
    else observed.flatMap(_.blocker).distinct
  }
}

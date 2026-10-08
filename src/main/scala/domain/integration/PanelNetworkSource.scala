package ru.bitec.app.ops
package domain.integration

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import io.circe.Json

sealed abstract class PanelSourceMode(val code: String)
sealed abstract class PanelConnectivityProblem(val code: String)
object PanelConnectivityProblem {
  case object ManualOnly extends PanelConnectivityProblem("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY")
}
object PanelSourceMode {
  case object Auto extends PanelSourceMode("AUTO")
  case object Manual extends PanelSourceMode("MANUAL")
  def fromCode(code: String): Option[PanelSourceMode] = List(Auto, Manual).find(_.code == code)
}

/** API endpoint evidence is a candidate, never proof of the Panel's outbound identity. */
final case class PanelSourceEvidence(mode: PanelSourceMode, sources: List[String], method: String,
  confidence: String, endpointFingerprint: String) {
  require(Set("DNS_BASE_URL", "MANAGED_PANEL_RESOURCE", "MANUAL")(method))
  require(Set("AUTO_CANDIDATE", "MANUAL", "UNRESOLVED")(confidence))
  require(endpointFingerprint.matches("[0-9a-f]{64}"))
  require(if(confidence == "UNRESOLVED") sources.isEmpty else
    OnboardingInput.canonicalCidrs(sources).contains(sources))
  require(mode != PanelSourceMode.Auto || sources.forall(s => s.endsWith("/32") || s.endsWith("/128")))
  require((mode == PanelSourceMode.Manual) == (method == "MANUAL"))
  require(confidence == "UNRESOLVED" || (mode == PanelSourceMode.Manual) == (confidence == "MANUAL"))
  def resolved: Boolean = sources.nonEmpty
}
object PanelSourceEvidence {
  def fingerprint(endpoint: IntegrationBaseUrl): String = MessageDigest.getInstance("SHA-256")
    .digest(endpoint.value.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  def encode(e: PanelSourceEvidence): Json = Json.obj("mode" -> Json.fromString(e.mode.code),
    "sources" -> Json.arr(e.sources.map(Json.fromString): _*), "method" -> Json.fromString(e.method),
    "confidence" -> Json.fromString(e.confidence), "endpointFingerprint" -> Json.fromString(e.endpointFingerprint))
  def decode(j: Json): PanelSourceEvidence = {
    require(j.asObject.exists(_.keys.toSet == Set("mode","sources","method","confidence","endpointFingerprint")))
    val c = j.hcursor
    PanelSourceEvidence(PanelSourceMode.fromCode(c.get[String]("mode").toOption.get).get,
      c.get[List[String]]("sources").toOption.get, c.get[String]("method").toOption.get,
      c.get[String]("confidence").toOption.get, c.get[String]("endpointFingerprint").toOption.get)
  }
}

/** Durable, sanitized proof collected at the connectivity boundary; no stderr or credentials. */
final case class PanelConnectivityFinding(panelSources: List[String], sourceEvidence: String, connected: Boolean) {
  require(OnboardingInput.canonicalCidrs(panelSources).contains(panelSources))
  require(Set("AUTO_CANDIDATE", "AUTO_OBSERVED", "MANUAL", "LEGACY_MANUAL")(sourceEvidence))
}
object PanelConnectivityFinding {
  def storage(f: PanelConnectivityFinding): Json = Json.obj("panelSources" -> Json.arr(f.panelSources.map(Json.fromString): _*),
    "sourceEvidence" -> Json.fromString(f.sourceEvidence), "connected" -> Json.fromBoolean(f.connected))
  def decode(j: Json): PanelConnectivityFinding = {
    require(j.asObject.exists(_.keys.toSet == Set("panelSources","sourceEvidence","connected")))
    val c=j.hcursor
    PanelConnectivityFinding(c.get[List[String]]("panelSources").toOption.get,c.get[String]("sourceEvidence").toOption.get,
      c.get[Boolean]("connected").toOption.get)
  }
  def encode(f: PanelConnectivityFinding): Json = Json.obj("component" -> Json.fromString("PANEL_CONNECTIVITY"),
    "localNode" -> Json.fromString("HEALTHY"), "panelNode" -> Json.fromString(if(f.connected) "CONNECTED" else "DISCONNECTED"),
    "firewall" -> Json.fromString("CONFIGURED"), "panelSources" -> Json.arr(f.panelSources.map(Json.fromString): _*),
    "sourceEvidence" -> Json.fromString(if(f.connected) "CONFIRMED" else f.sourceEvidence))
}

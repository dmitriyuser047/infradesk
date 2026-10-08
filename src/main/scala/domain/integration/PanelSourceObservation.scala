package ru.bitec.app.ops
package domain.integration

import io.circe.Json

sealed abstract class PanelSourceObservationStatus(val code: String)
object PanelSourceObservationStatus {
  case object Observed extends PanelSourceObservationStatus("AUTO_OBSERVED")
  case object NotRequired extends PanelSourceObservationStatus("NOT_REQUIRED")
  case object NoTraffic extends PanelSourceObservationStatus("NO_TRAFFIC")
  case object Ambiguous extends PanelSourceObservationStatus("AMBIGUOUS")
  case object Unavailable extends PanelSourceObservationStatus("UNAVAILABLE")
  val all=List(Observed,NotRequired,NoTraffic,Ambiguous,Unavailable)
}

/** A packet source is a candidate. Only the exact enabled Panel node can confirm it. */
final case class PanelSourceObservation(status: PanelSourceObservationStatus, sources: List[String]) {
  require(if(status==PanelSourceObservationStatus.Observed) sources.size==1 &&
    OnboardingInput.canonicalCidrs(sources).contains(sources) &&
    sources.forall(s => s.endsWith("/32") || s.endsWith("/128")) else sources.isEmpty)
}
object PanelSourceObservation {
  def encode(value: PanelSourceObservation): Json = Json.obj("status" -> Json.fromString(value.status.code),
    "sources" -> Json.arr(value.sources.map(Json.fromString): _*))
  def decode(json: Json): PanelSourceObservation = {
    require(json.asObject.exists(_.keys.toSet==Set("status","sources")))
    PanelSourceObservation(PanelSourceObservationStatus.all.find(_.code==json.hcursor.get[String]("status").toOption.get).get,
      json.hcursor.get[List[String]]("sources").toOption.get)
  }
}

sealed abstract class PanelConnectivityCompletion(val code: String)
object PanelConnectivityCompletion {
  case object Promoted extends PanelConnectivityCompletion("PROMOTED")
  case object RolledBack extends PanelConnectivityCompletion("ROLLED_BACK")
  val all=List(Promoted,RolledBack)
}

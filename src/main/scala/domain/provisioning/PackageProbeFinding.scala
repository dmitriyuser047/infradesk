package ru.bitec.app.ops
package domain.provisioning

import io.circe.Json

/** Closed facts about reviewed package names; arbitrary command output is never retained. */
final case class PackageProbeFinding(name: String, observedState: Option[String], classification: String, suggestedAction: String) {
  require(name.matches("[a-z0-9][a-z0-9+.-]{0,127}"))
  require(observedState.forall(_.matches("[uihrp][ncHUFWti][ R]")))
  require(Set("BROKEN","UNKNOWN")(classification))
  require(suggestedAction == (if(classification=="BROKEN") "REPAIR" else "MANUAL"))
  def json: Json = Json.obj("name" -> Json.fromString(name),"observedState" -> observedState.fold(Json.Null)(Json.fromString),
    "classification" -> Json.fromString(classification),"suggestedAction" -> Json.fromString(suggestedAction))
}
object PackageProbeFinding {
  def decode(j: Json): Option[PackageProbeFinding] = {
    val c=j.hcursor
    if(!j.asObject.exists(_.keys.toSet==Set("name","observedState","classification","suggestedAction"))) None
    else (for {
      name <- c.get[String]("name").toOption
      state <- c.get[Option[String]]("observedState").toOption
      classification <- c.get[String]("classification").toOption
      action <- c.get[String]("suggestedAction").toOption
      result <- scala.util.Try(PackageProbeFinding(name,state,classification,action)).toOption
    } yield result)
  }
  def fromObservation(j: Json): List[PackageProbeFinding] =
    j.hcursor.downField("packages").get[List[Json]]("findings").getOrElse(Nil).flatMap(decode)
}

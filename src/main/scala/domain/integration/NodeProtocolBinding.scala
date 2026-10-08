package ru.bitec.app.ops
package domain.integration

import java.util.UUID
import io.circe.Json

/** Durable receipt of a reviewed profile write. Contains no configuration or certificate material. */
final case class NodeProtocolBinding(profileId: UUID, inboundIds: List[UUID], configSha256: String) {
  require(profileId != new UUID(0,0) && inboundIds.nonEmpty && inboundIds.size <= 256 && inboundIds.distinct == inboundIds)
  require(configSha256.matches("[0-9a-f]{64}"))
}
object NodeProtocolBinding {
  def encode(b: NodeProtocolBinding): Json = Json.obj("profileId" -> Json.fromString(b.profileId.toString),
    "inboundIds" -> Json.arr(b.inboundIds.map(id => Json.fromString(id.toString)): _*),
    "configSha256" -> Json.fromString(b.configSha256))
  def decode(j: Json): NodeProtocolBinding = {
    require(j.asObject.exists(_.keys.toSet == Set("profileId", "inboundIds", "configSha256")))
    val c=j.hcursor
    NodeProtocolBinding(UUID.fromString(c.get[String]("profileId").toOption.get),
      c.get[List[String]]("inboundIds").toOption.get.map(UUID.fromString),c.get[String]("configSha256").toOption.get)
  }
}

sealed trait ProtocolProfileOutcome
object ProtocolProfileOutcome {
  final case class Confirmed(binding: NodeProtocolBinding) extends ProtocolProfileOutcome
  final case class Rejected(code: String) extends ProtocolProfileOutcome
  final case class Unknown(code: String) extends ProtocolProfileOutcome
}

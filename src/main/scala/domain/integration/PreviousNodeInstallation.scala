package ru.bitec.app.ops
package domain.integration

import java.util.UUID
import io.circe.Json

/** Immutable retirement parameters, separate from the new desired input. */
final case class PreviousNodeInstallation(nodeName: String, address: String, nodePort: Int,
  protocol: Option[RemnawaveProtocol], certificateId: Option[UUID], inventoryObjectId: Option[UUID] = None) {
  require(nodeName.nonEmpty && nodeName.length<=30 && !nodeName.exists(_.isControl))
  require(OnboardingInput.validAddress(address) && nodePort>=1 && nodePort<=65535)
  require(protocol.forall(p => RemnawaveProtocol.validate(p,nodePort).isRight))
}
object PreviousNodeInstallation {
  def fromRun(r: RemnawaveNodeOnboardingRun): PreviousNodeInstallation = {
    val input=r.snapshot.input
    val own=PreviousNodeInstallation(input.nodeName,input.address,input.nodePort,input.protocol,input.certificateId)
    if(r.externalNodeId.isEmpty) r.snapshot.recovery.flatMap(_.previousInstallation).getOrElse(own) else own
  }
  def encode(v: PreviousNodeInstallation): Json = Json.obj(
    "nodeName" -> Json.fromString(v.nodeName),"address" -> Json.fromString(v.address),"nodePort" -> Json.fromInt(v.nodePort),
    "protocol" -> v.protocol.fold(Json.Null)(RemnawaveProtocol.encode),
    "certificateId" -> v.certificateId.fold(Json.Null)(id => Json.fromString(id.toString)),
    "inventoryObjectId" -> v.inventoryObjectId.fold(Json.Null)(id => Json.fromString(id.toString)))
  def decode(json: Json): PreviousNodeInstallation = {
    require(json.asObject.exists(_.keys.toSet==Set("nodeName","address","nodePort","protocol","certificateId","inventoryObjectId")))
    val c=json.hcursor
    val port=c.get[Int]("nodePort").fold(_ => throw new IllegalArgumentException("Invalid previous installation"),identity)
    def text(key: String)=c.get[String](key).fold(_ => throw new IllegalArgumentException("Invalid previous installation"),identity)
    def id(key: String)=c.get[Option[String]](key).fold(_ => throw new IllegalArgumentException("Invalid previous installation"),identity).map(UUID.fromString)
    PreviousNodeInstallation(text("nodeName"),text("address"),port,
      c.downField("protocol").focus.filterNot(_.isNull).map(j => RemnawaveProtocol.decode(j,port)
        .fold(_ => throw new IllegalArgumentException("Invalid previous installation"),identity)),id("certificateId"),id("inventoryObjectId"))
  }
}

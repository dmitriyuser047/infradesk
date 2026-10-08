package ru.bitec.app.ops
package domain.integration

import io.circe.Json
import scala.util.Try

sealed abstract class NodeAddressMode(val code: String)
object NodeAddressMode {
  case object PublicIp extends NodeAddressMode("PUBLIC_IP")
  case object Domain extends NodeAddressMode("DOMAIN")
  // Historical snapshots retain their original address decision.
  case object Legacy extends NodeAddressMode("LEGACY")
  def fromCode(code: String): Option[NodeAddressMode] = List(PublicIp,Domain,Legacy).find(_.code==code)
}
object NodeAddress {
  def literal(value: String): Option[java.net.InetAddress] = {
    if(value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
      val octets=value.split("\\.").map(_.toInt)
      Option.when(octets.forall(n => n>=0 && n<=255))(java.net.InetAddress.getByAddress(octets.map(_.toByte)))
    } else if(value.contains(":") && value.matches("[0-9a-fA-F:]+")) Try(java.net.InetAddress.getByName(value)).toOption
    else None
  }
  def publicLiteral(value: String): Option[String] = literal(value).filter { a =>
    val b=a.getAddress.map(_ & 255)
    !a.isAnyLocalAddress && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isMulticastAddress &&
      (if(b.length==4) b(0)!=0 && b(0)<224 && !(b(0)==100 && b(1)>=64 && b(1)<=127) &&
        !(b(0)==192 && b(1)==0 && (b(2)==0 || b(2)==2)) && !(b(0)==198 && (b(1)==18 || b(1)==19 || b(1)==51 && b(2)==100)) &&
        !(b(0)==203 && b(1)==0 && b(2)==113)
      else (b(0) & 0xe0)==0x20 && !(b.take(4).toList==List(0x20,0x01,0x0d,0xb8)))
  }.map(_.getHostAddress)
}
final case class NodeAddressEvidence(mode: NodeAddressMode,address: String,publicAddresses: List[String]) {
  require(mode!=NodeAddressMode.Legacy && publicAddresses.nonEmpty && publicAddresses.size<=32 &&
    publicAddresses==publicAddresses.distinct.sorted && publicAddresses.forall(v => NodeAddress.publicLiteral(v).contains(v)))
  require(if(mode==NodeAddressMode.PublicIp) publicAddresses.contains(address)
    else OnboardingInput.validAddress(address) && NodeAddress.literal(address).isEmpty)
}
object NodeAddressEvidence {
  def encode(e: NodeAddressEvidence): Json = Json.obj("mode"->Json.fromString(e.mode.code),"address"->Json.fromString(e.address),
    "publicAddresses"->Json.fromValues(e.publicAddresses.map(Json.fromString)))
  def decode(j: Json): NodeAddressEvidence = {
    require(j.asObject.exists(_.keys.toSet==Set("mode","address","publicAddresses")))
    val c=j.hcursor
    NodeAddressEvidence(NodeAddressMode.fromCode(c.get[String]("mode").toOption.get).get,
      c.get[String]("address").toOption.get,c.get[List[String]]("publicAddresses").toOption.get)
  }
}

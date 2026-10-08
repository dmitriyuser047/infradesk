package ru.bitec.app.ops
package domain.integration

import java.net.IDN
import java.util.UUID
import scala.util.Try
import io.circe.Json

/** Public protocol intent. Certificate material and client credentials are never part of a plan. */
sealed trait RemnawaveProtocol {
  def port: Int
  def transport: String
}
object RemnawaveProtocol {
  final case class Hysteria2(port: Int, serverName: String) extends RemnawaveProtocol {
    val transport = "UDP"
  }
  final case class Shadowsocks(port: Int, method: String) extends RemnawaveProtocol {
    val transport = "TCP_UDP"
  }
  val shadowsocksMethods: Set[String] = Set("chacha20-ietf-poly1305", "aes-128-gcm", "aes-256-gcm")
  val presetVersion = 1
  def encode(value: RemnawaveProtocol): Json = {
    val base = Json.obj("version" -> Json.fromInt(presetVersion), "port" -> Json.fromInt(value.port))
    base.deepMerge(value match {
      case Hysteria2(_, name) => Json.obj("kind" -> Json.fromString("HYSTERIA2"), "serverName" -> Json.fromString(name))
      case Shadowsocks(_, method) => Json.obj("kind" -> Json.fromString("SHADOWSOCKS"), "method" -> Json.fromString(method))
    })
  }
  def decode(json: Json, managementPort: Int): Either[String, RemnawaveProtocol] = {
    val c = json.hcursor
    for {
      version <- c.get[Int]("version").left.map(_ => "REMNAWAVE_PROTOCOL_INVALID")
      _ <- Either.cond(version == presetVersion, (), "REMNAWAVE_PROTOCOL_VERSION_UNSUPPORTED")
      kind <- c.get[String]("kind").left.map(_ => "REMNAWAVE_PROTOCOL_INVALID")
      port <- c.get[Int]("port").left.map(_ => "REMNAWAVE_PROTOCOL_INVALID")
      value <- kind match {
        case "HYSTERIA2" if json.asObject.exists(_.keys.toSet == Set("version", "kind", "port", "serverName")) =>
          c.get[String]("serverName").left.map(_ => "REMNAWAVE_PROTOCOL_INVALID").map(Hysteria2(port, _))
        case "SHADOWSOCKS" if json.asObject.exists(_.keys.toSet == Set("version", "kind", "port", "method")) =>
          c.get[String]("method").left.map(_ => "REMNAWAVE_PROTOCOL_INVALID").map(Shadowsocks(port, _))
        case _ => Left("REMNAWAVE_PROTOCOL_INVALID")
      }
      validated <- validate(value, managementPort)
    } yield validated
  }

  def validate(value: RemnawaveProtocol, managementPort: Int): Either[String, RemnawaveProtocol] = {
    if (value.port < 1 || value.port > 65535 || value.port == managementPort)
      Left("REMNAWAVE_PROTOCOL_PORT_INVALID")
    else value match {
      case h: Hysteria2 if domain(h.serverName).contains(h.serverName) => Right(h)
      case _: Hysteria2 => Left("REMNAWAVE_PROTOCOL_TLS_DOMAIN_INVALID")
      case s: Shadowsocks if shadowsocksMethods(s.method) => Right(s)
      case _: Shadowsocks => Left("REMNAWAVE_PROTOCOL_METHOD_UNSUPPORTED")
    }
  }

  def domain(value: String): Option[String] = Try(IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES)
    .toLowerCase(java.util.Locale.ROOT)).toOption.filter(v => v == value && v.length <= 253 &&
    v.contains(".") && !v.matches("[0-9.]+") && v.split("\\.", -1).forall(label =>
      label.nonEmpty && label.length <= 63 && label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")))

  /** Full UUID, including tenant, prevents collisions in Panel's globally unique inbound tags. */
  def inboundTag(organizationId: UUID, resourceId: UUID, value: RemnawaveProtocol): String = {
    val identity = UUID.nameUUIDFromBytes((organizationId.toString + ":" + resourceId.toString)
      .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString.replace("-", "")
    (value match { case _: Hysteria2 => "HY2_"; case _: Shadowsocks => "SS_" }) + identity
  }
}

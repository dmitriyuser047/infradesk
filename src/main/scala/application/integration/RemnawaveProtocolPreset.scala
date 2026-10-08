package ru.bitec.app.ops
package application.integration

import domain.integration.RemnawaveProtocol
import domain.integration.RemnawaveProtocol.{Hysteria2, Shadowsocks}
import io.circe.Json

/** The reviewed Xray template is rendered on the server; UI only supplies typed public intent. */
object RemnawaveProtocolPreset {
  private def text(s: String) = Json.fromString(s)
  private def number(n: Int) = Json.fromInt(n)
  private def array(values: Json*) = Json.arr(values: _*)
  val certificateDirectory = "/var/lib/remnawave/configs/xray/ssl"

  def render(protocol: RemnawaveProtocol, tag: String): Json = {
    val inbound = protocol match {
      case Hysteria2(port, domain) => Json.obj(
        "tag" -> text(tag), "port" -> number(port), "listen" -> text("0.0.0.0"),
        "protocol" -> text("hysteria"),
        "settings" -> Json.obj("clients" -> array(), "version" -> number(2)),
        "streamSettings" -> Json.obj("network" -> text("hysteria"), "security" -> text("tls"),
          "finalmask" -> Json.obj("quicParams" -> Json.obj(
            "debug" -> Json.False, "bbrProfile" -> text("standard"), "congestion" -> text("bbr"),
            "maxIdleTimeout" -> number(30), "keepAlivePeriod" -> number(10),
            "maxStreamReceiveWindow" -> number(4194304), "disablePathMTUDiscovery" -> Json.False,
            "initStreamReceiveWindow" -> number(2097152), "maxConnectionReceiveWindow" -> number(16777216),
            "initConnectionReceiveWindow" -> number(8388608))),
          "tlsSettings" -> Json.obj("alpn" -> array(text("h3")), "serverName" -> text(domain),
            "certificates" -> array(Json.obj(
              "keyFile" -> text(s"$certificateDirectory/$domain.key"),
              "certificateFile" -> text(s"$certificateDirectory/$domain.pem")))),
          "hysteriaSettings" -> Json.obj("version" -> number(2))))
      case Shadowsocks(port, method) => Json.obj("tag" -> text(tag), "port" -> number(port),
        "listen" -> text("0.0.0.0"), "protocol" -> text("shadowsocks"),
        "settings" -> Json.obj("method" -> text(method), "clients" -> array(), "network" -> text("tcp,udp")),
        "sniffing" -> Json.obj("enabled" -> Json.True, "destOverride" -> array(text("http"), text("tls"), text("quic"))))
    }
    Json.obj("log" -> Json.obj("loglevel" -> text("warning")), "inbounds" -> array(inbound),
      "outbounds" -> array(Json.obj("tag" -> text("DIRECT"), "protocol" -> text("freedom")),
        Json.obj("tag" -> text("BLOCK"), "protocol" -> text("blackhole"))),
      "routing" -> Json.obj("rules" -> array(
        Json.obj("ip" -> array(text("geoip:private")), "outboundTag" -> text("BLOCK")),
        Json.obj("domain" -> array(text("geosite:private")), "outboundTag" -> text("BLOCK")),
        Json.obj("protocol" -> array(text("bittorrent")), "outboundTag" -> text("BLOCK")))))
  }

  def decode(json: Json, managementPort: Int): Either[String, RemnawaveProtocol] =
    RemnawaveProtocol.decode(json, managementPort)
}

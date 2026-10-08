package ru.bitec.app.ops
package application.integration

import domain.integration.RemnawaveProtocol
import domain.integration.RemnawaveProtocol.{Hysteria2, Shadowsocks}
import io.circe.Json
import io.circe.parser.parse
import java.util.UUID
import munit.FunSuite

final class RemnawaveProtocolPresetSpec extends FunSuite {
  test("Hysteria2 reproduces reviewed QUIC parameters, TLS and safe routing with integral numbers") {
    val result = RemnawaveProtocolPreset.render(Hysteria2(443, "example.org"), "HY2_test")
    val inbound = result.hcursor.downField("inbounds").downArray
    assertEquals(inbound.get[String]("protocol"), Right("hysteria"))
    assertEquals(inbound.get[Int]("port"), Right(443))
    val params = inbound.downField("streamSettings").downField("finalmask").downField("quicParams")
    List("maxIdleTimeout" -> 30, "keepAlivePeriod" -> 10, "maxStreamReceiveWindow" -> 4194304,
      "initStreamReceiveWindow" -> 2097152, "maxConnectionReceiveWindow" -> 16777216,
      "initConnectionReceiveWindow" -> 8388608).foreach { case (key, value) =>
      assertEquals(params.get[Int](key), Right(value))
      assertEquals(params.downField(key).focus.get.noSpaces, value.toString)
    }
    assertEquals(params.get[String]("congestion"), Right("bbr"))
    val tls = inbound.downField("streamSettings").downField("tlsSettings")
    assertEquals(tls.get[String]("serverName"), Right("example.org"))
    assertEquals(tls.get[List[String]]("alpn"), Right(List("h3")))
    assertEquals(tls.downField("certificates").downArray.get[String]("keyFile"),
      Right("/var/lib/remnawave/configs/xray/ssl/example.org.key"))
    assert(!result.noSpaces.contains("velesoracle"))
    assertEquals(result.hcursor.downField("routing").get[List[Json]]("rules").toOption.get.size, 3)
  }

  test("Shadowsocks uses AEAD with empty Panel-managed clients and both transports") {
    val config = RemnawaveProtocolPreset.render(Shadowsocks(1234, "chacha20-ietf-poly1305"), "SS_test")
    val inbound = config.hcursor.downField("inbounds").downArray
    assertEquals(inbound.downField("settings").get[String]("network"), Right("tcp,udp"))
    assertEquals(inbound.downField("settings").get[List[Json]]("clients"), Right(Nil))
    assert(!inbound.downField("streamSettings").succeeded)
  }

  test("closed input rejects secrets, arbitrary templates, invalid domains and unsupported methods") {
    val valid = parse("""{"version":1,"kind":"HYSTERIA2","port":443,"serverName":"example.org"}""").toOption.get
    assert(RemnawaveProtocolPreset.decode(valid, 2222).isRight)
    assert(RemnawaveProtocolPreset.decode(valid, 443).isLeft)
    List("../example.org", "example.org\n", "127.0.0.1", "*.example.org", "EXAMPLE.org", "a..org").foreach { name =>
      assert(RemnawaveProtocolPreset.decode(valid.mapObject(_.add("serverName", Json.fromString(name))), 2222).isLeft)
    }
    List("privateKey", "config", "clients", "command").foreach { key =>
      assert(RemnawaveProtocolPreset.decode(valid.mapObject(_.add(key, Json.fromString("secret"))), 2222).isLeft)
    }
    assert(RemnawaveProtocol.validate(Shadowsocks(443, "none"), 2222).isLeft)
    assert(RemnawaveProtocol.validate(Shadowsocks(0, "aes-256-gcm"), 2222).isLeft)
    assert(RemnawaveProtocolPreset.decode(valid.mapObject(_.add("version", Json.fromInt(2))), 2222).isLeft)
  }

  test("inbound identity is stable per tenant and target and differs between protocols") {
    val org = UUID.randomUUID(); val resource = UUID.randomUUID()
    val protocol = Hysteria2(443, "example.org")
    val tag = RemnawaveProtocol.inboundTag(org, resource, protocol)
    assertEquals(tag, RemnawaveProtocol.inboundTag(org, resource, protocol.copy(port = 8443)))
    assertNotEquals(tag, RemnawaveProtocol.inboundTag(UUID.randomUUID(), resource, protocol))
    assertNotEquals(tag, RemnawaveProtocol.inboundTag(org, UUID.randomUUID(), protocol))
    assertNotEquals(tag, RemnawaveProtocol.inboundTag(org, resource, Shadowsocks(443, "aes-256-gcm")))
  }
}

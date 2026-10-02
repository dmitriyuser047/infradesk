package ru.bitec.app.ops
package domain.provisioning

import io.circe.Json
import munit.FunSuite
import support.ServerProfileFixtures

final class ServerProfileDiffSpec extends FunSuite {
  private val desired=ServerProfileFixtures.content
  private val actual=ServerProfileFixtures.observed()
  private def change(module: String,key: String,value: Json): Json=actual.deepMerge(Json.obj(module -> Json.obj(key -> value)))
  test("all eight modules use independent observed facts; a desired profile is not observation") {
    assertEquals(ServerProfileObservationCodec.validate(actual),Right(actual))
    assert(ServerProfileDiff.assess(desired,actual).compliant)
    assert(!ServerProfileDiff.assess(desired,desired.json).compliant)
    assertEquals(ServerProfileDiff.assess(desired,actual).modules.size,8)
  }
  test("drift requires persistent ownership and real listeners, not matching live values alone") {
    List(change("network","managedFileHash",Json.Null),change("limits","managedSystemdDropinHash",Json.Null),
      change("caddy","listeningPorts",Json.arr(Json.fromInt(443))),change("site","managed",Json.False),
      change("fail2ban","managedFileHash",Json.Null)).foreach { facts => assert(!ServerProfileDiff.assess(desired,facts).compliant) }
  }
  test("same firewall id with changed port/source or multiple old tuples is drift") {
    val rule=Json.obj("id"->Json.fromString("ssh"),"protocol"->Json.fromString("tcp"),"port"->Json.fromInt(2222),
      "sources"->Json.arr(Json.fromString("ANY")))
    val f=change("firewall","managedRules",Json.arr(actual.hcursor.downField("firewall").get[List[Json]]("managedRules").toOption.get.head,rule))
    val findings=ServerProfileDiff.assess(desired,f).changes.filter(_.module=="firewall")
    assert(findings.exists(_.code=="FIREWALL_RULE_CHANGED"))
    assert(findings.head.before.exists(_.contains("2222")))
  }
  test("disabled modules are outside management even when installed state differs") {
    assert(ServerProfileDiff.assess(ServerProfileFixtures.disabled,Json.obj()).compliant)
    assertEquals(ServerProfileDiff.requiredPackages(ServerProfileFixtures.disabled,Json.obj()),Nil)
    val dependencies=ServerProfileDiff.requiredPackages(ServerProfileFixtures.content.copy(packages=PackageModule(false,List("unrequested"))),Json.obj())
    assert(dependencies.contains("caddy") && dependencies.contains("docker.io") && dependencies.contains("ufw"))
    assert(!dependencies.contains("unrequested"))
  }
  test("observation nullable values reject wrong types, unknown keys and impossible ports") {
    List(change("site","managedContentHash",Json.fromInt(1)),change("caddy","httpsPort",Json.fromString("8080")),
      change("caddy","httpsPort",Json.fromInt(65536)),change("limits","managedNofileSoft",Json.True),
      actual.deepMerge(Json.obj("secret"->Json.fromString("should never persist"))))
      .foreach(f => assert(ServerProfileObservationCodec.validate(f).isLeft,clues(f)))
  }
  test("reviewed hash is stable across semantic array/object ordering and changes with actual drift") {
    val reverse=change("packages","installed",Json.fromValues(actual.hcursor.downField("packages").get[List[String]]("installed").toOption.get.reverse.map(Json.fromString)))
    assertEquals(ServerProfileDiff.hashObservation(actual),ServerProfileDiff.hashObservation(reverse))
    assertNotEquals(ServerProfileDiff.reviewedHash(ServerProfileDiff.assess(desired,actual)),
      ServerProfileDiff.reviewedHash(ServerProfileDiff.assess(desired,change("docker","active",Json.False))))
  }
  test("BBR rendering preserves a explicitly selected qdisc, and HTTPS redirect uses actual reviewed port") {
    val network=desired.network.copy(sysctl=Map("net.core.default_qdisc"->"fq_codel"))
    assertEquals(ServerProfileDiff.effectiveSysctl(network)("net.core.default_qdisc"),"fq_codel")
    assertEquals(ServerProfileDiff.renderNetwork(network).linesIterator.count(_.startsWith("net.core.default_qdisc")),1)
    val caddy=ServerProfileDiff.renderCaddy(desired.caddy)
    assert(caddy.contains("https://node.example.com:8080") && caddy.contains("redir https://node.example.com:8080{uri} 308"))
  }
}

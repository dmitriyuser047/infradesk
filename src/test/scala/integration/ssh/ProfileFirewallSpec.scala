package ru.bitec.app.ops
package integration.ssh

import domain.provisioning.{FirewallModule,FirewallRule,ServerProfileContent,ServerProfileDiff,ServerProfileObservationCodec}
import support.ServerProfileFixtures
import io.circe.Json
import munit.FunSuite
import java.util.UUID

final class ProfileFirewallSpec extends FunSuite {
  private val resource=UUID.randomUUID()
  private val header="Added user rules (see 'ufw status' for running firewall)"
  test("protocol policy ignores demonstrably unrelated unsupported rules and rejects ambiguous or relevant policies") {
    val prefix=s"infradesk:remnawave-client:$resource:${UUID.randomUUID()}:"
    assertEquals(ProfileFirewall.parsePort(s"$header\nufw deny 22/tcp\nufw unsupported to any port 8443 proto tcp",prefix,443,List("tcp","udp")),Right(Nil))
    assertEquals(ProfileFirewall.parsePort(s"$header\nufw deny 443/tcp",prefix,443,List("udp")),Right(Nil))
    assert(ProfileFirewall.parsePort(s"$header\nufw unsupported to any port 443 proto udp",prefix,443,List("udp")).isLeft)
    assert(ProfileFirewall.parsePort(s"$header\nufw deny 440:450/udp",prefix,443,List("udp")).isLeft)
    assert(ProfileFirewall.parsePort(s"$header\nufw deny from any",prefix,443,List("udp")).isLeft)
    val allow=ProfileFirewall.parsePort(s"$header\nufw allow 443/udp",prefix,443,List("udp")).toOption.get
    assertEquals(allow.map(_.owned),List(false))
    assertEquals(allow.map(_.action),List("allow"))
    assertEquals(ProfileFirewall.parsePort(s"$header\nufw reject 443/udp",prefix,443,List("udp")).toOption.get.map(_.action),List("reject"))
    assert(ProfileFirewall.parsePort(s"$header\nufw allow 443/udp comment '${prefix}BAD!suffix'",prefix,443,List("udp")).isLeft)
  }
  test("UFW 0.36.2 empty output with colon header and None parses as no rules") {
    assertEquals(ProfileFirewall.parse(s"$header:\n(None)\n",resource),Right(Nil))
  }
  test("both supported headers with an empty body parse as no rules") {
    List(header,s"$header:").foreach { h =>
      assertEquals(ProfileFirewall.parse(s"$h\n",resource),Right(Nil))
    }
    assertEquals(ProfileFirewall.parse(s"$header\n(None)\n",resource),Right(Nil))
  }
  test("colon header preserves parsing of valid foreign allow rules") {
    assertEquals(ProfileFirewall.parse(s"$header:\nufw allow 22/tcp",resource),
      Right(List(ProfileFirewallRule("allow",FirewallRule("foreign","tcp",22,List("ANY")),false))))
  }
  test("unknown output after either supported header remains unsupported") {
    List(header,s"$header:").foreach { h =>
      assertEquals(ProfileFirewall.parse(s"$h\nsome unexpected data",resource),Left("FIREWALL_RULE_UNSUPPORTED"))
    }
  }
  test("None cannot hide rules or unknown data and requires a known header") {
    List(header,s"$header:").foreach { h =>
      List("(None)\nufw allow 22/tcp","ufw allow 22/tcp\n(None)","(None)\nsome unexpected data","(None)\n(None)").foreach { body =>
        assertEquals(ProfileFirewall.parse(s"$h\n$body",resource),Left("FIREWALL_RULE_UNSUPPORTED"))
      }
    }
    List("(None)",s"$header::\n(None)","").foreach { raw =>
      assertEquals(ProfileFirewall.parse(raw,resource),Left("FIREWALL_RULE_UNSUPPORTED"))
    }
  }
  test("unsupported UFW syntax remains blocked with either supported header") {
    List(header,s"$header:").foreach { h =>
      List("ufw allow out 22/tcp","ufw route allow 443/tcp").foreach { rule =>
        assertEquals(ProfileFirewall.parse(s"$h\n$rule",resource),Left("FIREWALL_RULE_UNSUPPORTED"))
      }
    }
  }
  test("both supported headers preserve exact InfraDesk resource ownership comments") {
    List(header,s"$header:").foreach { h =>
      val raw=s"$h\nufw allow 22/tcp comment 'infradesk:$resource:ssh'\nufw allow 443/tcp comment 'infradesk:${UUID.randomUUID()}:https'"
      assertEquals(ProfileFirewall.parse(raw,resource),Right(List(
        ProfileFirewallRule("allow",FirewallRule("ssh","tcp",22,List("ANY")),true),
        ProfileFirewallRule("allow",FirewallRule("foreign","tcp",443,List("ANY")),false))))
    }
  }
  test("desired IPv4 universal and ANY converge with owned short UFW output without fuzzy comparison") {
    val observed=ProfileFirewall.parse(s"ufw allow 22/tcp comment 'infradesk:$resource:admin-ssh'",resource).toOption.get.head
    List("0.0.0.0/0","ANY","10.0.0.0/8","192.168.1.1/32","::/0").foreach { source =>
      val raw=ServerProfileFixtures.disabled.copy(firewall=FirewallModule(true,List(FirewallRule("admin-ssh","tcp",22,List(source)))))
      val desired=ServerProfileContent.parse(raw.canonical).toOption.get
      val rule=desired.firewall.rules.head
      val compliant=Set("0.0.0.0/0","ANY")(source)
      assertEquals(ProfileFirewall.equivalent(rule,observed.rule),compliant,source)
      val facts=ServerProfileFixtures.observed(desired).deepMerge(Json.obj("firewall" -> Json.obj(
        "managedRules" -> Json.arr(ProfileFirewall.json(observed.rule)))))
      assertEquals(ServerProfileObservationCodec.validate(facts),Right(facts))
      assertEquals(ServerProfileDiff.assess(desired,facts).compliant,compliant,source)
      val foreign=observed.copy(owned=false)
      assertEquals(ProfileFirewall.coveredByForeign(rule,List(foreign)),compliant,source)
    }
    val explicit=ProfileFirewall.parse("ufw allow from 0.0.0.0/0 to any port 22 proto tcp",resource).toOption.get.head
    assertEquals(explicit.rule.sources,List("ANY"))
    val ipv6=ProfileFirewall.parse("ufw allow from ::/0 to any port 22 proto tcp",resource).toOption.get.head
    assertEquals(ipv6.rule.sources,List("0:0:0:0:0:0:0:0/0"))
    assert(!ProfileFirewall.equivalent(explicit.rule,ipv6.rule))
    assert(!ProfileFirewall.equivalent(observed.rule,ipv6.rule))
  }
  test("own comment, foreign equivalent allow and multisource rules remain separate") {
    val raw=s"""Added user rules (see 'ufw status' for running firewall)
      ufw allow from 192.0.2.4/24 to any port 22 proto tcp comment 'infradesk:$resource:ssh'
      ufw allow from 198.51.100.0/24 to any port 22 proto tcp comment 'infradesk:$resource:ssh'
      ufw allow 443/tcp comment 'someone else'
    """
    val rules=ProfileFirewall.parse(raw,resource).toOption.get
    assertEquals(rules.count(_.owned),2)
    val own=ProfileFirewall.aggregate(rules.filter(_.owned).map(_.rule))
    assertEquals(own.head.sources,List("192.0.2.0/24","198.51.100.0/24"))
    assert(ProfileFirewall.coveredByForeign(FirewallRule("https","tcp",443,List("ANY")),rules))
    assert(!rules.last.owned)
  }
  test("unsupported UFW rules and malformed addresses block instead of becoming deletion candidates") {
    List("ufw allow out 22/tcp","ufw allow from example.com to any port 22 proto tcp",
      "ufw allow from 1:::2 to any port 22 proto tcp","ufw route allow 443/tcp").foreach { raw =>
      assert(ProfileFirewall.parse(raw,resource).isLeft,raw)
    }
  }
  test("SSH proof requires literal source coverage for IPv4 and IPv6, never hostname resolution") {
    assert(ServerProfileContent.sourceCovers("192.0.2.0/24","192.0.2.31"))
    assert(!ServerProfileContent.sourceCovers("192.0.2.0/24","198.51.100.31"))
    assert(ServerProfileContent.sourceCovers("2001:db8::/64","2001:db8::f"))
    assert(!ServerProfileContent.sourceCovers("2001:db8::/64","2001:db9::f"))
    assert(!ServerProfileContent.sourceCovers("ANY","hostname.invalid"))
    assert(!ServerProfileContent.sourceCovers("::/0","192.0.2.31"))
  }
  test("foreign deny for actual SSH endpoint prevents safety proof") {
    val rules=ProfileFirewall.parse("ufw deny from 192.0.2.0/24 to any port 2222 proto tcp",resource).toOption.get
    assert(ProfileFirewall.sshBlocked(rules,"192.0.2.3",2222))
    assert(!ProfileFirewall.sshBlocked(rules,"198.51.100.3",2222))
    assert(!ProfileFirewall.sshBlocked(rules,"192.0.2.3",22))
  }
  test("observer handles tabs and integer systemd defaults without inventing facts") {
    assertEquals(SshProfileObserver.normalizeValue("10000\t65535\n"),Some("10000 65535"))
    assertEquals(SshProfileObserver.normalizeLimit("65536:65536"),Some("65536"))
    assertEquals(SshProfileObserver.normalizeLimit("65536"),Some("65536"))
    assertEquals(SshProfileObserver.normalizeLimit("65536:131072"),Some("65536:131072"))
    assertEquals(SshProfileObserver.parseEndpoint("192.0.2.3 49152 198.51.100.1 2222"),Some(("192.0.2.3","198.51.100.1",2222)))
    assertEquals(SshProfileObserver.parseEndpoint("hostname 49152 198.51.100.1 22"),None)
  }
}

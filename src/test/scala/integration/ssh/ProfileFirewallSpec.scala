package ru.bitec.app.ops
package integration.ssh

import domain.provisioning.{FirewallRule,ServerProfileContent}
import munit.FunSuite
import java.util.UUID

final class ProfileFirewallSpec extends FunSuite {
  private val resource=UUID.randomUUID()
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

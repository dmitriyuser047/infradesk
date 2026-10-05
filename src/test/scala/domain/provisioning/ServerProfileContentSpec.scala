package ru.bitec.app.ops
package domain.provisioning

import munit.FunSuite

final class ServerProfileContentSpec extends FunSuite {
  private val minimal = """{"schemaVersion":1,"packages":{"enabled":true,"packages":["curl","jq"]},"network":{"enabled":true,"bbr":true,"sysctl":{"net.ipv4.tcp_congestion_control":"bbr","net.core.somaxconn":"4096"}},"limits":{"enabled":true,"nofileSoft":65536,"nofileHard":65536,"systemdDefaultLimitNofile":65536},"firewall":{"enabled":true,"rules":[{"id":"ssh","protocol":"tcp","port":22,"sources":["192.0.2.2/24"]}]},"fail2ban":{"enabled":true},"docker":{"enabled":true},"caddy":{"enabled":true,"domain":"Node.Example.COM","localHttpsPort":8080,"siteRoot":"/var/www/infradesk/node","compression":"zstd","redirect":"HTTP_TO_HTTPS"},"site":{"enabled":true,"domain":"node.example.com","root":"/var/www/infradesk/node","template":"DEFAULT_PLACEHOLDER","templateVersion":1}}"""

  test("closed typed schema normalizes content before hashing") {
    val parsed = ServerProfileContent.parse(minimal).toOption.get
    assertEquals(parsed.packages.packages, List("curl", "jq"))
    assertEquals(parsed.caddy.domain, Some("node.example.com"))
    assertEquals(parsed.firewall.rules.head.sources, List("192.0.2.0/24"))
    assertEquals(parsed.hash, ServerProfileContent.parse(parsed.canonical).toOption.get.hash)
  }

  test("unknown nested fields, scripts, unsafe paths, and sysctl values are rejected") {
    assert(ServerProfileContent.parse(minimal.replace("\"enabled\":true,\"packages\"", "\"enabled\":true,\"script\":\"x\",\"packages\"")).isLeft)
    assert(ServerProfileContent.parse(minimal.replace("/var/www/infradesk/node", "/tmp/owned")).isLeft)
    assert(ServerProfileContent.parse(minimal.replace("\"4096\"", "\"999999999\"")).isLeft)
  }

  test("IPv6 CIDRs are canonical and malformed numeric forms fail without DNS lookup") {
    def withSource(source: String) = minimal.replace("192.0.2.2/24", source)
    val full = ServerProfileContent.parse(withSource("2001:0db8:0000:0000:0000:0000:0000:0001/64")).toOption.get
    val short = ServerProfileContent.parse(withSource("2001:db8::1/64")).toOption.get
    assertEquals(full.hash, short.hash)
    List("1:::2/64", "1::2::3/64", "1:2:3/64", "1:2:3:4:5:6:7:8:/128", "::%eth0/64", "127.1/24", "example.com/32")
      .foreach(value => assert(ServerProfileContent.parse(withSource(value)).isLeft, value))
  }

  test("bbr cannot be enabled while cubic is explicitly selected") {
    assert(ServerProfileContent.parse(minimal.replace("net.ipv4.tcp_congestion_control\":\"bbr", "net.ipv4.tcp_congestion_control\":\"cubic")).isLeft)
  }

  test("IPv4 universal sources use UFW ANY while IPv6 and narrower networks stay distinct") {
    def content(source: String) = ServerProfileContent.parse(minimal.replace("192.0.2.2/24",source)).toOption.get
    val any=content("ANY")
    List("0.0.0.0/0","192.0.2.2/0").foreach { source =>
      val parsed=content(source)
      assertEquals(parsed.firewall.rules.head.sources,List("ANY"))
      assertEquals(parsed.hash,any.hash)
      assertEquals(ServerProfileContent.parse(parsed.canonical),Right(parsed))
    }
    List("10.0.0.0/8","192.168.1.1/32","0.0.0.0/32","::/0").foreach { source =>
      assertNotEquals(content(source).firewall.rules,any.firewall.rules)
    }
    assertEquals(content("::/0").firewall.rules.head.sources,List("0:0:0:0:0:0:0:0/0"))
    assert(ServerProfileContent.sourceCovers("0.0.0.0/0","192.0.2.31"))
    assert(!ServerProfileContent.sourceCovers("0.0.0.0/0","2001:db8::1"))
    assert(ServerProfileContent.sourceCovers("::/0","2001:db8::1"))
    assert(!ServerProfileContent.sourceCovers("::/0","192.0.2.31"))
    assert(ServerProfileContent.parse(minimal.replace("\"192.0.2.2/24\"","\"ANY\",\"0.0.0.0/0\"")).isLeft)
  }
  test("persisted pre-normalization content hashes are validated before domain normalization") {
    val normalized=ServerProfileContent.parse(minimal.replace("192.0.2.2/24","0.0.0.0/0")).toOption.get
    val legacy=normalized.canonical.replace("\"ANY\"","\"0.0.0.0/0\"")
    val legacyHash=ServerProfileDiff.hashText(legacy)
    assertNotEquals(legacyHash,normalized.hash)
    assertEquals(ServerProfileContent.parsePersisted(legacy,legacyHash),Right(normalized))
    assertEquals(ServerProfileContent.parsePersisted(normalized.canonical,normalized.hash),Right(normalized))
    assert(ServerProfileContent.parsePersisted(legacy,"0"*64).isLeft)
    assert(ServerProfileContent.parsePersisted(legacy.replace("0.0.0.0/0","10.0.0.0/8"),legacyHash).isLeft)
    assert(ServerProfileContent.parsePersisted(normalized.canonical,legacyHash).isLeft)
  }
}

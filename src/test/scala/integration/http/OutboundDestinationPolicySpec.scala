package ru.bitec.app.ops
package integration.http

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.xbill.DNS.lookup.LookupSession
import org.xbill.DNS.SimpleResolver

import java.net.{DatagramSocket, InetAddress}
import java.time.Duration
import scala.concurrent.duration._

/** What the product is allowed to connect to on behalf of a configured channel. */
final class OutboundDestinationPolicySpec extends FunSuite {

  private val strict = OutboundDestinationPolicy.resolving(allowPrivateNetworks = false)
  private val permissive = OutboundDestinationPolicy.resolving(allowPrivateNetworks = true)

  private def codeOf(host: String, policy: OutboundDestinationPolicy): Option[String] =
    policy.pin(host).unsafeRunSync().left.toOption.map(_.code)

  test("the deployment's own interfaces are out of reach, however they are written") {
    val refused = List(
      "127.0.0.1", "localhost", "127.1", "0.0.0.0", "::1", "0:0:0:0:0:0:0:1",
      // The metadata endpoint of every major cloud, and the rest of the link-local range.
      "169.254.169.254", "169.254.1.1",
      // Multicast, and an IPv6-mapped loopback that a string check would miss.
      "224.0.0.1", "::ffff:127.0.0.1"
    )

    for (host <- refused) {
      assertEquals(codeOf(host, strict), Some(OutboundDestinationPolicy.Forbidden), clues(host))
      // No deployment gets to allow these: the flag is about private networks, not about the
      // loopback interface, the link-local range or a multicast group.
      assertEquals(codeOf(host, permissive), Some(OutboundDestinationPolicy.Forbidden), clues(host))
    }
  }

  test("a private network is refused by default and reachable only by an explicit decision") {
    val privateHosts = List("10.0.0.1", "192.168.1.10", "172.16.0.1", "fd00::1")

    for (host <- privateHosts) {
      assertEquals(codeOf(host, strict), Some(OutboundDestinationPolicy.Forbidden), clues(host))
      assertEquals(codeOf(host, permissive), None, clues(host))
    }
  }

  test("a public address is reachable and an empty host is refused") {
    assertEquals(codeOf("93.184.216.34", strict), None)
    // An empty host resolves as the loopback interface.
    assertEquals(codeOf("", strict), Some(OutboundDestinationPolicy.Forbidden))
  }

  test("the address pinned is the exact address a caller must connect to") {
    val pinned = strict.pin("93.184.216.34").unsafeRunSync()
    assertEquals(pinned.map(_.getHostAddress), Right("93.184.216.34"))
  }

  test("the asynchronous production resolver obeys its own DNS timeout") {
    // A bound UDP socket that deliberately never reads or answers DNS packets.
    val blackhole = new DatagramSocket(0, InetAddress.getLoopbackAddress)
    try {
      val resolver = new SimpleResolver("127.0.0.1")
      resolver.setPort(blackhole.getLocalPort)
      resolver.setTimeout(Duration.ofMillis(200))
      val lookup = LookupSession.builder().resolver(resolver).clearSearchPath().build()
      val policy = new ResolvingOutboundDestinationPolicy(allowPrivateNetworks = false, lookup)
      val started = System.nanoTime()

      val result = policy.pin("deadline.example.").unsafeRunSync()
      val elapsed = (System.nanoTime() - started).nanos

      assertEquals(result.left.toOption.map(_.code),
        Some(OutboundDestinationPolicy.ResolutionFailed))
      assert(elapsed < 1500.millis, clues(elapsed))
    } finally blackhole.close()
  }
}

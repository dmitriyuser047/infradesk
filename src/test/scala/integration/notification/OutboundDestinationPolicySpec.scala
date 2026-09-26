package ru.bitec.app.ops
package integration.notification

import cats.effect.unsafe.implicits.global
import munit.FunSuite


/** What the product is allowed to connect to on behalf of a configured channel. */
final class OutboundDestinationPolicySpec extends FunSuite {

  private val strict = OutboundDestinationPolicy.resolving(allowPrivateNetworks = false)
  private val permissive = OutboundDestinationPolicy.resolving(allowPrivateNetworks = true)

  test("the deployment's own interfaces are out of reach, however they are written") {
    val refused = List(
      "127.0.0.1", "localhost", "127.1", "0.0.0.0", "::1", "0:0:0:0:0:0:0:1",
      // The metadata endpoint of every major cloud, and the rest of the link-local range.
      "169.254.169.254", "169.254.1.1",
      // Multicast, and an IPv6-mapped loopback that a string check would miss.
      "224.0.0.1", "::ffff:127.0.0.1"
    )

    for (host <- refused) {
      assertEquals(strict.check(host).unsafeRunSync(), Left(OutboundDestinationPolicy.Forbidden),
        clues(host))
      // No deployment gets to allow these: the flag is about private networks, not about the
      // loopback interface, the link-local range or a multicast group.
      assertEquals(permissive.check(host).unsafeRunSync(),
        Left(OutboundDestinationPolicy.Forbidden), clues(host))
    }
  }

  test("a private network is refused by default and reachable only by an explicit decision") {
    val privateHosts = List("10.0.0.1", "192.168.1.10", "172.16.0.1", "fd00::1")

    for (host <- privateHosts) {
      assertEquals(strict.check(host).unsafeRunSync(), Left(OutboundDestinationPolicy.Forbidden),
        clues(host))
      assertEquals(permissive.check(host).unsafeRunSync(), Right(()), clues(host))
    }
  }

  test("a public address is reachable, and a name that does not resolve says so") {
    assertEquals(strict.check("93.184.216.34").unsafeRunSync(), Right(()))
    assertEquals(
      strict.check("no-such-host.invalid").unsafeRunSync(),
      Left(OutboundDestinationPolicy.Unresolvable))
    assertEquals(strict.check("").unsafeRunSync(), Left(OutboundDestinationPolicy.Forbidden))
  }
}

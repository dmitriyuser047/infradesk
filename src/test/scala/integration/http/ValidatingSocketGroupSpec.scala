package ru.bitec.app.ops
package integration.http

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.{Socket, SocketGroup, SocketOption}
import munit.FunSuite

import java.net.InetAddress

/** The mechanism that closes the gap a policy check followed by a separate connect leaves open
  * (DNS rebinding): whatever address the policy resolved and validated is the exact address the
  * underlying socket layer is asked to dial, never the host name again, and nothing is dialed at
  * all when the policy refuses.
  *
  * This is proved architecturally rather than by simulating an actual rebinding attack: the
  * underlying `SocketGroup` is a double that records what it was asked to connect to, so the test
  * shows directly that the value handed to it is the policy's own answer, not the original name.
  * Against the vulnerable shape this replaced — check the name, then let the client resolve and
  * connect to it again — the first assertion below could not hold, because that shape passes the
  * host name through unchanged and never learns what the policy's own resolution returned.
  */
final class ValidatingSocketGroupSpec extends FunSuite {

  private val port = Port.fromInt(443).get

  test("the address a policy validates is the exact address that is dialed") {
    val recording = new RecordingSocketGroup
    val resolved = InetAddress.getByName("93.184.216.34")
    val group = new ValidatingSocketGroup(recording, fixed(Right(resolved)))

    connect(group, "rebinding.example.test")

    // Not the host name the caller asked for: the address the policy itself resolved.
    assertEquals(recording.lastRequested.map(_.host.toString), Some("93.184.216.34"))
    assertEquals(recording.lastRequested.map(_.port), Some(port))
  }

  test("an address literal is still validated, not merely passed through") {
    val recording = new RecordingSocketGroup
    val group = new ValidatingSocketGroup(recording, fixed(Left(OutboundDestinationFailure.Forbidden("X"))))

    val result = connect(group, "127.0.0.1")

    assert(result.isLeft, "a literal address bypassed the policy")
    assertEquals(recording.lastRequested, None)
  }

  test("a forbidden destination is never handed to the socket layer") {
    val recording = new RecordingSocketGroup
    val group = new ValidatingSocketGroup(recording, fixed(Left(OutboundDestinationFailure.Forbidden("X"))))

    val result = connect(group, "internal.example.test")

    assert(result.isLeft, "the socket group connected despite a forbidden destination")
    assertEquals(recording.lastRequested, None)
    assert(result.left.exists(_.isInstanceOf[OutboundDestinationRejected]))
  }

  test("a destination that failed to resolve is never handed to the socket layer either") {
    val recording = new RecordingSocketGroup
    val group = new ValidatingSocketGroup(recording,
      fixed(Left(OutboundDestinationFailure.ResolutionFailed("Y"))))

    val result = connect(group, "flaky.example.test")

    assert(result.isLeft)
    assertEquals(recording.lastRequested, None)
    assert(result.left.exists(_.isInstanceOf[OutboundDestinationUnresolvable]))
  }

  private def connect(group: SocketGroup[IO], host: String): Either[Throwable, Unit] =
    group.client(SocketAddress(Host.fromString(host).get, port), Nil).use_.attempt.unsafeRunSync()

  private def fixed(outcome: Either[OutboundDestinationFailure, InetAddress]): OutboundDestinationPolicy =
    (_: String) => IO.pure(outcome)

  /** Records the one address it was asked to connect to, and never actually opens a socket: the
    * point of this double is what it was asked for, not a working connection.
    */
  private final class RecordingSocketGroup extends SocketGroup[IO] {
    @volatile var lastRequested: Option[SocketAddress[Host]] = None

    override def client(to: SocketAddress[Host], options: List[SocketOption]): Resource[IO, Socket[IO]] =
      Resource.eval(IO { lastRequested = Some(to) } *>
        IO.raiseError[Socket[IO]](new RuntimeException("test double: no real socket")))

    override def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
    ): Stream[IO, Socket[IO]] = Stream.empty

    override def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
    ): Resource[IO, (SocketAddress[com.comcast.ip4s.IpAddress], Stream[IO, Socket[IO]])] =
      Resource.eval(IO.raiseError(new RuntimeException("test double: no real server")))
  }
}

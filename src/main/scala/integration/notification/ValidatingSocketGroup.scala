package ru.bitec.app.ops
package integration.notification

import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import fs2.Stream
import fs2.io.net.{Socket, SocketGroup, SocketOption}

/** Wraps a real `SocketGroup` so that every connection this makes is opened to the address this
  * itself validated, and to no other.
  *
  * `SocketGroup.client` takes a `SocketAddress[Host]`, and `Host` is either an already-literal
  * `IpAddress` or a name still to be resolved. Ember builds one from the request's URI and hands
  * it to whichever `SocketGroup` the client was built with; this one resolves the name — or
  * validates the literal address — itself, right here, and passes the delegate a
  * `SocketAddress[IpAddress]` built from exactly what that resolution returned. There is no
  * second lookup afterwards for a different resolution to answer, which is what closes the gap a
  * policy check followed by an ordinary connect leaves open (DNS rebinding): the address that was
  * validated and the address that is dialed are the same value, not two lookups of the same name.
  *
  * TLS is unaffected: Ember derives the request's `Host` header and the TLS server name from the
  * request's own URI, not from the socket address a `SocketGroup` was asked to connect to, so
  * substituting the literal address here changes nothing about what the certificate is checked
  * against.
  */
final class ValidatingSocketGroup(
  delegate: SocketGroup[IO],
  policy: OutboundDestinationPolicy
) extends SocketGroup[IO] {

  override def client(
    to: SocketAddress[Host],
    options: List[SocketOption]
  ): Resource[IO, Socket[IO]] =
    Resource.eval(pin(to.host)).flatMap(ip => delegate.client(SocketAddress(ip, to.port), options))

  private def pin(host: Host): IO[IpAddress] =
    policy.pin(host.toString).flatMap {
      case Right(address) => IO.pure(IpAddress.fromInetAddress(address))
      case Left(OutboundDestinationFailure.Forbidden(code)) =>
        IO.raiseError(new OutboundDestinationRejected(code))
      case Left(OutboundDestinationFailure.ResolutionFailed(code)) =>
        IO.raiseError(new OutboundDestinationUnresolvable(code))
    }

  // Ember's client never calls these: a client-only wrapper still has to satisfy the trait.
  override def server(
    address: Option[Host],
    port: Option[com.comcast.ip4s.Port],
    options: List[SocketOption]
  ): Stream[IO, Socket[IO]] = delegate.server(address, port, options)

  override def serverResource(
    address: Option[Host],
    port: Option[com.comcast.ip4s.Port],
    options: List[SocketOption]
  ): Resource[IO, (SocketAddress[com.comcast.ip4s.IpAddress], Stream[IO, Socket[IO]])] =
    delegate.serverResource(address, port, options)
}

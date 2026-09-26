package ru.bitec.app.ops
package integration.notification

import java.net.{InetAddress, InetSocketAddress, Socket, SocketAddress}
import javax.net.SocketFactory

/** SMTP's destination pinning.
  *
  * Handed to Jakarta Mail as an object property (`mail.smtp.socketFactory` /
  * `mail.smtps.socketFactory`), which the library calls in place of the JDK default whenever it
  * opens a connection — for every security mode, including implicit TLS: Jakarta Mail's own
  * `SocketFetcher` never asks an `SSLSocketFactory` to open the underlying TCP connection itself,
  * only to wrap one that is already open, so pinning has to happen on the plain socket in every
  * mode, and the library's own `mail.<protocol>.ssl.checkserveridentity` handles the TLS
  * handshake and certificate/host name verification on top of it, against the host name the
  * channel is configured with, exactly as it would for any other connection.
  *
  * The instance is the only thing in this product that resolves an SMTP relay's host name: it
  * resolves and validates before anything is dialed, and connects to exactly the address that
  * validation returned, never to the host name again. A relay that is not allowed never gets a
  * socket, and nothing here falls back to an unprotected connection when that happens — Jakarta
  * Mail's own `socketFactory.fallback` would otherwise open one with the JDK default factory,
  * which is why the caller that builds these properties turns that off.
  *
  * `SocketFetcher` does not call any of the host-and-port `createSocket` overloads below: for a
  * plain `SocketFactory` it calls the zero-argument one to obtain an unconnected socket, and
  * connects that socket itself, building the target address from the host name a second time.
  * The pinning therefore lives in the connect, not in `createSocket`: the socket handed back is a
  * `PinningSocket`, whose own `connect` ignores whatever address the caller already resolved and
  * resolves the host name itself, once, through this factory's policy. The host-and-port
  * overloads are implemented the same way, for whatever else may call them directly.
  */
final class PinningSocketFactory(
  policy: OutboundDestinationPolicy,
  connectTimeoutMillis: Int
) extends SocketFactory {

  override def createSocket(): Socket = new PinningSocket(policy)

  override def createSocket(host: String, port: Int): Socket = connectTo(new PinningSocket(policy), host, port)

  override def createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = {
    val socket = new PinningSocket(policy)
    socket.bind(new InetSocketAddress(localHost, localPort))
    connectTo(socket, host, port)
  }

  override def createSocket(host: InetAddress, port: Int): Socket =
    connectTo(new PinningSocket(policy), host.getHostAddress, port)

  override def createSocket(
    address: InetAddress,
    port: Int,
    localAddress: InetAddress,
    localPort: Int
  ): Socket = {
    val socket = new PinningSocket(policy)
    socket.bind(new InetSocketAddress(localAddress, localPort))
    connectTo(socket, address.getHostAddress, port)
  }

  private def connectTo(socket: Socket, host: String, port: Int): Socket = {
    socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis)
    socket
  }
}

/** A socket whose own `connect` decides what is actually dialed.
  *
  * Jakarta Mail builds the `SocketAddress` it passes here itself, as `new
  * InetSocketAddress(host, port)`, which resolves the host name there and then — a second,
  * separate resolution from any check made against that same name earlier, and exactly the race
  * this class exists to close. Trusting the address this way carries would mean trusting
  * whichever answer that second lookup happened to get.
  *
  * `InetSocketAddress.getHostString()` returns the host name Jakarta Mail was given without
  * triggering that resolution again — it is stored on the object, not looked up by the getter —
  * so this class can recover the name and resolve it exactly once, itself, through the policy,
  * and connect to only that address. An endpoint that is not an `InetSocketAddress` at all
  * (nothing in this product produces one, but the method is public) is passed through unchanged
  * rather than guessed at.
  */
private final class PinningSocket(policy: OutboundDestinationPolicy) extends Socket {

  override def connect(endpoint: SocketAddress): Unit = connect(endpoint, 0)

  override def connect(endpoint: SocketAddress, timeout: Int): Unit = endpoint match {
    case address: InetSocketAddress =>
      super.connect(new InetSocketAddress(pin(address.getHostString), address.getPort), timeout)
    case other => super.connect(other, timeout)
  }

  /** Resolves and validates, or throws a bounded, credential-free exception the transport's own
    * classifier recognises: `connect` can only succeed or throw, so the decision has to travel
    * this way.
    */
  private def pin(host: String): InetAddress = policy.pinBlocking(host) match {
    case Right(address) => address
    case Left(OutboundDestinationFailure.Forbidden(code)) => throw new OutboundDestinationRejected(code)
    case Left(OutboundDestinationFailure.ResolutionFailed(code)) =>
      throw new OutboundDestinationUnresolvable(code)
  }
}

package ru.bitec.app.ops
package integration.notification

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, Socket, SocketAddress}
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.SocketFactory
import javax.net.ssl.{HttpsURLConnection, SSLSession, SSLSocketFactory}

/** The sockets a send opens, held so its deadline can close them below Jakarta Mail's locks. */
private[notification] final class SmtpAbort {
  private val sockets = new ConcurrentLinkedQueue[Socket]()
  @volatile private var aborted = false

  def register(socket: Socket): Unit = {
    sockets.add(socket)
    if (aborted) closeQuietly(socket)
  }

  def abort(): Unit = {
    aborted = true
    sockets.forEach(closeQuietly(_))
  }

  private def closeQuietly(socket: Socket): Unit =
    try socket.close()
    catch { case _: Throwable => () }
}

/** A socket factory that can dial only the address already resolved and approved by the policy.
  *
  * Jakarta Mail constructs its own `InetSocketAddress` before calling `Socket.connect`. The SMTP
  * transport therefore gives Jakarta Mail the pinned numeric address as its connection host, and
  * this socket independently ignores every endpoint address it receives. No DNS lookup is left in
  * the blocking Jakarta Mail exchange and no later resolution can replace the approved address.
  */
final class PinningSocketFactory(
  pinnedAddress: InetAddress,
  connectTimeoutMillis: Int,
  abort: SmtpAbort
) extends SocketFactory {

  override def createSocket(): Socket = tracked(new PinningSocket(pinnedAddress))

  override def createSocket(host: String, port: Int): Socket =
    connectTo(tracked(new PinningSocket(pinnedAddress)), port)

  override def createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = {
    val socket = tracked(new PinningSocket(pinnedAddress))
    socket.bind(new InetSocketAddress(localHost, localPort))
    connectTo(socket, port)
  }

  override def createSocket(host: InetAddress, port: Int): Socket =
    connectTo(tracked(new PinningSocket(pinnedAddress)), port)

  override def createSocket(
    address: InetAddress,
    port: Int,
    localAddress: InetAddress,
    localPort: Int
  ): Socket = {
    val socket = tracked(new PinningSocket(pinnedAddress))
    socket.bind(new InetSocketAddress(localAddress, localPort))
    connectTo(socket, port)
  }

  private def tracked(socket: Socket): Socket = { abort.register(socket); socket }

  private def connectTo(socket: Socket, port: Int): Socket = {
    socket.connect(new InetSocketAddress(pinnedAddress, port), connectTimeoutMillis)
    socket
  }
}

private final class PinningSocket(pinnedAddress: InetAddress) extends Socket {
  override def connect(endpoint: SocketAddress): Unit = connect(endpoint, 0)

  override def connect(endpoint: SocketAddress, timeout: Int): Unit = endpoint match {
    case address: InetSocketAddress =>
      super.connect(new InetSocketAddress(pinnedAddress, address.getPort), timeout)
    case other => super.connect(other, timeout)
  }
}

/** Preserves the configured relay name for SNI and certificate checks while the TCP connection is
  * opened with its pinned numeric address. Jakarta Mail passes the numeric connection host into
  * this factory; every overload deliberately substitutes the original configured name only for
  * the TLS layer. The raw socket underneath is still the already-connected pinned socket.
  */
private[notification] final class SmtpTlsSocketFactory(
  configuredHost: String,
  delegate: SSLSocketFactory = SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory]
) extends SSLSocketFactory {

  override def getDefaultCipherSuites: Array[String] = delegate.getDefaultCipherSuites
  override def getSupportedCipherSuites: Array[String] = delegate.getSupportedCipherSuites

  override def createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
    delegate.createSocket(socket, configuredHost, port, autoClose)

  override def createSocket(): Socket = unsupported()

  override def createSocket(host: String, port: Int): Socket = unsupported()

  override def createSocket(
    host: String,
    port: Int,
    localHost: InetAddress,
    localPort: Int
  ): Socket = unsupported()

  override def createSocket(host: InetAddress, port: Int): Socket = unsupported()

  override def createSocket(
    address: InetAddress,
    port: Int,
    localAddress: InetAddress,
    localPort: Int
  ): Socket = unsupported()

  private def unsupported(): Socket =
    throw new IOException("SMTP TLS factory requires an already-connected pinned socket")
}

/** The TLS socket's peer host is already the configured name, so the JDK endpoint check verifies
  * it during the handshake. Angus performs another verifier call with its numeric connection host;
  * this verifier repeats that check against the configured name.
  */
private[notification] final class SmtpHostnameVerifier(configuredHost: String)
  extends javax.net.ssl.HostnameVerifier {
  override def verify(ignoredConnectionHost: String, session: SSLSession): Boolean =
    HttpsURLConnection.getDefaultHostnameVerifier.verify(configuredHost, session)
}

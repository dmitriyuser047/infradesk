package ru.bitec.app.ops
package integration.notification

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, Socket, SocketAddress}
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.SocketFactory
import javax.net.ssl.{SNIHostName, SSLSession, SSLSocket, SSLSocketFactory}
import javax.naming.ldap.LdapName
import java.security.cert.X509Certificate
import scala.jdk.CollectionConverters._

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

/** Socket creation for implicit TLS.
  *
  * Angus gives `ssl.socketFactory` precedence for SMTPS. When that value is an
  * `SSLSocketFactory`, Angus creates an untracked raw `Socket` itself and only asks the factory to
  * wrap it afterwards. This factory deliberately extends plain `SocketFactory` while returning an
  * unconnected `SSLSocket`: Angus therefore obtains the socket from us, we register it before
  * connect, and its `useSSL` path sees that it is already an SSL socket and does not wrap it again.
  */
private[notification] final class PinnedImplicitTlsSocketFactory(
  pinnedAddress: InetAddress,
  configuredHost: String,
  connectTimeoutMillis: Int,
  abort: SmtpAbort,
  delegate: SSLSocketFactory = SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory]
) extends SocketFactory {

  override def createSocket(): Socket = trackedSocket()

  override def createSocket(host: String, port: Int): Socket = connectTo(trackedSocket(), port)

  override def createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = {
    val socket = trackedSocket()
    socket.bind(new InetSocketAddress(localHost, localPort))
    connectTo(socket, port)
  }

  override def createSocket(host: InetAddress, port: Int): Socket =
    connectTo(trackedSocket(), port)

  override def createSocket(
    address: InetAddress,
    port: Int,
    localAddress: InetAddress,
    localPort: Int
  ): Socket = {
    val socket = trackedSocket()
    socket.bind(new InetSocketAddress(localAddress, localPort))
    connectTo(socket, port)
  }

  private def trackedSocket(): SSLSocket = {
    val socket = delegate.createSocket().asInstanceOf[SSLSocket]
    val parameters = socket.getSSLParameters
    try parameters.setServerNames(Collections.singletonList(new SNIHostName(configuredHost)))
    catch { case _: IllegalArgumentException => () } // Address literals do not carry SNI.
    socket.setSSLParameters(parameters)
    abort.register(socket)
    socket
  }

  private def connectTo(socket: Socket, port: Int): Socket = {
    socket.connect(new InetSocketAddress(pinnedAddress, port), connectTimeoutMillis)
    socket
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

/** Checks the certificate against the configured relay name even though Angus connected using the
  * pinned numeric address. Both encrypted modes disable Angus's endpoint check because it would
  * be given that numeric connection address; Angus then invokes this verifier after the handshake.
  */
private[notification] final class SmtpHostnameVerifier(configuredHost: String)
  extends javax.net.ssl.HostnameVerifier {
  override def verify(ignoredConnectionHost: String, session: SSLSession): Boolean =
    try {
      val certificate = session.getPeerCertificates.head.asInstanceOf[X509Certificate]
      val dnsNames = Option(certificate.getSubjectAlternativeNames).toList.flatMap(_.asScala).flatMap {
        entry => Option(entry.get(0)).collect { case kind: Integer if kind == 2 => entry.get(1).toString }
      }
      val names = if (dnsNames.nonEmpty) dnsNames else commonNames(certificate)
      names.exists(matches(configuredHost, _))
    } catch { case _: Throwable => false }

  private def commonNames(certificate: X509Certificate): List[String] =
    new LdapName(certificate.getSubjectX500Principal.getName).getRdns.asScala.collect {
      case rdn if rdn.getType.equalsIgnoreCase("CN") => rdn.getValue.toString
    }.toList

  /** RFC 6125-style DNS matching: a wildcard replaces exactly the whole left-most label. */
  private def matches(host: String, pattern: String): Boolean = {
    val expected = java.net.IDN.toASCII(host).toLowerCase(java.util.Locale.ROOT)
    val actual = java.net.IDN.toASCII(pattern).toLowerCase(java.util.Locale.ROOT)
    if (actual.startsWith("*.")) {
      val suffix = actual.drop(2)
      expected.endsWith("." + suffix) && expected.count(_ == '.') == suffix.count(_ == '.') + 1
    } else expected == actual
  }
}

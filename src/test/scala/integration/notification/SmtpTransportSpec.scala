package ru.bitec.app.ops
package integration.notification

import application.notification.{NotificationEvent, NotificationMessage}
import application.port.NotificationSendResult
import cats.effect.unsafe.implicits.global
import com.icegreen.greenmail.util.{GreenMail, ServerSetup}
import domain.incident.IncidentReason
import domain.notification.{EmailSecurity, NotificationChannelSettings, NotificationEventType}
import munit.FunSuite

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net.{InetAddress, ServerSocket}
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Mail against a real SMTP server, so the three security modes are exercised as protocol
  * rather than as configuration, plus the destination pinning, the overall attempt deadline and
  * the resource safety a lease depends on.
  */
final class SmtpTransportSpec extends FunSuite {

  override val munitTimeout: Duration = 60.seconds

  private val User = "alerts@example.test"
  private val Password = "s3cret-smtp-password"

  private val message = NotificationMessage.of(NotificationEvent(
    eventId = UUID.fromString("c0000000-0000-0000-0000-000000000001"),
    eventType = NotificationEventType.IncidentOpened,
    occurredAt = Instant.parse("2026-09-25T10:00:00Z"),
    organizationId = UUID.fromString("20000000-0000-0000-0000-000000000001"),
    resourceId = UUID.fromString("70000000-0000-0000-0000-000000000001"),
    monitorRuleId = UUID.fromString("90000000-0000-0000-0000-000000000001"),
    incidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001"),
    reason = IncidentReason.ThresholdViolation
  ))

  test("a plain relay receives the message, from and to the addresses the channel holds") {
    withServer(ServerSetup.PROTOCOL_SMTP) { server =>
      val result = send(settings(server.getSmtp.getPort, EmailSecurity.None,
        recipients = List("admin@example.test", "ops@example.test")))

      assertEquals(result, NotificationSendResult.Sent)
      val received = server.getReceivedMessages
      // One message per recipient, as the relay delivers it.
      assertEquals(received.length, 2)
      assertEquals(received.head.getSubject, message.subject)
      assertEquals(received.head.getFrom.head.toString, User)
      assertEquals(received.head.getAllRecipients.map(_.toString).toSet,
        Set("admin@example.test", "ops@example.test"))
      assert(received.head.getContent.toString.contains("Reason: threshold exceeded"))
      // The account password is a credential, not a header.
      assert(!headers(received.head).exists(_.contains(Password)))
    }
  }

  test("a relay whose certificate does not check out is not sent to, in either encrypted mode") {
    // The test server presents a certificate no trust store knows, which is what makes this
    // worth asserting: the send fails, and in particular it does not quietly fall back to a
    // plain connection carrying the account password.
    withServer(ServerSetup.PROTOCOL_SMTP) { server =>
      val result = send(settings(server.getSmtp.getPort, EmailSecurity.StartTls))

      assertNotEquals(result, NotificationSendResult.Sent: NotificationSendResult)
      assertEquals(server.getReceivedMessages.length, 0)
    }

    withServer(ServerSetup.PROTOCOL_SMTPS) { server =>
      val result = send(settings(server.getSmtps.getPort, EmailSecurity.Tls))

      assertEquals(result, NotificationSendResult.RetryableFailure(SmtpTransport.TlsFailure))
      assertEquals(server.getReceivedMessages.length, 0)
    }
  }

  test("a relay that refuses the account is a configuration problem, not a retry") {
    withServer(ServerSetup.PROTOCOL_SMTP) { server =>
      val result = new SmtpTransport(Permissive, 5.seconds)
        .send(settings(server.getSmtp.getPort, EmailSecurity.None), "wrong-password", message)
        .unsafeRunSync()

      assertEquals(result, NotificationSendResult.PermanentFailure(SmtpTransport.AuthFailure))
      assertEquals(server.getReceivedMessages.length, 0)
      // What the relay said about the account does not become the outcome.
      assert(!result.toString.contains("wrong-password"))
      assert(!result.toString.contains(User))
    }
  }

  test("a relay that is not listening is worth trying again") {
    // A port nothing is bound to: the connection is refused rather than answered.
    val result = send(settings(port = 1, EmailSecurity.None))

    assertEquals(result, NotificationSendResult.RetryableFailure(SmtpTransport.TemporaryFailure))
  }

  test("a relay the deployment may not reach is refused before a connection is opened") {
    withServer(ServerSetup.PROTOCOL_SMTP) { server =>
      val transport = new SmtpTransport(
        OutboundDestinationPolicy.resolving(allowPrivateNetworks = true), 5.seconds)

      val result = transport
        .send(settings(server.getSmtp.getPort, EmailSecurity.None), Password, message)
        .unsafeRunSync()

      // The server really is on the loopback interface, which no deployment may reach.
      assertEquals(result,
        NotificationSendResult.PermanentFailure(OutboundDestinationPolicy.Forbidden))
      assertEquals(server.getReceivedMessages.length, 0)
    }
  }

  test("the three modes are three different connections, and two of them verify the relay") {
    val plain = SmtpTransport.properties(settings(25, EmailSecurity.None), 5.seconds, Permissive)
    val startTls =
      SmtpTransport.properties(settings(587, EmailSecurity.StartTls), 5.seconds, Permissive)
    val implicitTls =
      SmtpTransport.properties(settings(465, EmailSecurity.Tls), 5.seconds, Permissive)

    assertEquals(plain.getProperty("mail.smtp.auth"), "true")
    assertEquals(plain.getProperty("mail.smtp.starttls.enable"), null)
    // Required, not enabled: a relay that does not offer the upgrade fails instead of sending
    // the password in the clear.
    assertEquals(startTls.getProperty("mail.smtp.starttls.required"), "true")
    assertEquals(startTls.getProperty("mail.smtp.ssl.checkserveridentity"), "true")
    assertEquals(implicitTls.getProperty("mail.smtps.auth"), "true")
    assertEquals(implicitTls.getProperty("mail.smtps.ssl.checkserveridentity"), "true")
    // Nothing turns verification off, in any mode.
    for (properties <- List(plain, startTls, implicitTls)) {
      val names = properties.stringPropertyNames().asScala.toSet
      assert(!names.exists(_.contains("ssl.trust")), clues(names))
    }
    // The silent fallback to an unprotected plain socket is turned off in every mode: it is the
    // one setting that would otherwise undo the pinning below it.
    assertEquals(plain.getProperty("mail.smtp.socketFactory.fallback"), "false")
    assertEquals(startTls.getProperty("mail.smtp.socketFactory.fallback"), "false")
    assertEquals(implicitTls.getProperty("mail.smtps.socketFactory.fallback"), "false")
  }

  test("the relay dialed is the address a policy pinned, never a fresh resolution of the host name") {
    withServer(ServerSetup.PROTOCOL_SMTP) { server =>
      val pinnedToLoopback: OutboundDestinationPolicy = (_: String) =>
        Right(InetAddress.getByName("127.0.0.1"))

      // A name from a TLD reserved by RFC 2606 to never resolve: if the socket factory dialed
      // this name itself, rather than the address the policy handed it, there would be nothing
      // to connect to at all. The send can only succeed because the address that is actually
      // dialed is the policy's own answer.
      val result = new SmtpTransport(pinnedToLoopback, 5.seconds)
        .send(settings(server.getSmtp.getPort, EmailSecurity.None).copy(host = "unroutable.invalid"),
          Password, message)
        .unsafeRunSync()

      assertEquals(result, NotificationSendResult.Sent)
      assertEquals(server.getReceivedMessages.length, 1)
    }
  }

  test("a relay that never answers stays bounded by the attempt's own deadline, and its socket is not left open") {
    val server = new ScriptedSmtpServer(Nil, greetDelay = 3.seconds)
    val port = server.start()
    try {
      val started = System.nanoTime()
      val result = new SmtpTransport(Permissive, 300.millis)
        .send(settings(port, EmailSecurity.None), Password, message)
        .unsafeRunSync()
      val elapsed = (System.nanoTime() - started).nanos

      assertEquals(result, NotificationSendResult.RetryableFailure(SmtpTransport.Timeout))
      // Bounded by the attempt's own deadline, not by the relay's three-second silence.
      assert(elapsed < 3.seconds, clues(elapsed))
      assert(server.awaitClientClose(5.seconds),
        "the connection was left open past the attempt's own deadline")
    } finally server.stop()
  }

  test("the whole attempt is bounded even when no single operation is slow enough to time out") {
    // Every reply comes back well within the socket read timeout, so a per-operation timeout
    // never fires: a relay answering each step in 120ms is not slow. What is slow is the
    // sequence — greeting, EHLO, then the AUTH exchange — whose sum passes the whole-attempt
    // deadline before authentication even finishes. An implementation that relied on socket
    // timeouts alone would sail through every step and only stop seconds later, at QUIT.
    val perResponse = 400.millis
    val deadline = 600.millis
    // Each reply comes back in 400ms, comfortably under the 600ms socket read timeout, so no
    // single operation ever times out on its own. A full, successful exchange is nine such
    // steps — greeting, EHLO, the four AUTH lines, MAIL FROM, RCPT TO, DATA, the body — well
    // over three seconds; the deadline is passed during the AUTH exchange, long before that.
    val fullSequence = 3.seconds
    val server = new ScriptedSmtpServer(
      List(
        List("250-fake", "250 AUTH LOGIN"),
        List("334 VXNlcm5hbWU6"),
        List("334 UGFzc3dvcmQ6"),
        List("235 authentication successful"),
        List("250 OK"),
        List("250 OK"),
        List("354 end with ."),
        List("250 queued")
      ),
      perResponseDelay = perResponse
    )
    val port = server.start()
    try {
      val started = System.nanoTime()
      val result = new SmtpTransport(Permissive, deadline)
        .send(settings(port, EmailSecurity.None), Password, message)
        .unsafeRunSync()
      val elapsed = (System.nanoTime() - started).nanos
      val commandsAtReturn = server.commandsReceived

      assertEquals(result, NotificationSendResult.RetryableFailure(SmtpTransport.Timeout))
      // Cut off mid-handshake: the message body was never offered, let alone sent. Had the
      // implementation relied on socket timeouts alone, no step being slow enough to trip one,
      // it would have run the whole exchange to completion and reached DATA — this is the assertion
      // that tells a whole-attempt deadline apart from a per-operation one.
      assert(!server.sawData, "the send reached the DATA phase before the deadline")
      // Stopped near the deadline, not after the full exchange would have finished.
      assert(elapsed < fullSequence, clues(elapsed))
      // The deadline actually stopped the attempt: the socket is closed and no further command
      // arrives afterwards, so nothing is still talking to the relay once SMTP_TIMEOUT is returned.
      assert(server.awaitClientClose(5.seconds),
        "the connection was left open past the whole-attempt deadline")
      Thread.sleep(perResponse.toMillis)
      assertEquals(server.commandsReceived, commandsAtReturn,
        "a command reached the relay after the attempt had supposedly timed out")
    } finally server.stop()
  }

  test("the connection is closed even when authentication is refused") {
    val server = new ScriptedSmtpServer(List(
      List("250-fake", "250 AUTH LOGIN"),
      List("334 VXNlcm5hbWU6"),
      List("334 UGFzc3dvcmQ6"),
      List("535 authentication failed")
    ))
    val port = server.start()
    try {
      val result = new SmtpTransport(Permissive, 5.seconds)
        .send(settings(port, EmailSecurity.None), Password, message)
        .unsafeRunSync()

      assertEquals(result, NotificationSendResult.PermanentFailure(SmtpTransport.AuthFailure))
      assert(server.awaitClientClose(5.seconds),
        "the socket was left open after authentication failed")
    } finally server.stop()
  }

  test("the connection is closed after the relay refuses every recipient") {
    val server = new ScriptedSmtpServer(List(
      List("250-fake", "250 AUTH LOGIN"),
      List("334 VXNlcm5hbWU6"),
      List("334 UGFzc3dvcmQ6"),
      List("235 authentication successful"),
      List("250 OK"),
      List("550 no such user")
    ))
    val port = server.start()
    try {
      val result = new SmtpTransport(Permissive, 5.seconds)
        .send(settings(port, EmailSecurity.None), Password, message)
        .unsafeRunSync()

      assert(!result.isInstanceOf[NotificationSendResult.Sent.type], clues(result))
      assert(server.quitReceived || server.awaitClientClose(5.seconds),
        "the connection was left open after every recipient was refused")
    } finally server.stop()
  }

  private def send(settings: NotificationChannelSettings.Email): NotificationSendResult =
    new SmtpTransport(Permissive, 5.seconds).send(settings, Password, message).unsafeRunSync()

  private def settings(
    port: Int,
    security: EmailSecurity,
    recipients: List[String] = List("admin@example.test")
  ): NotificationChannelSettings.Email =
    NotificationChannelSettings.Email("127.0.0.1", port, security, User, User, recipients)

  private def headers(message: jakarta.mail.internet.MimeMessage): List[String] =
    message.getAllHeaderLines.asScala.toList.map(_.toString)

  /** Where the relay may be is checked on its own; here the point is the protocol. */
  private object Permissive extends OutboundDestinationPolicy {
    override def pinBlocking(host: String): Either[OutboundDestinationFailure, InetAddress] =
      Right(InetAddress.getByName(host))
  }

  /** One server per test, on a port the operating system picks. */
  private def withServer(protocol: String)(body: GreenMail => Unit): Unit = {
    val setup = new ServerSetup(0, "127.0.0.1", protocol)
    val server = new GreenMail(setup)
    server.setUser(User, User, Password)
    server.start()
    try body(server) finally server.stop()
  }

  /** A hand-rolled SMTP server that answers with exactly the lines it is told to, in order,
    * whatever the client actually sends: enough to drive a real `Transport` through a chosen
    * outcome (an auth rejection, a refused recipient, a relay that never speaks) without
    * depending on a library that does not expose those failures on demand.
    *
    * What it is for is not the protocol exchange but what happens to the connection afterwards:
    * every scenario below closes over `awaitClientClose`, which only returns once this server has
    * observed the client side of the socket go away — the one thing a resource leak would fail to
    * produce.
    */
  private final class ScriptedSmtpServer(
    script: List[List[String]],
    greetDelay: FiniteDuration = Duration.Zero,
    perResponseDelay: FiniteDuration = Duration.Zero
  ) {
    private val serverSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    @volatile var quitReceived: Boolean = false
    @volatile var commandsReceived: Int = 0
    @volatile var sawData: Boolean = false
    @volatile private var closedByClient: Boolean = false

    // The greeting waits for whichever delay is set: an explicit greeting-only silence, or the
    // per-response delay shared by every reply.
    private val greetWait: FiniteDuration =
      if (greetDelay > Duration.Zero) greetDelay else perResponseDelay

    private val thread = new Thread(() => {
      try {
        val socket = serverSocket.accept()
        try {
          val out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream, "US-ASCII"), true)
          val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "US-ASCII"))
          if (greetWait > Duration.Zero) Thread.sleep(greetWait.toMillis)
          out.print("220 fake smtp ready\r\n")
          out.flush()
          var remaining = script
          var line = in.readLine()
          while (line != null && !line.toUpperCase.startsWith("QUIT")) {
            commandsReceived += 1
            if (line.toUpperCase.startsWith("DATA")) sawData = true
            val (reply, rest) = remaining match {
              case head :: tail => (head, tail)
              case Nil => (List("250 OK"), Nil)
            }
            remaining = rest
            // The reply is delayed, not the read: each individual operation stays under the
            // socket read timeout, but their sum is what the whole-attempt deadline bounds.
            if (perResponseDelay > Duration.Zero) Thread.sleep(perResponseDelay.toMillis)
            reply.foreach { text => out.print(text + "\r\n"); out.flush() }
            line = in.readLine()
          }
          if (line != null && line.toUpperCase.startsWith("QUIT")) {
            quitReceived = true
            out.print("221 bye\r\n")
            out.flush()
          }
        } finally socket.close()
      } catch { case _: Throwable => () } finally closedByClient = true
    }, "scripted-smtp-server")
    thread.setDaemon(true)

    def start(): Int = {
      thread.start()
      serverSocket.getLocalPort
    }

    def awaitClientClose(timeout: FiniteDuration): Boolean = {
      val deadline = System.nanoTime() + timeout.toNanos
      while (!closedByClient && System.nanoTime() < deadline) Thread.sleep(10)
      closedByClient
    }

    def stop(): Unit = serverSocket.close()
  }
}

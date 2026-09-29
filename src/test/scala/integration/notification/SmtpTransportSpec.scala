package ru.bitec.app.ops
package integration.notification

import integration.http.{OutboundDestinationFailure, OutboundDestinationPolicy}
import application.notification.{NotificationEvent, NotificationMessage}
import application.port.NotificationSendResult
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.icegreen.greenmail.util.{GreenMail, ServerSetup}
import domain.incident.IncidentReason
import domain.notification.{EmailSecurity, NotificationChannelSettings, NotificationEventType}
import munit.FunSuite

import java.io.{BufferedReader, ByteArrayInputStream, IOException, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net.{InetAddress, ServerSocket, Socket}
import java.security.KeyStore
import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLException, SSLSocket, SSLSocketFactory, TrustManagerFactory}
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
    val startTls = new StartTlsSmtpServer(localTlsContexts().server)
    val startTlsPort = startTls.start()
    try {
      val result = send(settings(startTlsPort, EmailSecurity.StartTls))
      assertEquals(result, NotificationSendResult.RetryableFailure(SmtpTransport.TlsFailure))
      assert(!startTls.messageReceived)
    } finally startTls.stop()

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
    val pinned = InetAddress.getByName("127.0.0.1")
    val plain =
      SmtpTransport.properties(settings(25, EmailSecurity.None), 5.seconds, pinned, new SmtpAbort)
    val startTls =
      SmtpTransport.properties(settings(587, EmailSecurity.StartTls), 5.seconds, pinned, new SmtpAbort)
    val implicitTls =
      SmtpTransport.properties(settings(465, EmailSecurity.Tls), 5.seconds, pinned, new SmtpAbort)

    assertEquals(plain.getProperty("mail.smtp.auth"), "true")
    assertEquals(plain.getProperty("mail.smtp.starttls.enable"), null)
    // Required, not enabled: a relay that does not offer the upgrade fails instead of sending
    // the password in the clear.
    assertEquals(startTls.getProperty("mail.smtp.starttls.enable"), "true")
    assertEquals(startTls.getProperty("mail.smtp.starttls.required"), "true")
    // STARTTLS remains `smtp`, never implicit TLS / `smtps`.
    assertEquals(startTls.getProperty("mail.smtps.ssl.socketFactory"), null)
    // Angus receives the numeric pinned connection host. Its JDK endpoint check must therefore
    // stay off; SmtpHostnameVerifier checks the configured hostname after normal TLS validation.
    assertEquals(startTls.getProperty("mail.smtp.ssl.checkserveridentity"), "false")
    assert(startTls.get("mail.smtp.ssl.hostnameverifier").isInstanceOf[SmtpHostnameVerifier])
    assertEquals(implicitTls.getProperty("mail.smtps.auth"), "true")
    assertEquals(implicitTls.getProperty("mail.smtps.ssl.checkserveridentity"), "false")
    assert(implicitTls.get("mail.smtps.ssl.hostnameverifier").isInstanceOf[SmtpHostnameVerifier])
    // Angus calls createSocket on an ssl.socketFactory only when it is not an SSLSocketFactory.
    // This exact shape guarantees that the implicit-TLS socket is registered before connect.
    assert(implicitTls.get("mail.smtps.ssl.socketFactory")
      .isInstanceOf[PinnedImplicitTlsSocketFactory])
    assert(!implicitTls.get("mail.smtps.ssl.socketFactory")
      .isInstanceOf[javax.net.ssl.SSLSocketFactory])
    assertEquals(implicitTls.getProperty("mail.smtps.writetimeout"), null)
    // No mode bypasses certificate trust.
    for (properties <- List(plain, startTls, implicitTls)) {
      val names = properties.stringPropertyNames().asScala.toSet
      assert(!names.exists(_.contains("ssl.trust")), clues(names))
    }
    // The silent fallback to an unprotected plain socket is turned off in every mode: it is the
    // one setting that would otherwise undo the pinning below it.
    assertEquals(plain.getProperty("mail.smtp.socketFactory.fallback"), "false")
    assertEquals(startTls.getProperty("mail.smtp.socketFactory.fallback"), "false")
    assertEquals(implicitTls.getProperty("mail.smtps.socketFactory.fallback"), "false")
    // Waiting for a QUIT response is disabled as defense in depth. The resource finalizer also
    // closes the raw socket first, which is what prevents the QUIT write itself.
    assertEquals(plain.getProperty("mail.smtp.quitwait"), "false")
    assertEquals(startTls.getProperty("mail.smtp.quitwait"), "false")
    assertEquals(implicitTls.getProperty("mail.smtps.quitwait"), "false")
  }

  test("the relay dialed is the address a policy pinned, never a fresh resolution of the host name") {
    withServer(ServerSetup.PROTOCOL_SMTP) { server =>
      val pinnedToLoopback: OutboundDestinationPolicy = (_: String) =>
        IO.pure(Right(InetAddress.getByName("127.0.0.1")))

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

  test("STARTTLS sends through the pinned address and verifies the configured certificate name") {
    val tls = localTlsContexts()
    val server = new StartTlsSmtpServer(tls.server)
    val pinnedToLoopback: OutboundDestinationPolicy = (_: String) =>
      IO.pure(Right(InetAddress.getByName("127.0.0.1")))
    val port = server.start()
    try {
      // This is the DNS name of the local test certificate. It deliberately differs from the
      // dialed loopback IP, proving the TLS layer keeps the configured name separate.
      val result = new SmtpTransport(pinnedToLoopback, 5.seconds, tlsSocketFactory = tls.client)
        .send(settings(port, EmailSecurity.StartTls).copy(host = "smtp.example.test"), Password, message)
        .unsafeRunSync()

      assertEquals(result, NotificationSendResult.Sent)
      assert(server.messageReceived, "the local STARTTLS relay did not receive DATA")
    } finally server.stop()
  }

  test("a nested STARTTLS TLS failure is classified as TLS, not unexpected") {
    val messaging = new jakarta.mail.MessagingException("STARTTLS failed")
    val io = new IOException("TLS upgrade failed")
    io.initCause(new SSLException("certificate hostname mismatch"))
    messaging.setNextException(io)

    assertEquals(SmtpTransport.classifyError(messaging),
      NotificationSendResult.RetryableFailure(SmtpTransport.TlsFailure))
  }

  test("DNS resolution is cancelled and joined inside the whole-attempt deadline") {
    val cancelled = new AtomicBoolean(false)
    val stuckResolver = new OutboundDestinationPolicy {
      override def pin(host: String): IO[Either[OutboundDestinationFailure, InetAddress]] =
        IO.never[Either[OutboundDestinationFailure, InetAddress]]
          .onCancel(IO(cancelled.set(true)))
    }
    val deadline = 250.millis
    val started = System.nanoTime()

    val result = new SmtpTransport(stuckResolver, deadline)
      .send(settings(25, EmailSecurity.None).copy(host = "stuck.example"), Password, message)
      .unsafeRunSync()
    val elapsed = (System.nanoTime() - started).nanos

    assertEquals(result, NotificationSendResult.RetryableFailure(SmtpTransport.Timeout))
    assert(cancelled.get(), "the resolver fiber was left running after SMTP_TIMEOUT")
    assert(elapsed < 1500.millis, clues(elapsed))
  }

  test("implicit TLS stays inside the whole-attempt deadline after DNS used part of it") {
    val server = new SilentTcpServer
    val port = server.start()
    val dnsDelay = 600.millis
    val deadline = 900.millis
    val delayedPin = new OutboundDestinationPolicy {
      override def pin(host: String): IO[Either[OutboundDestinationFailure, InetAddress]] =
        IO.sleep(dnsDelay).flatMap(_ => IO.pure(Right(InetAddress.getByName("127.0.0.1"))))
    }
    try {
      val started = System.nanoTime()
      val result = new SmtpTransport(delayedPin, deadline)
        .send(settings(port, EmailSecurity.Tls).copy(host = "smtp.example.test"), Password, message)
        .unsafeRunSync()
      val elapsed = (System.nanoTime() - started).nanos

      assertEquals(result, NotificationSendResult.RetryableFailure(SmtpTransport.Timeout))
      // A per-socket 900ms timeout beginning after 600ms DNS would take about 1.5s. Returning
      // below this bound proves that the shared 900ms deadline closed the implicit-TLS socket.
      assert(elapsed < 1300.millis, clues(elapsed))
      assert(server.awaitBytes(1, 1.second),
        "the attempt never reached an implicit TLS ClientHello")
      assert(server.awaitClientClose(2.seconds),
        "the implicit-TLS socket was left open after SMTP_TIMEOUT")
      val bytesAtReturn = server.bytesReceived
      Thread.sleep(250)
      assertEquals(server.bytesReceived, bytesAtReturn,
        "network activity continued after the implicit-TLS timeout returned")
    } finally server.stop()
  }

  test("successful cleanup closes the raw socket without sending QUIT") {
    val server = new ScriptedSmtpServer(List(
      List("250-fake", "250 AUTH LOGIN"),
      List("334 VXNlcm5hbWU6"),
      List("334 UGFzc3dvcmQ6"),
      List("235 authentication successful"),
      List("250 sender ok"),
      List("250 recipient ok"),
      List("354 end with ."),
      List("250 queued")
    ))
    val port = server.start()
    try {
      val result = new SmtpTransport(Permissive, 5.seconds)
        .send(settings(port, EmailSecurity.None), Password, message)
        .unsafeRunSync()

      assertEquals(result, NotificationSendResult.Sent)
      assert(server.awaitClientClose(5.seconds), "the successful connection was left open")
      assert(!server.quitReceived, "cleanup wrote QUIT before closing the raw socket")
    } finally server.stop()
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

  test("the whole attempt is bounded during a connect that no single operation would time out") {
    // Every reply comes back in 350ms, comfortably under the 550ms socket read timeout, so no
    // single operation ever times out on its own. What is slow is the sequence: the greeting, the
    // EHLO and the AUTH exchange together pass the 550ms whole-attempt deadline before
    // authentication finishes. All of that happens inside one synchronized `Transport.connect`,
    // which holds the connection's lock for its whole duration — so a `Transport.close()` from
    // another fiber could not have stopped it here (it would only take the lock once connect had
    // already returned, seconds later). Only closing the socket underneath connect stops it at the
    // deadline, which is what this asserts: the attempt ends near 550ms, not near the ~1.75s a full
    // greeting+EHLO+AUTH exchange at 350ms a step would take.
    val perResponse = 350.millis
    val deadline = 550.millis
    val server = new ScriptedSmtpServer(
      List(
        List("250-fake", "250 AUTH LOGIN"),
        List("334 VXNlcm5hbWU6"),
        List("334 UGFzc3dvcmQ6"),
        List("235 authentication successful"),
        List("250 OK")
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
      // Stopped near the deadline, not after the whole connect exchange would have finished: this
      // bound is below the ~1.75s the full handshake would take, so it fails outright for any
      // approach that lets the synchronized connect run to completion.
      assert(elapsed < 1400.millis, clues(elapsed))
      // Cut off mid-handshake, structurally: the server never got through the AUTH exchange, so it
      // never reached the send phase. A completed connect would have taken all four AUTH lines.
      assert(commandsAtReturn < 4, clues(commandsAtReturn))
      assert(!server.sawData, "the send reached the DATA phase before the deadline")
      // The deadline actually stopped the attempt: the socket is closed and no further command
      // arrives afterwards, so nothing is still talking to the relay once SMTP_TIMEOUT is returned.
      assert(server.awaitClientClose(5.seconds),
        "the connection was left open past the whole-attempt deadline")
      Thread.sleep(perResponse.toMillis)
      assertEquals(server.commandsReceived, commandsAtReturn,
        "a command reached the relay after the attempt had supposedly timed out")
    } finally server.stop()
  }

  test("a timeout inside sendMessage is enforced by closing the socket, not the synchronized transport") {
    // Connect and authentication are instant here, so the attempt gets cleanly past them and into
    // `sendMessage` — the send phase. From MAIL FROM on, each reply takes 350ms, again under the
    // 550ms socket read timeout, so no single step times out; but MAIL FROM plus RCPT TO pass the
    // 550ms deadline. `sendMessage` is synchronized on the same lock as `close`, and holds it for
    // the whole MAIL/RCPT/DATA exchange, so a `Transport.close()` could not interrupt it — only
    // closing the socket underneath does. This proves the deadline reaches inside a running
    // `sendMessage`, not just a running `connect`.
    val perResponseWhileSending = 350.millis
    val deadline = 550.millis
    val server = new ScriptedSmtpServer(
      List(
        List("250-fake", "250 AUTH LOGIN"), // EHLO
        List("334 VXNlcm5hbWU6"),           // AUTH LOGIN
        List("334 UGFzc3dvcmQ6"),           // username
        List("235 authentication successful"), // password
        List("250 sender ok"),              // MAIL FROM  (slow from here)
        List("250 recipient ok"),           // RCPT TO
        List("354 end with ."),             // DATA
        List("250 queued")                  // end of body
      ),
      // Replies 1–4 (connect + auth) are instant; replies 5+ (the send phase) are the slow ones.
      slowFrom = Some(5),
      slowDelay = perResponseWhileSending
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
      // The attempt got past connect and auth into the send itself: MAIL FROM is only sent from
      // inside sendMessage, so five or more commands mean the timeout landed there, not in connect.
      assert(commandsAtReturn >= 5, clues(commandsAtReturn))
      // And it was stopped near the deadline, not after the send exchange would have finished.
      assert(elapsed < 1400.millis, clues(elapsed))
      assert(server.awaitClientClose(5.seconds),
        "the socket was left open after a timeout inside sendMessage")
      Thread.sleep(perResponseWhileSending.toMillis)
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
    override def pin(host: String): IO[Either[OutboundDestinationFailure, InetAddress]] =
      IO.pure(Right(InetAddress.getByName(host)))
  }

  private final class TestTlsContexts(val server: SSLSocketFactory, val client: SSLSocketFactory)

  /** Test-only PKCS#12 with CN and SAN localhost. The client trusts this one certificate through
    * a normal TrustManager; production continues to use the JVM trust store.
    */
  private val StartTlsTestKeyStore = "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"

  private def localTlsContexts(): TestTlsContexts = {
    val store = KeyStore.getInstance("PKCS12")
    store.load(new ByteArrayInputStream(Base64.getDecoder.decode(StartTlsTestKeyStore)),
      "changeit".toCharArray)

    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keys.init(store, "changeit".toCharArray)
    val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trusts.init(store)

    val server = SSLContext.getInstance("TLS")
    server.init(keys.getKeyManagers, null, null)
    val client = SSLContext.getInstance("TLS")
    client.init(null, trusts.getTrustManagers, null)
    new TestTlsContexts(server.getSocketFactory, client.getSocketFactory)
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
    perResponseDelay: FiniteDuration = Duration.Zero,
    slowFrom: Option[Int] = None,
    slowDelay: FiniteDuration = Duration.Zero
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
          var repliesSent = 0
          // Sends the next scripted reply and returns whether it opened the DATA phase (a 354).
          // Its delay is the shared per-response one, or, from `slowFrom` on, the slow one — so a
          // test can keep connect and auth quick and make only the send phase (MAIL/RCPT/DATA)
          // slow, timing the attempt out inside a synchronized `sendMessage` rather than `connect`.
          def sendNextReply(): Boolean = {
            val (reply, rest) = remaining match {
              case head :: tail => (head, tail)
              case Nil => (List("250 OK"), Nil)
            }
            remaining = rest
            repliesSent += 1
            val delay = if (slowFrom.exists(repliesSent >= _)) slowDelay else perResponseDelay
            if (delay > Duration.Zero) Thread.sleep(delay.toMillis)
            reply.foreach { text => out.print(text + "\r\n"); out.flush() }
            reply.headOption.exists(_.startsWith("354"))
          }
          var readingBody = false
          var running = true
          var line = in.readLine()
          while (running && line != null) {
            if (readingBody) {
              // During DATA the client sends the message and a lone ".": consume the body silently
              // and only answer the terminating dot.
              if (line == ".") readingBody = sendNextReply()
            } else if (line.toUpperCase.startsWith("QUIT")) {
              quitReceived = true
              out.print("221 bye\r\n")
              out.flush()
              running = false
            } else {
              commandsReceived += 1
              if (line.toUpperCase.startsWith("DATA")) sawData = true
              readingBody = sendNextReply()
            }
            if (running) line = in.readLine()
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

  /** A small protocol server for the one thing GreenMail's SMTP listener does not model: a plain
    * SMTP session upgraded in-place with STARTTLS. It authenticates the test account and records
    * DATA only after the real TLS handshake, so a successful client result means the STARTTLS
    * transport, not SMTPS, was used.
    */
  private final class StartTlsSmtpServer(tlsFactory: SSLSocketFactory) {
    private val serverSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    @volatile var messageReceived = false
    @volatile private var clientSocket: Socket = _

    private val thread = new Thread(() => {
      try {
        val plain = serverSocket.accept()
        clientSocket = plain
        val plainReader = new BufferedReader(new InputStreamReader(plain.getInputStream, "US-ASCII"))
        val plainWriter = new PrintWriter(new OutputStreamWriter(plain.getOutputStream, "US-ASCII"), true)
        plainWriter.print("220 local STARTTLS relay ready\r\n")
        plainWriter.flush()
        plainReader.readLine() // EHLO
        plainWriter.print("250-local\r\n250-STARTTLS\r\n250 AUTH LOGIN\r\n")
        plainWriter.flush()
        if (plainReader.readLine().equalsIgnoreCase("STARTTLS")) {
          plainWriter.print("220 Ready to start TLS\r\n")
          plainWriter.flush()
          val secure = tlsFactory.createSocket(plain, "127.0.0.1", plain.getPort, true)
            .asInstanceOf[SSLSocket]
          secure.setUseClientMode(false)
          secure.startHandshake()
          clientSocket = secure
          val reader = new BufferedReader(new InputStreamReader(secure.getInputStream, "US-ASCII"))
          val writer = new PrintWriter(new OutputStreamWriter(secure.getOutputStream, "US-ASCII"), true)
          reader.readLine() // EHLO after TLS
          writer.print("250-local\r\n250 AUTH LOGIN\r\n")
          writer.flush()
          reader.readLine() // AUTH LOGIN
          writer.print("334 VXNlcm5hbWU6\r\n")
          writer.flush()
          reader.readLine() // username
          writer.print("334 UGFzc3dvcmQ6\r\n")
          writer.flush()
          reader.readLine() // password
          writer.print("235 authenticated\r\n")
          writer.flush()
          reader.readLine() // MAIL FROM
          writer.print("250 sender ok\r\n")
          writer.flush()
          reader.readLine() // RCPT TO
          writer.print("250 recipient ok\r\n")
          writer.flush()
          reader.readLine() // DATA
          writer.print("354 end with .\r\n")
          writer.flush()
          var line = reader.readLine()
          while (line != null && line != ".") line = reader.readLine()
          messageReceived = line == "."
          if (messageReceived) {
            writer.print("250 queued\r\n")
            writer.flush()
          }
        }
      } catch { case _: Throwable => () }
      finally Option(clientSocket).foreach(socket => try socket.close() catch { case _: Throwable => () })
    }, "starttls-smtp-server")
    thread.setDaemon(true)

    def start(): Int = { thread.start(); serverSocket.getLocalPort }

    def stop(): Unit = {
      Option(clientSocket).foreach(socket => try socket.close() catch { case _: Throwable => () })
      try serverSocket.close() catch { case _: Throwable => () }
    }
  }

  /** Accepts TCP and consumes the TLS ClientHello without ever answering it. */
  private final class SilentTcpServer {
    private val serverSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    @volatile private var clientSocket: Socket = _
    @volatile private var closedByClient = false
    @volatile var bytesReceived = 0

    private val thread = new Thread(() => {
      try {
        clientSocket = serverSocket.accept()
        val input = clientSocket.getInputStream
        val buffer = new Array[Byte](1024)
        var read = input.read(buffer)
        while (read >= 0) {
          bytesReceived += read
          read = input.read(buffer)
        }
      } catch { case _: Throwable => () }
      finally closedByClient = true
    }, "silent-smtps-server")
    thread.setDaemon(true)

    def start(): Int = { thread.start(); serverSocket.getLocalPort }

    def awaitClientClose(timeout: FiniteDuration): Boolean = {
      val deadline = System.nanoTime() + timeout.toNanos
      while (!closedByClient && System.nanoTime() < deadline) Thread.sleep(10)
      closedByClient
    }

    def awaitBytes(minimum: Int, timeout: FiniteDuration): Boolean = {
      val deadline = System.nanoTime() + timeout.toNanos
      while (bytesReceived < minimum && System.nanoTime() < deadline) Thread.sleep(10)
      bytesReceived >= minimum
    }

    def stop(): Unit = {
      Option(clientSocket).foreach(socket => try socket.close() catch { case _: Throwable => () })
      try serverSocket.close() catch { case _: Throwable => () }
    }
  }
}

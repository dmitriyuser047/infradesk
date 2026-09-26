package ru.bitec.app.ops
package integration.notification

import application.notification.{NotificationEvent, NotificationMessage}
import application.port.NotificationSendResult
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.icegreen.greenmail.util.{GreenMail, ServerSetup}
import domain.incident.IncidentReason
import domain.notification.{EmailSecurity, NotificationChannelSettings, NotificationEventType}
import munit.FunSuite

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Mail against a real SMTP server, so the three security modes are exercised as protocol
  * rather than as configuration.
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
    val plain = SmtpTransport.properties(settings(25, EmailSecurity.None), 5.seconds)
    val startTls = SmtpTransport.properties(settings(587, EmailSecurity.StartTls), 5.seconds)
    val implicitTls = SmtpTransport.properties(settings(465, EmailSecurity.Tls), 5.seconds)

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
    override def check(host: String): IO[Either[String, Unit]] = IO.pure(Right(()))
  }

  /** One server per test, on a port the operating system picks. */
  private def withServer(protocol: String)(body: GreenMail => Unit): Unit = {
    val setup = new ServerSetup(0, "127.0.0.1", protocol)
    val server = new GreenMail(setup)
    server.setUser(User, User, Password)
    server.start()
    try body(server) finally server.stop()
  }
}

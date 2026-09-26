package ru.bitec.app.ops
package integration.notification

import application.notification.NotificationMessage
import application.port.{EmailNotificationTransport, NotificationSendResult}
import cats.effect.{IO, Resource}
import cats.syntax.all._
import domain.notification.{EmailSecurity, NotificationChannelSettings}
import jakarta.mail.internet.{InternetAddress, MimeMessage}
import jakarta.mail.{
  AuthenticationFailedException,
  Message,
  MessagingException,
  SendFailedException,
  Session,
  Transport
}

import java.util.Properties
import scala.concurrent.duration.FiniteDuration

/** Sends a message through the SMTP relay a mail channel is configured with.
  *
  * The three security modes are three different connections, not a flag: a plain one, a plain
  * one that is upgraded before anything is sent, and one that is encrypted from the first byte.
  * The upgrade is required rather than attempted, so a relay that does not offer STARTTLS fails
  * instead of quietly sending the password in the clear. Certificate and host name verification
  * stay on in both encrypted modes.
  *
  * Jakarta Mail is blocking and connection-oriented, so the whole exchange runs on the blocking
  * pool and the transport is closed by its own scope whether the send succeeded or not.
  */
final class SmtpTransport(
  policy: OutboundDestinationPolicy,
  timeout: FiniteDuration
) extends EmailNotificationTransport[IO] {

  import SmtpTransport._

  override def send(
    settings: NotificationChannelSettings.Email,
    password: String,
    message: NotificationMessage
  ): IO[NotificationSendResult] =
    policy.check(settings.host).flatMap {
      case Left(code) => IO.pure(NotificationSendResult.PermanentFailure(code))
      case Right(_) => deliver(settings, password, message)
        .as(NotificationSendResult.Sent: NotificationSendResult)
        .handleError(classifyError)
    }

  private def deliver(
    settings: NotificationChannelSettings.Email,
    password: String,
    message: NotificationMessage
  ): IO[Unit] =
    transport(settings, password).use { case (transport, session) =>
      IO.blocking {
        val mail = new MimeMessage(session)
        mail.setFrom(new InternetAddress(settings.fromAddress))
        mail.setRecipients(Message.RecipientType.TO,
          settings.recipients.map(new InternetAddress(_): jakarta.mail.Address).toArray)
        mail.setSubject(message.subject, "UTF-8")
        mail.setText(message.text, "UTF-8")
        transport.sendMessage(mail, mail.getAllRecipients)
      }
    }

  /** One connection per send, closed by its own scope: SMTP is a session, and a session that
    * outlives the message it carried is a socket nobody is watching.
    */
  private def transport(
    settings: NotificationChannelSettings.Email,
    password: String
  ): Resource[IO, (Transport, Session)] =
    Resource.make(IO.blocking {
      val session = Session.getInstance(properties(settings, timeout))
      val transport = session.getTransport(protocol(settings.security))
      transport.connect(settings.host, settings.port, settings.username, password)
      (transport, session)
    })({ case (transport, _) => IO.blocking(transport.close()).handleError(_ => ()) })
}

object SmtpTransport {

  val Timeout: String = "SMTP_TIMEOUT"
  val TlsFailure: String = "SMTP_TLS_FAILURE"
  val TemporaryFailure: String = "SMTP_TEMPORARY_FAILURE"
  val AuthFailure: String = "SMTP_AUTH_FAILURE"
  val PermanentFailure: String = "SMTP_PERMANENT_FAILURE"
  val UnexpectedError: String = "SMTP_UNEXPECTED_ERROR"

  private def protocol(security: EmailSecurity): String = security match {
    case EmailSecurity.Tls => "smtps"
    case EmailSecurity.StartTls | EmailSecurity.None => "smtp"
  }

  /** What the three modes mean, spelled out for the implementation.
    *
    * `starttls.required` rather than `starttls.enable` alone: enabling it upgrades the
    * connection when the relay offers it and carries on in the clear when it does not, which is
    * precisely the outcome a person choosing STARTTLS is trying to avoid.
    */
  private[notification] def properties(
    settings: NotificationChannelSettings.Email,
    timeout: FiniteDuration
  ): Properties = {
    val protocolName = protocol(settings.security)
    val values = new Properties()
    val millis = timeout.toMillis.toString
    values.put(s"mail.$protocolName.connectiontimeout", millis)
    values.put(s"mail.$protocolName.timeout", millis)
    values.put(s"mail.$protocolName.writetimeout", millis)
    values.put(s"mail.$protocolName.auth", "true")
    values.put(s"mail.$protocolName.host", settings.host)
    values.put(s"mail.$protocolName.port", settings.port.toString)
    settings.security match {
      case EmailSecurity.None => ()
      case EmailSecurity.StartTls =>
        values.put("mail.smtp.starttls.enable", "true")
        values.put("mail.smtp.starttls.required", "true")
        // The name the channel was configured with is the name the certificate has to match.
        values.put("mail.smtp.ssl.checkserveridentity", "true")
      case EmailSecurity.Tls =>
        values.put("mail.smtps.ssl.checkserveridentity", "true")
    }
    values
  }

  /** A relay refuses for two different kinds of reason, and they are not retried the same way.
    *
    * Nothing of what it said is kept: a reply can quote the address, the message or the account.
    */
  def classifyError(error: Throwable): NotificationSendResult = error match {
    case _: AuthenticationFailedException => NotificationSendResult.PermanentFailure(AuthFailure)
    case failure: SendFailedException =>
      // Addresses the relay called invalid will be invalid next time too; ones it merely could
      // not reach are worth another attempt.
      if (Option(failure.getInvalidAddresses).exists(_.nonEmpty))
        NotificationSendResult.PermanentFailure(PermanentFailure)
      else NotificationSendResult.RetryableFailure(TemporaryFailure)
    case _: java.util.concurrent.TimeoutException => NotificationSendResult.RetryableFailure(Timeout)
    case _: java.net.SocketTimeoutException => NotificationSendResult.RetryableFailure(Timeout)
    // A relay whose certificate does not check out is not sent to, and not sent to in the
    // clear either. Worth repeating: a trust store is fixed without touching the channel.
    case _: javax.net.ssl.SSLException => NotificationSendResult.RetryableFailure(TlsFailure)
    case _: java.net.ConnectException => NotificationSendResult.RetryableFailure(TemporaryFailure)
    case _: java.net.UnknownHostException =>
      NotificationSendResult.PermanentFailure(PermanentFailure)
    case failure: MessagingException => messagingFailure(failure)
    case _: IllegalArgumentException => NotificationSendResult.PermanentFailure(PermanentFailure)
    case _: jakarta.mail.internet.AddressException =>
      NotificationSendResult.PermanentFailure(PermanentFailure)
    case _ => NotificationSendResult.RetryableFailure(UnexpectedError)
  }

  /** The reply code decides, when there is one: 4xx is the relay asking to be tried later, 5xx
    * is it declining. A failure that carries no reply is a connection that went wrong.
    */
  private def messagingFailure(failure: MessagingException): NotificationSendResult =
    replyCode(failure) match {
      case Some(code) if code >= 400 && code < 500 =>
        NotificationSendResult.RetryableFailure(TemporaryFailure)
      case Some(_) => NotificationSendResult.PermanentFailure(PermanentFailure)
      case None => Option(failure.getNextException) match {
        case Some(cause) if cause ne failure => classifyError(cause)
        case _ => NotificationSendResult.RetryableFailure(TemporaryFailure)
      }
    }

  /** Jakarta Mail puts the reply at the start of the message; the rest of it is not read. */
  private def replyCode(failure: MessagingException): Option[Int] =
    Option(failure.getMessage).map(_.trim).flatMap { message =>
      val digits = message.takeWhile(_.isDigit)
      if (digits.length == 3) scala.util.Try(digits.toInt).toOption else None
    }
}

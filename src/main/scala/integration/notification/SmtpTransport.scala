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
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/** Sends a message through the SMTP relay a mail channel is configured with.
  *
  * The three security modes are three different connections, not a flag: a plain one, a plain
  * one that is upgraded before anything is sent, and one that is encrypted from the first byte.
  * The upgrade is required rather than attempted, so a relay that does not offer STARTTLS fails
  * instead of quietly sending the password in the clear. Certificate and host name verification
  * stay on in both encrypted modes, and the destination — the relay a person configured — is
  * resolved and validated by `PinningSocketFactory` at the moment the connection is opened,
  * never by a check made earlier against a name that could since answer differently.
  *
  * Jakarta Mail is blocking and connection-oriented, so the whole exchange runs on the blocking
  * pool. The attempt carries one overall deadline, because DNS, the connection, the TLS
  * handshake, authentication and the send itself are each bounded on their own but their sum is
  * not: without an outer bound the whole attempt could run past the lease that a claimed delivery
  * holds it under, and a second worker could pick the same delivery up while this one was still
  * running. The transport is closed by its own scope whether the send succeeded, failed, timed
  * out or was cancelled.
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
    deliver(settings, password, message)
      .as(NotificationSendResult.Sent: NotificationSendResult)
      .timeout(timeout)
      .handleError {
        case _: TimeoutException => NotificationSendResult.RetryableFailure(Timeout)
        case error => OutboundDestinationPolicy.classify(error).getOrElse(classifyError(error))
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

  /** One connection per send, closed by its own scope whatever ends it.
    *
    * The `Transport` is constructed first and its finalizer registered before anything that can
    * fail is attempted: `connect` runs inside the resource's scope, via `evalTap`, precisely so
    * that a `Transport` object that never finished connecting is still the one `close` is called
    * on. Creating the object and connecting it as a single step in the acquire would leave a
    * half-opened connection unmanaged whenever the connect itself is what failed.
    */
  private def transport(
    settings: NotificationChannelSettings.Email,
    password: String
  ): Resource[IO, (Transport, Session)] =
    Resource.make(IO.blocking {
      val session = Session.getInstance(properties(settings, timeout, policy))
      (session.getTransport(protocol(settings.security)), session)
    })({ case (transport, _) => IO.blocking(transport.close()).handleError(_ => ()) })
      .evalTap { case (transport, _) =>
        IO.blocking(transport.connect(settings.host, settings.port, settings.username, password))
      }
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
    *
    * The same plain factory is handed to every mode, implicit TLS included: Jakarta Mail's own
    * `SocketFetcher` never asks an `SSLSocketFactory` to open the underlying connection, only to
    * wrap one that is already open, so a factory that is itself an `SSLSocketFactory` never gets
    * the chance to pin anything — the connection it would wrap is opened first, by the library's
    * own unvalidated resolution of the host name. `checkserveridentity` is what verifies the
    * certificate and host name in both encrypted modes, against the name the channel is
    * configured with, using Jakarta Mail's own handshake handling; nothing here needs a custom
    * `SSLSocketFactory` of its own to get that.
    *
    * The factory is handed in as an object value, not a `.class` name: Jakarta Mail accepts
    * either, and only the object form lets each send carry its own policy and timeout rather
    * than a class loaded once with none. `socketFactory.fallback` is turned off on purpose — it
    * defaults to on, meaning a relay this factory refused would otherwise be dialed again with
    * the plain JDK factory, which is exactly the unprotected connection the pinning exists to
    * prevent.
    */
  private[notification] def properties(
    settings: NotificationChannelSettings.Email,
    timeout: FiniteDuration,
    policy: OutboundDestinationPolicy
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
    values.put(s"mail.$protocolName.socketFactory.fallback", "false")
    values.put(s"mail.$protocolName.socketFactory", new PinningSocketFactory(policy, timeout.toMillis.toInt))

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
    case _: TimeoutException => NotificationSendResult.RetryableFailure(Timeout)
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
        case Some(cause) if cause ne failure =>
          OutboundDestinationPolicy.classify(cause).getOrElse(classifyError(cause))
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

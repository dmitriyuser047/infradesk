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

import java.net.InetAddress
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
  * resolved asynchronously before Jakarta Mail starts. The approved address is then passed to a
  * socket factory that can dial only that value, so there is no second DNS answer to rebind it.
  *
  * Jakarta Mail is blocking and connection-oriented, so the whole exchange runs on the blocking
  * pool. Each socket operation is bounded on its own by connect/read/write timeouts, but their
  * sum is not, so the attempt also carries one overall deadline: without it the whole attempt
  * could run past the lease that a claimed delivery holds it under, and a second worker could
  * pick the same delivery up while this one was still running. That deadline is real rather than
  * nominal — a blocking send on the blocking pool cannot be interrupted by cancelling it, so on
  * the deadline the raw socket is closed to unblock the operation, the fiber is cancelled, and
  * the attempt is joined before a timeout is reported. Its scope includes cleanup, so the send is
  * finished and the connection is closed before the delivery is released.
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
      .handleError {
        case _: TimeoutException => NotificationSendResult.RetryableFailure(Timeout)
        case error => OutboundDestinationPolicy.classify(error).getOrElse(classifyError(error))
      }

  /** DNS, connect, TLS, authentication, send and cleanup share one deadline. DNS runs through a
    * cancelable effect. Jakarta Mail's socket I/O runs on the blocking pool, where cancellation
    * alone cannot interrupt it, and `Transport.close` takes the same lock the active operation
    * holds. The deadline therefore closes the raw socket first, cancels the attempt and joins it.
    * A returned `SMTP_TIMEOUT` cannot leave DNS or an SMTP send running past the delivery lease.
    *
    * The pinned numeric address is passed to Jakarta Mail because it constructs an
    * `InetSocketAddress` itself. This removes its otherwise synchronous second DNS lookup. TLS
    * receives the configured host separately for SNI and certificate verification.
    */
  private def deliver(
    settings: NotificationChannelSettings.Email,
    password: String,
    message: NotificationMessage
  ): IO[Unit] = {
    val abort = new SmtpAbort
    val attempt = policy.pin(settings.host).flatMap {
      case Left(OutboundDestinationFailure.Forbidden(code)) =>
        IO.raiseError(new OutboundDestinationRejected(code))
      case Left(OutboundDestinationFailure.ResolutionFailed(code)) =>
        IO.raiseError(new OutboundDestinationUnresolvable(code))
      case Right(pinnedAddress) =>
        transport(settings, pinnedAddress, abort).use { case (transport, session) =>
          IO.blocking {
            // A numeric connection host prevents Jakarta Mail from starting its own synchronous
            // DNS lookup. TLS still uses the configured host through the SSL factory below.
            transport.connect(pinnedAddress.getHostAddress, settings.port, settings.username, password)
            val mail = new MimeMessage(session)
            mail.setFrom(new InternetAddress(settings.fromAddress))
            mail.setRecipients(Message.RecipientType.TO,
              settings.recipients.map(new InternetAddress(_): jakarta.mail.Address).toArray)
            mail.setSubject(message.subject, "UTF-8")
            mail.setText(message.text, "UTF-8")
            transport.sendMessage(mail, mail.getAllRecipients)
          }
        }
    }
    withinDeadline(attempt, abort)
  }

  /** Runs the complete attempt on its own fiber and holds it to `timeout`.
    *
    * On the deadline (or on the caller cancelling the send) the send's socket is closed first,
    * which unblocks socket I/O. Cancelling the fiber also cancels an in-flight asynchronous DNS
    * lookup. Only then is the attempt joined, so control cannot return while any attempt phase or
    * its resource finalizer is still active.
    */
  private def withinDeadline(attempt: IO[Unit], abort: SmtpAbort): IO[Unit] = {
    val abortNow = IO.blocking(abort.abort())
    attempt.start.flatMap { fiber =>
      IO.race(IO.sleep(timeout), fiber.join)
        .flatMap {
          case Right(outcome) => outcome.embedNever
          case Left(_) =>
            abortNow *> fiber.cancel *> fiber.join.void *>
              IO.raiseError(new TimeoutException(TimeoutMessage))
        }
        .onCancel(abortNow *> fiber.cancel *> fiber.join.void)
    }
  }

  /** The scope is inside the deadline. Its finalizer first closes the raw socket and only then
    * calls `Transport.close()` to release Jakarta Mail's local state. Angus therefore cannot write
    * or flush QUIT during cleanup, even after a successful send.
    */
  private def transport(
    settings: NotificationChannelSettings.Email,
    pinnedAddress: InetAddress,
    abort: SmtpAbort
  ): Resource[IO, (Transport, Session)] =
    Resource.make(IO.blocking {
      val session = Session.getInstance(properties(settings, timeout, pinnedAddress, abort))
      (session.getTransport(protocol(settings.security)), session)
    })({ case (transport, _) =>
      IO.blocking {
        abort.abort()
        transport.close()
      }.handleError(_ => ())
    })
}

object SmtpTransport {

  val Timeout: String = "SMTP_TIMEOUT"
  private val TimeoutMessage: String = "SMTP attempt exceeded its whole-attempt deadline"
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
    * The plain factory opens every connection, including implicit TLS, to the pinned address. The
    * TLS factory can only wrap that connected socket and substitutes the configured host for SNI
    * and certificate verification; all overloads that could open another connection are refused.
    *
    * The factory is handed in as an object value, not a `.class` name: Jakarta Mail accepts
    * either, and only the object form lets each send carry its own policy, timeout and abort
    * handle rather than a class loaded once with none. `socketFactory.fallback` is turned off on
    * purpose — it defaults to on, meaning a relay this factory refused would otherwise be dialed
    * again with the plain JDK factory, which is exactly the unprotected connection the pinning
    * exists to prevent.
    *
    * `quitwait` is off as defense in depth, but it only disables waiting for the relay's 221
    * response. The finalizer's raw-socket close is what prevents the QUIT write itself, and the
    * finalizer remains inside the same whole-attempt deadline.
    */
  private[notification] def properties(
    settings: NotificationChannelSettings.Email,
    timeout: FiniteDuration,
    pinnedAddress: InetAddress,
    abort: SmtpAbort
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
    values.put(s"mail.$protocolName.quitwait", "false")
    values.put(s"mail.$protocolName.socketFactory",
      new PinningSocketFactory(pinnedAddress, timeout.toMillis.toInt, abort))

    settings.security match {
      case EmailSecurity.None => ()
      case EmailSecurity.StartTls =>
        values.put("mail.smtp.starttls.enable", "true")
        values.put("mail.smtp.starttls.required", "true")
        values.put("mail.smtp.ssl.socketFactory", new SmtpTlsSocketFactory(settings.host))
        values.put("mail.smtp.ssl.hostnameverifier", new SmtpHostnameVerifier(settings.host))
        // The name the channel was configured with is the name the certificate has to match.
        values.put("mail.smtp.ssl.checkserveridentity", "true")
      case EmailSecurity.Tls =>
        values.put("mail.smtps.ssl.socketFactory", new SmtpTlsSocketFactory(settings.host))
        values.put("mail.smtps.ssl.hostnameverifier", new SmtpHostnameVerifier(settings.host))
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

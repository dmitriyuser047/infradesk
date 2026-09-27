package ru.bitec.app.ops
package infrastructure.config

import application.auth.{AuthRateLimitSettings, BootstrapConfig}
import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import infrastructure.database.DatabaseConfig
import infrastructure.http.AuthSettings
import integration.ssh.{EnvironmentSecrets, SecretEncryptionConfig}
import org.http4s.Uri

import scala.concurrent.duration._
import scala.util.Try

final case class HttpConfig(host: Host, port: Port)
final case class SchedulerConfig(
  enabled: Boolean,
  pollInterval: FiniteDuration,
  batchSize: Int,
  maxConcurrency: Int,
  claimLease: FiniteDuration
)

/** Outbound notifications. Without a webhook URL the subsystem is off: no deliveries are
  * recorded and no dispatcher runs.
  */
final case class NotificationConfig(
  webhookUrl: Option[Uri],
  pollInterval: FiniteDuration,
  batchSize: Int,
  maxConcurrency: Int,
  claimLease: FiniteDuration,
  requestTimeout: FiniteDuration,
  maxAttempts: Long,
  /** Whether a configured channel may point at a private network.
    *
    * An internal SMTP relay or a webhook receiver inside the perimeter is an ordinary
    * arrangement, and letting any user of the product reach one is an ordinary mistake, so the
    * deployment decides rather than the channel. The loopback interface, link-local addresses
    * and the cloud metadata endpoint stay out of reach either way.
    */
  allowPrivateDestinations: Boolean
) {
  /** Whether the webhook the deployment configures itself exists. Channels are configured in
    * the database and are not what this answers.
    */
  def enabled: Boolean = webhookUrl.isDefined

  // The URL may carry a token, so it never reaches a log line.
  override def toString: String =
    s"NotificationConfig(webhook=${if (enabled) "configured" else "disabled"}, " +
      s"pollInterval=$pollInterval, batchSize=$batchSize, maxConcurrency=$maxConcurrency, " +
      s"claimLease=$claimLease, requestTimeout=$requestTimeout, maxAttempts=$maxAttempts, " +
      s"allowPrivateDestinations=$allowPrivateDestinations)"
}

final case class AppConfig(
  database: DatabaseConfig,
  http: HttpConfig,
  auth: AuthSettings,
  loginRateLimit: AuthRateLimitSettings,
  bootstrap: Option[BootstrapConfig],
  secretEncryption: SecretEncryptionConfig,
  scheduler: SchedulerConfig,
  notification: NotificationConfig,
  sshEnvironmentSecrets: EnvironmentSecrets
)

object AppConfig {
  def load: IO[AppConfig] = IO(sys.env).flatMap(values => IO.fromEither(fromEnvironment(values)))

  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, AppConfig] =
    for {
      database <- DatabaseConfig.fromEnvironment(values)
      http <- parseHttp(values)
      auth <- AuthSettings.fromEnvironment(values)
      loginRateLimit <- parseLoginRateLimit(values)
      bootstrap <- BootstrapConfig.fromEnvironment(values)
      secretEncryption <- SecretEncryptionConfig.fromEnvironment(values)
      scheduler <- parseScheduler(values)
      notification <- parseNotification(values)
    } yield AppConfig(database, http, auth, loginRateLimit, bootstrap, secretEncryption, scheduler,
      notification, EnvironmentSecrets.fromEnvironment(values))

  private def parseLoginRateLimit(
    values: Map[String, String]
  ): Either[IllegalArgumentException, AuthRateLimitSettings] = {
    val d = AuthRateLimitSettings.default
    for {
      idWindow <- positiveInt(values, "INFRADESK_AUTH_IDENTIFIER_WINDOW_SECONDS", d.identifierWindow.toSeconds.toInt)
      idMax <- positiveInt(values, "INFRADESK_AUTH_IDENTIFIER_MAX_FAILURES", d.identifierMaxFailures)
      idBlock <- positiveInt(values, "INFRADESK_AUTH_IDENTIFIER_BLOCK_SECONDS", d.identifierBlock.toSeconds.toInt)
      srcWindow <- positiveInt(values, "INFRADESK_AUTH_SOURCE_WINDOW_SECONDS", d.sourceWindow.toSeconds.toInt)
      srcMax <- positiveInt(values, "INFRADESK_AUTH_SOURCE_MAX_FAILURES", d.sourceMaxFailures)
      srcBlock <- positiveInt(values, "INFRADESK_AUTH_SOURCE_BLOCK_SECONDS", d.sourceBlock.toSeconds.toInt)
      retention <- positiveInt(values, "INFRADESK_AUTH_THROTTLE_RETENTION_SECONDS", d.retention.toSeconds.toInt)
    } yield AuthRateLimitSettings(idWindow.seconds, idMax, idBlock.seconds, srcWindow.seconds, srcMax,
      srcBlock.seconds, retention.seconds)
  }

  private def parseHttp(values: Map[String, String]): Either[IllegalArgumentException, HttpConfig] = {
    val host = values.get("INFRADESK_HTTP_HOST") match {
      case None => Right(Host.fromString("0.0.0.0").get)
      case Some(value) => Host.fromString(value)
        .filter(_ => validHost(value))
        .toRight(
        new IllegalArgumentException("Invalid INFRADESK_HTTP_HOST: expected a valid host")
      )
    }
    val port = values.get("INFRADESK_HTTP_PORT") match {
      case None => Right(Port.fromInt(8080).get)
      case Some(value) => Try(value.toInt).toOption.filter(_ > 0).flatMap(Port.fromInt).toRight(
        new IllegalArgumentException("Invalid INFRADESK_HTTP_PORT: expected integer 1..65535")
      )
    }
    for { h <- host; p <- port } yield HttpConfig(h, p)
  }

  private def validHost(value: String): Boolean = {
    val dnsOrIpv4 = value.split("\\.", -1).forall(
      _.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    )
    val ipv6 = value.contains(":") && value.matches("\\[?[0-9A-Fa-f:.]+\\]?")
    dnsOrIpv4 || ipv6
  }

  private def parseNotification(values: Map[String, String]): Either[IllegalArgumentException, NotificationConfig] =
    for {
      webhookUrl <- parseWebhookUrl(values)
      pollIntervalSeconds <- positiveInt(values, "INFRADESK_NOTIFICATION_POLL_INTERVAL_SECONDS", 5)
      batchSize <- positiveInt(values, "INFRADESK_NOTIFICATION_BATCH_SIZE", 50)
      maxConcurrency <- positiveInt(values, "INFRADESK_NOTIFICATION_MAX_CONCURRENCY", 5)
      claimLeaseSeconds <- positiveInt(values, "INFRADESK_NOTIFICATION_CLAIM_LEASE_SECONDS", 60)
      requestTimeoutSeconds <- positiveInt(values, "INFRADESK_NOTIFICATION_REQUEST_TIMEOUT_SECONDS", 10)
      maxAttempts <- positiveInt(values, "INFRADESK_NOTIFICATION_MAX_ATTEMPTS", 10)
      allowPrivate <- parseBoolean(values, "INFRADESK_NOTIFICATION_ALLOW_PRIVATE_DESTINATIONS")
      // A lease shorter than a request would let a second dispatcher start the same delivery
      // while the first one is still waiting for the receiver. The dispatcher claims in waves of
      // at most maxConcurrency, so the lease only has to cover one request plus database overhead,
      // not the whole batch.
      _ <- Either.cond(
        claimLeaseSeconds > requestTimeoutSeconds,
        (),
        new IllegalArgumentException(
          "INFRADESK_NOTIFICATION_CLAIM_LEASE_SECONDS must be greater than INFRADESK_NOTIFICATION_REQUEST_TIMEOUT_SECONDS"
        )
      )
    } yield NotificationConfig(webhookUrl, pollIntervalSeconds.seconds, batchSize, maxConcurrency,
      claimLeaseSeconds.seconds, requestTimeoutSeconds.seconds, maxAttempts.toLong, allowPrivate)

  /** Absent means no, and anything that is not plainly yes or no is a configuration error
    * rather than a default: this one decides what the product is allowed to connect to.
    */
  private def parseBoolean(
    values: Map[String, String],
    key: String
  ): Either[IllegalArgumentException, Boolean] =
    values.get(key).map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty) match {
      case None => Right(false)
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(_) => Left(new IllegalArgumentException(s"Invalid $key: expected true or false"))
    }

  private def parseWebhookUrl(values: Map[String, String]): Either[IllegalArgumentException, Option[Uri]] =
    values.get("INFRADESK_NOTIFICATION_WEBHOOK_URL").map(_.trim).filter(_.nonEmpty) match {
      case None => Right(None)
      case Some(value) =>
        // The value itself is never echoed back: it may contain a token.
        val invalid = new IllegalArgumentException(
          "Invalid INFRADESK_NOTIFICATION_WEBHOOK_URL: expected an absolute http or https URL"
        )
        Uri.fromString(value).toOption
          .filter(uri => uri.scheme.exists(scheme => scheme.value == "http" || scheme.value == "https"))
          .filter(_.host.exists(_.value.trim.nonEmpty))
          .toRight(invalid)
          .map(Some(_))
    }

  private def positiveInt(
    values: Map[String, String],
    key: String,
    default: Int
  ): Either[IllegalArgumentException, Int] =
    values.get(key) match {
      case None => Right(default)
      case Some(value) => Try(value.toInt).toOption.filter(_ > 0).toRight(
        new IllegalArgumentException(s"Invalid $key: expected positive integer")
      )
    }

  private def parseScheduler(values: Map[String, String]): Either[IllegalArgumentException, SchedulerConfig] = {
    def positiveInt(key: String, default: Int): Either[IllegalArgumentException, Int] =
      values.get(key) match {
        case None => Right(default)
        case Some(value) => Try(value.toInt).toOption.filter(_ > 0).toRight(
          new IllegalArgumentException(s"Invalid $key: expected positive integer")
        )
      }

    for {
      enabled <- parseBoolean(values, "INFRADESK_SCHEDULER_ENABLED", default = true)
      seconds <- positiveInt("INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS", 1)
      batchSize <- positiveInt("INFRADESK_SCHEDULER_BATCH_SIZE", 100)
      maxConcurrency <- positiveInt("INFRADESK_SCHEDULER_MAX_CONCURRENCY", 5)
      claimLeaseSeconds <- positiveInt("INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS", 900)
    } yield SchedulerConfig(enabled, seconds.seconds, batchSize, maxConcurrency,
      claimLeaseSeconds.seconds)
  }

  private def parseBoolean(
    values: Map[String, String],
    key: String,
    default: Boolean
  ): Either[IllegalArgumentException, Boolean] =
    values.get(key).map(_.trim.toLowerCase) match {
      case None => Right(default)
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(_) => Left(new IllegalArgumentException(s"Invalid $key: expected true or false"))
    }
}

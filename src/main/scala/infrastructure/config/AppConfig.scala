package ru.bitec.app.ops
package infrastructure.config

import application.auth.BootstrapConfig
import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import infrastructure.database.DatabaseConfig
import infrastructure.http.AuthSettings
import integration.ssh.{EnvironmentSecrets, SecretEncryptionConfig}

import scala.concurrent.duration._
import scala.util.Try

final case class HttpConfig(host: Host, port: Port)
final case class SchedulerConfig(pollInterval: FiniteDuration, batchSize: Int, maxConcurrency: Int, claimLease: FiniteDuration)

final case class AppConfig(
  database: DatabaseConfig,
  http: HttpConfig,
  auth: AuthSettings,
  bootstrap: Option[BootstrapConfig],
  secretEncryption: SecretEncryptionConfig,
  scheduler: SchedulerConfig,
  sshEnvironmentSecrets: EnvironmentSecrets
)

object AppConfig {
  def load: IO[AppConfig] = IO(sys.env).flatMap(values => IO.fromEither(fromEnvironment(values)))

  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, AppConfig] =
    for {
      database <- DatabaseConfig.fromEnvironment(values)
      http <- parseHttp(values)
      auth <- AuthSettings.fromEnvironment(values)
      bootstrap <- BootstrapConfig.fromEnvironment(values)
      secretEncryption <- SecretEncryptionConfig.fromEnvironment(values)
      scheduler <- parseScheduler(values)
    } yield AppConfig(database, http, auth, bootstrap, secretEncryption, scheduler,
      EnvironmentSecrets.fromEnvironment(values))

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

  private def parseScheduler(values: Map[String, String]): Either[IllegalArgumentException, SchedulerConfig] = {
    def positiveInt(key: String, default: Int): Either[IllegalArgumentException, Int] =
      values.get(key) match {
        case None => Right(default)
        case Some(value) => Try(value.toInt).toOption.filter(_ > 0).toRight(
          new IllegalArgumentException(s"Invalid $key: expected positive integer")
        )
      }

    for {
      seconds <- positiveInt("INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS", 1)
      batchSize <- positiveInt("INFRADESK_SCHEDULER_BATCH_SIZE", 100)
      maxConcurrency <- positiveInt("INFRADESK_SCHEDULER_MAX_CONCURRENCY", 5)
      claimLeaseSeconds <- positiveInt("INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS", 900)
    } yield SchedulerConfig(seconds.seconds, batchSize, maxConcurrency, claimLeaseSeconds.seconds)
  }
}

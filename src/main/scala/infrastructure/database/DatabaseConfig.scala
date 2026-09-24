package ru.bitec.app.ops
package infrastructure.database

import scala.concurrent.duration._

/** Connection settings and the size of the pool built from them.
  *
  * The pool is stated explicitly rather than left to library defaults, because its size is what
  * bounds how many transactions a deployment can hold open at once.
  */
final case class DatabaseConfig(
  url: String,
  user: String,
  password: String,
  maxPoolSize: Int = DatabaseConfig.DefaultMaxPoolSize,
  connectionTimeout: FiniteDuration = DatabaseConfig.DefaultConnectionTimeout
) {
  // The URL carries the host and the password never belongs in a log line at all.
  override def toString: String =
    s"DatabaseConfig(<redacted>, maxPoolSize=$maxPoolSize, connectionTimeout=$connectionTimeout)"
}

object DatabaseConfig {

  val DefaultMaxPoolSize: Int = 10
  val DefaultConnectionTimeout: FiniteDuration = 10.seconds

  /** More connections than this on one node exhaust a small PostgreSQL long before they help. */
  val MaxPoolSizeLimit: Int = 100

  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, DatabaseConfig] = {
    def required(key: String): Either[IllegalArgumentException, String] =
      values.get(key).filter(_.trim.nonEmpty).toRight(
        new IllegalArgumentException(s"$key is required")
      )

    def positiveInt(key: String, default: Int, limit: Int): Either[IllegalArgumentException, Int] =
      values.get(key).map(_.trim).filter(_.nonEmpty) match {
        case None => Right(default)
        case Some(value) =>
          scala.util.Try(value.toInt).toOption
            .filter(parsed => parsed > 0 && parsed <= limit)
            .toRight(new IllegalArgumentException(
              s"Invalid $key: expected an integer between 1 and $limit"))
      }

    for {
      url <- required("INFRADESK_DB_URL")
      user <- required("INFRADESK_DB_USER")
      password <- required("INFRADESK_DB_PASSWORD")
      maxPoolSize <- positiveInt("INFRADESK_DB_MAX_POOL_SIZE", DefaultMaxPoolSize, MaxPoolSizeLimit)
      connectionTimeout <- positiveInt("INFRADESK_DB_CONNECTION_TIMEOUT_SECONDS",
        DefaultConnectionTimeout.toSeconds.toInt, 600)
    } yield DatabaseConfig(url, user, password, maxPoolSize, connectionTimeout.seconds)
  }
}

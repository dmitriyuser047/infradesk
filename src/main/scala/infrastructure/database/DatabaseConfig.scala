package ru.bitec.app.ops
package infrastructure.database

final case class DatabaseConfig(url: String, user: String, password: String) {
  override def toString: String = "DatabaseConfig(<redacted>)"
}

object DatabaseConfig {

  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, DatabaseConfig] = {
    def required(key: String): Either[IllegalArgumentException, String] =
      values.get(key).filter(_.trim.nonEmpty).toRight(
        new IllegalArgumentException(s"$key is required")
      )

    for {
      url <- required("INFRADESK_DB_URL")
      user <- required("INFRADESK_DB_USER")
      password <- required("INFRADESK_DB_PASSWORD")
    } yield DatabaseConfig(url, user, password)
  }
}

package ru.bitec.app.ops
package infrastructure.database

import cats.effect.IO

final case class DatabaseConfig(
                               url: String,
                               user: String,
                               password: String
                               )

object DatabaseConfig {

  def load: IO[DatabaseConfig] =
    for {
      url <- env("INFRADESK_DB_URL")
      user <- env("INFRADESK_DB_USER")
      password <- env("INFRADESK_DB_PASSWORD")
    } yield DatabaseConfig(
      url = url,
      user = user,
      password = password
    )

  private def env(name: String): IO[String] =
    IO(sys.env.get(name)).flatMap {
      case Some(value) =>
        IO.pure(value)

      case None =>
        IO.raiseError(
          new IllegalStateException(
            s"Environment variable $name is not set"
          )
        )
    }

}

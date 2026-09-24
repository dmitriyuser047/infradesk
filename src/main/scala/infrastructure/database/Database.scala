package ru.bitec.app.ops
package infrastructure.database

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.HikariConfig
import org.typelevel.doobie.hikari.HikariTransactor

object Database {

  /** The pool is a runtime resource of the composition root: it opens before the application is
    * assembled and closes after every worker that could use it has stopped.
    */
  def transactor(config: DatabaseConfig): Resource[IO, HikariTransactor[IO]] = for {
    hikari <- Resource.eval(IO {
      val settings = new HikariConfig()
      settings.setDriverClassName("org.postgresql.Driver")
      settings.setJdbcUrl(config.url)
      settings.setUsername(config.user)
      settings.setPassword(config.password)
      // Stated rather than inherited: the pool bounds how many transactions this node can hold.
      settings.setMaximumPoolSize(config.maxPoolSize)
      settings.setConnectionTimeout(config.connectionTimeout.toMillis)
      settings.setPoolName("infradesk")
      settings
    })
    xa <- HikariTransactor.fromHikariConfig[IO](hikari)
  } yield xa
}

package ru.bitec.app.ops
package infrastructure.database

import cats.effect.{IO, Resource}
import org.typelevel.doobie.hikari.HikariTransactor
import org.typelevel.doobie.util.ExecutionContexts

object Database {

  def transactor(config: DatabaseConfig): Resource[IO, HikariTransactor[IO]] = for {
    connectEc <- ExecutionContexts.fixedThreadPool[IO](4)
    xa <- HikariTransactor
      .newHikariTransactor[IO]("org.postgresql.Driver", config.url, config.user,config.password, connectEc)
  } yield xa
}

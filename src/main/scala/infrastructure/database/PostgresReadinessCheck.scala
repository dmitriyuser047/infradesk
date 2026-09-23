package ru.bitec.app.ops
package infrastructure.database

import application.port.ReadinessCheck
import cats.effect.IO
import cats.syntax.all._
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits._

final class PostgresReadinessCheck(xa: Transactor[IO]) extends ReadinessCheck[IO] {
  override def check: IO[Unit] =
    sql"select 1".query[Int].unique.transact(xa).void
}

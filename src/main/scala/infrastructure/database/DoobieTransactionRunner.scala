package ru.bitec.app.ops
package infrastructure.database


import application.port.TransactionRunner
import cats.effect.IO
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits._

final class DoobieTransactionRunner(xa: Transactor[IO]) extends TransactionRunner[IO, ConnectionIO] {

  override def run[A](program: ConnectionIO[A]): IO[A] =
    program.transact(xa)
}
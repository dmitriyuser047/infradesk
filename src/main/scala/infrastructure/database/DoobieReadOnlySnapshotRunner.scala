package ru.bitec.app.ops
package infrastructure.database

import application.port.TransactionRunner
import cats.effect.IO
import org.typelevel.doobie.{ConnectionIO, Transactor}
import cats.syntax.all._
import org.typelevel.doobie.implicits._

/** Runs a read model in one read-only snapshot.
  *
  * Every statement of the program sees the same committed state, so the parts of a composite read
  * — counts and the list they summarize — cannot disagree, and PostgreSQL rejects any write the
  * program might attempt. No row is locked.
  */
final class DoobieReadOnlySnapshotRunner(xa: Transactor[IO]) extends TransactionRunner[IO, ConnectionIO] {

  override def run[A](program: ConnectionIO[A]): IO[A] =
    (sql"set transaction isolation level repeatable read, read only".update.run *> program).transact(xa)
}

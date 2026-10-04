package ru.bitec.app.ops
package persistence.postgres

import application.integration.FleetRolloutLeaseLost
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.util.UUID

/** Transaction-local correlation. It cannot leak through the pool or into another member fiber. */
final class PostgresFleetRolloutChildRunner(base: TransactionRunner[IO, ConnectionIO], id: UUID, token: UUID)
  extends TransactionRunner[IO, ConnectionIO] {
  def run[A](program: ConnectionIO[A]): IO[A] = base.run(for {
    live <- sql"""select 1 from remnawave_fleet_rollout where id=$id and claim_token=$token
      and claim_deadline>clock_timestamp() and state in ('RUNNING','QUEUED','ROLLING_BACK') for update"""
      .query[Int].option.map(_.nonEmpty)
    _ <- if (live) ().pure[ConnectionIO] else FleetRolloutLeaseLost.raiseError[ConnectionIO, Unit]
    _ <- sql"select set_config('infradesk.fleet_rollout_id',${id.toString},true)".query[String].unique
    _ <- sql"select set_config('infradesk.fleet_rollout_token',${token.toString},true)".query[String].unique
    result <- program
  } yield result)
}

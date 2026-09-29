package ru.bitec.app.ops
package application.terminal

import application.port.{TerminalSessionRepository, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.terminal._
import org.typelevel.log4cats.Logger
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

final class TerminalSessionLifecycle[Tx[_]](
  sessions: TerminalSessionRepository[Tx], runner: TransactionRunner[IO, Tx],
  owner: UUID, userLimit: Int, organizationLimit: Int, leaseDuration: FiniteDuration, logger: Logger[IO]
) {
  def claim(org: UUID, connection: UUID, user: UUID, authSession: UUID, version: Instant): IO[TerminalClaimResult] =
    IO.realTimeInstant.flatMap { now =>
      val session = TerminalSession(UUID.randomUUID(), org, connection, user, authSession,
        TerminalSessionState.Opening, owner, UUID.randomUUID(), now.plusMillis(leaseDuration.toMillis),
        version, now, None, None, None)
      runner.run(sessions.claim(session, userLimit, organizationLimit)).flatTap {
        case TerminalClaimResult.Claimed(value) => logger.info(s"terminal.session.claimed sessionId=${value.id} organizationId=$org userId=$user connectionId=$connection")
        case _ => logger.warn(s"terminal.capacity.rejected organizationId=$org userId=$user connectionId=$connection")
      }
    }

  def activate(session: TerminalSession): IO[Boolean] = IO.realTimeInstant.flatMap(now =>
    runner.run(sessions.activate(session.organizationId, session.id, session.leaseToken,
      now, now.plusMillis(leaseDuration.toMillis))))

  def renew(session: TerminalSession): IO[TerminalRenewResult] = IO.realTimeInstant.flatMap(now =>
    runner.run(sessions.renew(session.organizationId, session.id, session.leaseToken, now, now.plusMillis(leaseDuration.toMillis))))

  def close(session: TerminalSession, reason: TerminalCloseReason): IO[Unit] = IO.realTimeInstant.flatMap(now =>
    runner.run(sessions.closeOwned(session.organizationId, session.id, session.leaseToken, now, reason))).flatMap {
      case true => logger.info(s"terminal.session.closed sessionId=${session.id} organizationId=${session.organizationId} closeReason=${reason.code}")
      case false => IO.unit
    }

  def reap: IO[Nothing] = (IO.realTimeInstant.flatMap(now => runner.run(sessions.reapExpired(now, 100)))
    .flatMap(count => if (count > 0) logger.info(s"terminal.session.reaped count=$count") else IO.unit)
    .handleErrorWith(failure => logger.error(failure)("terminal.session.reaper.failed")) *>
    IO.sleep(1.minute)).foreverM
}

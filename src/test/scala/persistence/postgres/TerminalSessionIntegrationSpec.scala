package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.terminal._
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class TerminalSessionIntegrationSpec extends FunSuite {
  test("claim, activate and close are fenced and audit exactly once") { withSetup { s =>
    val session = s.session()
    for {
      claimed <- s.run(s.repo.claim(session, 4, 32))
      wrongOrg <- s.run(s.repo.activate(UUID.randomUUID(), session.id, session.leaseToken, s.now, s.now.plusSeconds(45)))
      wrongToken <- s.run(s.repo.activate(s.org, session.id, UUID.randomUUID(), s.now, s.now.plusSeconds(45)))
      activated <- s.run(s.repo.activate(s.org, session.id, session.leaseToken, s.now, s.now.plusSeconds(45)))
      duplicate <- s.run(s.repo.activate(s.org, session.id, session.leaseToken, s.now, s.now.plusSeconds(45)))
      renewed <- s.run(s.repo.renew(s.org, session.id, session.leaseToken, s.now, s.now.plusSeconds(60)))
      lost <- s.run(s.repo.renew(s.org, session.id, UUID.randomUUID(), s.now, s.now.plusSeconds(60)))
      crossClose <- s.run(s.repo.closeOwned(UUID.randomUUID(), session.id, session.leaseToken, s.now, TerminalCloseReason.ClientClose))
      closed <- s.run(s.repo.closeOwned(s.org, session.id, session.leaseToken, s.now, TerminalCloseReason.ClientClose))
      closedAgain <- s.run(s.repo.closeOwned(s.org, session.id, session.leaseToken, s.now, TerminalCloseReason.ClientClose))
      count <- s.auditCount(session.id)
    } yield {
      assert(claimed.isInstanceOf[TerminalClaimResult.Claimed])
      assert(!wrongOrg && !wrongToken && activated && !duplicate && !crossClose && closed && !closedAgain)
      assertEquals(renewed, TerminalRenewResult.Renewed(s.now.plusSeconds(60)))
      assertEquals(lost, TerminalRenewResult.Lost)
      assertEquals(count, 2L)
    }
  } }

  test("a shell that no socket resumed closes as DETACH_TIMEOUT, fenced and audited once") { withSetup { s =>
    val session = s.session()
    for {
      _ <- s.run(s.repo.claim(session, 4, 32))
      _ <- s.run(s.repo.activate(s.org, session.id, session.leaseToken, s.now, s.now.plusSeconds(45)))
      stale <- s.run(s.repo.closeOwned(s.org, session.id, UUID.randomUUID(), s.now, TerminalCloseReason.DetachTimeout))
      closed <- s.run(s.repo.closeOwned(s.org, session.id, session.leaseToken, s.now, TerminalCloseReason.DetachTimeout))
      stored <- s.run(sql"select state, close_reason from terminal_session where id = ${session.id}".query[(String, String)].unique)
      count <- s.auditCount(session.id)
    } yield {
      assert(!stale && closed)
      assertEquals(stored, ("CLOSED", "DETACH_TIMEOUT"))
      assertEquals(count, 2L)
    }
  } }

  test("activation starts a fresh lease after SSH setup") { withSetup { s =>
    val session = s.session()
    val opened = s.now.plusSeconds(20)
    val until = opened.plusSeconds(45)
    for {
      _ <- s.run(s.repo.claim(session, 4, 32))
      activated <- s.run(s.repo.activate(s.org, session.id, session.leaseToken, opened, until))
      expiry <- s.run(sql"select lease_expires_at from terminal_session where id = ${session.id}".query[Instant].unique)
    } yield {
      assert(activated)
      assertEquals(expiry, until)
    }
  } }

  test("independent concurrent transactions cannot exceed per-user capacity") { withSetup { s =>
    List.fill(16)(s.session()).parTraverse(value => s.run(s.repo.claim(value, 4, 32))).map { results =>
      assertEquals(results.count(_.isInstanceOf[TerminalClaimResult.Claimed]), 4)
      assertEquals(results.count(_ == TerminalClaimResult.CapacityRejected), 12)
    }
  } }
  test("independent concurrent transactions cannot exceed organization capacity") { withSetup { s =>
    List.fill(16)(s.session()).parTraverse(value => s.run(s.repo.claim(value, 32, 5))).map { results =>
      assertEquals(results.count(_.isInstanceOf[TerminalClaimResult.Claimed]), 5)
    }
  } }

  test("expired leases do not consume capacity and bounded reaper leaves active leases untouched") { withSetup { s =>
    val stale = s.session().copy(createdAt = s.now.minusSeconds(90), leaseExpiresAt = s.now.minusSeconds(1))
    val current = s.session()
    for {
      _ <- s.run(s.repo.claim(stale, 4, 32))
      claimed <- s.run(s.repo.claim(current, 1, 1))
      first <- s.run(s.repo.reapExpired(s.now, 1))
      second <- s.run(s.repo.reapExpired(s.now, 100))
      count <- s.auditCount(stale.id)
      active <- s.run(sql"select state from terminal_session where id = ${current.id}".query[String].unique)
    } yield {
      assert(claimed.isInstanceOf[TerminalClaimResult.Claimed])
      assertEquals(first, 1); assertEquals(second, 0); assertEquals(count, 1L); assertEquals(active, "OPENING")
    }
  } }

  List("auth-revoked", "auth-expired", "member", "membership-deleted", "user-inactive", "connection-inactive", "connection-version").foreach { change =>
    test(s"heartbeat revokes after $change with one close audit") { withSetup { s =>
      val session = s.session()
      val expected = if (change.startsWith("auth") || change == "user-inactive") TerminalCloseReason.AuthSessionEnded
        else if (change == "member" || change == "membership-deleted") TerminalCloseReason.PermissionRevoked
        else TerminalCloseReason.ConnectionChanged
      val mutation = change match {
        case "auth-revoked" => sql"update auth_session set revoked_at = ${s.now} where id = ${s.auth}".update.run
        case "auth-expired" => sql"update auth_session set expires_at = ${s.now} where id = ${s.auth}".update.run
        case "member" => sql"update organization_membership set role = 'MEMBER' where organization_id = ${s.org}".update.run
        case "membership-deleted" => sql"delete from organization_membership where organization_id = ${s.org}".update.run
        case "user-inactive" => sql"update user_account set is_active = false where id = ${s.user}".update.run
        case "connection-inactive" => sql"update connection set is_active = false where id = ${s.connection}".update.run
        case _ => sql"update connection set updated_at = ${s.now.plusSeconds(1)} where id = ${s.connection}".update.run
      }
      for {
        _ <- s.run(s.repo.claim(session, 4, 32))
        _ <- s.run(s.repo.activate(s.org, session.id, session.leaseToken, s.now, s.now.plusSeconds(45)))
        _ <- s.run(mutation)
        result <- s.run(s.repo.renew(s.org, session.id, session.leaseToken, s.now, s.now.plusSeconds(60)))
        again <- s.run(s.repo.renew(s.org, session.id, session.leaseToken, s.now, s.now.plusSeconds(60)))
        count <- s.auditCount(session.id)
      } yield {
        assertEquals(result, TerminalRenewResult.Revoked(expected))
        assertEquals(again, TerminalRenewResult.Lost)
        assertEquals(count, 2L)
      }
    } }
  }

  test("expired and foreign-tenant operations cannot change ownership") { withSetup { s =>
    val session = s.session()
    val otherOrg = UUID.randomUUID()
    for {
      _ <- s.run(s.repo.claim(session, 4, 32))
      crossRevoke <- s.run(s.repo.revoke(otherOrg, session.id, s.now))
      crossRenew <- s.run(s.repo.renew(otherOrg, session.id, session.leaseToken, s.now, s.now.plusSeconds(60)))
      expiredActivate <- s.run(s.repo.activate(s.org, session.id, session.leaseToken,
        s.now.plusSeconds(45), s.now.plusSeconds(90)))
      expiredClose <- s.run(s.repo.closeOwned(s.org, session.id, session.leaseToken, s.now.plusSeconds(45), TerminalCloseReason.ClientClose))
      count <- s.auditCount(session.id)
    } yield {
      assert(!crossRevoke && !expiredActivate && !expiredClose)
      assertEquals(crossRenew, TerminalRenewResult.Lost)
      assertEquals(count, 0L)
    }
  } }

  test("explicit revocation is atomic and idempotent") { withSetup { s =>
    val session = s.session()
    for {
      _ <- s.run(s.repo.claim(session, 4, 32))
      _ <- s.run(s.repo.activate(s.org, session.id, session.leaseToken, s.now, s.now.plusSeconds(45)))
      first <- s.run(s.repo.revoke(s.org, session.id, s.now))
      second <- s.run(s.repo.revoke(s.org, session.id, s.now))
      count <- s.auditCount(session.id)
    } yield { assert(first && !second); assertEquals(count, 2L) }
  } }

  test("failed audit rolls back lifecycle activation") { withSetup { s =>
    val session = s.session()
    val failedAudit = new application.port.AuditEventRepository[ConnectionIO] {
      def save(value: domain.audit.AuditEvent): ConnectionIO[Unit] = new RuntimeException("audit failure").raiseError[ConnectionIO, Unit]
      def saveAll(values: List[domain.audit.AuditEvent]): ConnectionIO[Unit] = new RuntimeException("audit failure").raiseError[ConnectionIO, Unit]
      def listByOrganization(org: UUID, before: Option[domain.audit.AuditCursor], limit: Int): ConnectionIO[List[domain.audit.AuditEvent]] = List.empty[domain.audit.AuditEvent].pure[ConnectionIO]
    }
    for {
      _ <- s.run(s.repo.claim(session, 4, 32))
      result <- s.run(new PostgresTerminalSessionRepository(failedAudit).activate(s.org, session.id,
        session.leaseToken, s.now, s.now.plusSeconds(45))).attempt
      state <- s.run(sql"select state from terminal_session where id = ${session.id}".query[String].unique)
    } yield { assert(result.isLeft); assertEquals(state, "OPENING") }
  } }

  private def withSetup(body: Setup => IO[Unit]): Unit = {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"), "PostgreSQL integration tests disabled")
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val s = new Setup(new DoobieTransactionRunner(xa))
      s.initialize *> body(s).guarantee(s.cleanup)
    }.unsafeRunSync()
  }
  private final class Setup(runner: DoobieTransactionRunner) {
    val org = UUID.randomUUID(); val connection = UUID.randomUUID(); val user = UUID.randomUUID(); val auth = UUID.randomUUID()
    val now = Instant.parse("2026-09-27T20:00:00Z")
    val repo = new PostgresTerminalSessionRepository(new PostgresAuditEventRepository)
    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)
    def session(): TerminalSession = TerminalSession(UUID.randomUUID(), org, connection, user, auth,
      TerminalSessionState.Opening, UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(45), now, now, None, None, None)
    def auditCount(id: UUID): IO[Long] = run(sql"select count(*) from audit_event where target_id = $id".query[Long].unique)
    def initialize: IO[Unit] = run(for {
      _ <- sql"insert into organization(id, code, name) values ($org, ${org.toString}, 'Terminal test')".update.run
      _ <- sql"insert into user_account(id,email,password_hash,display_name,created_at,updated_at) values ($user, ${s"$user@example.test"}, 'hash', 'User', $now, $now)".update.run
      _ <- sql"insert into auth_session(id,user_id,token_hash,created_at,expires_at) values ($auth,$user,${auth.toString},$now,${now.plusSeconds(3600)})".update.run
      _ <- sql"insert into organization_membership(user_id,organization_id,role,created_at,updated_at) values ($user,$org,'OWNER',$now,$now)".update.run
      _ <- sql"insert into connection(id,organization_id,scope_type,connector_type,code,name,updated_at) values ($connection,$org,'ORGANIZATION','SSH','terminal','Terminal',$now)".update.run
    } yield ())
    def cleanup: IO[Unit] = run(for {
      _ <- sql"delete from terminal_session where organization_id = $org".update.run
      _ <- sql"delete from audit_event where organization_id = $org".update.run
      _ <- sql"delete from auth_session where user_id = $user".update.run
      _ <- sql"delete from organization_membership where organization_id = $org".update.run
      _ <- sql"delete from connection where organization_id = $org".update.run
      _ <- sql"delete from user_account where id = $user".update.run
      _ <- sql"delete from organization where id = $org".update.run
    } yield ())
  }
}

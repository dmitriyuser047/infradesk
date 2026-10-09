package ru.bitec.app.ops
package persistence.postgres

import application.administration._
import application.administration.AdministrationScope._
import application.audit.AuditRecorder
import application.auth.BCryptPasswordHasher
import cats.effect.{IO,Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.auth.{AuthenticatedUser,OrganizationRole,UserAccount}
import infrastructure.database.{ConnectionIOIdGenerator,ConnectionIOTimeProvider,DoobieTransactionRunner}
import infrastructure.http.{AdministrationRoutes,OrganizationAccessContext,OrganizationAuthorization}
import munit.FunSuite
import org.http4s.{Method,Request,Uri}
import org.typelevel.doobie.{ConnectionIO,Transactor}
import org.typelevel.doobie.util.log.{LogEvent,LogHandler}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class AdministrationIntegrationSpec extends FunSuite {
  private val actor=UUID.fromString("10000000-0000-0000-0000-0000000000aa")
  private val org=UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val password="administration-fixture-password"
  private def fixture(body: AdminFixture => IO[Unit]): Unit = {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"))
    PostgresTestDatabase.isolatedTransactor(PostgresTestDatabase.config).use { xa =>
      body(new AdminFixture(new DoobieTransactionRunner(xa),xa.kernel.getJdbcUrl))
    }.unsafeRunSync()
  }
  private class AdminFixture(val runner: DoobieTransactionRunner,val databaseUrl: String) {
    val repository=new PostgresAdministrationRepository
    val users=new PostgresUserAccountRepository
    val memberships=new PostgresOrganizationMembershipRepository
    val hasher=new BCryptPasswordHasher
    val service=new Administration[ConnectionIO](repository,users,memberships,new PostgresOrganizationRepository,runner,
      hasher,new ConnectionIOIdGenerator,new ConnectionIOTimeProvider,
      new AuditRecorder(new PostgresAuditEventRepository,new ConnectionIOIdGenerator,new ConnectionIOTimeProvider))
    def run[A](p: ConnectionIO[A]): IO[A]=runner.run(p)
    def globalAdmin: IO[Unit]=service.bootstrapAdministrator(Some("integration-fixture@example.test"))
    def command(role: String="MEMBER",id: UUID=UUID.randomUUID())=CreateAdministrationUserCommand(id,
      s"$id@example.test","AdminFixture user",password,Some(org),role,false)
    def create(role: String="MEMBER")=service.createUser(actor,Organization(org),command(role))
    def error[A](result: IO[A],code: String): IO[Unit]=result.attempt.flatMap(r => IO {
      assertEquals(r.left.toOption.collect { case e: AdministrationError => e.code },Some(code))
    })
  }

  test("one user owns multiple isolated organizations and created users receive a working hashed password") { fixture { f => for {
    second <- f.run(f.service.createOrganization(actor,CreateOrganizationCommand(UUID.randomUUID(),"second","Second organization")))
    user <- f.create("OPERATOR")
    account <- f.run(f.users.findByEmail(user.email)).map(_.get)
    passwordWorks <- f.hasher.verify(password,account.passwordHash)
    scopes <- f.run(f.memberships.listActiveOrganizations(actor))
    role <- f.run(f.memberships.findActiveRole(user.id,org))
    other <- f.run(f.memberships.findActiveRole(user.id,second.id))
    _ <- IO { assert(passwordWorks); assert(account.passwordHash!=password); assert(scopes.map(_.id).contains(second.id))
      assertEquals(role,Some(OrganizationRole.Operator)); assertEquals(other,None); assert(!user.isAdministrator) }
  } yield () } }

  test("concurrent retried creations produce one user and do not change its password") { fixture { f =>
    val c=f.command(); for {
      result <- (f.service.createUser(actor,Organization(org),c),f.service.createUser(actor,Organization(org),c)).parTupled
      before <- f.run(f.users.findByEmail(c.email)).map(_.get.passwordHash)
      replay <- f.service.createUser(actor,Organization(org),c.copy(password="different-fixture-password"))
      after <- f.run(f.users.findByEmail(c.email)).map(_.get.passwordHash)
      count <- f.run(sql"select count(*) from administration_audit where action='USER_CREATED' and target_id=${c.requestId}".query[Long].unique)
      _ <- IO { assertEquals(result._1.id,result._2.id); assertEquals(replay.id,c.requestId); assertEquals(before,after); assertEquals(count,1L) }
      _ <- f.error(f.service.createUser(actor,Organization(org),c.copy(displayName="Other intent")),"ADMINISTRATION_REQUEST_CONFLICT")
    } yield ()
  } }

  test("organization owners cannot use global administration or escalate a new account globally") { fixture { f => for {
    _ <- f.error(f.run(f.service.listUsers(actor,None)),"FORBIDDEN")
    _ <- f.error(f.service.createUser(actor,Organization(org),f.command().copy(isAdministrator=true)),"FORBIDDEN")
    user <- f.create()
    _ <- f.error(f.run(f.service.listMembers(user.id,Organization(org),org,None)),"FORBIDDEN")
    unknown=UUID.randomUUID()
    _ <- f.error(f.run(f.service.listMembers(actor,Organization(unknown),unknown,None)),"ORGANIZATION_NOT_FOUND")
    _ <- f.error(f.run(f.service.changeMembership(actor,Organization(org),unknown,user.id,ChangeMembershipCommand("OWNER",true,None))),"FORBIDDEN")
  } yield () } }

  test("organization administrators may manage lower roles but cannot grant or change peer or owner access") { fixture { f => for {
    admin <- f.create("ADMINISTRATOR"); member <- f.create()
    current <- f.run(f.repository.findMember(org,member.id)).map(_.get)
    changed <- f.run(f.service.changeMembership(admin.id,Organization(org),org,member.id,ChangeMembershipCommand("OPERATOR",true,Some(current.updatedAt))))
    _ <- f.error(f.run(f.service.changeMembership(admin.id,Organization(org),org,member.id,ChangeMembershipCommand("OWNER",true,Some(changed.updatedAt)))),"FORBIDDEN")
    owner <- f.run(f.repository.findMember(org,actor)).map(_.get)
    _ <- f.error(f.run(f.service.changeMembership(admin.id,Organization(org),org,actor,ChangeMembershipCommand("MEMBER",false,Some(owner.updatedAt)))),"FORBIDDEN")
    _ <- IO(assertEquals(changed.role,OrganizationRole.Operator))
  } yield () } }

  test("two concurrent owner removals leave an active owner") { fixture { f => for {
    _ <- f.globalAdmin; second <- f.create("OWNER")
    firstMembership <- f.run(f.repository.findMember(org,actor)).map(_.get)
    secondMembership <- f.run(f.repository.findMember(org,second.id)).map(_.get)
    result <- (f.run(f.service.changeMembership(actor,Installation,org,actor,ChangeMembershipCommand("MEMBER",true,Some(firstMembership.updatedAt)))).attempt,
      f.run(f.service.changeMembership(actor,Installation,org,second.id,ChangeMembershipCommand("MEMBER",true,Some(secondMembership.updatedAt)))).attempt).parTupled
    _ <- IO { assertEquals(List(result._1,result._2).count(_.isRight),1)
      assert(List(result._1,result._2).exists(_.left.toOption.exists { case e: AdministrationError => e.code=="LAST_ORGANIZATION_OWNER"; case _ => false })) }
  } yield () } }

  test("last global administrator and sole organization owner are protected; blocked sessions are revoked") { fixture { f => for {
    _ <- f.globalAdmin; current <- f.run(f.repository.findUser(actor)).map(_.get)
    _ <- f.error(f.run(f.service.changeUserStatus(actor,actor,ChangeUserStatusCommand(true,false,current.updatedAt))),"LAST_INSTALLATION_ADMINISTRATOR")
    _ <- f.error(f.run(f.service.changeUserStatus(actor,actor,ChangeUserStatusCommand(false,false,current.updatedAt))),"LAST_ORGANIZATION_OWNER")
    member <- f.create(); session=UUID.randomUUID(); now=Instant.now()
    _ <- f.run(sql"""insert into auth_session(id,user_id,token_hash,created_at,expires_at) values($session,${member.id},${session.toString},$now,${now.plusSeconds(3600)})""".update.run)
    blocked <- f.run(f.service.changeUserStatus(actor,member.id,ChangeUserStatusCommand(false,false,member.updatedAt)))
    revoked <- f.run(sql"select revoked_at is not null from auth_session where id=$session".query[Boolean].unique)
    _ <- IO { assert(!blocked.isActive); assert(revoked) }
    _ <- f.error(f.run(f.service.changeUserStatus(actor,member.id,ChangeUserStatusCommand(true,false,member.updatedAt))),"ADMINISTRATION_STALE")
  } yield () } }

  test("a failed audit rolls back account creation, membership and request identity") { fixture { f => for {
    _ <- f.run(sql"""create function test_reject_admin_audit() returns trigger language plpgsql as 'BEGIN RAISE EXCEPTION ''fixture audit failure''; END';""".update.run)
    _ <- f.run(sql"create trigger test_reject_admin_audit before insert on administration_audit for each row execute function test_reject_admin_audit()".update.run)
    c=f.command()
    result <- f.service.createUser(actor,Organization(org),c).attempt
    account <- f.run(f.users.findByEmail(c.email)); request <- f.run(f.repository.findRequest(c.requestId))
    _ <- IO { assert(result.isLeft); assertEquals(account,None); assertEquals(request,None) }
  } yield () } }

  test("production HTTP member projection contains no hashes and refuses a member's read") { fixture { f => for {
    user <- f.create()
    routes=new AdministrationRoutes(f.service,f.runner).routes.orNotFound
    ownerRequest=OrganizationAuthorization.withContext(Request[IO](Method.GET,Uri.unsafeFromString(s"/api/v1/organizations/$org/members")),
      OrganizationAccessContext(AuthenticatedUser(actor,"fixture@example.test","Owner"),org,OrganizationRole.Owner))
    response <- routes.run(ownerRequest); json <- response.as[String]
    memberRequest=OrganizationAuthorization.withContext(Request[IO](Method.GET,Uri.unsafeFromString(s"/api/v1/organizations/$org/members")),
      OrganizationAccessContext(AuthenticatedUser(user.id,user.email,user.displayName),org,OrganizationRole.Member))
    denied <- routes.run(memberRequest)
    _ <- IO { assertEquals(response.status.code,200); assert(json.contains(user.email)); assert(!json.contains("password")); assert(!json.contains("hash")); assertEquals(denied.status.code,403) }
  } yield () } }

  test("organization retries are durable and cannot reopen revoked access") { fixture { f =>
    val c=CreateOrganizationCommand(UUID.randomUUID(),"durable-org","Durable organization")
    for {
      _ <- f.globalAdmin
      first <- f.run(f.service.createOrganization(actor,c))
      again <- f.run(f.service.createOrganization(actor,c))
      _ <- IO(assertEquals(first,again))
      _ <- f.error(f.run(f.service.createOrganization(actor,c.copy(name="Changed"))),"ADMINISTRATION_REQUEST_CONFLICT")
      owner <- f.create("OWNER")
      _ <- f.run(f.service.changeMembership(actor,Installation,first.id,owner.id,ChangeMembershipCommand("OWNER",true,None)))
      membership <- f.run(f.repository.findMember(first.id,actor)).map(_.get)
      _ <- f.run(f.service.changeMembership(actor,Installation,first.id,actor,ChangeMembershipCommand("MEMBER",false,Some(membership.updatedAt))))
      _ <- f.error(f.run(f.service.createOrganization(actor,c)),"ORGANIZATION_NOT_FOUND")
    } yield ()
  } }

  test("revoked administrators cannot mutate; stale versions do not overwrite access") { fixture { f => for {
    admin <- f.create("ADMINISTRATOR"); target <- f.create()
    before <- f.run(f.repository.findMember(org,target.id)).map(_.get)
    changed <- f.run(f.service.changeMembership(actor,Organization(org),org,target.id,ChangeMembershipCommand("OPERATOR",true,Some(before.updatedAt))))
    _ <- f.error(f.run(f.service.changeMembership(actor,Organization(org),org,target.id,ChangeMembershipCommand("OWNER",true,Some(before.updatedAt)))),"ADMINISTRATION_STALE")
    adminBefore <- f.run(f.repository.findMember(org,admin.id)).map(_.get)
    _ <- f.run(f.service.changeMembership(actor,Organization(org),org,admin.id,ChangeMembershipCommand("MEMBER",false,Some(adminBefore.updatedAt))))
    _ <- f.error(f.run(f.service.changeMembership(admin.id,Organization(org),org,target.id,ChangeMembershipCommand("MEMBER",true,Some(changed.updatedAt)))),"ORGANIZATION_NOT_FOUND")
  } yield () } }

  test("concurrent global administrator removals preserve one and bootstrap never restores removed rights") { fixture { f => for {
    _ <- f.globalAdmin
    second <- f.service.createUser(actor,Installation,f.command().copy(isAdministrator=true))
    first <- f.run(f.repository.findUser(actor)).map(_.get)
    result <- (f.run(f.service.changeUserStatus(actor,actor,ChangeUserStatusCommand(true,false,first.updatedAt))).attempt,
      f.run(f.service.changeUserStatus(actor,second.id,ChangeUserStatusCommand(true,false,second.updatedAt))).attempt).parTupled
    admins <- f.run(sql"select count(*) from installation_administrator where is_active".query[Long].unique)
    _ <- IO { assertEquals(List(result._1,result._2).count(_.isRight),1); assertEquals(admins,1L) }
    _ <- f.service.bootstrapAdministrator(Some("account-removed@example.test"))
    remaining <- f.run(sql"select count(*) from installation_administrator where is_active".query[Long].unique)
    _ <- IO(assertEquals(remaining,1L))
  } yield () } }

  test("authorized account and member pages use three statements for 1, 100 and 500 users") { fixture { f =>
    def counted[A](program: ConnectionIO[A]): IO[(A,Int)] = Ref.of[IO,Int](0).flatMap { counter =>
      val config=PostgresTestDatabase.config
      val handler=new LogHandler[IO] { def run(event: LogEvent): IO[Unit]=counter.update(_+1) }
      val logged=Transactor.fromDriverManager[IO]("org.postgresql.Driver",f.databaseUrl,config.user,config.password,Some(handler))
      new DoobieTransactionRunner(logged).run(program).flatMap(result => counter.get.map(result -> _))
    }
    f.globalAdmin *> List(1,100,500).traverse_ { count => for {
      _ <- f.run(sql"""insert into user_account(id,email,password_hash,display_name,created_at,updated_at)
        select md5('administration-scale-'||i)::uuid,'scale-'||i||'@example.test','x','Scale user '||i,now(),now()
        from generate_series(2,$count) i on conflict do nothing""".update.run)
      _ <- f.run(sql"""insert into organization_membership(user_id,organization_id,role,created_at,updated_at)
        select id,$org,'MEMBER',now(),now() from user_account where id<>$actor on conflict do nothing""".update.run)
      users <- counted(f.service.listUsers(actor,None))
      members <- counted(f.service.listMembers(actor,Organization(org),org,None))
      _ <- IO { assertEquals(users._1.size,math.min(count,51)); assertEquals(members._1.size,math.min(count,51))
        assertEquals(users._2,3); assertEquals(members._2,3) }
    } yield () }
  } }
}

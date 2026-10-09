package ru.bitec.app.ops
package application.administration

import application.account.AccountValidation
import application.audit.AuditRecorder
import application.auth.{ActorContext, PasswordHasher}
import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.auth.{OrganizationRole, UserAccount}
import domain.audit.{AuditAction, AuditTargetType}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

sealed trait AdministrationScope
object AdministrationScope {
  case object Installation extends AdministrationScope
  final case class Organization(id: UUID) extends AdministrationScope
}
final case class AdministrationError(code: String, override val getMessage: String) extends RuntimeException(getMessage)
final case class CreateOrganizationCommand(requestId: UUID,code: String,name: String)
final case class CreateAdministrationUserCommand(requestId: UUID,email: String,displayName: String,password: String,
  organizationId: Option[UUID],role: String,isAdministrator: Boolean) {
  override def toString: String = s"CreateAdministrationUserCommand($requestId,password=<redacted>)"
}
final case class ChangeMembershipCommand(role: String,isActive: Boolean,expectedUpdatedAt: Option[Instant])
final case class ChangeUserStatusCommand(isActive: Boolean,isAdministrator: Boolean,expectedUpdatedAt: Instant)

/** Global/selection principle: bounded credential-free projections and explicit object operations.
  * Every write reauthorizes after the shared access lock, and commits the change and audit together.
  */
final class Administration[Tx[_]: MonadThrow](
  repository: AdministrationRepository[Tx],users: UserAccountRepository[Tx],
  memberships: OrganizationMembershipRepository[Tx],organizations: OrganizationRepository[Tx],
  runner: TransactionRunner[IO,Tx],passwords: PasswordHasher,ids: IdGenerator[Tx],time: TimeProvider[Tx],
  audit: AuditRecorder[Tx]
) {
  import AdministrationScope._
  private def error(code: String): AdministrationError = AdministrationError(code,code)
  private def ensure(ok: Boolean,code: String): Tx[Unit] = if(ok) ().pure[Tx] else error(code).raiseError[Tx,Unit]
  private def activeActor(actor: UUID): Tx[Unit] = users.findActiveById(actor).flatMap(u => ensure(u.nonEmpty,"FORBIDDEN"))
  def authorizeInstallation(actor: UUID): Tx[Unit] =
    activeActor(actor) *> repository.isAdministrator(actor).flatMap(ensure(_,"FORBIDDEN"))
  private def authorize(actor: UUID,scope: AdministrationScope): Tx[Option[OrganizationRole]] = scope match {
    case Installation => authorizeInstallation(actor).as(None)
    case Organization(org) => activeActor(actor) *> memberships.findActiveRole(actor,org).flatMap {
      case Some(role @ (OrganizationRole.Owner | OrganizationRole.Administrator)) => role.some.pure[Tx]
      case Some(_) => error("FORBIDDEN").raiseError[Tx,Option[OrganizationRole]]
      case None => error("ORGANIZATION_NOT_FOUND").raiseError[Tx,Option[OrganizationRole]]
    }
  }
  private def grantable(actorRole: Option[OrganizationRole],newRole: OrganizationRole,oldRole: Option[OrganizationRole]): Tx[Unit] =
    ensure(actorRole != Some(OrganizationRole.Administrator) ||
      (!Set[OrganizationRole](OrganizationRole.Owner,OrganizationRole.Administrator).contains(newRole) &&
        !oldRole.exists(Set[OrganizationRole](OrganizationRole.Owner,OrganizationRole.Administrator).contains)),"FORBIDDEN")
  private def role(code: String): Tx[OrganizationRole] =
    OrganizationRole.fromCode(code).leftMap(_ => error("INVALID_REQUEST")).liftTo[Tx]
  private def fingerprint(parts: String*): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.map(p => s"${p.length}:$p").mkString.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  private def replay(requestId: UUID,actor: UUID,kind: String,hash: String): Tx[Option[UUID]] =
    repository.findRequest(requestId).flatMap {
      case None => none[UUID].pure[Tx]
      case Some(r) => ensure(r.actorId==actor && r.kind==kind && r.fingerprint==hash,"ADMINISTRATION_REQUEST_CONFLICT").as(Some(r.targetId))
    }
  private def record(actor: UUID,action: String,target: UUID,org: Option[UUID]): Tx[Unit] = for {
    id <- ids.nextId; now <- time.now
    _ <- repository.recordAudit(AdministrationAudit(id,actor,action,target,org,now))
    _ <- org.traverse_(organization => AuditAction.fromCode(action).liftTo[Tx].flatMap(a =>
      audit.record(ActorContext(actor,organization),a,AuditTargetType.Account,Some(target))))
  } yield ()
  def isAdministrator(actor: UUID): IO[Boolean] = runner.run(repository.isAdministrator(actor))
  def bootstrapAdministrator(email: Option[String]): IO[Unit] = email.traverse_ { raw => runner.run(for {
    _ <- repository.lockAccessChanges
    provisioned <- repository.administratorProvisioned
    _ <- if(provisioned) ().pure[Tx] else for {
      account <- users.findByEmail(UserAccount.normalizeEmail(raw))
      user <- account.filter(_.isActive).liftTo[Tx](error("BOOTSTRAP_ADMINISTRATOR_NOT_FOUND"))
      now <- time.now
      _ <- repository.bootstrapFirstAdministrator(user.id,now)
    } yield ()
  } yield ()) }

  def listUsers(actor: UUID,after: Option[UUID]): Tx[List[AdministrationUser]] =
    authorizeInstallation(actor) *> repository.listUsers(after,51)
  def listOrganizations(actor: UUID,after: Option[UUID]): Tx[List[AdministrationOrganization]] =
    authorizeInstallation(actor) *> repository.listOrganizations(after,101)
  def listMembers(actor: UUID,scope: AdministrationScope,org: UUID,after: Option[UUID]): Tx[List[AdministrationMember]] =
    authorize(actor,scope) *> ensure(scope==Installation || scope==Organization(org),"FORBIDDEN") *> repository.listMembers(org,after,51)
  def user(actor: UUID,id: UUID): Tx[AdministrationUser] =
    authorizeInstallation(actor) *> repository.findUser(id).flatMap(_.liftTo[Tx](error("USER_NOT_FOUND")))
  def userMemberships(actor: UUID,id: UUID,after: Option[UUID]): Tx[List[AdministrationMember]] =
    authorizeInstallation(actor) *> repository.listUserMemberships(id,after,51)
  def membership(actor: UUID,org: UUID,id: UUID): Tx[Option[AdministrationMember]] =
    authorizeInstallation(actor) *> organizations.findActiveById(org).flatMap(o => ensure(o.nonEmpty,"ORGANIZATION_NOT_FOUND")) *>
      repository.findMember(org,id)
  def events(actor: UUID,after: Option[UUID]): Tx[List[AdministrationAudit]] =
    authorizeInstallation(actor) *> repository.listAudit(after,51)

  def createOrganization(actor: UUID,c: CreateOrganizationCommand): Tx[AdministrationOrganization] = for {
    _ <- ensure(c.code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"),"INVALID_REQUEST")
    name <- AccountValidation.displayName(c.name).leftMap(_ => error("INVALID_REQUEST")).liftTo[Tx]
    _ <- repository.lockAccessChanges
    _ <- activeActor(actor)
    hash = fingerprint(c.code,name)
    previous <- replay(c.requestId,actor,"CREATE_ORGANIZATION",hash)
    result <- previous match {
      case Some(id) => memberships.findActiveRole(actor,id).flatMap(r => ensure(r.nonEmpty,"ORGANIZATION_NOT_FOUND")) *>
        organizations.findActiveById(id).flatMap(_.liftTo[Tx](error("ORGANIZATION_NOT_FOUND")))
        .map(o => AdministrationOrganization(o.id,o.code,o.name))
      case None => for {
        now <- time.now
        created <- repository.createOrganization(c.requestId,c.code,name,now)
        _ <- ensure(created,"ORGANIZATION_CODE_ALREADY_EXISTS")
        _ <- repository.saveMember(c.requestId,actor,OrganizationRole.Owner,true,now)
        _ <- repository.saveRequest(c.requestId,AdministrationRequest(actor,"CREATE_ORGANIZATION",hash,c.requestId),now)
        _ <- record(actor,"ORGANIZATION_CREATED",c.requestId,None)
        _ <- audit.record(ActorContext(actor,c.requestId),AuditAction.OrganizationCreated,AuditTargetType.Organization,Some(c.requestId))
      } yield AdministrationOrganization(c.requestId,c.code,name)
    }
  } yield result

  def createUser(actor: UUID,scope: AdministrationScope,c: CreateAdministrationUserCommand): IO[AdministrationUser] = {
    val validated = for {
      _ <- Either.cond(c.email.trim.length<=320 && c.email.trim.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),(),error("INVALID_REQUEST"))
      name <- AccountValidation.displayName(c.displayName).leftMap(_ => error("INVALID_REQUEST"))
      _ <- AccountValidation.password(c.password,"").leftMap(e => error(e.code))
      _ <- Either.cond(c.password.getBytes(StandardCharsets.UTF_8).length<=72,(),error("PASSWORD_TOO_LONG"))
    } yield (UserAccount.normalizeEmail(c.email),name)
    for {
      input <- IO.fromEither(validated)
      // Check access before expensive bcrypt; then check again in the mutation transaction.
      _ <- runner.run(authorize(actor,scope))
      hash <- passwords.hash(c.password)
      result <- runner.run(for {
        _ <- repository.lockAccessChanges
        actorRole <- authorize(actor,scope)
        targetRole <- role(c.role)
        _ <- grantable(actorRole,targetRole,None)
        _ <- ensure(scope==Installation || (c.organizationId.exists(org => scope==Organization(org)) && !c.isAdministrator),"FORBIDDEN")
        _ <- c.organizationId.traverse_(org => organizations.findActiveById(org).flatMap(o => ensure(o.nonEmpty,"ORGANIZATION_NOT_FOUND")))
        intent = fingerprint(input._1,input._2,c.organizationId.fold("")(_.toString),targetRole.code,c.isAdministrator.toString,scope.toString)
        previous <- replay(c.requestId,actor,"CREATE_USER",intent)
        result <- previous match {
          case Some(id) => repository.findUser(id).flatMap(_.liftTo[Tx](error("USER_NOT_FOUND")))
          case None => for {
            existing <- users.findByEmail(input._1)
            _ <- ensure(existing.isEmpty,"USER_EMAIL_ALREADY_EXISTS")
            now <- time.now
            account = UserAccount(c.requestId,input._1,hash,input._2,true,now,now)
            _ <- users.createIfMissing(account)
            actual <- users.findByEmail(input._1)
            _ <- ensure(actual.exists(_.id==c.requestId),"USER_EMAIL_ALREADY_EXISTS")
            _ <- c.organizationId.traverse_(org => repository.saveMember(org,c.requestId,targetRole,true,now))
            _ <- if(c.isAdministrator) repository.setUserStatus(c.requestId,true,true,now,now).flatMap(ensure(_,"ADMINISTRATION_STALE")) else ().pure[Tx]
            _ <- repository.saveRequest(c.requestId,AdministrationRequest(actor,"CREATE_USER",intent,c.requestId),now)
            _ <- record(actor,"USER_CREATED",c.requestId,c.organizationId)
            stored <- repository.findUser(c.requestId).flatMap(_.liftTo[Tx](error("USER_NOT_FOUND")))
          } yield stored
        }
      } yield result)
    } yield result
  }

  def changeMembership(actor: UUID,scope: AdministrationScope,org: UUID,userId: UUID,c: ChangeMembershipCommand): Tx[AdministrationMember] = for {
    _ <- repository.lockAccessChanges
    actorRole <- authorize(actor,scope)
    _ <- ensure(scope==Installation || scope==Organization(org),"FORBIDDEN")
    _ <- organizations.findActiveById(org).flatMap(o => ensure(o.nonEmpty,"ORGANIZATION_NOT_FOUND"))
    target <- repository.findUser(userId).flatMap(_.filter(_.isActive).liftTo[Tx](error("USER_NOT_FOUND")))
    old <- repository.findMember(org,userId)
    newRole <- role(c.role)
    _ <- grantable(actorRole,newRole,old.map(_.role))
    _ <- ensure(old.map(_.updatedAt)==c.expectedUpdatedAt,"ADMINISTRATION_STALE")
    _ <- if(old.exists(m => m.isActive && m.role==OrganizationRole.Owner) && (!c.isActive || newRole!=OrganizationRole.Owner))
      repository.countOtherOwners(org,target.id).flatMap(n => ensure(n>0,"LAST_ORGANIZATION_OWNER")) else ().pure[Tx]
    now <- time.now
    _ <- repository.saveMember(org,userId,newRole,c.isActive,now)
    _ <- record(actor,"MEMBERSHIP_CHANGED",userId,Some(org))
    result <- repository.findMember(org,userId).flatMap(_.liftTo[Tx](error("USER_NOT_FOUND")))
  } yield result

  def addMemberByEmail(actor: UUID,org: UUID,email: String,c: ChangeMembershipCommand): Tx[AdministrationMember] = for {
    _ <- repository.lockAccessChanges
    _ <- authorize(actor,Organization(org))
    target <- users.findByEmail(UserAccount.normalizeEmail(email)).flatMap(_.filter(_.isActive).liftTo[Tx](error("USER_NOT_FOUND")))
    result <- changeMembership(actor,Organization(org),org,target.id,c.copy(expectedUpdatedAt=None))
  } yield result

  def changeUserStatus(actor: UUID,id: UUID,c: ChangeUserStatusCommand): Tx[AdministrationUser] = for {
    _ <- repository.lockAccessChanges
    _ <- authorizeInstallation(actor)
    old <- repository.findUser(id).flatMap(_.liftTo[Tx](error("USER_NOT_FOUND")))
    _ <- ensure(c.isActive || !c.isAdministrator,"INVALID_REQUEST")
    _ <- if(!c.isActive && old.isActive) repository.hasSoleOwnership(id).flatMap(sole => ensure(!sole,"LAST_ORGANIZATION_OWNER")) else ().pure[Tx]
    _ <- if(old.isActive && old.isAdministrator && (!c.isActive || !c.isAdministrator))
      repository.countOtherAdministrators(id).flatMap(n => ensure(n>0,"LAST_INSTALLATION_ADMINISTRATOR")) else ().pure[Tx]
    now <- time.now
    changed <- repository.setUserStatus(id,c.isActive,c.isAdministrator,c.expectedUpdatedAt,now)
    _ <- ensure(changed,"ADMINISTRATION_STALE")
    _ <- if(!c.isActive) repository.revokeUserSessions(id,now) else ().pure[Tx]
    _ <- record(actor,"USER_STATUS_CHANGED",id,None)
    result <- repository.findUser(id).flatMap(_.liftTo[Tx](error("USER_NOT_FOUND")))
  } yield result
}

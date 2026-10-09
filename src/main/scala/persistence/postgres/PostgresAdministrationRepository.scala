package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.auth.OrganizationRole
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class PostgresAdministrationRepository extends AdministrationRepository[ConnectionIO] {
  private val userColumns = fr"u.id,u.email,u.display_name,u.is_active,coalesce(a.is_active,false),u.updated_at"
  private val userFrom = fr"from user_account u left join installation_administrator a on a.user_id=u.id"
  private type MemberRow = (UUID,String,String,Boolean,Boolean,Instant,UUID,String,Boolean,Instant,String)
  private def member(row: MemberRow): ConnectionIO[AdministrationMember] = {
    val (id,email,name,active,admin,userAt,org,role,memberActive,at,orgName)=row
    OrganizationRole.fromCode(role).liftTo[ConnectionIO].map(r =>
      AdministrationMember(AdministrationUser(id,email,name,active,admin,userAt),org,r,memberActive,at,orgName))
  }
  private def cursor(column: Fragment, after: Option[UUID]): Fragment =
    after.fold(Fragment.empty)(id => fr"and" ++ column ++ fr"> $id")

  def lockAccessChanges: ConnectionIO[Unit] =
    sql"select id from administration_access_guard where id=true for update".query[Boolean].unique.void
  def administratorProvisioned: ConnectionIO[Boolean] =
    sql"select exists(select 1 from installation_administrator)".query[Boolean].unique
  def isAdministrator(userId: UUID): ConnectionIO[Boolean] =
    sql"""select exists(select 1 from installation_administrator a join user_account u on u.id=a.user_id
      where a.user_id=$userId and a.is_active and u.is_active)""".query[Boolean].unique
  def bootstrapFirstAdministrator(userId: UUID, at: Instant): ConnectionIO[Unit] =
    lockAccessChanges *> sql"""insert into installation_administrator(user_id,created_at,updated_at)
      select $userId,$at,$at where not exists(select 1 from installation_administrator)
      and exists(select 1 from user_account where id=$userId and is_active)""".update.run.flatMap { count =>
        if(count==0) ().pure[ConnectionIO]
        else recordAudit(AdministrationAudit(UUID.randomUUID(),userId,"ADMINISTRATOR_BOOTSTRAPPED",userId,None,at))
      }
  def findUser(userId: UUID): ConnectionIO[Option[AdministrationUser]] =
    (fr"select" ++ userColumns ++ userFrom ++ fr"where u.id=$userId").query[AdministrationUser].option
  def listUsers(after: Option[UUID], limit: Int): ConnectionIO[List[AdministrationUser]] =
    (fr"select" ++ userColumns ++ userFrom ++ fr"where true" ++ cursor(fr"u.id",after) ++
      fr"order by u.id limit $limit").query[AdministrationUser].to[List]
  def listOrganizations(after: Option[UUID], limit: Int): ConnectionIO[List[AdministrationOrganization]] =
    (fr"select id,code,name from organization where is_active" ++ cursor(fr"id",after) ++
      fr"order by id limit $limit").query[AdministrationOrganization].to[List]
  private val memberSelect = fr"select" ++ userColumns ++
    fr",m.organization_id,m.role,m.is_active,m.updated_at,o.name" ++ userFrom ++
    fr"join organization_membership m on m.user_id=u.id join organization o on o.id=m.organization_id"
  def listMembers(org: UUID, after: Option[UUID], limit: Int): ConnectionIO[List[AdministrationMember]] =
    (memberSelect ++ fr"where m.organization_id=$org and o.is_active" ++ cursor(fr"u.id",after) ++
      fr"order by u.id limit $limit").query[MemberRow].to[List].flatMap(_.traverse(member))
  def listUserMemberships(userId: UUID, after: Option[UUID], limit: Int): ConnectionIO[List[AdministrationMember]] =
    (memberSelect ++ fr"where u.id=$userId and o.is_active" ++ cursor(fr"m.organization_id",after) ++
      fr"order by m.organization_id limit $limit").query[MemberRow].to[List].flatMap(_.traverse(member))
  def findMember(org: UUID, userId: UUID): ConnectionIO[Option[AdministrationMember]] =
    (memberSelect ++ fr"where m.organization_id=$org and u.id=$userId and o.is_active")
      .query[MemberRow].option.flatMap(_.traverse(member))
  def saveMember(org: UUID,userId: UUID,role: OrganizationRole,active: Boolean,at: Instant): ConnectionIO[Unit] =
    sql"""insert into organization_membership(user_id,organization_id,role,is_active,created_at,updated_at)
      values($userId,$org,${role.code},$active,$at,$at) on conflict(user_id,organization_id)
      do update set role=excluded.role,is_active=excluded.is_active,updated_at=excluded.updated_at""".update.run.void
  def countOtherOwners(org: UUID,userId: UUID): ConnectionIO[Long] =
    sql"""select count(*) from organization_membership m join user_account u on u.id=m.user_id
      where m.organization_id=$org and m.user_id<>$userId and m.role='OWNER' and m.is_active and u.is_active"""
      .query[Long].unique
  def hasSoleOwnership(userId: UUID): ConnectionIO[Boolean] =
    sql"""select exists(select 1 from organization_membership m join organization o on o.id=m.organization_id
      where m.user_id=$userId and m.is_active and m.role='OWNER' and o.is_active
      and not exists(select 1 from organization_membership other join user_account u on u.id=other.user_id
        where other.organization_id=m.organization_id and other.user_id<>$userId
        and other.role='OWNER' and other.is_active and u.is_active))""".query[Boolean].unique
  def countOtherAdministrators(userId: UUID): ConnectionIO[Long] =
    sql"""select count(*) from installation_administrator a join user_account u on u.id=a.user_id
      where a.user_id<>$userId and a.is_active and u.is_active""".query[Long].unique
  def setUserStatus(userId: UUID,active: Boolean,administrator: Boolean,expected: Instant,at: Instant): ConnectionIO[Boolean] =
    sql"""update user_account set is_active=$active,updated_at=$at where id=$userId and updated_at=$expected"""
      .update.run.flatMap { count =>
        if(count==0) false.pure[ConnectionIO]
        else sql"""insert into installation_administrator(user_id,is_active,created_at,updated_at)
          values($userId,$administrator,$at,$at) on conflict(user_id)
          do update set is_active=excluded.is_active,updated_at=excluded.updated_at""".update.run.as(true)
      }
  def revokeUserSessions(userId: UUID,at: Instant): ConnectionIO[Unit] =
    sql"update auth_session set revoked_at=$at where user_id=$userId and revoked_at is null".update.run.void
  def createOrganization(id: UUID,code: String,name: String,at: Instant): ConnectionIO[Boolean] =
    sql"""insert into organization(id,code,name,created_at,updated_at)
      values($id,$code,$name,$at,$at) on conflict do nothing""".update.run.map(_==1)
  def findRequest(id: UUID): ConnectionIO[Option[AdministrationRequest]] =
    sql"select actor_id,kind,fingerprint,target_id from administration_request where id=$id".query[AdministrationRequest].option
  def saveRequest(id: UUID,r: AdministrationRequest,at: Instant): ConnectionIO[Unit] =
    sql"""insert into administration_request(id,actor_id,kind,fingerprint,target_id,created_at)
      values($id,${r.actorId},${r.kind},${r.fingerprint},${r.targetId},$at)""".update.run.void
  def recordAudit(e: AdministrationAudit): ConnectionIO[Unit] =
    sql"""insert into administration_audit(id,actor_id,action,target_id,organization_id,occurred_at)
      values(${e.id},${e.actorId},${e.action},${e.targetId},${e.organizationId},${e.occurredAt})""".update.run.void
  def listAudit(after: Option[UUID],limit: Int): ConnectionIO[List[AdministrationAudit]] =
    // The cursor identifies a stored row; comparisons use both timestamp and id.
    (fr"select id,actor_id,action,target_id,organization_id,occurred_at from administration_audit where true" ++
      after.fold(Fragment.empty)(id => fr"and (occurred_at,id)<(select occurred_at,id from administration_audit where id=$id)") ++
      fr"order by occurred_at desc,id desc limit $limit").query[AdministrationAudit].to[List]
}

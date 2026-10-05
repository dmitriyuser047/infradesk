package ru.bitec.app.ops
package persistence.postgres

import application.port.ProvisioningRunRepository
import cats.syntax.all._
import domain.provisioning._
import io.circe.{Decoder, Encoder, Json}
import io.circe.parser.parse
import org.typelevel.doobie.Fragment
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class PostgresProvisioningRunRepository extends ProvisioningRunRepository[ConnectionIO] {
  import PostgresProvisioningRunRepository._
  override def insertPlan(run: ProvisioningRun): ConnectionIO[Unit] = for {
    stepsJson <- (Json.fromValues(run.input.steps.map(s => Json.fromString(s.code))).noSpaces).pure[ConnectionIO]
    profileJson = run.input.profileApply.map(profileSnapshotJson).map(_.noSpaces)
    _ <- sql"""insert into provisioning_run(id, organization_id, resource_id, run_kind, connection_id,
      connection_updated_at, resource_type, resource_kind, schema_version, steps, request_id,
      requested_by_user_id, current_step, status, created_at, updated_at, profile_apply_snapshot) values (${run.id}, ${run.organizationId}, ${run.resourceId},
      ${run.input.runKind.code}, ${run.input.connectionId}, ${run.input.connectionUpdatedAt}, ${run.input.resourceType},
      ${run.input.resourceKind}, ${run.input.schemaVersion}, cast($stepsJson as jsonb),
      ${Option.empty[UUID]}, ${run.requestedBy}, ${run.currentStep.map(_.code)}, ${run.state.code}, ${run.createdAt}, ${run.updatedAt}, cast($profileJson as jsonb))""".update.run
    _ <- run.input.steps.zipWithIndex.traverse_ { case (kind,position) =>
      sql"insert into provisioning_run_step(id, run_id, step_kind, position, status) values (${UUID.nameUUIDFromBytes((run.id.toString + ":" + kind.code).getBytes(java.nio.charset.StandardCharsets.UTF_8))}, ${run.id}, ${kind.code}, $position, 'PENDING')".update.run
    }
  } yield ()

  override def lockApproval(org: UUID, planId: UUID, requestId: UUID): ConnectionIO[Option[ProvisioningRun]] = for {
    resource <- sql"select resource_id from provisioning_run where organization_id=$org and id=$planId".query[UUID].option
    _ <- PostgresProvisioningLocks.lockRequest(org,requestId)
    byRequest <- selectRun(where = fr"organization_id = $org and request_id = $requestId").option
    result <- byRequest match {
      case some @ Some(_) => some.pure[ConnectionIO]
      case None => resource match {
        case None => Option.empty[ProvisioningRun].pure[ConnectionIO]
        case Some(resourceId) => for {
          _ <- PostgresProvisioningLocks.lockResource(org,resourceId)
          locked <- (fr"select " ++ columns ++ fr" from provisioning_run where organization_id=$org and id=$planId for update")
            .query[Row].option.map(_.map(decode))
        } yield locked
      }
    }
  } yield result

  override def start(org: UUID, planId: UUID, requestId: UUID, actorId: UUID,
    now: Instant): ConnectionIO[Option[(ProvisioningRun, Boolean)]] = for {
    resource <- sql"select resource_id from provisioning_run where organization_id=$org and id=$planId".query[UUID].option
    answer <- resource match {
      case None => Option.empty[(ProvisioningRun, Boolean)].pure[ConnectionIO]
      case Some(resourceId) => for {
        _ <- PostgresProvisioningLocks.lockRequestThenResource(org,requestId,resourceId)
        byRequest <- selectRun(where = fr"organization_id = $org and request_id = $requestId").option
        result <- byRequest match {
          case Some(run) => Option((run, false)).pure[ConnectionIO]
          case None => for {
            active <- sql"select exists(select 1 from provisioning_run where organization_id=$org and resource_id=$resourceId and status in ('QUEUED','RUNNING'))".query[Boolean].unique
            started <- if (active) Option.empty[(ProvisioningRun, Boolean)].pure[ConnectionIO]
              else sql"""update provisioning_run set request_id=$requestId, requested_by_user_id=$actorId,
                status='QUEUED', updated_at=$now where organization_id=$org and id=$planId and status='PLANNED'
                and created_at > ($now - interval '24 hours')
                and created_at > (clock_timestamp() - interval '24 hours')""".update.run.flatMap {
                case 1 => find(org, planId).map(_.map(x => x._1 -> true))
                case _ => Option.empty[(ProvisioningRun, Boolean)].pure[ConnectionIO]
              }
          } yield started
        }
      } yield result
    }
  } yield answer

  override def deleteExpiredPlans(before: Instant, limit: Int, scope: Option[UUID]): ConnectionIO[Int] =
    (fr"with expired as (select id from provisioning_run where status='PLANNED' and created_at <= $before" ++
      scope.fold(fr"")(org => fr" and organization_id=$org") ++
      fr" order by created_at,id for update skip locked limit ${limit.max(1).min(500)}), deleted as (delete from provisioning_run r using expired e where r.id=e.id and r.status='PLANNED' returning r.id) select count(*) from deleted")
      .query[Long].unique.map(_.toInt)

  override def find(org: UUID, id: UUID): ConnectionIO[Option[(ProvisioningRun, List[ProvisioningStep])]] =
    selectRun(where = fr"organization_id = $org and id = $id").option.flatMap {
      case None => Option.empty[(ProvisioningRun, List[ProvisioningStep])].pure[ConnectionIO]
      case Some(run) => steps(id).map(s => Some(run -> s))
    }

  override def findRequest(org: UUID, requestId: UUID): ConnectionIO[Option[ProvisioningRun]] =
    selectRun(where = fr"organization_id = $org and request_id = $requestId").option

  override def history(org: UUID, resourceId: Option[UUID], limit: Int): ConnectionIO[List[ProvisioningRun]] =
    (fr"select " ++ columns ++ fr" from provisioning_run where organization_id = $org and status <> 'PLANNED'" ++
      resourceId.fold(fr"")(id => fr" and resource_id = $id") ++ fr" order by created_at desc, id desc limit ${limit.max(1).min(100)}")
      .query[Row].to[List].map(_.map(decode))

  override def latestProfileApply(org: UUID, resourceId: UUID, assignmentId: UUID,
    assignmentVersion: Long, revisionId: UUID, connectionId: UUID, connectionUpdatedAt: Instant): ConnectionIO[Option[ProvisioningRun]] =
    (fr"select " ++ columns ++ fr" from provisioning_run r where r.organization_id=$org and r.resource_id=$resourceId" ++
      fr" and r.run_kind='SERVER_PROFILE_APPLY' and r.connection_id=$connectionId and r.connection_updated_at=$connectionUpdatedAt" ++
      fr" and r.status <> 'PLANNED' and r.profile_apply_snapshot->>'assignmentId'=$assignmentId::text" ++
      fr" and (r.profile_apply_snapshot->>'assignmentVersion')::bigint=$assignmentVersion" ++
      fr" and r.profile_apply_snapshot->>'revisionId'=$revisionId::text" ++
      fr" order by case when r.status in ('QUEUED','RUNNING') then 0 else 1 end, coalesce(r.started_at,r.updated_at) desc, r.id desc limit 1")
      .query[Row].option.map(_.map(decode))

  override def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int,
    scope: Option[UUID]): ConnectionIO[List[ProvisioningRun]] = for {
    _ <- (fr"""with expired as (select id from provisioning_run where status = 'RUNNING' and claim_until <= $now""" ++
      scope.fold(fr"")(org => fr" and organization_id=$org") ++ fr""" for update skip locked)
      update provisioning_run r set status = 'UNKNOWN', failure_code = 'PROVISIONING_LEASE_EXPIRED',
        safe_message='The worker lease expired; confirm the server state before continuing.',
        updated_at = $now, finished_at = $now, claimed_by = null, claim_token = null, claim_until = null
      from expired where r.id = expired.id""").update.run
    _ <- (fr"""update provisioning_run_step s set status = case when s.status = 'RUNNING' then 'UNKNOWN' else 'SKIPPED' end,
        finished_at = $now, failure_code = case when s.status = 'RUNNING' then 'PROVISIONING_LEASE_EXPIRED' else s.failure_code end
      from provisioning_run r where s.run_id = r.id and r.status = 'UNKNOWN' and r.failure_code = 'PROVISIONING_LEASE_EXPIRED'
        and s.status in ('PENDING','RUNNING')""" ++ scope.fold(fr"")(org => fr" and r.organization_id=$org")).update.run
      rows <- (fr"""with picked as (select id from provisioning_run where status = 'QUEUED'""" ++
        scope.fold(fr"")(org => fr" and organization_id=$org") ++ fr""" order by created_at, id for update skip locked limit $limit), changed as (
        update provisioning_run r set status='RUNNING', current_step='PREFLIGHT', claimed_by=$owner, claim_token=$token,
          claim_until=$until, started_at=coalesce(started_at,$now), updated_at=$now
        from picked where r.id=picked.id returning r.*) select """ ++ columns ++ fr" from changed")
        .query[Row].to[List]
  } yield rows.map(decode)

  override def renew(org: UUID, id: UUID, token: UUID, now: Instant, until: Instant): ConnectionIO[Boolean] =
    lease(org,id,token,now).flatMap {
      case false => false.pure[ConnectionIO]
      case true => sql"update provisioning_run set claim_until=$until, updated_at=$now where organization_id=$org and id=$id and status='RUNNING' and claim_token=$token and claim_until > greatest($now, clock_timestamp())".update.run.map(_ == 1)
    }

  override def beginStep(org: UUID, id: UUID, token: UUID, kind: ProvisioningStepKind, now: Instant): ConnectionIO[Boolean] =
    lease(org,id,token,now).flatMap {
    case false => false.pure[ConnectionIO]
    case true => (sql"update provisioning_run set current_step=${kind.code}, updated_at=$now where organization_id=$org and id=$id and claim_token=$token and claim_until>greatest($now, clock_timestamp())".update.run,
      sql"""update provisioning_run_step s set status='RUNNING', started_at=$now, attempt=attempt+1
      from provisioning_run r where s.run_id=r.id and r.organization_id=$org and r.id=$id
      and r.status='RUNNING' and r.claim_token=$token and r.claim_until > greatest($now, clock_timestamp())
      and s.step_kind=${kind.code} and s.status='PENDING'""".update.run).mapN((runChanged, stepChanged) => runChanged == 1 && stepChanged == 1)
    }

  override def finishStep(org: UUID, id: UUID, token: UUID, kind: ProvisioningStepKind,
    state: ProvisioningStepState, facts: Map[String, String], failureCode: Option[String],
    outputTruncated: Boolean, now: Instant, verificationResult: Option[Boolean]): ConnectionIO[Boolean] =
    lease(org,id,token,now).flatMap {
    case false => false.pure[ConnectionIO]
    case true => {
    val factsJson = Json.obj(facts.toList.map { case (k,v) => k -> Json.fromString(v) }: _*).noSpaces
    val safeMessage = ProvisioningSafeMessage.forCode(failureCode)
    sql"""update provisioning_run_step s set status=${state.code}, finished_at=$now,
      facts=cast($factsJson as jsonb), failure_code=$failureCode,
      safe_message=$safeMessage,
      verification_result=$verificationResult,
      output_truncated=$outputTruncated,
      output_summary=case when ${state.code}='FAILED' then 'Step failed' when ${state.code}='SKIPPED' then 'Step skipped' else 'Step completed' end
      where s.run_id=$id and s.step_kind=${kind.code} and s.status='RUNNING'
      and exists(select 1 from provisioning_run r where r.organization_id=$org and r.id=$id
        and r.status='RUNNING' and r.claim_token=$token and r.claim_until>greatest($now, clock_timestamp()))""".update.run.map(_ == 1)
    }
    }

  override def skipPending(org: UUID, id: UUID, token: UUID, now: Instant): ConnectionIO[Boolean] =
    lease(org,id,token,now).flatMap {
      case false => false.pure[ConnectionIO]
      case true => sql"""update provisioning_run_step set status='SKIPPED', finished_at=$now where run_id=$id and status='PENDING'
        and exists(select 1 from provisioning_run where organization_id=$org and id=$id and claim_token=$token
          and status='RUNNING' and claim_until>greatest($now, clock_timestamp()))""".update.run.map(_ >= 0)
    }

  override def finish(org: UUID, id: UUID, token: UUID, state: ProvisioningRunState,
    failureCode: Option[String], now: Instant): ConnectionIO[Boolean] =
    lease(org,id,token,now).flatMap {
    case false => false.pure[ConnectionIO]
    case true => {
    val safeMessage = ProvisioningSafeMessage.forCode(failureCode)
    sql"""update provisioning_run set status=${state.code}, failure_code=$failureCode, safe_message=$safeMessage,
      updated_at=$now, finished_at=$now, claimed_by=null, claim_token=null, claim_until=null
      where organization_id=$org and id=$id and status='RUNNING' and claim_token=$token and claim_until > greatest($now, clock_timestamp())
      and (${state.code} <> 'SUCCEEDED' or (
        (select count(*) from provisioning_run_step where run_id=$id and status in ('SUCCEEDED','SKIPPED')) =
          (select jsonb_array_length(steps) from provisioning_run where id=$id)
        and (select count(*) from provisioning_run_step where run_id=$id and step_kind='PREFLIGHT' and status='SUCCEEDED') = 1
        and (select count(*) from provisioning_run_step where run_id=$id and step_kind='VERIFY' and status='SUCCEEDED' and verification_result=true) = 1
        and not exists (select 1 from provisioning_run_step where run_id=$id and status='SKIPPED' and
          (step_kind not in ('INSTALL_PACKAGES','CONFIGURE_NETWORK','CONFIGURE_LIMITS','CONFIGURE_FIREWALL','CONFIGURE_FAIL2BAN','CONFIGURE_DOCKER','DEPLOY_SITE','CONFIGURE_CADDY')
            or coalesce(facts->>'skipReason','') not in ('NOT_MANAGED','ALREADY_COMPLIANT')))
      ))""".update.run.map(_ == 1)
    }
    }

  private def steps(id: UUID): ConnectionIO[List[ProvisioningStep]] =
    sql"select step_kind, status, started_at, finished_at, facts::text, failure_code, attempt, safe_message, output_summary, verification_result, output_truncated, position from provisioning_run_step where run_id=$id order by position"
      .query[(String,String,Option[Instant],Option[Instant],String,Option[String],Int,Option[String],Option[String],Option[Boolean],Boolean,Int)].to[List].map(_.map {
        case (kind,state,start,end,facts,code,attempt,message,output,verified,truncated,position) => ProvisioningStep(id, ProvisioningStepKind.fromCode(kind),
          ProvisioningStepState.fromCode(state), start, end, parse(facts).toOption.flatMap(_.as[Map[String,String]].toOption).getOrElse(Map.empty), code,
          attempt,message,output,verified,truncated,Some(position))
      })

  private def lease(org: UUID,id: UUID,token: UUID,now: Instant): ConnectionIO[Boolean] =
    // Lock first, then evaluate the deadline. A deadline predicate attached to FOR UPDATE can
    // be evaluated before waiting for the lock and remain true after the lease expires.
    sql"select id from provisioning_run where organization_id=$org and id=$id and status='RUNNING' and claim_token=$token for update".query[UUID].option.flatMap {
      case None => false.pure[ConnectionIO]
      case Some(_) => sql"select exists(select 1 from provisioning_run where organization_id=$org and id=$id and status='RUNNING' and claim_token=$token and claim_until>greatest($now, clock_timestamp()))".query[Boolean].unique
    }

  private def selectRun(where: Fragment) = (fr"select " ++ columns ++ fr" from provisioning_run where " ++ where).query[Row].map(decode)
}

private object PostgresProvisioningRunRepository {
  import io.circe.parser.parse
  private type Row = (UUID,UUID,UUID,String,Option[UUID],Option[UUID],Option[String],UUID,Instant,String,String,Int,String,String,Instant,Instant,Option[Instant],Option[Instant],Option[String],Option[UUID],Option[Instant],Option[String])
  private val columns = fr"id, organization_id, resource_id, run_kind, request_id, requested_by_user_id, current_step, connection_id, connection_updated_at, resource_type, resource_kind, schema_version, steps::text, status, created_at, updated_at, started_at, finished_at, failure_code, claim_token, claim_until, profile_apply_snapshot::text"
  private def decode(row: Row): ProvisioningRun = {
    val (id,org,res,runKind,req,requestedBy,currentStep,conn,connAt,typ,kind,version,steps,status,created,updated,started,finished,failure,token,until,profileJson) = row
    val profile = profileJson.flatMap(s => parse(s).toOption.flatMap(decodeProfileSnapshot))
    require(runKind != ProvisioningRunKind.ServerProfileApply.code || profile.nonEmpty, "Invalid persisted server profile apply snapshot")
    ProvisioningRun(id,org,res,req,requestedBy,ProvisioningInputSnapshot(version,ProvisioningRunKind.fromCode(runKind),org,res,typ,kind,conn,connAt,
      parse(steps).toOption.flatMap(_.as[List[String]].toOption).getOrElse(Nil).map(ProvisioningStepKind.fromCode),profile),ProvisioningRunState.fromCode(status),created,updated,failure,token,until,
      currentStep.map(ProvisioningStepKind.fromCode),ProvisioningSafeMessage.forCode(failure),started,finished)
  }

  private def profileSnapshotJson(p: ServerProfileApplySnapshot): Json = Json.obj(
    "assignmentId" -> Json.fromString(p.assignmentId.toString), "assignmentVersion" -> Json.fromLong(p.assignmentVersion),
    "profileId" -> Json.fromString(p.profileId.toString), "revisionId" -> Json.fromString(p.revisionId.toString),
    "revisionNumber" -> Json.fromInt(p.revisionNumber), "revisionHash" -> Json.fromString(p.revisionHash),
    "content" -> p.content.json, "observationId" -> Json.fromString(p.observationId.toString),
    "observationHash" -> Json.fromString(p.observationHash), "reviewedDiffHash" -> Json.fromString(p.reviewedDiffHash),
    "blockingProblems" -> Json.fromValues(p.blockingProblems.map(Json.fromString)))

  private def decodeProfileSnapshot(j: Json): Option[ServerProfileApplySnapshot] = for {
    _ <- Option.when(j.asObject.exists(_.keys.toSet == Set("assignmentId","assignmentVersion","profileId","revisionId","revisionNumber","revisionHash","content","observationId","observationHash","reviewedDiffHash","blockingProblems")))(())
    c <- j.hcursor.downField("content").focus
    assignment <- uuid(j,"assignmentId"); av <- j.hcursor.get[Long]("assignmentVersion").toOption
    profile <- uuid(j,"profileId"); revision <- uuid(j,"revisionId"); rn <- j.hcursor.get[Int]("revisionNumber").toOption
    rh <- j.hcursor.get[String]("revisionHash").toOption; observation <- uuid(j,"observationId")
    content <- ServerProfileContent.parsePersisted(c.noSpaces,rh).toOption
    oh <- j.hcursor.get[String]("observationHash").toOption; dh <- j.hcursor.get[String]("reviewedDiffHash").toOption
    _ <- Option.when(av > 0 && rn > 0 && List(rh,oh,dh).forall(_.matches("[0-9a-f]{64}")))(())
    blockers <- j.hcursor.get[List[String]]("blockingProblems").toOption.filter(xs => xs.size <= 64 && xs.forall(_.matches("[A-Z0-9_]{1,64}")))
  } yield ServerProfileApplySnapshot(assignment,av,profile,revision,rn,content.hash,content,observation,oh,dh,blockers)
  private def uuid(j: Json, key: String): Option[UUID] = j.hcursor.get[String](key).toOption.flatMap(s => scala.util.Try(UUID.fromString(s)).toOption)
}

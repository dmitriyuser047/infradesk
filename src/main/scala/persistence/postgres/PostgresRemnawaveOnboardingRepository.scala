package ru.bitec.app.ops
package persistence.postgres

import application.port._
import application.integration.IntegrationError
import cats.syntax.all._
import domain.integration._
import domain.provisioning.ProvisioningRunState
import io.circe.parser.parse
import org.typelevel.doobie.{ConnectionIO,Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class PostgresRemnawaveOnboardingRepository extends RemnawaveOnboardingRepository[ConnectionIO] {
  private case class Row(id: UUID, org: UUID, integration: UUID, resource: UUID, request: Option[UUID], actor: UUID,
    state: String, phase: String, snapshot: String, created: Instant, updated: Instant,
    node: Option[UUID], baseline: Option[UUID], sync: Option[UUID], failure: Option[String],
    started: Option[Instant], finished: Option[Instant], token: Option[UUID], deadline: Option[Instant], connectivity: Option[String]) {
    def domain = RemnawaveNodeOnboardingRun(id,org,integration,resource,request,actor,ProvisioningRunState.fromCode(state),
      OnboardingPhase.fromCode(phase),OnboardingSnapshotCodec.decode(parse(snapshot).toOption.get),created,updated,
      node,baseline,sync,failure,started,finished,token,deadline,connectivity.map(j => PanelConnectivityFinding.decode(parse(j).toOption.get)))
  }
  private val columns = fr"id,organization_id,integration_id,resource_id,request_id,created_by,state,phase,input_snapshot::text,created_at,updated_at,external_node_id,baseline_run_id,sync_session_id,failure_code,started_at,finished_at,claim_token,claim_deadline,connectivity_finding::text"
  private def selected(where: Fragment) = (fr"select" ++ columns ++ fr"from remnawave_node_onboarding where" ++ where).query[Row].map(_.domain)
  private def fail(code: String): ConnectionIO[Nothing] = IntegrationError(code,"Remnawave onboarding could not proceed").raiseError[ConnectionIO,Nothing]
  def lockResource(org: UUID, resourceId: UUID): ConnectionIO[Unit] = PostgresProvisioningLocks.lockResource(org,resourceId)
  def active(org: UUID, resourceId: UUID): ConnectionIO[Boolean] =
    (fr"select" ++ RemnawaveOnboardingActivitySql.resource(org,fr"$resourceId")).query[Boolean].unique

  def insertPlan(r: RemnawaveNodeOnboardingRun): ConnectionIO[Unit] = for {
    _ <- sql"""insert into remnawave_node_onboarding(id,organization_id,integration_id,resource_id,created_by,state,phase,input_snapshot,created_at,updated_at,external_node_id)
      values(${r.id},${r.organizationId},${r.integrationId},${r.resourceId},${r.createdBy},'PLANNED','VALIDATE',
      cast(${OnboardingSnapshotCodec.encode(r.snapshot).noSpaces} as jsonb),${r.createdAt},${r.updatedAt},${r.externalNodeId})""".update.run
    _ <- OnboardingPhase.forSnapshot(r.snapshot).zipWithIndex.traverse_ { case (p,i) => sql"""insert into remnawave_node_onboarding_phase(run_id,phase,position,state)
      values(${r.id},${p.code},$i,'PENDING')""".update.run }
  } yield ()
  def start(org: UUID, integration: UUID, plan: UUID, request: UUID, actor: UUID, now: Instant, confirmRecreate: Boolean = false): ConnectionIO[RemnawaveNodeOnboardingRun] =
    startResult(org,integration,plan,request,actor,now,confirmRecreate).map(_.run)
  def startResult(org: UUID, integration: UUID, plan: UUID, request: UUID, actor: UUID, now: Instant,
    confirmRecreate: Boolean = false): ConnectionIO[OnboardingStartResult] = for {
    _ <- PostgresProvisioningLocks.lockRequest(org,request)
    existing <- selected(fr"organization_id=$org and request_id=$request").option
    result <- existing match {
      case Some(r) if r.id == plan && r.integrationId == integration => OnboardingStartResult(r,false).pure[ConnectionIO]
      case Some(_) => fail("REMNAWAVE_ONBOARDING_REQUEST_REUSED")
      case None => for {
        _ <- sql"select id from integration where organization_id=$org and id=$integration and deleted_at is null for update".query[UUID].option
          .flatMap(_.liftTo[ConnectionIO](IntegrationError("REMNAWAVE_ONBOARDING_NOT_FOUND","Integration was not found")))
        draft <- selected(fr"organization_id=$org and integration_id=$integration and id=$plan").option
          .flatMap(_.liftTo[ConnectionIO](IntegrationError("REMNAWAVE_ONBOARDING_NOT_FOUND","Onboarding plan was not found")))
        _ <- lockResource(org,draft.resourceId)
        locked <- (fr"select" ++ columns ++ fr"from remnawave_node_onboarding where organization_id=$org and id=$plan for update").query[Row].unique.map(_.domain)
        _ <- if (locked.state != ProvisioningRunState.Planned) fail("REMNAWAVE_ONBOARDING_ALREADY_STARTED") else ().pure[ConnectionIO]
        _ <- if (locked.snapshot.recovery.exists(!_.reusesNode) && !confirmRecreate)
          fail("REMNAWAVE_ONBOARDING_RECREATE_CONFIRMATION_REQUIRED") else ().pure[ConnectionIO]
        expired <- sql"select ${locked.createdAt} <= greatest($now,clock_timestamp())-interval '24 hours'".query[Boolean].unique
        _ <- if (expired) fail("PROVISIONING_PLAN_EXPIRED") else ().pure[ConnectionIO]
        busy <- active(org,locked.resourceId)
        _ <- if (busy) fail("REMNAWAVE_ONBOARDING_RESOURCE_BUSY") else ().pure[ConnectionIO]
        previous <- createdNodes(org,locked.resourceId)
        recovery <- RemnawaveNodeOnboardingRun.recoveryCandidate(previous,integration,locked.snapshot.input,
          locked.snapshot.imageReference,locked.snapshot.connectionId).leftMap(code => IntegrationError(code,"Existing node requires review")).liftTo[ConnectionIO]
        chainValid=locked.snapshot.recovery match {
          case Some(proof) => recovery.exists(r => r.id==proof.sourceRunId &&
            proof.previousImageReference.getOrElse(locked.snapshot.imageReference) == (if(r.externalNodeId.isEmpty &&
              !(r.state==ProvisioningRunState.Unknown && r.phase==OnboardingPhase.CreateNode))
              r.snapshot.recovery.flatMap(_.previousImageReference).getOrElse(r.snapshot.installationImageReference)
              else r.snapshot.installationImageReference)) &&
            Set("PRESENT_EXACT","PRESENT_UNHEALTHY","CONFIRMED_NOT_FOUND")(proof.state) &&
            (if(proof.reusesNode) locked.externalNodeId.nonEmpty && locked.snapshot.correlationId==proof.previousCorrelationId
             else locked.externalNodeId.isEmpty && proof.previousExternalNodeId.nonEmpty &&
               locked.snapshot.correlationId!=proof.previousCorrelationId)
          case None => recovery.flatMap(_.externalNodeId)==locked.externalNodeId &&
            recovery.forall(_.snapshot.correlationId==locked.snapshot.correlationId)
        }
        _ <- if (!chainValid) fail("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED") else ().pure[ConnectionIO]
        _ <- if (locked.snapshot.blockers.nonEmpty) fail("REMNAWAVE_ONBOARDING_BLOCKED") else ().pure[ConnectionIO]
        _ <- if (locked.snapshot.lifecycleVersion>=3 && locked.snapshot.recovery.exists(r =>
          r.localInstallation.forall(!_.state.repairable))) fail("REMNAWAVE_ONBOARDING_BLOCKED") else ().pure[ConnectionIO]
        started <- (fr"update remnawave_node_onboarding set state='QUEUED',request_id=$request,updated_at=$now where id=$plan returning" ++ columns)
          .query[Row].unique.map(_.domain)
      } yield OnboardingStartResult(started,true)
    }
  } yield result
  def find(org: UUID, integration: UUID, id: UUID): ConnectionIO[Option[(RemnawaveNodeOnboardingRun,List[OnboardingPhaseRecord])]] =
    selected(fr"organization_id=$org and integration_id=$integration and id=$id").option.flatMap(_.traverse(r =>
      sql"select phase,state,started_at,finished_at,failure_code from remnawave_node_onboarding_phase where run_id=$id order by position"
        .query[(String,String,Option[Instant],Option[Instant],Option[String])].to[List].map(rows => r -> rows.map {
          case(p,s,a,b,c) => OnboardingPhaseRecord(OnboardingPhase.fromCode(p),s,a,b,c) })))
  def history(org: UUID, integration: UUID, limit: Int): ConnectionIO[List[RemnawaveNodeOnboardingRun]] =
    selected(fr"organization_id=$org and integration_id=$integration and state<>'PLANNED' order by created_at desc,id desc limit $limit").to[List]
  def createdNodes(org: UUID, resource: UUID): ConnectionIO[List[RemnawaveNodeOnboardingRun]] =
    (fr"""with candidates as (select * from remnawave_node_onboarding
      where organization_id=$org and resource_id=$resource and state<>'PLANNED' and
        (external_node_id is not null or input_snapshot->'recovery' is not null and input_snapshot->'recovery'<>'null'::jsonb
          or state='UNKNOWN' and phase='CREATE_NODE'))
      select""" ++ columns ++ fr"""from candidates r where not exists(select 1 from candidates child
        where child.input_snapshot->'recovery'->>'sourceRunId'=r.id::text)
      and not exists(select 1 from candidates later where later.external_node_id=r.external_node_id
        and (later.created_at,later.id)>(r.created_at,r.id))
      order by created_at desc,id desc limit 2""").query[Row].to[List].map(_.map(_.domain))
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): ConnectionIO[List[RemnawaveNodeOnboardingRun]] =
    (fr"""with picked as(select id from remnawave_node_onboarding
      where state='QUEUED' or (state='RUNNING' and claim_deadline<=greatest($now,clock_timestamp()))
      order by created_at,id for update skip locked limit $limit), changed as(
      update remnawave_node_onboarding r set state='RUNNING',claim_owner=$owner,claim_token=$token,claim_deadline=$until,
      started_at=coalesce(started_at,$now),updated_at=$now from picked where r.id=picked.id returning r.*)
      select""" ++ columns ++ fr"from changed").query[Row].to[List].map(_.map(_.domain))
  private def fence(r: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): ConnectionIO[Boolean] = for {
    locked <- sql"select id from remnawave_node_onboarding where organization_id=${r.organizationId} and id=${r.id} and state='RUNNING' and claim_token=$token for update".query[UUID].option
    valid <- if (locked.isEmpty) false.pure[ConnectionIO] else sql"select claim_deadline>greatest($now,clock_timestamp()) from remnawave_node_onboarding where id=${r.id}".query[Boolean].unique
  } yield valid
  def renew(r: RemnawaveNodeOnboardingRun, token: UUID, now: Instant, until: Instant): ConnectionIO[Boolean] =
    fence(r,token,now).flatMap(ok => if (!ok) false.pure[ConnectionIO] else
      sql"update remnawave_node_onboarding set claim_deadline=$until,updated_at=$now where id=${r.id}".update.run.map(_ == 1))
  def beginPhase(r: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): ConnectionIO[Boolean] = for {
    valid <- fence(r,token,now)
    _ <- if (!valid) fail("REMNAWAVE_ONBOARDING_LEASE_LOST") else ().pure[ConnectionIO]
    fresh <- sql"""update remnawave_node_onboarding_phase set state='RUNNING',started_at=$now
      where run_id=${r.id} and phase=${r.phase.code} and state='PENDING'
      and exists(select 1 from remnawave_node_onboarding where id=${r.id} and phase=${r.phase.code})""".update.run.map(_ == 1)
  } yield fresh
  def persist(r: RemnawaveNodeOnboardingRun, token: UUID, n: RemnawaveNodeOnboardingRun, now: Instant,
    complete: Boolean): ConnectionIO[Boolean] = for {
    valid <- fence(r,token,now)
    saved <- if (!valid) false.pure[ConnectionIO] else for {
      _ <- if (n.state.terminal || complete) sql"""update remnawave_node_onboarding_phase set state=${if (n.state.terminal && n.state!=ProvisioningRunState.Succeeded) n.state.code else "SUCCEEDED"},
        finished_at=$now,failure_code=${n.failureCode} where run_id=${r.id} and phase=${r.phase.code} and state='RUNNING'""".update.run else 0.pure[ConnectionIO]
      changed <- sql"""update remnawave_node_onboarding set state=${n.state.code},phase=${n.phase.code},external_node_id=coalesce(${n.externalNodeId},external_node_id),
        baseline_run_id=coalesce(${n.baselineRunId},baseline_run_id),sync_session_id=coalesce(${n.syncSessionId},sync_session_id),failure_code=${n.failureCode},updated_at=$now,
        connectivity_finding=cast(${n.connectivityFinding.map(f => PanelConnectivityFinding.storage(f).noSpaces)} as jsonb),
        finished_at=${Option.when(n.state.terminal)(now)},
        claim_owner=case when ${n.state.terminal} then null else claim_owner end,
        claim_token=case when ${n.state.terminal} then null else claim_token end,
        claim_deadline=case when ${n.state.terminal} then null else claim_deadline end
        where id=${r.id} and phase=${r.phase.code}""".update.run
    } yield changed==1
  } yield saved
  def attachBaseline(r: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): ConnectionIO[UUID] = for {
    _ <- lockResource(r.organizationId,r.resourceId)
    valid <- fence(r,token,now)
    _ <- if (!valid) fail("REMNAWAVE_ONBOARDING_LEASE_LOST") else ().pure[ConnectionIO]
    count <- sql"""update provisioning_run set onboarding_parent_id=${r.id}
      where id=${r.snapshot.baselinePlanId} and organization_id=${r.organizationId} and resource_id=${r.resourceId}
        and (onboarding_parent_id is null or onboarding_parent_id=${r.id})""".update.run
    _ <- if(count!=1) fail("REMNAWAVE_ONBOARDING_BASELINE_CHANGED") else ().pure[ConnectionIO]
    _ <- sql"update remnawave_node_onboarding set baseline_run_id=${r.snapshot.baselinePlanId},updated_at=$now where id=${r.id}".update.run
  } yield r.snapshot.baselinePlanId
  def attachSync(r: RemnawaveNodeOnboardingRun, token: UUID, sessionId: UUID, now: Instant): ConnectionIO[Unit] = for {
    valid <- fence(r,token,now)
    _ <- if (!valid) fail("REMNAWAVE_ONBOARDING_LEASE_LOST") else ().pure[ConnectionIO]
    count <- sql"""update remnawave_node_onboarding set sync_session_id=$sessionId,updated_at=$now
      where id=${r.id} and organization_id=${r.organizationId} and integration_id=${r.integrationId}""".update.run
    _ <- if (count!=1) fail("REMNAWAVE_ONBOARDING_SYNC_UNKNOWN") else ().pure[ConnectionIO]
  } yield ()
  def saveSecret(r: RemnawaveNodeOnboardingRun, token: UUID, secret: IntegrationSecret, now: Instant): ConnectionIO[Unit] = for {
    valid <- fence(r,token,now)
    _ <- if(!valid) fail("REMNAWAVE_ONBOARDING_LEASE_LOST") else ().pure[ConnectionIO]
    _ <- sql"""insert into remnawave_node_installation_secret(run_id,organization_id,kind,nonce,ciphertext)
      values(${r.id},${r.organizationId},${secret.kind},${secret.nonce},${secret.ciphertext})
      on conflict(run_id) do update set nonce=excluded.nonce,ciphertext=excluded.ciphertext""".update.run
  } yield ()
  def secret(r: RemnawaveNodeOnboardingRun): ConnectionIO[Option[IntegrationSecret]] =
    sql"select run_id,organization_id,kind,nonce,ciphertext from remnawave_node_installation_secret where run_id=${r.id} and organization_id=${r.organizationId}"
      .query[IntegrationSecret].option
  def deleteSecret(r: RemnawaveNodeOnboardingRun, token: UUID, now: Instant): ConnectionIO[Unit] = for {
    valid <- fence(r,token,now)
    _ <- if(!valid) fail("REMNAWAVE_ONBOARDING_LEASE_LOST") else ().pure[ConnectionIO]
    _ <- sql"delete from remnawave_node_installation_secret where run_id=${r.id} and organization_id=${r.organizationId}".update.run
  } yield ()
  def replaceFleetMembership(r: RemnawaveNodeOnboardingRun, token: UUID, node: UUID, now: Instant): ConnectionIO[Unit] =
    sql"select 1 from infradesk_onboarding_replace_membership(${r.organizationId},${r.id},$token,$node,$now)".query[Int].unique.void
  def cleanupPlans(before: Instant, limit: Int): ConnectionIO[Int] = sql"""delete from remnawave_node_onboarding where id in(
    select id from remnawave_node_onboarding where state='PLANNED' and created_at<=$before
    for update skip locked limit $limit) and state='PLANNED'""".update.run
}

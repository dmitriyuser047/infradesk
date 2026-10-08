package ru.bitec.app.ops
package persistence.postgres

import application.integration.IntegrationError
import application.port.RemnawaveOnboardingRepository
import cats.effect.{IO,Ref}
import cats.syntax.all._
import domain.integration._
import domain.provisioning._
import integration.secret.NodeInstallationCipher
import integration.ssh.SecretEncryptionConfig
import java.time.Instant
import java.util.{Base64, UUID}
import munit.FunSuite
import org.typelevel.doobie.{ConnectionIO,Transactor}
import org.typelevel.doobie.util.log.{LogEvent,LogHandler}
import infrastructure.database.DoobieTransactionRunner
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.AuthorizationFixtures
import scala.concurrent.duration._

/** PostgreSQL constraints, migrations, and repository behavior for durable onboarding plans. */
final class RemnawaveOnboardingIntegrationSpec extends FunSuite {
  override val munitTimeout: Duration = 120.seconds
  // Claims intentionally span all tenants. Keep other concurrently running worker suites out of this database.
  import ConfigurationDeploymentWorld.{runIsolated => run}

  private val repo = new PostgresRemnawaveOnboardingRepository
  private val query = new PostgresRemnawaveOnboardingQuery
  private val provisioning = new PostgresProvisioningRunRepository
  private val cipher = NodeInstallationCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(Map(
    "INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(Array.fill[Byte](32)(11)))).toOption.get)
  private val installationKey = "secret-installation-key-for-persistence-test"

  private def uid = UUID.randomUUID()
  test("new-config replacement is immutable, confirmed, SQL-journaled and scoped to the exact old ownership chain") {
    List(false,true).foreach { bound => inWorld { w => for {
      pair <- integration(w,w.org,"new-config-replacement")
      target <- w.node("new-config-replacement")
      at <- connectionUpdated(w,target.connectionId); now <- IO.realTimeInstant
      original <- plan(w,pair._1,pair._2,target.resourceId,target.connectionId,at,now)
      _ <- w.run(repo.start(w.org,pair._1,original.id,uid,AuthorizationFixtures.ActorUserId,now))
      token=uid
      claimed <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
      oldId=UUID.fromString("58065b61-0cd1-4312-92c8-e443cb739172")
      _ <- w.run(repo.beginPhase(claimed.head,token,now))
      _ <- w.run(repo.persist(claimed.head,token,claimed.head.copy(state=ProvisioningRunState.Failed,externalNodeId=Some(oldId),failureCode=Some("TEST_FAILURE")),now,false))
      oldBefore <- w.run(repo.find(w.org,pair._1,original.id))
      sessionId=uid; inventoryId=uid
      _ <- w.run(for {
        _ <- sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,started_at,recover_after_at,finished_at,status)
          values($sessionId,${w.org},${pair._1},'MANUAL',$now,$now,$now,'COMPLETED')""".update.run
        _ <- sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,external_id,display_name,
          summary_version,summary,is_active,first_seen_at,last_seen_at,last_seen_sync_session_id,created_at,updated_at)
          values($inventoryId,${w.org},${pair._1},'NODE',${oldId.toString},'Previous node',1,'{}'::jsonb,false,$now,$now,$sessionId,$now,$now)""".update.run
        _ <- if(bound) sql"""insert into integration_resource_binding(id,organization_id,integration_id,inventory_object_id,resource_id,
          created_by_user_id,created_at,updated_at) values(${uid},${w.org},${pair._1},$inventoryId,${target.resourceId},
          ${AuthorizationFixtures.ActorUserId},$now,$now)""".update.run.void else ().pure[ConnectionIO]
      } yield ())
      binding <- w.run(query.replacementBinding(w.org,pair._1,target.resourceId,oldId))
      _ = assertEquals(binding,Right(Option.when(bound)(inventoryId)))
      _ <- w.run(sql"update integration_inventory_object set is_active=true where id=$inventoryId".update.run)
      active <- w.run(query.replacementBinding(w.org,pair._1,target.resourceId,oldId))
      _ = assertEquals(active,Left("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
      _ <- w.run(sql"update integration_inventory_object set is_active=false where id=$inventoryId".update.run)
      old=PreviousNodeInstallation.fromRun(original).copy(inventoryObjectId=Option.when(bound)(inventoryId))
      proof=OnboardingRecovery(original.id,Some(oldId),original.snapshot.correlationId,original.id,"CONFIRMED_NOT_FOUND","RECREATE_WITH_NEW_CONFIG",
        Some(original.snapshot.input.panelCidrs),Some(original.snapshot.imageReference),Some(LocalInstallationObservation.fromState(LocalInstallationState.OwnedComplete)),
        previousNodeAddress=Some(old.address),previousInstallation=Some(old))
      desired=original.snapshot.input.copy(address="138.124.38.229",configProfileId=OnboardingInput.GeneratedProfileId,activeInboundIds=Nil,
        protocol=Some(RemnawaveProtocol.Hysteria2(443,"hy2.velesoracle.com")),tlsHttp01=Some(NodeTlsHttp01(uid,"operator@example.org")))
      source=PanelSourceEvidence(PanelSourceMode.Manual,desired.panelCidrs,"MANUAL","MANUAL",PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.org").toOption.get))
      draft=original.copy(id=uid,createdAt=now.plusSeconds(1),snapshot=original.snapshot.copy(input=desired,correlationId=uid,recovery=Some(proof),lifecycleVersion=7,panelSource=Some(source)))
      _ <- w.run(repo.insertPlan(draft))
      stored <- w.run(repo.find(w.org,pair._1,draft.id))
      sqlPhases <- w.run(sql"select infradesk_onboarding_expected_phases(cast(${OnboardingSnapshotCodec.encode(draft.snapshot).noSpaces} as jsonb))".query[Array[String]].unique)
      _ = assertEquals(sqlPhases.toList,OnboardingPhase.forSnapshot(draft.snapshot).map(_.code))
      _ = assert(!sqlPhases.contains("DELETE_NODE"))
      _ = assertEquals(stored.get._1.protocolBinding,None)
      unconfirmed <- w.run(repo.start(w.org,pair._1,draft.id,uid,AuthorizationFixtures.ActorUserId,now,false)).attempt
      _ = assertEquals(errorCode(unconfirmed),Some("REMNAWAVE_ONBOARDING_RECREATE_CONFIRMATION_REQUIRED"))
      malformed=draft.copy(id=uid,snapshot=draft.snapshot.copy(recovery=Some(proof.copy(previousInstallation=Some(old.copy(nodePort=2222))))))
      rejected <- w.run(repo.insertPlan(malformed)).attempt
      _ = assert(rejected.isLeft)
      _ <- List(proof.copy(previousExternalNodeId=Some(uid)),proof.copy(installationOwnerId=uid),
        proof.copy(previousCorrelationId=uid),proof.copy(localInstallation=Some(LocalInstallationObservation.fromState(LocalInstallationState.Foreign))),
        proof.copy(localInstallation=Some(LocalInstallationObservation.fromState(LocalInstallationState.Unknown))))
        .traverse_ { invalid => w.run(repo.insertPlan(draft.copy(id=uid,snapshot=draft.snapshot.copy(recovery=Some(invalid)))))
          .attempt.map(result => assert(result.isLeft)) }
      started <- w.run(repo.start(w.org,pair._1,draft.id,uid,AuthorizationFixtures.ActorUserId,now,true))
      _ = assertEquals(started.state,ProvisioningRunState.Queued)
      _ <- if(!bound) IO.unit else for {
        premature <- w.run(sql"delete from integration_resource_binding where inventory_object_id=$inventoryId".update.run).attempt
        _ = assert(premature.isLeft)
        newToken=uid
        running <- w.run(repo.claim(uid,newToken,now,now.plusSeconds(300),1))
        ready <- w.run(OnboardingPhase.forSnapshot(draft.snapshot).takeWhile(_!=OnboardingPhase.UnbindPreviousNode)
          .foldLeftM(running.head) { (r,phase) =>
            val next=r.copy(phase=OnboardingPhase.next(phase,draft.snapshot).get)
            repo.beginPhase(r,newToken,now) *> repo.persist(r,newToken,next,now,true).as(next)
          })
        _ = assertEquals(ready.phase,OnboardingPhase.UnbindPreviousNode)
        _ <- w.run(repo.beginPhase(ready,newToken,now))
        removed <- w.run(sql"delete from integration_resource_binding where inventory_object_id=$inventoryId".update.run)
        _ = assertEquals(removed,1)
        retry <- w.run(sql"delete from integration_resource_binding where inventory_object_id=$inventoryId".update.run)
        _ = assertEquals(retry,0)
      } yield ()
      oldAfter <- w.run(repo.find(w.org,pair._1,original.id))
      _ = assertEquals(oldBefore,oldAfter)
    } yield () } }
  }
  test("cleanup waiting for concurrent preview reservation keeps the newly referenced identity") {
    inWorld { w => for {
      pair <- integration(w,w.org,"tls-cleanup-preview")
      target <- w.node("tls-preview-target")
      at <- connectionUpdated(w,target.connectionId); now <- IO.realTimeInstant
      template <- plan(w,pair._1,pair._2,target.resourceId,target.connectionId,at,now.minusSeconds(90000))
      _ <- w.run(sql"delete from remnawave_node_onboarding where id=${template.id} and state='PLANNED'".update.run)
      identity=uid
      input=template.snapshot.input.copy(configProfileId=OnboardingInput.GeneratedProfileId,activeInboundIds=Nil,
        protocol=Some(RemnawaveProtocol.Hysteria2(443,"example.org")),tlsHttp01=Some(NodeTlsHttp01(identity,"operator@example.org")))
      evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.org").toOption.get))
      draft=template.copy(id=uid,snapshot=template.snapshot.copy(input=input,lifecycleVersion=7,panelSource=Some(evidence)))
      _ <- w.run(repo.insertPlan(draft))
      reservation=new java.util.concurrent.CountDownLatch(1)
      fresh=draft.copy(id=uid,createdAt=now,updatedAt=now)
      preview <- w.run(for {
        _ <- sql"select infradesk_tls_identity_lock($identity)".query[Unit].unique
        _ <- org.typelevel.doobie.free.connection.delay(reservation.countDown())
        _ <- sql"select pg_sleep(0.3)".query[Unit].unique
        _ <- repo.insertPlan(fresh)
      } yield ()).start
      acquired <- IO.blocking(reservation.await(5,java.util.concurrent.TimeUnit.SECONDS))
      _ = assert(acquired)
      removed <- w.run(repo.cleanupPlans(now.minusSeconds(86400),100))
      _ <- preview.joinWithNever
      exists <- w.run(sql"select exists(select 1 from remnawave_tls_issuance_identity where id=$identity)".query[Boolean].unique)
      retained <- w.run(repo.find(w.org,pair._1,fresh.id))
      _ = assertEquals(removed,1)
      _ = assert(exists && retained.nonEmpty)
    } yield () }
  }
  test("concurrent start and cleanup keep the run and its issuance identity consistent") {
    inWorld { w => for {
      pair <- integration(w,w.org,"tls-cleanup-start")
      target <- w.node("tls-start-target")
      at <- connectionUpdated(w,target.connectionId); now <- IO.realTimeInstant
      template <- plan(w,pair._1,pair._2,target.resourceId,target.connectionId,at,now)
      _ <- w.run(sql"delete from remnawave_node_onboarding where id=${template.id} and state='PLANNED'".update.run)
      identity=uid
      input=template.snapshot.input.copy(configProfileId=OnboardingInput.GeneratedProfileId,activeInboundIds=Nil,
        protocol=Some(RemnawaveProtocol.Hysteria2(443,"example.org")),tlsHttp01=Some(NodeTlsHttp01(identity,"operator@example.org")))
      evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.org").toOption.get))
      draft=template.copy(id=uid,snapshot=template.snapshot.copy(input=input,lifecycleVersion=7,panelSource=Some(evidence)))
      _ <- w.run(repo.insertPlan(draft))
      raced <- (w.run(repo.start(w.org,pair._1,draft.id,uid,AuthorizationFixtures.ActorUserId,now)).attempt,
        w.run(repo.cleanupPlans(now,100))).parTupled
      stored <- w.run(repo.find(w.org,pair._1,draft.id))
      identityExists <- w.run(sql"select exists(select 1 from remnawave_tls_issuance_identity where id=$identity)".query[Boolean].unique)
      _ = assertEquals(identityExists,stored.nonEmpty)
      _ = stored match {
        case Some((r,_)) => assertEquals(r.state,ProvisioningRunState.Queued); assert(raced._1.isRight); assertEquals(raced._2,0)
        case None => assert(raced._1.isLeft); assertEquals(raced._2,1)
      }
    } yield () }
  }
  test("V7 SQL phase sequence matches Scala and expired plans collect only unreferenced unissued TLS identities") {
    inWorld { w => for {
      pair <- integration(w,w.org,"tls-cleanup")
      target <- w.node("tls-cleanup-target")
      at <- connectionUpdated(w,target.connectionId); now <- IO.realTimeInstant
      template <- plan(w,pair._1,pair._2,target.resourceId,target.connectionId,at,now.minusSeconds(90000))
      _ <- w.run(sql"delete from remnawave_node_onboarding where id=${template.id} and state='PLANNED'".update.run)
      orphan=uid; shared=uid; issued=uid; terminal=uid
      input=template.snapshot.input.copy(configProfileId=OnboardingInput.GeneratedProfileId,activeInboundIds=Nil,
        protocol=Some(RemnawaveProtocol.Hysteria2(443,"example.org")))
      evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.org").toOption.get))
      draft=template.copy(id=uid,snapshot=template.snapshot.copy(input=input.copy(tlsHttp01=Some(NodeTlsHttp01(orphan,"operator@example.org"))),
        lifecycleVersion=7,panelSource=Some(evidence)))
      sqlPhases <- w.run(sql"select infradesk_onboarding_expected_phases(${OnboardingSnapshotCodec.encode(draft.snapshot).noSpaces}::jsonb)".query[List[String]].unique)
      _ = assertEquals(sqlPhases,OnboardingPhase.forSnapshot(draft.snapshot).map(_.code))
      runs=List(orphan,shared,issued,terminal).map(identity => draft.copy(id=uid,
        snapshot=draft.snapshot.copy(input=draft.snapshot.input.copy(tlsHttp01=Some(NodeTlsHttp01(identity,"operator@example.org"))))))
      _ <- runs.traverse_(r => w.run(repo.insertPlan(r)))
      retained=runs(1).copy(id=uid,createdAt=now,updatedAt=now)
      _ <- w.run(repo.insertPlan(retained))
      cert=NodeTlsCertificate(issued,w.org,target.resourceId,"example.org","a"*64,now.plusSeconds(90*86400))
      _ <- w.run(repo.insertCertificate(cert,cipher.encryptTls(issued,w.org,new NodeTlsMaterial("certificate-fixture","private-key-fixture"))))
      // Terminal history must pin the issuance identity even without issued material.
      terminalRun=runs(3).copy(createdAt=now,updatedAt=now)
      terminalHistory=terminalRun.copy(id=uid)
      _ <- w.run(repo.insertPlan(terminalHistory))
      _ <- w.run(repo.start(w.org,pair._1,terminalHistory.id,uid,AuthorizationFixtures.ActorUserId,now))
      token=uid
      claimed <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
      active=claimed.find(_.id==terminalHistory.id).get
      _ <- w.run(repo.beginPhase(active,token,now) *> repo.persist(active,token,
        active.copy(state=ProvisioningRunState.Failed,failureCode=Some("TEST_FAILURE")),now,false))
      count <- w.run(repo.cleanupPlans(now.minusSeconds(86400),100))
      remaining <- w.run(sql"select id from remnawave_tls_issuance_identity where organization_id=${w.org}".query[UUID].to[List])
      _ = assertEquals(count,4)
      _ = assertEquals(remaining.toSet,Set(shared,issued,terminal))
      _ <- w.run(repo.cleanupPlans(now.plusSeconds(1),100))
      after <- w.run(sql"select id from remnawave_tls_issuance_identity where organization_id=${w.org}".query[UUID].to[List])
      _ = assertEquals(after.toSet,Set(issued,terminal))
    } yield () }
  }
  private def finishWorld(w: ConfigurationDeploymentWorld)(body: IO[Unit]): IO[Unit] =
    body.guarantee(w.run(for {
      // Isolated teardown bypasses terminal-history and FK triggers, then removes the linked rows explicitly.
      _ <- sql"set local session_replication_role='replica'".update.run
      _ <- sql"delete from remnawave_node_onboarding_phase where run_id in (select id from remnawave_node_onboarding where organization_id in (${w.org},${w.foreignOrg}))".update.run
      _ <- sql"delete from remnawave_node_installation_secret where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"update provisioning_run set onboarding_parent_id=null where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_node_onboarding where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_node_tls_certificate where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_tls_issuance_identity where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_action_execution where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_resource_binding where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_inventory_object where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_sync_session where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_secret where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from server_profile_observation where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from server_profile_assignment where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from server_profile_revision where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from server_profile where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"set local session_replication_role='origin'".update.run
    } yield ()))

  private def inWorld(body: ConfigurationDeploymentWorld => IO[Unit]): Unit = run { w =>
    finishWorld(w)(body(w))
  }

  private def integration(w: ConfigurationDeploymentWorld, organizationId: UUID, label: String): IO[(UUID, UUID)] = {
    val integrationId = uid
    val secretId = uid
    val now = Instant.now()
    val nonce = Array.fill[Byte](12)(3)
    val ciphertext = Array.fill[Byte](32)(4)
    w.run(for {
      _ <- sql"""insert into integration_secret(id,organization_id,kind,nonce,ciphertext,created_at)
        values($secretId,$organizationId,'INTEGRATION_CREDENTIAL',$nonce,$ciphertext,$now)""".update.run
      _ <- sql"""insert into integration(id,organization_id,name,provider_type,base_url,enabled,secret_id,
        caddy_api_key_configured,created_at,updated_at,management_mode)
        values($integrationId,$organizationId,$label,'REMNAWAVE','https://panel.example.test',true,$secretId,false,$now,$now,'MANAGED_SELECTED')""".update.run
    } yield integrationId -> secretId)
  }

  private def snapshot(integrationSecretId: UUID, resourceId: UUID, connectionId: UUID,
    connectionUpdatedAt: Instant, baselinePlanId: UUID = UUID.randomUUID(), baselineNeeded: Boolean = false): OnboardingSnapshot = {
    val input = OnboardingInput(resourceId, "edge-persist", "192.0.2.90", 30222, uid, List(uid), List("198.51.100.0/24"))
    val api = NodeApiCompatibility(Some("2.8.0"), Some("PROFILE_PUBKEY"), Some("reviewed-commit"),
      Set(NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData,
        NodeProvisioningCapability.Status, NodeProvisioningCapability.ConfigProfile), None)
    OnboardingSnapshot(input, connectionUpdatedAt, integrationSecretId, connectionId, connectionUpdatedAt,
      uid, uid, 1, uid, 1L, "a" * 64, baselinePlanId, baselineNeeded, api, "remnawave/node:2.8.0", uid,
      "Persist node", "Baseline", "Profile", List("inbound"), Nil, Nil, Nil)
  }

  private def plan(w: ConfigurationDeploymentWorld, integrationId: UUID, integrationSecretId: UUID,
    resourceId: UUID, connectionId: UUID, connectionUpdatedAt: Instant, createdAt: Instant,
    baselinePlanId: UUID = UUID.randomUUID(), organizationId: UUID = null,
    baselineNeeded: Boolean = false): IO[RemnawaveNodeOnboardingRun] = {
    val run = RemnawaveNodeOnboardingRun(uid, Option(organizationId).getOrElse(w.org), integrationId, resourceId, None,
      AuthorizationFixtures.ActorUserId, ProvisioningRunState.Planned, OnboardingPhase.Validate,
      snapshot(integrationSecretId, resourceId, connectionId, connectionUpdatedAt, baselinePlanId, baselineNeeded), createdAt, createdAt)
    w.run(repo.insertPlan(run)).as(run)
  }

  private def connectionUpdated(w: ConfigurationDeploymentWorld, id: UUID): IO[Instant] =
    w.run(sql"select updated_at from connection where id=$id".query[Instant].unique)

  private def errorCode[A](result: Either[Throwable, A]): Option[String] =
    result.left.toOption.collect { case error: IntegrationError => error.code }

  private def httpStart(w: ConfigurationDeploymentWorld, integrationId: UUID, planId: UUID,
    requestId: UUID, confirmed: Boolean): IO[(org.http4s.Status,String)] = {
    val api = new application.integration.RemnawaveOnboardingApi {
      def options(o: UUID,i: UUID) = IO.raiseError[io.circe.Json](new UnsupportedOperationException)
      def preview(a: application.auth.ActorContext,i: UUID,input: OnboardingInput) = IO.raiseError[io.circe.Json](new UnsupportedOperationException)
      def reconcile(a: application.auth.ActorContext,i: UUID,r: UUID,action: String) = IO.raiseError[io.circe.Json](new UnsupportedOperationException)
      def start(a: application.auth.ActorContext,i: UUID,p: UUID,r: UUID,confirmRecreate: Boolean) =
        w.run(repo.start(a.organizationId,i,p,r,a.userId,Instant.now(),confirmRecreate))
      def detail(o: UUID,i: UUID,r: UUID) = IO.raiseError[io.circe.Json](new UnsupportedOperationException)
      def history(o: UUID,i: UUID) = IO.raiseError[io.circe.Json](new UnsupportedOperationException)
    }
    val route = new infrastructure.http.RemnawaveOnboardingRoutes[IO](api,AuthorizationFixtures.authorization,
      org.typelevel.log4cats.noop.NoOpLogger[IO]).routes.orNotFound
    val body = io.circe.Json.obj("planId" -> io.circe.Json.fromString(planId.toString),
      "requestId" -> io.circe.Json.fromString(requestId.toString),"confirmRecreate" -> io.circe.Json.fromBoolean(confirmed))
    val path=s"/api/v1/organizations/${w.org}/integrations/$integrationId/remnawave-node-onboarding/runs"
    val request=AuthorizationFixtures.as(org.http4s.Request[IO](org.http4s.Method.POST,org.http4s.Uri.unsafeFromString(path)).withEntity(body.noSpaces),
      w.org,domain.auth.OrganizationRole.Owner)
    route.run(request).flatMap(response => response.as[String].map(response.status -> _))
  }

  private def provisioningPlan(w: ConfigurationDeploymentWorld, node: ConfigurationDeploymentWorld.Node,
    id: UUID, now: Instant): ProvisioningRun = ProvisioningRun(id, w.org, node.resourceId, None, None,
    ProvisioningInputSnapshot(1, ProvisioningRunKind.ServerBaselineCheck, w.org, node.resourceId, "NODE", "VPS",
      node.connectionId, now, ProvisioningStepKind.Baseline), ProvisioningRunState.Planned, now, now)

  private def approvedProfileApplyPlan(w: ConfigurationDeploymentWorld, node: ConfigurationDeploymentWorld.Node,
    id: UUID, now: Instant): ProvisioningRun = {
    val snapshot = ServerProfileApplySnapshot(uid, 1L, uid, uid, 1, support.ServerProfileFixtures.content.hash, support.ServerProfileFixtures.content,
      uid, "c" * 64, "d" * 64)
    val input = provisioningPlan(w, node, id, now).input.copy(runKind = ProvisioningRunKind.ServerProfileApply,
      steps = ProvisioningStepKind.ProfileApply, profileApply = Some(snapshot))
    provisioningPlan(w, node, id, now).copy(input = input)
  }

  test("V6 stores a fenced immutable protocol receipt before creating Node and keeps legacy phases intact") {
    inWorld { w => for {
      pair <- integration(w,w.org,"protocol-receipt")
      target <- w.node("protocol-receipt")
      at <- connectionUpdated(w,target.connectionId)
      now <- IO.realTimeInstant
      template <- plan(w,pair._1,pair._2,target.resourceId,target.connectionId,at,now)
      input=template.snapshot.input.copy(configProfileId=OnboardingInput.GeneratedProfileId,activeInboundIds=Nil,
        protocol=Some(RemnawaveProtocol.Shadowsocks(443,"aes-256-gcm")))
      evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.org").toOption.get))
      draft=template.copy(id=uid,snapshot=template.snapshot.copy(input=input,lifecycleVersion=6,panelSource=Some(evidence)))
      _ <- w.run(repo.insertPlan(draft))
      phases <- w.run(sql"select phase from remnawave_node_onboarding_phase where run_id=${draft.id} order by position".query[String].to[List])
      _ = assertEquals(phases,OnboardingPhase.forSnapshot(draft.snapshot).map(_.code))
      _ <- w.run(repo.start(w.org,pair._1,draft.id,uid,AuthorizationFixtures.ActorUserId,now))
      token=uid
      claimed <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
      before <- w.run(OnboardingPhase.forSnapshot(draft.snapshot).takeWhile(_!=OnboardingPhase.CreateProtocolProfile)
        .foldLeftM(claimed.head) { (r,p) =>
          val next=r.copy(phase=OnboardingPhase.next(p,r.snapshot).get)
          repo.beginPhase(r,token,now) *> repo.persist(r,token,next,now,true).as(next)
        })
      receipt=NodeProtocolBinding(uid,List(uid),"b"*64)
      premature <- w.run(repo.persist(before,token,before.copy(externalNodeId=Some(uid)),now,false)).attempt
      _ = assert(premature.isLeft)
      _ <- w.run(repo.beginPhase(before,token,now))
      next=before.copy(protocolBinding=Some(receipt),phase=OnboardingPhase.CreateNode)
      stale <- w.run(repo.persist(before,uid,next,now,true))
      _ = assertEquals(stale,false)
      saved <- w.run(repo.persist(before,token,next,now,true))
      _ = assertEquals(saved,true)
      read <- w.run(repo.find(w.org,pair._1,draft.id))
      _ = assertEquals(read.get._1.protocolBinding,Some(receipt))
      _ = assertEquals(read.get._1.intent.configProfileId,receipt.profileId)
      _ <- w.run(repo.beginPhase(next,token,now))
      changed <- w.run(repo.persist(next,token,next.copy(protocolBinding=Some(receipt.copy(configSha256="c"*64))),now,false)).attempt
      _ = assert(changed.isLeft)
    } yield () }
  }
  test("TLS versions are encrypted, immutable and scoped to the exact resource; references prevent deletion") {
    inWorld { w => for {
      pair <- integration(w,w.org,"tls-reference")
      target <- w.node("tls-reference"); other <- w.node("tls-other")
      at <- connectionUpdated(w,target.connectionId); now <- IO.realTimeInstant
      template <- plan(w,pair._1,pair._2,target.resourceId,target.connectionId,at,now)
      id=uid
      cert=NodeTlsCertificate(id,w.org,target.resourceId,"example.org","a"*64,now.plusSeconds(90*86400))
      material=new NodeTlsMaterial("certificate-fixture","PRIVATE-KEY-FIXTURE")
      secret=cipher.encryptTls(id,w.org,material)
      _ <- w.run(repo.insertCertificate(cert,secret))
      own <- w.run(repo.certificate(w.org,target.resourceId,id)); wrong <- w.run(repo.certificate(w.org,other.resourceId,id))
      foreign <- w.run(repo.certificate(w.foreignOrg,target.resourceId,id))
      _ = assertEquals(own.map(_._1),Some(cert)); _ = assertEquals(wrong,None); _ = assertEquals(foreign,None)
      _ = assertEquals(cipher.decryptTls(own.get._2).privateKeyPem,material.privateKeyPem)
      input=template.snapshot.input.copy(configProfileId=OnboardingInput.GeneratedProfileId,activeInboundIds=Nil,
        protocol=Some(RemnawaveProtocol.Hysteria2(443,"example.org")),tlsCertificateId=Some(id))
      evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.org").toOption.get))
      draft=template.copy(id=uid,snapshot=template.snapshot.copy(input=input,lifecycleVersion=6,panelSource=Some(evidence)))
      _ <- w.run(repo.insertPlan(draft))
      mismatch <- w.run(repo.insertPlan(draft.copy(id=uid,resourceId=other.resourceId,snapshot=draft.snapshot.copy(input=input.copy(resourceId=other.resourceId))))).attempt
      _ = assert(mismatch.isLeft)
      update <- w.run(sql"update remnawave_node_tls_certificate set fingerprint=${"c"*64} where id=$id".update.run).attempt
      deletion <- w.run(sql"delete from remnawave_node_tls_certificate where id=$id".update.run).attempt
      _ = assert(update.isLeft); _ = assert(deletion.isLeft)
    } yield () }
  }
  test("HTTP-01 reserves one resource identity and saves issued material only under the live phase lease") {
    inWorld { w => for {
      pair <- integration(w,w.org,"http01-identity")
      target <- w.node("http01-target"); other <- w.node("http01-other")
      at <- connectionUpdated(w,target.connectionId); now <- IO.realTimeInstant
      template <- plan(w,pair._1,pair._2,target.resourceId,target.connectionId,at,now)
      identity=uid
      input=template.snapshot.input.copy(configProfileId=OnboardingInput.GeneratedProfileId,activeInboundIds=Nil,
        protocol=Some(RemnawaveProtocol.Hysteria2(443,"example.org")),tlsHttp01=Some(NodeTlsHttp01(identity,"operator@example.org")))
      evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.org").toOption.get))
      draft=template.copy(id=uid,snapshot=template.snapshot.copy(input=input,lifecycleVersion=6,panelSource=Some(evidence)))
      _ <- w.run(repo.insertPlan(draft))
      conflict <- w.run(repo.insertPlan(draft.copy(id=uid,resourceId=other.resourceId,
        snapshot=draft.snapshot.copy(input=input.copy(resourceId=other.resourceId))))).attempt
      _ = assert(conflict.isLeft)
      _ <- w.run(repo.start(w.org,pair._1,draft.id,uid,AuthorizationFixtures.ActorUserId,now))
      token=uid
      claimed <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
      before <- w.run(OnboardingPhase.forSnapshot(draft.snapshot).takeWhile(_!=OnboardingPhase.IssueTls)
        .foldLeftM(claimed.head) { (r,p) =>
          val next=r.copy(phase=OnboardingPhase.next(p,r.snapshot).get)
          repo.beginPhase(r,token,now) *> repo.persist(r,token,next,now,true).as(next)
        })
      _ <- w.run(repo.beginPhase(before,token,now))
      cert=NodeTlsCertificate(identity,w.org,target.resourceId,"example.org","a"*64,now.plusSeconds(90*86400))
      encrypted=cipher.encryptTls(identity,w.org,new NodeTlsMaterial("certificate-fixture","private-key-fixture"))
      stale <- w.run(repo.saveIssuedCertificate(before,uid,cert,encrypted,now)).attempt
      _ = assertEquals(errorCode(stale),Some("REMNAWAVE_ONBOARDING_LEASE_LOST"))
      _ <- w.run(repo.saveIssuedCertificate(before,token,cert,encrypted,now))
      public <- w.run(repo.certificateMetadata(w.org,target.resourceId,identity))
      _ = assertEquals(public,Some(cert))
      _ <- w.run(repo.persist(before,token,before.copy(phase=OnboardingPhase.CreateProtocolProfile),now,true))
    } yield () }
  }
  test("managed Panel source requires the matching tenant HOST binding and active SSH connection") {
    inWorld { w =>
      def bind(org: UUID, integrationId: UUID, target: ConfigurationDeploymentWorld.Node, kind: String): IO[Unit] = {
        val now=Instant.now(); val sessionId=uid; val inventoryId=uid
        w.run(for {
          _ <- sql"""update resource set spec='{"hostname":"panel.example.test"}'::jsonb where id=${target.resourceId}""".update.run
          _ <- sql"""update connection set config=jsonb_set(config,'{host}','"185.10.20.30"'::jsonb) where id=${target.connectionId}""".update.run
          _ <- sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,started_at,recover_after_at,finished_at,status)
            values($sessionId,$org,$integrationId,'MANUAL',$now,$now,$now,'COMPLETED')""".update.run
          _ <- sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,external_id,display_name,
            summary_version,summary,is_active,first_seen_at,last_seen_at,last_seen_sync_session_id,created_at,updated_at)
            values($inventoryId,$org,$integrationId,$kind,${uid.toString},'Panel',1,'{}'::jsonb,true,$now,$now,$sessionId,$now,$now)""".update.run
          _ <- sql"""insert into integration_resource_binding(id,organization_id,integration_id,inventory_object_id,resource_id,
            created_by_user_id,created_at,updated_at) values(${uid},$org,$integrationId,$inventoryId,${target.resourceId},
            ${AuthorizationFixtures.ActorUserId},$now,$now)""".update.run
        } yield ())
      }
      for {
        own <- integration(w,w.org,"managed-panel-source")
        foreign <- integration(w,w.foreignOrg,"foreign-panel-source")
        node <- w.node("unrelated-node-source")
        _ <- bind(w.org,own._1,node,"NODE")
        unrelated <- w.run(query.managedPanelAddresses(w.org,own._1,"panel.example.test"))
        _ = assertEquals(unrelated,Nil)
        panel <- w.node("managed-panel-host")
        _ <- bind(w.org,own._1,panel,"HOST")
        sources <- w.run(query.managedPanelAddresses(w.org,own._1,"PANEL.EXAMPLE.TEST"))
        _ = assertEquals(sources,List("185.10.20.30"))
        wrongHost <- w.run(query.managedPanelAddresses(w.org,own._1,"other.example.test"))
        wrongTenant <- w.run(query.managedPanelAddresses(w.foreignOrg,own._1,"panel.example.test"))
        wrongIntegration <- w.run(query.managedPanelAddresses(w.org,foreign._1,"panel.example.test"))
        _ = assertEquals(wrongHost,Nil)
        _ = assertEquals(wrongTenant,Nil)
        _ = assertEquals(wrongIntegration,Nil)
        _ <- w.run(sql"update connection set is_active=false where id=${panel.connectionId}".update.run)
        fallback <- w.run(query.managedPanelAddresses(w.org,own._1,"panel.example.test"))
        _ = assertEquals(fallback,List("panel.example.test"))
      } yield ()
    }
  }

  test("V3 terminal connectivity failure builds V4/V5 reuse plans with immutable observed candidates, fenced findings and no create/install phases") {
    for(version <- List(4,5)) inWorld { w => for {
      pair <- integration(w,w.org,"panel-connectivity-v4")
      (integrationId,secretId)=pair
      target <- w.node("panel-connectivity-v4")
      sourceAt <- connectionUpdated(w,target.connectionId)
      now <- IO.realTimeInstant
      template <- plan(w,integrationId,secretId,target.resourceId,target.connectionId,sourceAt,now)
      original=template.copy(id=uid,snapshot=template.snapshot.copy(lifecycleVersion=3,
        input=template.snapshot.input.copy(panelCidrs=List("2.27.26.18/32"))))
      _ <- w.run(repo.insertPlan(original))
      _ <- w.run(repo.start(w.org,integrationId,original.id,uid,AuthorizationFixtures.ActorUserId,now))
      oldToken=uid
      claimed <- w.run(repo.claim(uid,oldToken,now,now.plusSeconds(300),1))
      external=uid
      oldFailed <- w.run(OnboardingPhase.forSnapshot(original.snapshot).takeWhile(_ != OnboardingPhase.WaitForPanel)
        .foldLeftM(claimed.head) { (r,phase) =>
          val next=r.copy(phase=OnboardingPhase.next(phase,original.snapshot).get,externalNodeId=Some(external))
          repo.beginPhase(r,oldToken,now) *> repo.persist(r,oldToken,next,now,true).as(next)
        }.flatMap(r => repo.beginPhase(r,oldToken,now) *> repo.persist(r,oldToken,r.copy(state=ProvisioningRunState.Failed,
          failureCode=Some("REMNAWAVE_NODE_CONNECTION_TIMEOUT")),now,false)))
      before <- w.run(repo.find(w.org,integrationId,original.id))
      evidence=PanelSourceEvidence(PanelSourceMode.Auto,List("185.10.20.30/32"),"DNS_BASE_URL","AUTO_CANDIDATE",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.test").toOption.get))
      proof=OnboardingRecovery(original.id,Some(external),original.snapshot.correlationId,original.id,
        "PRESENT_UNHEALTHY","REPAIR_PANEL_CONNECTIVITY",Some(original.snapshot.input.panelCidrs),
        localInstallation=Some(LocalInstallationObservation.fromState(LocalInstallationState.OwnedComplete)),localVerified=true,
        previousNodeAddress=Option.when(version==5)(original.snapshot.input.address))
      address=Option.when(version==5)(NodeAddressEvidence(NodeAddressMode.PublicIp,"185.10.20.20",List("185.10.20.20")))
      draft=original.copy(id=uid,externalNodeId=Some(external),createdAt=now.plusMillis(1),snapshot=original.snapshot.copy(
        lifecycleVersion=version,recovery=Some(proof),input=original.snapshot.input.copy(panelCidrs=evidence.sources,
          panelSourceMode=PanelSourceMode.Auto,address=address.fold(original.snapshot.input.address)(_.address),
          nodeAddressMode=address.fold[NodeAddressMode](NodeAddressMode.Legacy)(_.mode)),panelSource=Some(evidence),nodeAddress=address))
      _ <- w.run(repo.insertPlan(draft))
      rows <- w.run(repo.find(w.org,integrationId,draft.id))
      request=uid
      first <- w.run(repo.startResult(w.org,integrationId,draft.id,request,AuthorizationFixtures.ActorUserId,now))
      repeat <- w.run(repo.startResult(w.org,integrationId,draft.id,request,AuthorizationFixtures.ActorUserId,now))
      token=uid
      active <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
      stale <- w.run(repo.persist(active.head,uid,active.head.copy(connectivityFinding=Some(PanelConnectivityFinding(evidence.sources,"AUTO_CANDIDATE",false))),now,false))
      _ <- OnboardingPhase.forSnapshot(draft.snapshot).foldLeftM(active.head) { (r,phase) =>
        val next=OnboardingPhase.next(phase,draft.snapshot)
        val updated=r.copy(phase=next.getOrElse(phase),state=if(next.isEmpty) ProvisioningRunState.Succeeded else ProvisioningRunState.Running,
          connectivityFinding=if(phase==OnboardingPhase.WaitForPanel) Some(PanelConnectivityFinding(evidence.sources,"AUTO_CANDIDATE",version==4))
            else if(phase==OnboardingPhase.VerifyObservedPanel) Some(PanelConnectivityFinding(List("185.10.20.41/32"),"AUTO_OBSERVED",true)) else r.connectivityFinding,
          observedPanelSource=if(phase==OnboardingPhase.ObservePanelSource) Some(PanelSourceObservation(PanelSourceObservationStatus.Observed,List("185.10.20.41/32"))) else r.observedPanelSource,
          connectivityCompletion=if(version==5 && phase==OnboardingPhase.FinalizePanelSources) Some(PanelConnectivityCompletion.Promoted) else r.connectivityCompletion)
        w.run(repo.beginPhase(r,token,now) *> repo.persist(r,token,updated,now,true)) *>
          (if(phase==OnboardingPhase.AddObservedPanelSource) w.run(sql"""update remnawave_node_onboarding
            set observed_panel_source='{"status":"AUTO_OBSERVED","sources":["185.10.20.42/32"]}'::jsonb where id=${draft.id}""".update.run).attempt
            .map(result => assert(result.isLeft)) else IO.unit).as(updated)
      }
      after <- w.run(repo.find(w.org,integrationId,original.id))
      complete <- w.run(repo.find(w.org,integrationId,draft.id))
      hidden <- w.run(repo.find(w.foreignOrg,integrationId,draft.id))
      corrupt <- w.run(sql"update remnawave_node_onboarding set connectivity_finding='{}'::jsonb where id=${draft.id}".update.run).attempt
    } yield {
      assert(oldFailed); assert(!stale)
      assertEquals(rows.get._2.map(_.phase),OnboardingPhase.forSnapshot(draft.snapshot))
      assert(!rows.get._2.exists(p => Set[OnboardingPhase](OnboardingPhase.CreateNode,OnboardingPhase.DeleteNode,OnboardingPhase.InstallNode)(p.phase)))
      assert(first.newlyStarted); assert(!repeat.newlyStarted)
      assertEquals(before,after)
      assertEquals(complete.get._1.state,ProvisioningRunState.Succeeded)
      assertEquals(complete.get._1.externalNodeId,Some(external))
      assertEquals(complete.get._1.connectivityFinding.map(_.connected),Some(true))
      assertEquals(hidden,None); assert(corrupt.isLeft)
    } }
  }
  test("start is request-idempotent, tenant scoped, immutable, and history omits expired drafts") {
    inWorld { w =>
      for {
        pair <- integration(w, w.org, "onboarding-main")
        (integrationId, secretId) = pair
        foreign <- integration(w, w.foreignOrg, "onboarding-foreign")
        target <- w.node("onboarding-main")
        foreignTarget <- w.node("onboarding-foreign", w.foreignOrg)
        targetAt <- connectionUpdated(w, target.connectionId)
        foreignAt <- connectionUpdated(w, foreignTarget.connectionId)
        now <- IO.realTimeInstant
        approved <- plan(w, integrationId, secretId, target.resourceId, target.connectionId, targetAt, now)
        expired <- plan(w, integrationId, secretId, target.resourceId, target.connectionId, targetAt, now.minusSeconds(86401))
        hidden <- plan(w, integrationId, secretId, target.resourceId, target.connectionId, targetAt, now)
        foreignPlan <- plan(w, foreign._1, foreign._2, foreignTarget.resourceId, foreignTarget.connectionId, foreignAt, now,
          organizationId = w.foreignOrg)
        requestId = uid
        initial <- w.run(repo.startResult(w.org, integrationId, approved.id, requestId, AuthorizationFixtures.ActorUserId, now))
        started = initial.run
        repeated <- w.run(repo.startResult(w.org, integrationId, approved.id, requestId, AuthorizationFixtures.ActorUserId, now))
        retry = repeated.run
        foreignStarted <- w.run(repo.start(w.foreignOrg, foreign._1, foreignPlan.id, requestId, AuthorizationFixtures.ActorUserId, now))
        reused <- w.run(repo.start(w.org, integrationId, hidden.id, requestId, AuthorizationFixtures.ActorUserId, now).attempt)
        secondRequest <- w.run(repo.start(w.org, integrationId, hidden.id, uid, AuthorizationFixtures.ActorUserId, now).attempt)
        foreignVisible <- w.run(repo.find(w.org, foreign._1, foreignPlan.id))
        foreignHistory <- w.run(repo.history(w.org, integrationId, 50))
        otherTenantHistory <- w.run(repo.history(w.foreignOrg, foreign._1, 50))
        immutable <- w.run(sql"update remnawave_node_onboarding set input_snapshot=jsonb_set(input_snapshot,'{nodeName}','\"tampered\"'::jsonb) where id=${approved.id}".update.run).attempt
        stored <- w.run(repo.find(w.org, integrationId, approved.id))
        expiredStart <- w.run(repo.start(w.org, integrationId, expired.id, uid, AuthorizationFixtures.ActorUserId, now).attempt)
        removed <- w.run(repo.cleanupPlans(now.minusSeconds(86400), 100))
        expiredAfter <- w.run(repo.find(w.org, integrationId, expired.id))
        history <- w.run(repo.history(w.org, integrationId, 50))
      } yield {
        assertEquals(started.state, ProvisioningRunState.Queued)
        assert(initial.newlyStarted)
        assert(!repeated.newlyStarted)
        assertEquals(retry, started)
        assertEquals(retry.id, started.id)
        assertEquals(errorCode(reused), Some("REMNAWAVE_ONBOARDING_REQUEST_REUSED"))
        assertEquals(errorCode(secondRequest), Some("REMNAWAVE_ONBOARDING_RESOURCE_BUSY"))
        assertEquals(foreignVisible, None)
        assertEquals(foreignStarted.state, ProvisioningRunState.Queued)
        assertEquals(otherTenantHistory.map(_.id), List(foreignPlan.id))
        assertEquals(foreignHistory.map(_.id).toSet, Set(approved.id))
        assertEquals(stored.toList.flatMap(_._2).map(_.phase), OnboardingPhase.all)
        assert(stored.toList.flatMap(_._2).forall(_.state == "PENDING"))
        assert(immutable.isLeft)
        assertEquals(stored.map(_._1.snapshot), Some(approved.snapshot))
        assertEquals(errorCode(expiredStart), Some("PROVISIONING_PLAN_EXPIRED"))
        assertEquals(removed, 1)
        assertEquals(expiredAfter, None)
        assertEquals(history.map(_.id), List(approved.id))
        assert(!history.exists(_.id == hidden.id))
      }
    }
  }

  test("a failed firewall run retains its node and a separately approved recovery persists the same identity without changing terminal history") {
    inWorld { w =>
      for {
        pair <- integration(w,w.org,"firewall-recovery")
        (integrationId,secretId) = pair
        target <- w.node("firewall-recovery")
        sourceAt <- connectionUpdated(w,target.connectionId)
        now <- IO.realTimeInstant
        approved <- plan(w,integrationId,secretId,target.resourceId,target.connectionId,sourceAt,now)
        stalePlan = approved.copy(id=uid,createdAt=now.plusSeconds(1))
        _ <- w.run(repo.insertPlan(stalePlan))
        _ <- w.run(repo.start(w.org,integrationId,approved.id,uid,AuthorizationFixtures.ActorUserId,now))
        token = uid
        claimed <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
        external = uid
        atFirewall <- w.run(OnboardingPhase.all.take(4).foldLeftM(claimed.head) { (r,phase) =>
          val next = r.copy(phase=OnboardingPhase.next(phase).get,
            externalNodeId=if (phase==OnboardingPhase.CreateNode) Some(external) else r.externalNodeId)
          repo.beginPhase(r,token,now) *> repo.persist(r,token,next,now,complete=true).as(next)
        })
        _ <- w.run(repo.beginPhase(atFirewall,token,now))
        _ <- w.run(repo.saveSecret(atFirewall,token,cipher.encrypt(atFirewall.id,w.org,NodeInstallationData.fromSecretKey(installationKey)),now))
        _ <- w.run(repo.deleteSecret(atFirewall,token,now))
        failed = atFirewall.copy(state=ProvisioningRunState.Failed,failureCode=Some("FIREWALL_RULE_UNSUPPORTED"),finishedAt=Some(now))
        _ <- w.run(repo.persist(atFirewall,token,failed,now,complete=false))
        original <- w.run(repo.find(w.org,integrationId,approved.id))
        existing <- w.run(repo.createdNodes(w.org,target.resourceId))
        foreign <- w.run(repo.createdNodes(w.foreignOrg,target.resourceId))
        staleStart <- w.run(repo.start(w.org,integrationId,stalePlan.id,uid,AuthorizationFixtures.ActorUserId,now)).attempt
        reviewedImage = RemnawaveNodeReleaseCatalog.forReference(approved.snapshot.imageReference).get.imageReference
        candidate <- IO.fromEither(RemnawaveNodeOnboardingRun.recoveryCandidate(existing,integrationId,
          approved.snapshot.input,reviewedImage,approved.snapshot.connectionId).leftMap(new RuntimeException(_)))
        recovery = approved.copy(id=uid,createdAt=now.plusSeconds(2),externalNodeId=candidate.flatMap(_.externalNodeId),
          snapshot=approved.snapshot.copy(imageReference=reviewedImage,recovery=Some(OnboardingRecovery(failed.id,Some(external),
            approved.snapshot.correlationId,approved.id,"PRESENT_UNHEALTHY","RECOVER",previousImageReference=Some(approved.snapshot.imageReference)))))
        _ <- w.run(repo.insertPlan(recovery))
        readPlan <- w.run(repo.find(w.org,integrationId,recovery.id))
        queued <- w.run(repo.start(w.org,integrationId,recovery.id,uid,AuthorizationFixtures.ActorUserId,now))
        token2=uid
        claimed2 <- w.run(repo.claim(uid,token2,now,now.plusSeconds(300),1))
        _ <- w.run(OnboardingPhase.all.take(2).foldLeftM(claimed2.head) { (r,phase) =>
          val next=r.copy(phase=OnboardingPhase.next(phase).get)
          repo.beginPhase(r,token2,now) *> repo.persist(r,token2,next,now,complete=true).as(next)
        })
        current2 <- w.run(repo.find(w.org,integrationId,recovery.id))
        atCreate=current2.get._1
        _ <- w.run(repo.beginPhase(atCreate,token2,now))
        _ <- w.run(repo.persist(atCreate,token2,atCreate.copy(state=ProvisioningRunState.Unknown,
          failureCode=Some("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN")),now,complete=false))
        heads <- w.run(repo.createdNodes(w.org,target.resourceId))
        preservedUnknown <- w.run(repo.find(w.org,integrationId,recovery.id))
        unresolvedBefore <- w.run(new PostgresIntegrationActionRepository().hasUnresolvedUnknown(w.org,integrationId))
        foreignUnresolved <- w.run(new PostgresIntegrationActionRepository().hasUnresolvedUnknown(w.foreignOrg,integrationId))
        retry=recovery.copy(id=uid,createdAt=now.plusSeconds(3),snapshot=recovery.snapshot.copy(
          recovery=recovery.snapshot.recovery.map(_.copy(sourceRunId=recovery.id))))
        competitor=retry.copy(id=uid)
        _ <- w.run(repo.insertPlan(retry))
        _ <- w.run(repo.insertPlan(competitor))
        winners <- List(retry,competitor).parTraverse(p =>
          w.run(repo.start(w.org,integrationId,p.id,uid,AuthorizationFixtures.ActorUserId,now)).attempt)
        unchanged <- w.run(repo.find(w.org,integrationId,approved.id))
        unknownAfter <- w.run(repo.find(w.org,integrationId,recovery.id))
        journalMutation <- w.run(sql"update remnawave_node_onboarding_phase set failure_code='changed' where run_id=${recovery.id} and phase='CREATE_NODE'".update.run).attempt
        finalToken=uid
        finalClaim <- w.run(repo.claim(uid,finalToken,now,now.plusSeconds(300),1))
        _ <- w.run(OnboardingPhase.all.foldLeftM(finalClaim.head) { (r,phase) =>
          val next=OnboardingPhase.next(phase,r.snapshot)
          val updated=r.copy(phase=next.getOrElse(phase),state=if(next.isEmpty) ProvisioningRunState.Succeeded else ProvisioningRunState.Running)
          repo.beginPhase(r,finalToken,now) *> repo.persist(r,finalToken,updated,now,complete=true).as(updated)
        })
        syncId=uid
        inventoryId=uid
        _ <- w.run(sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,started_at,recover_after_at,finished_at,status)
          values($syncId,${w.org},$integrationId,'MANUAL',$now,${now.plusSeconds(60)},$now,'COMPLETED')""".update.run)
        _ <- w.run(sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,external_id,display_name,
          summary_version,summary,is_active,first_seen_at,last_seen_at,last_seen_sync_session_id,created_at,updated_at)
          values($inventoryId,${w.org},$integrationId,'NODE',${external.toString},'Recovered node',1,'{}'::jsonb,true,$now,$now,$syncId,$now,$now)""".update.run)
        provenance <- w.run(new PostgresRemnawaveFleetQuery().provenance(w.org,integrationId,target.resourceId,inventoryId))
        unresolvedAfter <- w.run(new PostgresIntegrationActionRepository().hasUnresolvedUnknown(w.org,integrationId))
      } yield {
        assert(unresolvedBefore)
        assert(!foreignUnresolved)
        assert(!unresolvedAfter)
        assertEquals(provenance.map(_.onboardingId),Some(approved.id))
        assertEquals(provenance.map(_.imageReference),Some(approved.snapshot.imageReference))
        assertEquals(queued.snapshot.imageReference,reviewedImage)
        assertEquals(queued.snapshot.installationImageReference,approved.snapshot.imageReference)
        assertEquals(heads.map(_.id),List(recovery.id))
        assertEquals(winners.count(_.isRight),1)
        assertEquals(errorCode(winners.find(_.isLeft).get),Some("REMNAWAVE_ONBOARDING_RESOURCE_BUSY"))
        assertEquals(unknownAfter,preservedUnknown)
        assert(journalMutation.isLeft)
        assertEquals(existing.map(_.externalNodeId),List(Some(external)))
        assertEquals(foreign,Nil)
        assertEquals(errorCode(staleStart),Some("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
        assertEquals(readPlan.map(_._1.externalNodeId),Some(Some(external)))
        assertEquals(queued.externalNodeId,Some(external))
        assertEquals(queued.snapshot.correlationId,original.get._1.snapshot.correlationId)
        assertEquals(unchanged,original)
        assertEquals(original.get._1.state,ProvisioningRunState.Failed)
        assertEquals(original.get._2.filter(p => Set[OnboardingPhase](OnboardingPhase.CreateNode,OnboardingPhase.GetInstallationData)(p.phase)).map(_.state),List("SUCCEEDED","SUCCEEDED"))
      }
    }
  }

  test("claim recovers an in-flight phase, fences the stale worker, stores only encrypted credentials, and never resumes terminal state") {
    inWorld { w =>
      for {
        pair <- integration(w, w.org, "onboarding-lease")
        (integrationId, secretId) = pair
        target <- w.node("onboarding-lease")
        sourceAt <- connectionUpdated(w, target.connectionId)
        now <- IO.realTimeInstant
        approved <- plan(w, integrationId, secretId, target.resourceId, target.connectionId, sourceAt, now)
        requestId = uid
        _ <- w.run(repo.start(w.org, integrationId, approved.id, requestId, AuthorizationFixtures.ActorUserId, now))
        oldOwner = uid
        oldToken = uid
        first <- w.run(repo.claim(oldOwner, oldToken, now, now.plusSeconds(30), 1))
        firstFresh <- w.run(repo.beginPhase(first.head, oldToken, now))
        _ <- w.run(sql"update remnawave_node_onboarding set claim_deadline=clock_timestamp()-interval '1 second' where id=${approved.id}".update.run)
        recoveryOwner = uid
        recoveryToken = uid
        recovered <- w.run(repo.claim(recoveryOwner, recoveryToken, now, now.plusSeconds(60), 1))
        recoveredFresh <- w.run(repo.beginPhase(recovered.head, recoveryToken, now))
        recoveredJournal <- w.run(repo.find(w.org, integrationId, approved.id))
        staleRenew <- w.run(repo.renew(first.head, oldToken, now, now.plusSeconds(30)))
        stalePersist <- w.run(repo.persist(first.head, oldToken, first.head.copy(state = ProvisioningRunState.Failed,
          failureCode = Some("STALE"), finishedAt = Some(now)), now, complete = false))
        secret = cipher.encrypt(approved.id, w.org, NodeInstallationData.fromSecretKey(installationKey))
        _ <- w.run(repo.saveSecret(recovered.head, recoveryToken, secret, now))
        _ <- w.run(repo.saveSecret(recovered.head, recoveryToken, secret, now.plusSeconds(1)))
        storedSecret <- w.run(repo.secret(recovered.head))
        _ <- w.run(repo.deleteSecret(recovered.head, recoveryToken, now.plusSeconds(2)))
        deletedSecret <- w.run(repo.secret(recovered.head))
        terminal = recovered.head.copy(state = ProvisioningRunState.Failed, failureCode = Some("TEST_TERMINAL"), finishedAt = Some(now))
        terminalSaved <- w.run(repo.persist(recovered.head, recoveryToken, terminal, now, complete = false))
        claimedAgain <- w.run(repo.claim(uid, uid, now.plusSeconds(3), now.plusSeconds(90), 10))
        replay <- w.run(repo.start(w.org, integrationId, approved.id, requestId, AuthorizationFixtures.ActorUserId, now.plusSeconds(4)))
        read <- w.run(repo.find(w.org, integrationId, approved.id))
      } yield {
        assertEquals(firstFresh, true)
        assertEquals(recoveredFresh, false)
        assertEquals(recoveredJournal.toList.flatMap(_._2).head.state, "RUNNING")
        assertEquals(staleRenew, false)
        assertEquals(stalePersist, false)
        assertEquals(storedSecret.map(_.kind), Some(NodeInstallationCipher.Kind))
        assertEquals(cipher.decrypt(storedSecret.get).secretKey, installationKey)
        assert(!new String(storedSecret.get.ciphertext, java.nio.charset.StandardCharsets.UTF_8).contains(installationKey))
        assertEquals(deletedSecret, None)
        assertEquals(terminalSaved, true)
        assertEquals(claimedAgain, Nil)
        assertEquals(replay.state, ProvisioningRunState.Failed)
        assertEquals(read.map(_._1.state), Some(ProvisioningRunState.Failed))
      }
    }
  }

  test("a baseline attached before a crash is started once by its deterministic request, and only by its own parent") {
    inWorld { w =>
      for {
        pair <- integration(w, w.org, "onboarding-baseline-recovery")
        (integrationId, secretId) = pair
        target <- w.node("onboarding-baseline-recovery")
        other <- w.node("onboarding-baseline-other")
        targetAt <- connectionUpdated(w, target.connectionId)
        otherAt <- connectionUpdated(w, other.connectionId)
        now <- IO.realTimeInstant
        childId = uid
        _ <- w.run(provisioning.insertPlan(approvedProfileApplyPlan(w, target, childId, now)))
        parent <- plan(w, integrationId, secretId, target.resourceId, target.connectionId, targetAt, now,
          childId, baselineNeeded = true)
        _ <- w.run(repo.start(w.org, integrationId, parent.id, uid, AuthorizationFixtures.ActorUserId, now))
        token = uid
        claimed <- w.run(repo.claim(uid, token, now, now.plusSeconds(90), 1))
        // The crash window: the attach committed, and nothing has started the child yet.
        attached <- w.run(repo.attachBaseline(claimed.head, token, now))
        plannedStill <- w.run(provisioning.find(w.org, childId))
        parentLink <- w.run(query.baselinePlanParent(w.org, childId))
        afterAttach <- w.run(repo.find(w.org, integrationId, parent.id))
        // Recovery repeats the same attach and the same request; both are the same operation.
        reattached <- w.run(repo.attachBaseline(claimed.head, token, now.plusSeconds(1)))
        request = RemnawaveNodeOnboardingRun.baselineRequestId(parent.id)
        first <- w.run(provisioning.start(w.org, childId, request, AuthorizationFixtures.ActorUserId, now))
        again <- w.run(provisioning.start(w.org, childId, request, AuthorizationFixtures.ActorUserId, now.plusSeconds(2)))
        runsForTarget <- w.run(sql"select count(*) from provisioning_run where organization_id=${w.org} and resource_id=${target.resourceId}".query[Int].unique)
        // An onboarding of another server may not adopt this approved child.
        foreignParent <- plan(w, integrationId, secretId, other.resourceId, other.connectionId, otherAt, now,
          childId, baselineNeeded = true)
        foreignToken = uid
        _ <- w.run(repo.start(w.org, integrationId, foreignParent.id, uid, AuthorizationFixtures.ActorUserId, now))
        foreignClaimed <- w.run(repo.claim(uid, foreignToken, now, now.plusSeconds(90), 10))
        stolen <- w.run(repo.attachBaseline(foreignClaimed.find(_.id == foreignParent.id).get, foreignToken,
          now.plusSeconds(3)).attempt)
        ownerAfterTheft <- w.run(query.baselinePlanParent(w.org, childId))
      } yield {
        assertEquals(attached, childId)
        assertEquals(plannedStill.map(_._1.state), Some(ProvisioningRunState.Planned))
        assertEquals(parentLink, Some(parent.id))
        assertEquals(afterAttach.map(_._1.baselineRunId), Some(Some(childId)))
        assertEquals(reattached, childId)
        assertEquals(first.map(_._2), Some(true))
        assertEquals(again.map(_._2), Some(false))
        assertEquals(again.map(_._1.id), Some(childId))
        assertEquals(again.map(_._1.state), Some(ProvisioningRunState.Queued))
        assertEquals(runsForTarget, 1)
        assertEquals(errorCode(stolen), Some("REMNAWAVE_ONBOARDING_BASELINE_CHANGED"))
        assertEquals(ownerAfterTheft, Some(parent.id))
      }
    }
  }

  test("the synchronization session a phase depends on is stored under the lease and can be replaced") {
    inWorld { w =>
      for {
        pair <- integration(w, w.org, "onboarding-sync-attach")
        (integrationId, secretId) = pair
        target <- w.node("onboarding-sync-attach")
        targetAt <- connectionUpdated(w, target.connectionId)
        now <- IO.realTimeInstant
        parent <- plan(w, integrationId, secretId, target.resourceId, target.connectionId, targetAt, now)
        _ <- w.run(repo.start(w.org, integrationId, parent.id, uid, AuthorizationFixtures.ActorUserId, now))
        token = uid
        claimed <- w.run(repo.claim(uid, token, now, now.plusSeconds(90), 1))
        foreignSession = uid
        ownSession = uid
        _ <- List(foreignSession -> "COMPLETED", ownSession -> "RUNNING").traverse_ { case (id, status) =>
          w.run(sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,requested_by_user_id,
            started_at,recover_after_at,finished_at,status,nodes_count,hosts_count,config_profiles_count,deactivated_count)
            values($id,${w.org},$integrationId,'MANUAL',${AuthorizationFixtures.ActorUserId},$now,${now.plusSeconds(60)},
            ${Option.when(status == "COMPLETED")(now)},$status,1,0,0,0)""".update.run)
        }
        _ <- w.run(repo.attachSync(claimed.head, token, foreignSession, now))
        afterForeign <- w.run(repo.find(w.org, integrationId, parent.id))
        // A session that did not observe the new node is replaced by the onboarding's own.
        _ <- w.run(repo.attachSync(claimed.head, token, ownSession, now.plusSeconds(1)))
        afterOwn <- w.run(repo.find(w.org, integrationId, parent.id))
        stale <- w.run(repo.attachSync(claimed.head, uid, foreignSession, now.plusSeconds(2)).attempt)
        unchanged <- w.run(repo.find(w.org, integrationId, parent.id))
      } yield {
        assertEquals(afterForeign.map(_._1.syncSessionId), Some(Some(foreignSession)))
        assertEquals(afterOwn.map(_._1.syncSessionId), Some(Some(ownSession)))
        assertEquals(errorCode(stale), Some("REMNAWAVE_ONBOARDING_LEASE_LOST"))
        assertEquals(unchanged.map(_._1.syncSessionId), Some(Some(ownSession)))
      }
    }
  }

  test("active provisioning blocks onboarding, onboarding blocks unrelated provisioning, and its exact approved child is admitted") {
    inWorld { w =>
      for {
        pair <- integration(w, w.org, "onboarding-admission")
        (integrationId, secretId) = pair
        busyTarget <- w.node("onboarding-existing-provisioning")
        target <- w.node("onboarding-child-admission")
        busyAt <- connectionUpdated(w, busyTarget.connectionId)
        targetAt <- connectionUpdated(w, target.connectionId)
        now <- IO.realTimeInstant
        existingProvisioningId = uid
        existingProvisioning = provisioningPlan(w, busyTarget, existingProvisioningId, now)
        _ <- w.run(provisioning.insertPlan(existingProvisioning))
        _ <- w.run(provisioning.start(w.org, existingProvisioningId, uid, AuthorizationFixtures.ActorUserId, now))
        blockedPlan <- plan(w, integrationId, secretId, busyTarget.resourceId, busyTarget.connectionId, busyAt, now)
        blockedStart <- w.run(repo.start(w.org, integrationId, blockedPlan.id, uid, AuthorizationFixtures.ActorUserId, now).attempt)
        _ <- w.run(sql"update provisioning_run set status='FAILED', finished_at=$now, failure_code='TEST_RELEASED' where id=$existingProvisioningId".update.run)
        approvedChildId = uid
        approvedChild = approvedProfileApplyPlan(w, target, approvedChildId, now)
        unrelatedChildId = uid
        unrelatedChild = provisioningPlan(w, target, unrelatedChildId, now)
        _ <- w.run(provisioning.insertPlan(approvedChild) *> provisioning.insertPlan(unrelatedChild))
        parent <- plan(w, integrationId, secretId, target.resourceId, target.connectionId, targetAt, now,
          approvedChildId, baselineNeeded = true)
        _ <- w.run(repo.start(w.org, integrationId, parent.id, uid, AuthorizationFixtures.ActorUserId, now))
        owner = uid
        token = uid
        claimed <- w.run(repo.claim(owner, token, now, now.plusSeconds(90), 1))
        attached <- w.run(repo.attachBaseline(claimed.head, token, now))
        childStarted <- w.run(provisioning.start(w.org, attached, uid, AuthorizationFixtures.ActorUserId, now))
        wrongChildQueued <- w.run(sql"""update provisioning_run set request_id=${uid}, requested_by_user_id=${AuthorizationFixtures.ActorUserId},
          status='QUEUED', updated_at=$now where id=$unrelatedChildId""".update.run).attempt
        sessionId = uid
        inventoryObjectId = uid
        _ <- w.run(sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,requested_by_user_id,
          started_at,recover_after_at,finished_at,status,nodes_count,hosts_count,config_profiles_count,deactivated_count)
          values($sessionId,${w.org},$integrationId,'MANUAL',${AuthorizationFixtures.ActorUserId},$now,$now,$now,'COMPLETED',1,0,0,0)""".update.run)
        _ <- w.run(sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,external_id,display_name,
          summary_version,summary,is_active,first_seen_at,last_seen_at,last_seen_sync_session_id,created_at,updated_at)
          values($inventoryObjectId,${w.org},$integrationId,'NODE','unproven-external-node','Unproven node',1,'{}'::jsonb,true,$now,$now,$sessionId,$now,$now)""".update.run)
        manualBinding <- w.run(sql"""insert into integration_resource_binding(id,organization_id,integration_id,inventory_object_id,
          resource_id,created_by_user_id,created_at,updated_at)
          values(${uid},${w.org},$integrationId,$inventoryObjectId,${target.resourceId},${AuthorizationFixtures.ActorUserId},$now,$now)""".update.run).attempt
      } yield {
        assertEquals(errorCode(blockedStart), Some("REMNAWAVE_ONBOARDING_RESOURCE_BUSY"))
        assertEquals(attached, approvedChildId)
        assert(childStarted.exists(_._1.state == ProvisioningRunState.Queued))
        assert(wrongChildQueued.isLeft)
        assert(manualBinding.isLeft)
      }
    }
  }
  test("approved recreate has its own durable phases and identity; deletion plans require every extra phase") {
    List("RECREATE","DELETE_RECREATE").flatMap(a => List((a,1,None),(a,2,None)) ++ List(LocalInstallationState.Absent,LocalInstallationState.OwnedComplete,LocalInstallationState.OwnedPartial,LocalInstallationState.OwnedDamaged).map(s => (a,3,Some(LocalInstallationObservation.fromState(s))))).foreach { case(action,lifecycleVersion,localInstallation) => inWorld { w => for {
      pair <- integration(w,w.org,"recreate-"+action)
      (integrationId,secretId)=pair
      target <- w.node("recreate-"+action)
      sourceAt <- connectionUpdated(w,target.connectionId)
      now <- IO.realTimeInstant
      original <- plan(w,integrationId,secretId,target.resourceId,target.connectionId,sourceAt,now)
      _ <- w.run(repo.start(w.org,integrationId,original.id,uid,AuthorizationFixtures.ActorUserId,now))
      token=uid
      claimed <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
      _ <- w.run(repo.beginPhase(claimed.head,token,now))
      oldExternal=uid
      _ <- w.run(repo.persist(claimed.head,token,claimed.head.copy(state=ProvisioningRunState.Failed,
        externalNodeId=Some(oldExternal),failureCode=Some("TEST_FAILURE")),now,complete=false))
      oldHistory <- w.run(repo.find(w.org,integrationId,original.id))
      recovery=OnboardingRecovery(original.id,Some(oldExternal),original.snapshot.correlationId,original.id,
        if(action=="RECREATE") "CONFIRMED_NOT_FOUND" else "PRESENT_EXACT",action,localInstallation=localInstallation)
      draft=original.copy(id=uid,createdAt=now.plusSeconds(1),snapshot=original.snapshot.copy(correlationId=uid,recovery=Some(recovery),lifecycleVersion=lifecycleVersion))
      _ <- w.run(repo.insertPlan(draft))
      phases <- w.run(repo.find(w.org,integrationId,draft.id))
      foreignStart <- w.run(repo.start(w.foreignOrg,integrationId,draft.id,uid,AuthorizationFixtures.ActorUserId,now)).attempt
      requestId=uid
      unconfirmed <- w.run(repo.start(w.org,integrationId,draft.id,requestId,AuthorizationFixtures.ActorUserId,now)).attempt
      _ <- IO(assertEquals(errorCode(unconfirmed),Some("REMNAWAVE_ONBOARDING_RECREATE_CONFIRMATION_REQUIRED")))
      firstHttp <- httpStart(w,integrationId,draft.id,requestId,confirmed=true)
      _ <- IO(assertEquals(firstHttp._1,org.http4s.Status.Accepted))
      first <- w.run(repo.find(w.org,integrationId,draft.id)).map(_.get._1)
      retryHttp <- httpStart(w,integrationId,draft.id,requestId,confirmed=false)
      _ <- IO(assertEquals(retryHttp,firstHttp))
      retry <- w.run(repo.start(w.org,integrationId,draft.id,requestId,AuthorizationFixtures.ActorUserId,now.plusSeconds(1)))
      _ <- IO(assertEquals(retry,first))
      newToken=uid
      active <- w.run(repo.claim(uid,newToken,now,now.plusSeconds(300),1))
      premature <- w.run(sql"update remnawave_node_onboarding set state='SUCCEEDED',phase='FINAL_VERIFY',finished_at=$now,claim_owner=null,claim_token=null,claim_deadline=null where id=${draft.id}".update.run).attempt
      newExternal=uid
      completed <- w.run(OnboardingPhase.forSnapshot(draft.snapshot).foldLeftM(active.head) { (r,phase) =>
        val next=OnboardingPhase.next(phase,draft.snapshot)
        val updated=r.copy(phase=next.getOrElse(phase),state=if(next.isEmpty) ProvisioningRunState.Succeeded else ProvisioningRunState.Running,
          externalNodeId=if(phase==OnboardingPhase.CreateNode) Some(newExternal) else r.externalNodeId)
        repo.beginPhase(r,newToken,now) *> repo.persist(r,newToken,updated,now,complete=true).as(updated)
      })
      oldAfter <- w.run(repo.find(w.org,integrationId,original.id))
      historyDelete <- w.run(sql"delete from remnawave_node_onboarding where id=${original.id}".update.run).attempt
      phaseDelete <- w.run(sql"delete from remnawave_node_onboarding_phase where run_id=${original.id}".update.run).attempt
      heads <- w.run(repo.createdNodes(w.org,target.resourceId))
      invalidPhase <- w.run(sql"update remnawave_node_onboarding_phase set phase='DELETE_NODE' where run_id=${draft.id} and position=0".update.run).attempt
      syncId=uid
      inventoryId=uid
      _ <- w.run(sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,started_at,recover_after_at,finished_at,status)
        values($syncId,${w.org},$integrationId,'MANUAL',$now,${now.plusSeconds(60)},$now,'COMPLETED')""".update.run)
      _ <- w.run(sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,external_id,display_name,
        summary_version,summary,is_active,first_seen_at,last_seen_at,last_seen_sync_session_id,created_at,updated_at)
        values($inventoryId,${w.org},$integrationId,'NODE',${newExternal.toString},'Recreated node',1,'{}'::jsonb,true,$now,$now,$syncId,$now,$now)""".update.run)
      provenance <- w.run(new PostgresRemnawaveFleetQuery().provenance(w.org,integrationId,target.resourceId,inventoryId))
    } yield {
      assertEquals(phases.get._2.map(_.phase),OnboardingPhase.forSnapshot(draft.snapshot))
      assertEquals(phases.get._2.length,(if(action=="RECREATE") 14 else 16)+(if(lifecycleVersion>=2) 1 else 0)-(if(localInstallation.exists(_.state==LocalInstallationState.Absent)) 2 else 0))
      assertEquals(errorCode(foreignStart),Some("REMNAWAVE_ONBOARDING_NOT_FOUND"))
      assert(premature.isLeft)
      assert(invalidPhase.isLeft)
      assertEquals(completed.state,ProvisioningRunState.Succeeded)
      assertEquals(completed.externalNodeId,Some(newExternal))
      assertEquals(oldAfter,oldHistory)
      assert(historyDelete.isLeft)
      assert(phaseDelete.isLeft)
      assertEquals(heads.map(_.id),List(draft.id))
      assertEquals(provenance.map(_.onboardingId),Some(draft.id))
      assertNotEquals(completed.snapshot.correlationId,original.snapshot.correlationId)
    } } }
  }

  test("v3 unsafe local observations cannot be forced by confirmation even without a copied blocker") {
    List(LocalInstallationState.Foreign,LocalInstallationState.PortConflict,LocalInstallationState.Unknown).foreach { state => inWorld { w => for {
      pair <- integration(w,w.org,"unsafe-local")
      target <- w.node("unsafe-local")
      at <- connectionUpdated(w,target.connectionId)
      now <- IO.realTimeInstant
      original <- plan(w,pair._1,pair._2,target.resourceId,target.connectionId,at,now)
      _ <- w.run(repo.start(w.org,pair._1,original.id,uid,AuthorizationFixtures.ActorUserId,now))
      token=uid
      claimed <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
      _ <- w.run(repo.beginPhase(claimed.head,token,now))
      oldExternal=uid
      _ <- w.run(repo.persist(claimed.head,token,claimed.head.copy(state=ProvisioningRunState.Failed,
        externalNodeId=Some(oldExternal),failureCode=Some("TEST_FAILURE")),now,complete=false))
      proof=OnboardingRecovery(original.id,Some(oldExternal),original.snapshot.correlationId,original.id,"CONFIRMED_NOT_FOUND","RECREATE",
        localInstallation=Some(LocalInstallationObservation.fromState(state)))
      draft=original.copy(id=uid,snapshot=original.snapshot.copy(correlationId=uid,recovery=Some(proof),lifecycleVersion=3))
      _ <- w.run(repo.insertPlan(draft))
      started <- w.run(repo.start(w.org,pair._1,draft.id,uid,AuthorizationFixtures.ActorUserId,now,confirmRecreate=true)).attempt
      stored <- w.run(repo.find(w.org,pair._1,draft.id))
    } yield {
      assertEquals(errorCode(started),Some("REMNAWAVE_ONBOARDING_BLOCKED"))
      assertEquals(stored.map(_._1.state),Some(ProvisioningRunState.Planned))
    } } }
  }

  test("onboarding server options use four SQL statements for 1, 100 and 500 resources with identical compliant semantics") {
    inWorld { w =>
      val targets = new PostgresProvisioningTargetQuery
      val profiles = new PostgresServerProfileRepository
      val ids = List.fill(500)(uid)
      val profileId = uid; val revisionId = uid; val now = Instant.now()
      val content = support.ServerProfileFixtures.content
      val observed = support.ServerProfileFixtures.observed()
      for {
        source <- w.node("options-shared-ssh")
        sourceAt <- connectionUpdated(w,source.connectionId)
        _ <- w.run(for {
          _ <- sql"""insert into resource(id,organization_id,environment_id,resource_type_id,code,name,is_active,spec)
            select n.id,r.organization_id,r.environment_id,r.resource_type_id,n.id::text,n.id::text,true,r.spec
            from unnest(${ids.toArray[UUID]}) as n(id) cross join resource r where r.id=${source.resourceId}""".update.run
          _ <- sql"""insert into external_ref(id,organization_id,connection_id,external_type,external_id,resource_id)
            select gen_random_uuid(),${w.org},${source.connectionId},'NODE',n.id::text,n.id from unnest(${ids.toArray[UUID]}) as n(id)""".update.run
          _ <- profiles.insertProfile(ServerProfile(profileId,w.org,"Options baseline","options-baseline",None,false,1,
            AuthorizationFixtures.ActorUserId,now,now))
          _ <- profiles.insertRevision(ServerProfileRevision(revisionId,w.org,profileId,1,content,content.hash,AuthorizationFixtures.ActorUserId,now))
          _ <- sql"""insert into server_profile_assignment(id,organization_id,resource_id,profile_id,revision_id,revision_number,version,assigned_by_user_id,assigned_at)
            select gen_random_uuid(),${w.org},n.id,$profileId,$revisionId,1,1,${AuthorizationFixtures.ActorUserId},$now
            from unnest(${ids.toArray[UUID]}) as n(id)""".update.run
          _ <- sql"""insert into server_profile_observation(id,organization_id,resource_id,source_connection_id,source_updated_at,
            assignment_id,assignment_version,revision_id,schema_version,content,content_hash,observed_at)
            select gen_random_uuid(),${w.org},a.resource_id,${source.connectionId},$sourceAt,a.id,a.version,a.revision_id,1,
              cast(${observed.noSpaces} as jsonb),${ServerProfileDiff.hashObservation(observed)},$now
            from server_profile_assignment a where a.organization_id=${w.org} and a.profile_id=$profileId""".update.run
        } yield ())
        databaseName <- w.run(sql"select current_database()".query[String].unique)
        _ <- List(1,100,500).traverse_ { size => Ref.of[IO,Int](0).flatMap { counter =>
          val handler = new LogHandler[IO] { def run(event: LogEvent): IO[Unit] = counter.update(_+1) }
          val config = PostgresTestDatabase.config
          val url = config.url.replaceFirst("/[^/?]+(?=\\?|$)","/"+databaseName)
          val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver",url,config.user,config.password,Some(handler))
          val runner = new DoobieTransactionRunner(xa)
          for {
            result <- runner.run(for {
              candidates <- query.candidates(w.org,size)
              eligible <- targets.eligibleBatch(w.org,candidates.map(_.id))
              statuses <- query.serverStatuses(w.org,eligible)
            } yield candidates -> statuses)
            count <- counter.get
            _ = assertEquals(count,4,s"size=$size")
            _ = assertEquals(result._1.size,size)
            _ = assertEquals(result._2.size,size)
            _ = assert(result._2.values.forall(s => s.profileAssigned && s.profileState=="COMPLIANT" && !s.busy && !s.bindingConflict))
            foreign <- runner.run(query.serverStatuses(w.foreignOrg,result._1.map(_.id -> Left("PROVISIONING_TARGET_NOT_FOUND")).toMap))
            _ = assert(foreign.values.forall(s => !s.profileAssigned && s.profileState=="UNOBSERVED" && !s.busy && !s.bindingConflict))
          } yield ()
        }}
      } yield ()
    }
  }

  test("onboarding and node actions serialize one winner for bound and historical unbound identities") {
    List("NODE_ENABLE","NODE_DISABLE","NODE_RESTART").foreach { action => List(true,false).foreach { bound => inWorld { w =>
      for {
        pair <- integration(w,w.org,"action-race")
        (integrationId,secretId)=pair
        target <- w.node("action-race")
        sourceAt <- connectionUpdated(w,target.connectionId)
        now <- IO.realTimeInstant
        original <- plan(w,integrationId,secretId,target.resourceId,target.connectionId,sourceAt,now)
        _ <- w.run(repo.start(w.org,integrationId,original.id,uid,AuthorizationFixtures.ActorUserId,now))
        token=uid
        claimed <- w.run(repo.claim(uid,token,now,now.plusSeconds(300),1))
        oldExternal=uid
        _ <- w.run(repo.beginPhase(claimed.head,token,now))
        _ <- w.run(repo.persist(claimed.head,token,claimed.head.copy(state=ProvisioningRunState.Failed,
          externalNodeId=Some(oldExternal),failureCode=Some("TEST_FAILURE")),now,complete=false))
        originalBefore <- w.run(repo.find(w.org,integrationId,original.id))
        recovery=original.copy(id=uid,externalNodeId=Some(oldExternal),snapshot=original.snapshot.copy(lifecycleVersion=2,
          recovery=Some(OnboardingRecovery(original.id,Some(oldExternal),original.snapshot.correlationId,original.id,"PRESENT_UNHEALTHY","RECOVER"))))
        _ <- w.run(repo.insertPlan(recovery))
        sessionId=uid; inventoryId=uid
        _ <- w.run(for {
          _ <- sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,started_at,recover_after_at,finished_at,status)
            values($sessionId,${w.org},$integrationId,'MANUAL',$now,$now,$now,'COMPLETED')""".update.run
          _ <- sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,external_id,display_name,
            summary_version,summary,is_active,first_seen_at,last_seen_at,last_seen_sync_session_id,created_at,updated_at)
            values($inventoryId,${w.org},$integrationId,'NODE',${oldExternal.toString},'Existing node',1,'{}'::jsonb,true,$now,$now,$sessionId,$now,$now)""".update.run
          _ <- if(bound) sql"""insert into integration_resource_binding(id,organization_id,integration_id,inventory_object_id,resource_id,
            created_by_user_id,created_at,updated_at) values(${uid},${w.org},$integrationId,$inventoryId,${target.resourceId},
            ${AuthorizationFixtures.ActorUserId},$now,$now)""".update.run.void else ().pure[ConnectionIO]
        } yield ())
        actionId=uid; actionRequest=uid
        winners <- (w.run(repo.start(w.org,integrationId,recovery.id,uid,AuthorizationFixtures.ActorUserId,now)).attempt,
          w.run(sql"""insert into integration_action_execution(id,organization_id,integration_id,inventory_object_id,request_id,
            action_code,external_id_snapshot,display_name_snapshot,requested_by_user_id,status,created_at,updated_at)
            values($actionId,${w.org},$integrationId,$inventoryId,$actionRequest,$action,${oldExternal.toString},'Existing node',
              ${AuthorizationFixtures.ActorUserId},'QUEUED',$now,$now)""".update.run).attempt).parTupled
        originalAfter <- w.run(repo.find(w.org,integrationId,original.id))
        _ = assertEquals(List(winners._1.isRight,winners._2.isRight).count(identity),1,s"action=$action bound=$bound")
        _ = assertEquals(originalAfter,originalBefore)
        _ = assert(errorCode(winners._1).contains("REMNAWAVE_ONBOARDING_RESOURCE_BUSY") ||
          List(winners._1.left.toOption,winners._2.left.toOption).flatten.exists(_.getMessage.contains("REMNAWAVE_ONBOARDING_RESOURCE_BUSY")),
          List(winners._1.left.toOption,winners._2.left.toOption).flatten.map(_.getMessage).mkString("; "))
        _ <- if(winners._1.isRight) w.run(sql"""insert into integration_action_execution(id,organization_id,integration_id,inventory_object_id,request_id,
            action_code,external_id_snapshot,display_name_snapshot,requested_by_user_id,status,created_at,updated_at)
            values(${uid},${w.org},$integrationId,$inventoryId,${uid},$action,${oldExternal.toString},'Existing node',
              ${AuthorizationFixtures.ActorUserId},'QUEUED',$now,$now)""".update.run).attempt.map(r => assert(r.isLeft))
          else w.run(repo.start(w.org,integrationId,recovery.id,uid,AuthorizationFixtures.ActorUserId,now)).attempt.map(r => assert(r.isLeft))
      } yield ()
    }}}
  }

}

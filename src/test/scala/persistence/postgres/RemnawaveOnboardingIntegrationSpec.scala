package ru.bitec.app.ops
package persistence.postgres

import application.integration.IntegrationError
import application.port.RemnawaveOnboardingRepository
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import domain.provisioning._
import integration.secret.NodeInstallationCipher
import integration.ssh.SecretEncryptionConfig
import java.time.Instant
import java.util.{Base64, UUID}
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.AuthorizationFixtures

/** PostgreSQL constraints, migrations, and repository behavior for durable onboarding plans. */
final class RemnawaveOnboardingIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run

  private val repo = new PostgresRemnawaveOnboardingRepository
  private val query = new PostgresRemnawaveOnboardingQuery
  private val provisioning = new PostgresProvisioningRunRepository
  private val cipher = NodeInstallationCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(Map(
    "INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(Array.fill[Byte](32)(11)))).toOption.get)
  private val installationKey = "secret-installation-key-for-persistence-test"

  private def uid = UUID.randomUUID()
  private def finishWorld(w: ConfigurationDeploymentWorld)(body: IO[Unit]): IO[Unit] =
    body.guarantee(w.run(for {
      // Isolated teardown bypasses terminal-history and FK triggers, then removes the linked rows explicitly.
      _ <- sql"set local session_replication_role='replica'".update.run
      _ <- sql"delete from remnawave_node_onboarding_phase where run_id in (select id from remnawave_node_onboarding where organization_id in (${w.org},${w.foreignOrg}))".update.run
      _ <- sql"delete from remnawave_node_installation_secret where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"update provisioning_run set onboarding_parent_id=null where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_node_onboarding where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_resource_binding where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_inventory_object where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_sync_session where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_secret where organization_id in (${w.org},${w.foreignOrg})".update.run
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
        started <- w.run(repo.start(w.org, integrationId, approved.id, requestId, AuthorizationFixtures.ActorUserId, now))
        retry <- w.run(repo.start(w.org, integrationId, approved.id, requestId, AuthorizationFixtures.ActorUserId, now.plusSeconds(1)))
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
        candidate <- IO.fromEither(RemnawaveNodeOnboardingRun.recoveryCandidate(existing,integrationId,
          approved.snapshot.input,approved.snapshot.imageReference,approved.snapshot.connectionId).leftMap(new RuntimeException(_)))
        recovery = approved.copy(id=uid,createdAt=now.plusSeconds(2),externalNodeId=candidate.flatMap(_.externalNodeId),
          snapshot=approved.snapshot.copy(recovery=Some(OnboardingRecovery(failed.id,Some(external),
            approved.snapshot.correlationId,approved.id,"PRESENT_UNHEALTHY","RECOVER"))))
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
      } yield {
        assertEquals(provenance.map(_.onboardingId),Some(approved.id))
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
    List("RECREATE","DELETE_RECREATE").foreach { action => inWorld { w => for {
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
        if(action=="RECREATE") "CONFIRMED_NOT_FOUND" else "PRESENT_EXACT",action)
      draft=original.copy(id=uid,createdAt=now.plusSeconds(1),snapshot=original.snapshot.copy(correlationId=uid,recovery=Some(recovery)))
      _ <- w.run(repo.insertPlan(draft))
      phases <- w.run(repo.find(w.org,integrationId,draft.id))
      foreignStart <- w.run(repo.start(w.foreignOrg,integrationId,draft.id,uid,AuthorizationFixtures.ActorUserId,now)).attempt
      _ <- w.run(repo.start(w.org,integrationId,draft.id,uid,AuthorizationFixtures.ActorUserId,now))
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
      assertEquals(phases.get._2.length,if(action=="RECREATE") 14 else 16)
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

}

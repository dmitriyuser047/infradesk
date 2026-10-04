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
}

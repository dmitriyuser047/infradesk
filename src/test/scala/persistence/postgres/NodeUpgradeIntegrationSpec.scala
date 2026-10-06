package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.{AuthorizationFixtures, NodeUpgradeFixtures}

/** Real PostgreSQL, tenant FKs, cross-engine races, live leases and immutable history. */
final class NodeUpgradeIntegrationSpec extends FunSuite {
  private val repo = new PostgresRemnawaveFleetUpgradeRepository
  private val fleets = new PostgresRemnawaveFleetRepository
  private val rollouts = new PostgresRemnawaveFleetRolloutRepository
  private val onboardings = new PostgresRemnawaveOnboardingRepository
  private val actor = AuthorizationFixtures.ActorUserId
  private def uid = UUID.randomUUID()
  private case class Setup(run: RemnawaveFleetUpgradeRun, members: List[RemnawaveFleetUpgradeMember], revision: FleetNodeReleaseRevision)
  private def teardown(w: ConfigurationDeploymentWorld): IO[Unit] = w.run(for {
    _ <- sql"set local session_replication_role='replica'".update.run
    _ <- sql"delete from remnawave_fleet_upgrade_action where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_upgrade_member where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_upgrade_run where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_node_image_observation where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_release_policy where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_release_revision where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_rollout_action where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_rollout_member where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_rollout where organization_id=${w.org}".update.run
    _ <- sql"delete from operation_execution where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_membership_replacement where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_node_onboarding where organization_id=${w.org}".update.run
    _ <- sql"delete from integration_action_execution where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_node_assessment where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_membership where organization_id=${w.org}".update.run
    _ <- sql"update remnawave_fleet set desired_revision_id=null where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet_revision where organization_id=${w.org}".update.run
    _ <- sql"delete from remnawave_fleet where organization_id=${w.org}".update.run
    _ <- sql"delete from integration_resource_binding where organization_id=${w.org}".update.run
    _ <- sql"delete from integration_inventory_object where organization_id=${w.org}".update.run
    _ <- sql"delete from integration_sync_session where organization_id=${w.org}".update.run
    _ <- sql"delete from integration where organization_id=${w.org}".update.run
    _ <- sql"delete from integration_secret where organization_id=${w.org}".update.run
  } yield ())
  private def world(body: (ConfigurationDeploymentWorld, Setup) => IO[Unit]): Unit = ConfigurationDeploymentWorld.run { w =>
    (setup(w).flatMap(s => body(w, s))).guarantee(teardown(w))
  }
  private def setup(w: ConfigurationDeploymentWorld): IO[Setup] = {
    val raw = NodeUpgradeFixtures.run(1)
    val org = w.org; val integration = raw.integrationId; val secret = uid; val session = uid
    val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    for {
      source <- w.node("node-upgrade")
      sourceAt <- w.run(sql"select updated_at from connection where id=${source.connectionId}".query[Instant].unique)
      p = raw.snapshot.members.head.copy(resourceId = source.resourceId, sourceConnectionId = source.connectionId, sourceUpdatedAt = sourceAt)
      snapshot = raw.snapshot.copy(organizationId = org, integrationPin = now, members = List(p))
      run = raw.copy(organizationId = org, createdBy = actor, state = FleetRolloutState.Planned, requestId = None,
        snapshot = snapshot, snapshotHash = snapshot.hash, createdAt = now, updatedAt = now, expiresAt = now.plusSeconds(86400))
      membership = RemnawaveFleetMembership(p.membershipId, org, run.fleetId, integration, p.inventoryNodeId, source.resourceId, 1L, actor, now)
      fleet = RemnawaveFleet(run.fleetId, org, integration, "image-upgrade", "Image upgrade", None, None, 1L, false, actor, now, now)
      config = RemnawaveFleetRevision(snapshot.fleetRevisionId, org, run.fleetId, 1, 1, snapshot.configuration.hash, snapshot.configuration, actor, now)
      revision = FleetNodeReleaseRevision(run.releaseRevisionId, org, integration, run.fleetId, 1, snapshot.target, snapshot.panel, actor, Instant.now())
      pinned = run.copy(snapshot = snapshot.copy(fleetRevisionHash = config.contentHash, releaseRevisionHash = revision.hash))
      prepared = pinned.copy(snapshotHash = pinned.snapshot.hash)
      members = NodeUpgradeFixtures.members(prepared)
      _ <- w.run(for {
        _ <- sql"""insert into integration_secret(id,organization_id,kind,nonce,ciphertext,created_at)
          values($secret,$org,'INTEGRATION_CREDENTIAL',${Array.fill[Byte](12)(1)},${Array.fill[Byte](32)(2)},$now)""".update.run
        _ <- sql"""insert into integration(id,organization_id,name,provider_type,base_url,enabled,secret_id,
          caddy_api_key_configured,created_at,updated_at,management_mode)
          values($integration,$org,'Panel','REMNAWAVE','https://panel.example.test',true,$secret,false,$now,$now,'MANAGED_SELECTED')""".update.run
        _ <- sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,requested_by_user_id,
          started_at,recover_after_at,finished_at,status,nodes_count,hosts_count,config_profiles_count,deactivated_count)
          values($session,$org,$integration,'MANUAL',$actor,$now,$now,$now,'COMPLETED',1,0,0,0)""".update.run
        _ <- sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,external_id,
          display_name,summary_version,summary,is_active,first_seen_at,last_seen_at,last_seen_sync_session_id,created_at,updated_at)
          values(${p.inventoryNodeId},$org,$integration,'NODE',${p.externalNodeId.toString},'Node',1,'{}',true,$now,$now,$session,$now,$now)""".update.run
        _ <- sql"""insert into integration_resource_binding(id,organization_id,integration_id,inventory_object_id,resource_id,
          created_by_user_id,created_at,updated_at) values(${uid},$org,$integration,${p.inventoryNodeId},${p.resourceId},$actor,$now,$now)""".update.run
        _ <- sql"""insert into server_profile(id,organization_id,code,name,archived,latest_revision,created_by_user_id,created_at,updated_at)
          values(${snapshot.configuration.serverProfileId},$org,'upgrade-profile','Upgrade baseline',false,1,$actor,$now,$now)""".update.run
        _ <- sql"""insert into server_profile_revision(id,organization_id,profile_id,revision_number,schema_version,content,content_hash,created_by_user_id,created_at)
          values(${snapshot.configuration.serverProfileRevisionId},$org,${snapshot.configuration.serverProfileId},1,1,
            cast(${support.ServerProfileFixtures.content.canonical} as jsonb),${support.ServerProfileFixtures.content.hash},$actor,$now)""".update.run
        _ <- sql"""insert into configuration_profile(id,organization_id,code,name,archived,latest_revision_number,created_at,updated_at,kind)
          values(${snapshot.configuration.configurationProfileId},$org,'upgrade-config','Node configuration',false,1,$now,$now,'REMNAWAVE_CONFIG')""".update.run
        _ <- sql"""insert into configuration_revision(id,organization_id,profile_id,revision_number,template_text,created_by_user_id,created_at)
          values(${snapshot.configuration.configRevisionId},$org,${snapshot.configuration.configurationProfileId},1,'{}',$actor,$now)""".update.run
        _ <- fleets.insertFleet(fleet)
        _ <- fleets.insertRevision(config)
        _ <- fleets.promote(org, run.fleetId, config.id, 1L, now)
        _ <- fleets.insertMembership(membership, now)
        _ <- repo.promote(revision)
        _ <- repo.insertPlan(prepared, members)
      } yield ())
    } yield Setup(prepared, members, revision)
  }
  private case class RecoveryPlan(run: RemnawaveNodeOnboardingRun, sourceId: UUID, oldExternalId: UUID,
    snapshot: OnboardingSnapshot)
  private def recoveryPlan(w: ConfigurationDeploymentWorld, s: Setup): IO[RecoveryPlan] = {
    val p = s.run.snapshot.members.head
    val now = Instant.now()
    val sourceId = uid
    val oldExternalId = p.externalNodeId
    val input = OnboardingInput(p.resourceId, "replacement-node", "192.0.2.88", 30222, uid, List(uid),
      List("198.51.100.0/24"))
    val compatibility = NodeApiCompatibility(Some("2.8.0"), Some("PROFILE_PUBKEY"), Some("reviewed-commit"),
      Set(NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData,
        NodeProvisioningCapability.Status, NodeProvisioningCapability.ConfigProfile), None)
    val snapshot = OnboardingSnapshot(input, now, uid, p.sourceConnectionId, p.sourceConnectionAt,
      uid, uid, 1, uid, 1L, "a" * 64, uid, false, compatibility, "remnawave/node:2.8.0", uid,
      "Replacement node", "Baseline", "Profile", List("inbound"), Nil, Nil, Nil)
    val source = RemnawaveNodeOnboardingRun(sourceId, w.org, s.run.integrationId, p.resourceId, None, actor,
      domain.provisioning.ProvisioningRunState.Planned, OnboardingPhase.Validate, snapshot, now, now)
    val proof = OnboardingRecovery(sourceId, Some(oldExternalId), snapshot.correlationId, sourceId,
      "PRESENT_UNHEALTHY", "RECREATE")
    val recoverySnapshot = snapshot.copy(correlationId = uid, recovery = Some(proof))
    val recovery = RemnawaveNodeOnboardingRun(uid, w.org, s.run.integrationId, p.resourceId, None, actor,
      domain.provisioning.ProvisioningRunState.Planned, OnboardingPhase.Validate, recoverySnapshot, now.plusMillis(1), now.plusMillis(1))
    for {
      _ <- w.run(onboardings.insertPlan(source))
      _ <- w.run(onboardings.start(w.org, source.integrationId, source.id, uid, actor, now))
      token = uid
      claimed <- w.run(onboardings.claim(uid, token, now, now.plusSeconds(600), 10))
      sourceClaim = claimed.find(_.id == source.id).get
      _ <- w.run(onboardings.beginPhase(sourceClaim, token, now))
      failed = sourceClaim.copy(state = domain.provisioning.ProvisioningRunState.Failed,
        externalNodeId = Some(oldExternalId), failureCode = Some("TEST_FAILURE"), finishedAt = Some(now))
      _ <- w.run(onboardings.persist(sourceClaim, token, failed, now, complete = true))
      _ <- w.run(onboardings.insertPlan(recovery))
    } yield RecoveryPlan(recovery, sourceId, oldExternalId, recoverySnapshot)
  }
  private def startRecovery(w: ConfigurationDeploymentWorld, plan: RecoveryPlan, now: Instant): IO[Unit] =
    w.run(onboardings.start(w.org, plan.run.integrationId, plan.run.id, uid, actor, now, confirmRecreate = true)).void

  private def runToBindResource(w: ConfigurationDeploymentWorld, plan: RecoveryPlan,
    newExternalId: UUID): IO[(RemnawaveNodeOnboardingRun, UUID, Instant)] = for {
    now <- IO.realTimeInstant
    _ <- startRecovery(w, plan, now)
    token = uid
    claimed <- w.run(onboardings.claim(uid, token, now, now.plusSeconds(3600), 20))
    initial = claimed.find(_.id == plan.run.id).get
    bound <- OnboardingPhase.forSnapshot(initial.snapshot).takeWhile(_ != OnboardingPhase.BindResource)
      .foldLeftM(initial) { (r, phase) =>
        val at = now.plusMillis(1)
        val next = OnboardingPhase.next(phase, r.snapshot).get
        val updated = r.copy(phase = next,
          externalNodeId = if (phase == OnboardingPhase.CreateNode) Some(newExternalId) else r.externalNodeId)
        for {
          begun <- w.run(onboardings.beginPhase(r, token, at))
          _ = assert(begun)
          saved <- w.run(onboardings.persist(r, token, updated, at, complete = true))
          _ = assert(saved)
        } yield updated
      }
    _ = assertEquals(bound.phase, OnboardingPhase.BindResource)
    _ <- w.run(onboardings.beginPhase(bound, token, now.plusSeconds(1))).map(ok => assert(ok))
  } yield (bound, token, now.plusSeconds(1))

  private def replacementNode(w: ConfigurationDeploymentWorld, s: Setup): IO[(UUID, UUID)] = {
    val p = s.run.snapshot.members.head
    val nodeId = uid; val externalId = uid; val now = Instant.now()
    for {
      session <- w.run(sql"select id from integration_sync_session where integration_id=${s.run.integrationId} and status='COMPLETED' limit 1".query[UUID].unique)
      _ <- w.run(sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,external_id,
        display_name,summary_version,summary,is_active,first_seen_at,last_seen_at,last_seen_sync_session_id,created_at,updated_at)
        values($nodeId,${w.org},${s.run.integrationId},'NODE',${externalId.toString},'Replacement node',1,'{}'::jsonb,true,
          $now,$now,$session,$now,$now)""".update.run)
    } yield nodeId -> externalId
  }
  private def rejected[A](io: IO[A], code: String): IO[Unit] = io.attempt.map { result =>
    assert(result.isLeft)
    assert(result.left.toOption.exists(_.getMessage.contains(code)), result.left.toOption.map(_.getMessage))
  }
  private def start(w: ConfigurationDeploymentWorld, s: Setup): IO[Unit] =
    w.run(repo.start(w.org, s.run.id, uid, Instant.now())).map(ok => assert(ok))
  private def claim(w: ConfigurationDeploymentWorld, s: Setup): IO[(RemnawaveFleetUpgradeRun, UUID)] = for {
    now <- IO.realTimeInstant
    token = uid
    claimed <- w.run(repo.claim(uid, token, now, now.plusSeconds(120), 20))
  } yield claimed.find(_.id == s.run.id).get -> token
  private def operation(w: ConfigurationDeploymentWorld, s: Setup): ConnectionIO[Int] = {
    val p = s.run.snapshot.members.head; val now = Instant.now()
    sql"""insert into operation_execution(id,organization_id,resource_id,actor_user_id,operation,target_connection_id,
      target_external_type,target_external_id,status,started_at,created_at,updated_at,recover_after_at)
      values(${uid},${w.org},${p.resourceId},$actor,'CONTAINER_RESTART',${p.sourceConnectionId},'CONTAINER','node',
        'RUNNING',$now,$now,$now,${now.plusSeconds(120)})""".update.run
  }
  private def rolloutPlan(w: ConfigurationDeploymentWorld, s: Setup): (RemnawaveFleetRollout, List[RemnawaveFleetRolloutMember]) = {
    val p = s.run.snapshot.members.head; val now = Instant.now(); val id = uid
    val baseline = FleetMemberBaseline(None, None, None, "COMPLIANT", "HEALTHY")
    val mp = FleetRolloutMemberPlan(p.membershipId, 1L, p.inventoryNodeId, p.resourceId, p.externalNodeId.toString,
      p.nodeName, 0, 0, None, List(FleetActionKind.DesiredState), baseline)
    val content = s.run.snapshot.configuration
    val snap = FleetRolloutSnapshot(s.run.fleetId, s.run.integrationId, s.run.snapshot.integrationPin,
      s.run.snapshot.fleetRevisionId, 1, s.run.snapshot.fleetRevisionHash, content,
      FleetRolloutPolicy(1, Nil, false, false, FleetRollbackScope.CurrentWave),
      FleetRolloutSharedConfig(false, content.inventoryConfigProfileId, 1, content.configRevisionHash, None, None, 0, 0), List(mp))
    val run = RemnawaveFleetRollout(id, w.org, s.run.integrationId, s.run.fleetId, snap.revisionId, None,
      FleetRolloutState.Planned, FleetRolloutPhase.Validate, snap, snap.hash, 0, 1, false, false,
      FleetRollbackScope.CurrentWave, actor, now, now.plusSeconds(86400), None, None, None, None, false,
      None, None, None, None, None, None, now, now, None, 1L, now)
    run -> List(RemnawaveFleetRolloutMember(uid, w.org, id, s.run.fleetId, p.membershipId, 1L, p.inventoryNodeId,
      p.resourceId, p.externalNodeId.toString, 0, 0, FleetRolloutMemberState.Pending, None, List(FleetActionKind.DesiredState),
      None, None, None, None, None, 1L, now))
  }
  test("release revision and separate pointer round trip with stable digest hash; configuration desired stays untouched") {
    world { (w, s) => for {
      target <- w.run(repo.releaseTarget(w.org, s.run.fleetId))
      _ = assertEquals(target.map(_.hash), Some(s.revision.hash))
      config <- w.run(fleets.fleet(w.org, s.run.integrationId, s.run.fleetId))
      _ = assertEquals(config.flatMap(_.desiredRevisionId), Some(s.run.snapshot.fleetRevisionId))
      foreign <- w.run(repo.releaseTarget(w.foreignOrg, s.run.fleetId))
      _ = assertEquals(foreign, None)
      _ <- rejected(w.run(sql"update remnawave_fleet_release_revision set node_version='9.9.9' where id=${s.revision.id}".update.run), "REVISION_IMMUTABLE")
      _ <- rejected(w.run(sql"delete from remnawave_fleet_release_revision where id=${s.revision.id}".update.run), "REVISION_IMMUTABLE")
    } yield () }
  }
  test("one request starts exactly once, snapshot remains immutable, and a second plan cannot acquire active members") {
    world { (w, s) => for {
      first <- w.run(repo.run(w.org, s.run.fleetId, s.run.id))
      _ = assertEquals(first.map(_.snapshotHash), Some(s.run.snapshotHash))
      _ = assertEquals(first.map(_.snapshot), Some(s.run.snapshot))
      _ <- start(w, s)
      r <- w.run(repo.byId(s.run.id)).map(_.get)
      again <- w.run(repo.start(w.org, s.run.id, r.requestId.get, Instant.now()))
      _ = assert(!again)
      byRequest <- w.run(repo.byRequest(w.org, r.requestId.get))
      _ = assertEquals(byRequest.map(_.id), Some(s.run.id))
      busy <- w.run(fleets.rolloutActive(w.org, s.run.fleetId))
      _ = assert(busy)
      _ <- rejected(w.run(sql"update remnawave_fleet_upgrade_member set active=false where id=${s.members.head.id}".update.run), "LOCK_IMMUTABLE")
      _ <- rejected(w.run(sql"update remnawave_fleet_upgrade_run set snapshot_hash=${"0" * 64} where id=${s.run.id}".update.run), "SNAPSHOT_IMMUTABLE")
      other = s.run.copy(id = uid)
      _ <- w.run(repo.insertPlan(other, NodeUpgradeFixtures.members(other)))
      _ <- rejected(w.run(repo.start(w.org, other.id, uid, Instant.now())), "RESOURCE_BUSY")
    } yield () }
  }
  test("live fencing covers parent, member and action writes and prevents an expired claim from renewing") {
    world { (w, s) => for {
      _ <- start(w, s)
      claimed <- claim(w, s)
      (r, token) = claimed
      now <- IO.realTimeInstant
      m = s.members.head
      a = RemnawaveFleetUpgradeAction(uid, w.org, r.id, m.id, "PREFETCH", false, FleetActionState.Running,
        s.run.snapshot.members.head.previousImageReference.get, None, now, None)
      valid <- w.run(repo.saveAction(a, token, now))
      _ = assert(valid)
      _ <- w.run(sql"update remnawave_fleet_upgrade_run set claim_deadline=clock_timestamp()-interval '1 second' where id=${r.id}".update.run)
      renewed <- w.run(repo.renew(r.id, token, now, now.plusSeconds(120)))
      p <- w.run(repo.save(r.copy(phase = NodeUpgradePhase.PrepareCanary), token, now, false))
      n <- w.run(repo.saveMember(m.copy(state = FleetRolloutMemberState.Running), token, now))
      j <- w.run(repo.saveAction(a.copy(state = FleetActionState.Succeeded, finishedAt = Some(now)), token, now))
      _ = assert(!renewed && !p && !n && !j)
      replacement <- claim(w, s)
      _ = assert(replacement._2 != token)
    } yield () }
  }
  test("terminal run, member and action history cannot be edited, retried or deleted") {
    world { (w, s) => for {
      _ <- start(w, s)
      claimed <- claim(w, s)
      (r, token) = claimed
      now <- IO.realTimeInstant
      a = RemnawaveFleetUpgradeAction(uid, w.org, r.id, s.members.head.id, "SWITCH", false, FleetActionState.Unknown,
        s.run.snapshot.members.head.previousImageReference.get, Some("NODE_UPGRADE_REMOTE_UNKNOWN"), now, Some(now))
      _ <- w.run(repo.saveAction(a, token, now))
      _ <- rejected(w.run(repo.saveAction(a.copy(state = FleetActionState.Running), token, now)), "ACTION_IMMUTABLE")
      _ <- w.run(repo.save(r.copy(state = FleetRolloutState.Unknown, phase = NodeUpgradePhase.Complete, finishedAt = Some(now)), token, now, true))
      _ <- rejected(w.run(sql"update remnawave_fleet_upgrade_run set state='QUEUED' where id=${r.id}".update.run), "TERMINAL")
      _ <- rejected(w.run(sql"delete from remnawave_fleet_upgrade_action where id=${a.id}".update.run), "HISTORY_IMMUTABLE")
      _ <- rejected(w.run(sql"delete from remnawave_fleet_upgrade_member where id=${s.members.head.id}".update.run), "HISTORY_IMMUTABLE")
      _ <- rejected(w.run(sql"delete from remnawave_fleet_upgrade_run where id=${r.id}".update.run), "TERMINAL")
    } yield () }
  }
  test("unverified member and unverified parent cannot report success") {
    world { (w, s) => for {
      _ <- start(w, s)
      claimed <- claim(w, s)
      (r, token) = claimed
      now <- IO.realTimeInstant
      _ <- rejected(w.run(repo.saveMember(s.members.head.copy(state = FleetRolloutMemberState.Succeeded), token, now)), "UNVERIFIED")
      _ <- rejected(w.run(repo.save(r.copy(state = FleetRolloutState.Succeeded, phase = NodeUpgradePhase.Complete, finishedAt = Some(now)), token, now, true)), "VERIFICATION_REQUIRED")
    } yield () }
  }
  test("upgrade locks block SSH/source, binding, configuration desired, release pointer and manual container actions") {
    world { (w, s) => for {
      _ <- start(w, s)
      p = s.run.snapshot.members.head
      _ <- rejected(w.touchConnection(p.sourceConnectionId), "SOURCE_BUSY")
      _ <- rejected(w.run(sql"delete from external_ref where resource_id=${p.resourceId}".update.run), "RESOURCE_BUSY")
      _ <- rejected(w.run(sql"delete from integration_resource_binding where inventory_object_id=${p.inventoryNodeId}".update.run), "RESOURCE_BUSY")
      _ <- rejected(w.run(sql"update remnawave_fleet set archived=true where id=${s.run.fleetId}".update.run), "UPGRADE_ACTIVE")
      _ <- rejected(w.run(repo.promote(s.revision.copy(id = uid, number = 2))), "UPGRADE_ACTIVE")
      _ <- rejected(w.run(operation(w, s)), "RESOURCE_BUSY")
    } yield () }
  }
  test("manual operation blocks an upgrade start in the opposite direction") {
    world { (w, s) => w.run(operation(w, s)) *> rejected(w.run(repo.start(w.org, s.run.id, uid, Instant.now())), "RESOURCE_BUSY") }
  }
  private def baseline(w: ConfigurationDeploymentWorld, s: Setup): ConnectionIO[Int] = {
    val p = s.run.snapshot.members.head; val now = Instant.now()
    sql"""insert into provisioning_run(id,organization_id,resource_id,run_kind,connection_id,connection_updated_at,
      resource_type,resource_kind,schema_version,steps,request_id,requested_by_user_id,status,created_at,updated_at)
      values(${uid},${w.org},${p.resourceId},'SERVER_BASELINE_CHECK',${p.sourceConnectionId},${p.sourceUpdatedAt},
        'SERVER','VPS',1,'["PREFLIGHT","VERIFY"]',${uid},$actor,'QUEUED',$now,$now)""".update.run
  }
  private def onboarding(w: ConfigurationDeploymentWorld, s: Setup): ConnectionIO[Int] = {
    val now = Instant.now()
    sql"""insert into remnawave_node_onboarding(id,organization_id,integration_id,resource_id,request_id,created_by,
      state,phase,input_snapshot,created_at,updated_at)
      values(${uid},${w.org},${s.run.integrationId},${s.run.snapshot.members.head.resourceId},${uid},$actor,
        'QUEUED','VALIDATE','{}',$now,$now)""".update.run
  }
  test("25A and 25C cannot activate during upgrade; their active runs also block upgrade admission") {
    world { (w, s) => start(w, s) *> rejected(w.run(baseline(w, s)), "UPGRADE_RESOURCE_BUSY") *>
      rejected(w.run(onboarding(w, s)), "UPGRADE_RESOURCE_BUSY") }
    world { (w, s) => w.run(baseline(w, s)) *> rejected(w.run(repo.start(w.org, s.run.id, uid, Instant.now())), "UPGRADE_RESOURCE_BUSY") }
    world { (w, s) => w.run(onboarding(w, s)) *> rejected(w.run(repo.start(w.org, s.run.id, uid, Instant.now())), "UPGRADE_RESOURCE_BUSY") }
  }
  test("25B deployment and manual Panel node action activation are blocked by the same upgrade lock") {
    world { (w, s) =>
      val p = s.run.snapshot.members.head
      start(w, s) *>
        rejected(w.run(sql"insert into configuration_deployment(id,organization_id,resource_id,state) values(${uid},${w.org},${p.resourceId},'QUEUED')".update.run), "UPGRADE_RESOURCE_BUSY") *>
        rejected(w.run(sql"""insert into integration_action_execution(id,organization_id,integration_id,inventory_object_id,status)
          values(${uid},${w.org},${s.run.integrationId},${p.inventoryNodeId},'QUEUED')""".update.run), "UPGRADE_RESOURCE_BUSY")
    }
  }
  test("25E and 25F starts exclude each other in both directions") {
    world { (w, s) =>
      val (legacy, members) = rolloutPlan(w, s)
      for {
        _ <- w.run(rollouts.insertPlan(legacy, members))
        _ <- start(w, s)
        _ <- rejected(w.run(rollouts.start(w.org, legacy.id, uid, Instant.now())), "conflicting workflow")
      } yield ()
    }
    world { (w, s) =>
      val (legacy, members) = rolloutPlan(w, s)
      w.run(rollouts.insertPlan(legacy, members)) *> w.run(rollouts.start(w.org, legacy.id, uid, Instant.now())) *>
        rejected(w.run(repo.start(w.org, s.run.id, uid, Instant.now())), "UPGRADE_RESOURCE_BUSY")
    }
  }
  test("simultaneous 25E and 25F transactions admit only one active engine") {
    world { (w, s) =>
      val (legacy, members) = rolloutPlan(w, s)
      for {
        _ <- w.run(rollouts.insertPlan(legacy, members))
        results <- (w.run(repo.start(w.org, s.run.id, uid, Instant.now())).attempt,
          w.run(rollouts.start(w.org, legacy.id, uid, Instant.now())).attempt).parTupled
        _ = assertEquals(List(results._1, results._2).count(_.toOption.contains(true)), 1)
        count <- w.run(sql"""select (select count(*) from remnawave_fleet_upgrade_run where fleet_id=${s.run.fleetId} and state='QUEUED') +
          (select count(*) from remnawave_fleet_rollout where fleet_id=${s.run.fleetId} and state='QUEUED')""".query[Long].unique)
        _ = assertEquals(count, 1L)
      } yield ()
    }
  }
}

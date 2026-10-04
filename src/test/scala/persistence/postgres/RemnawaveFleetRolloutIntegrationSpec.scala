package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import domain.integration._
import domain.provisioning.ServerProfileAssignment
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.{AuthorizationFixtures, ServerProfileFixtures}

/** The database guarantees of Stage25E: one active rollout per fleet, an idempotent start, a fenced
  * lease, and a fleet that cannot be re-pointed while a rollout holds it.
  */
final class RemnawaveFleetRolloutIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run

  private val fleets = new PostgresRemnawaveFleetRepository
  private val repo = new PostgresRemnawaveFleetRolloutRepository
  private def uid = UUID.randomUUID()
  private val actor = AuthorizationFixtures.ActorUserId

  private def finishWorld(w: ConfigurationDeploymentWorld)(body: IO[Unit]): IO[Unit] =
    body.guarantee(w.run(for {
      _ <- sql"set local session_replication_role='replica'".update.run
      _ <- sql"delete from integration_action_execution where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_desired_state where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_fleet_rollout_action where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_fleet_rollout_member where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_fleet_rollout where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_fleet_node_assessment where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_fleet_membership where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"update remnawave_fleet set desired_revision_id=null where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_fleet_revision where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from remnawave_fleet where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_config_profile_binding where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_resource_binding where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_inventory_object where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_sync_session where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from integration_secret where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from server_profile_assignment where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from server_profile_revision where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from server_profile where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from configuration_revision_secure_payload where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from configuration_revision where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"delete from configuration_profile where organization_id in (${w.org},${w.foreignOrg})".update.run
      _ <- sql"set local session_replication_role='origin'".update.run
    } yield ()))

  private def inWorld(body: ConfigurationDeploymentWorld => IO[Unit]): Unit = run(w => finishWorld(w)(body(w)))

  private final case class Setup(fleet: RemnawaveFleet, revision: RemnawaveFleetRevision,
    membership: RemnawaveFleetMembership, content: FleetDesiredContent, nodeObjectId: UUID, resourceId: UUID)

  /** A fleet with one pinned revision and one member, ready for rollouts. */
  private def setup(w: ConfigurationDeploymentWorld, label: String, now: Instant): IO[Setup] = {
    val integrationId = uid; val secretId = uid; val sessionId = uid; val nodeObjectId = uid
    val configObjectId = uid; val serverProfileId = uid; val serverRevisionId = uid
    val configurationProfileId = uid; val configRevisionId = uid; val inbound = uid
    val externalNode = uid; val externalProfile = uid
    val nonce = Array.fill[Byte](12)(3); val ciphertext = Array.fill[Byte](32)(4)
    val nodeSummary = s"""{"address":"192.0.2.10","port":2222,"state":"CONNECTED","isConnected":true,
      "isConnecting":false,"isDisabled":false,"lastStatusChange":null,"xrayVersion":null,"nodeVersion":null,
      "xrayUptimeSeconds":0,"trafficTrackingActive":false,"trafficLimitBytes":null,"trafficUsedBytes":null,
      "usersOnline":0,"countryCode":"NL","cpuCount":null,"cpuModel":null,"memoryTotalBytes":null,
      "activeConfigProfileUuid":"$externalProfile","tags":[],"providerUuid":null,"providerName":null,
      "activeInboundIds":["$inbound"]}""".replaceAll("\\s+", " ")
    val profileSummary = s"""{"viewPosition":0,"createdAt":"${now}","updatedAt":"${now}","nodeUuids":[],
      "configSha256":"${"b" * 64}","inbounds":[{"uuid":"$inbound","tag":"in","type":"VLESS","network":null,
      "security":null,"port":null}]}""".replaceAll("\\s+", " ")
    val content = FleetDesiredContent(serverProfileId, serverRevisionId, 1, ServerProfileFixtures.content.hash,
      configObjectId, externalProfile, configurationProfileId, configRevisionId, 1, "b" * 64, List(inbound), 2222,
      List("198.51.100.0/24"), IntegrationDesiredNodeState.Enabled).normalized.toOption.get
    val fleet = RemnawaveFleet(uid, w.org, integrationId, label, "Production VPN", None, None, 1L,
      archived = false, actor, now, now)
    val revision = RemnawaveFleetRevision(uid, w.org, fleet.id, 1, 1, content.hash, content, actor, now)
    for {
      node <- w.node("rollout-" + label)
      membership = RemnawaveFleetMembership(uid, w.org, fleet.id, integrationId, nodeObjectId, node.resourceId, 1L,
        actor, now)
      _ <- w.run(for {
        _ <- sql"""insert into integration_secret(id,organization_id,kind,nonce,ciphertext,created_at)
          values($secretId,${w.org},'INTEGRATION_CREDENTIAL',$nonce,$ciphertext,$now)""".update.run
        _ <- sql"""insert into integration(id,organization_id,name,provider_type,base_url,enabled,secret_id,
          caddy_api_key_configured,created_at,updated_at,management_mode)
          values($integrationId,${w.org},$label,'REMNAWAVE','https://panel.example.test',true,$secretId,
          false,$now,$now,'MANAGED_SELECTED')""".update.run
        _ <- sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,
          requested_by_user_id,started_at,recover_after_at,finished_at,status,nodes_count,hosts_count,
          config_profiles_count,deactivated_count)
          values($sessionId,${w.org},$integrationId,'MANUAL',$actor,$now,$now,$now,'COMPLETED',1,0,1,0)""".update.run
        _ <- sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,
          external_id,display_name,summary_version,summary,is_active,first_seen_at,last_seen_at,
          last_seen_sync_session_id,created_at,updated_at)
          values($nodeObjectId,${w.org},$integrationId,'NODE',${externalNode.toString},'edge-01',1,
          cast($nodeSummary as jsonb),true,$now,$now,$sessionId,$now,$now)""".update.run
        _ <- sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,
          external_id,display_name,summary_version,summary,is_active,first_seen_at,last_seen_at,
          last_seen_sync_session_id,created_at,updated_at)
          values($configObjectId,${w.org},$integrationId,'CONFIG_PROFILE',${externalProfile.toString},
          'Reality',1,cast($profileSummary as jsonb),true,$now,$now,$sessionId,$now,$now)""".update.run
        _ <- sql"""insert into integration_resource_binding(id,organization_id,integration_id,inventory_object_id,
          resource_id,created_by_user_id,created_at,updated_at)
          values(${uid},${w.org},$integrationId,$nodeObjectId,${node.resourceId},$actor,$now,$now)""".update.run
        _ <- sql"""insert into server_profile(id,organization_id,code,name,description,archived,latest_revision,
          created_by_user_id,created_at,updated_at)
          values($serverProfileId,${w.org},${"sp-" + label},'VPN Production',null,false,1,$actor,$now,$now)""".update.run
        _ <- sql"""insert into server_profile_revision(id,organization_id,profile_id,revision_number,
          schema_version,content,content_hash,created_by_user_id,created_at)
          values($serverRevisionId,${w.org},$serverProfileId,1,1,
          cast(${ServerProfileFixtures.content.canonical} as jsonb),${ServerProfileFixtures.content.hash},$actor,$now)""".update.run
        _ <- sql"""insert into configuration_profile(id,organization_id,code,name,description,archived,
          latest_revision_number,created_at,updated_at,kind)
          values($configurationProfileId,${w.org},${"cp-" + label},'Reality',null,false,1,$now,$now,
          'REMNAWAVE_CONFIG')""".update.run
        _ <- sql"""insert into configuration_revision(id,organization_id,profile_id,revision_number,
          template_text,created_by_user_id,created_at)
          values($configRevisionId,${w.org},$configurationProfileId,1,'{}',$actor,$now)""".update.run
        _ <- sql"""insert into configuration_revision_secure_payload(revision_id,organization_id,profile_id,
          purpose,nonce,ciphertext,content_sha256,created_at)
          values($configRevisionId,${w.org},$configurationProfileId,
          'infradesk/configuration/remnawave/v1',$nonce,${Array.fill[Byte](32)(9)},${"b" * 64},$now)""".update.run
        _ <- sql"""insert into integration_config_profile_binding(id,organization_id,integration_id,
          inventory_object_id,configuration_profile_id,created_by_user_id,created_at,updated_at)
          values(${uid},${w.org},$integrationId,$configObjectId,$configurationProfileId,$actor,$now,$now)""".update.run
        _ <- fleets.insertFleet(fleet)
        _ <- fleets.insertRevision(revision)
        _ <- fleets.promote(w.org, fleet.id, revision.id, 1L, now)
        _ <- fleets.insertMembership(membership, now)
      } yield ())
    } yield Setup(fleet.copy(desiredRevisionId = Some(revision.id), version = 2L), revision, membership, content,
      nodeObjectId, node.resourceId)
  }

  private def plan(w: ConfigurationDeploymentWorld, s: Setup, now: Instant, expires: Instant)
  : (RemnawaveFleetRollout, List[RemnawaveFleetRolloutMember]) = {
    val baseline = FleetMemberBaseline(None, None, None, "DRIFTED", "HEALTHY")
    val memberPlan = FleetRolloutMemberPlan(s.membership.id, s.membership.version, s.nodeObjectId, s.resourceId,
      "ext", "edge-01", 0, 0, None, List(FleetActionKind.DesiredState), baseline)
    val policy = FleetRolloutPolicy(2, Nil, pauseAfterCanary = false, automaticRollback = true,
      FleetRollbackScope.CurrentWave)
    val shared = FleetRolloutSharedConfig(required = false, s.content.inventoryConfigProfileId, 1, "b" * 64,
      None, None, 0, 0)
    val snapshot = FleetRolloutSnapshot(s.fleet.id, s.fleet.integrationId, now, s.revision.id, 1,
      s.revision.contentHash, s.content, policy, shared, List(memberPlan))
    val id = uid
    val rollout = RemnawaveFleetRollout(id, w.org, s.fleet.integrationId, s.fleet.id, s.revision.id, None,
      FleetRolloutState.Planned, FleetRolloutPhase.Validate, snapshot, snapshot.hash, 0, 1, false, true,
      FleetRollbackScope.CurrentWave, actor, now, expires, None, None, None, None, false, None, None, None, None,
      None, None, now, now, None, 1L, now)
    val member = RemnawaveFleetRolloutMember(uid, w.org, id, s.fleet.id, s.membership.id, s.membership.version,
      s.nodeObjectId, s.resourceId, "ext", 0, 0, FleetRolloutMemberState.Pending, None,
      List(FleetActionKind.DesiredState), None, None, None, None, None, 1L, now)
    (rollout, List(member))
  }

  test("a plan starts once by request id, only one rollout is active per fleet, and expired plans are purged") {
    inWorld { w =>
      for {
        now <- IO.realTimeInstant
        s <- setup(w, "single", now)
        (first, firstMembers) = plan(w, s, now, now.plusSeconds(3600))
        (second, secondMembers) = plan(w, s, now, now.plusSeconds(3600))
        (stale, staleMembers) = plan(w, s, now.minusSeconds(100), now.minusSeconds(10))
        _ <- w.run(repo.insertPlan(first, firstMembers))
        _ <- w.run(repo.insertPlan(second, secondMembers))
        _ <- w.run(repo.insertPlan(stale, staleMembers))
        request = uid
        expiredStart <- w.run(repo.start(w.org, stale.id, uid, now))
        started <- w.run(repo.start(w.org, first.id, request, now))
        again <- w.run(repo.start(w.org, first.id, uid, now))
        busy <- w.run(repo.start(w.org, second.id, uid, now))
        replay <- w.run(repo.byRequest(w.org, request))
        active <- w.run(repo.activeOf(w.org, s.fleet.id))
        purged <- w.run(repo.purgeExpired(now, 10))
        gone <- w.run(repo.rolloutById(stale.id))
        history <- w.run(repo.history(w.org, s.fleet.id, 10))
        members <- w.run(repo.members(first.id))
      } yield {
        assertEquals(expiredStart, false)
        assertEquals(started, true)
        assertEquals(again, false)
        assertEquals(busy, false)
        assertEquals(replay.map(_.id), Some(first.id))
        assertEquals(active.map(_.id), Some(first.id))
        assertEquals(active.map(_.state), Some(FleetRolloutState.Queued))
        assertEquals(purged, 1)
        assert(gone.isEmpty)
        assertEquals(history.map(_.id).toSet, Set(first.id, second.id))
        assertEquals(members.map(_.membershipId), List(s.membership.id))
        assertEquals(active.map(_.snapshot), Some(first.snapshot))
      }
    }
  }

  test("an active rollout freezes its fleet: no promote, no member change") {
    inWorld { w =>
      for {
        now <- IO.realTimeInstant
        s <- setup(w, "guard", now)
        (r, members) = plan(w, s, now, now.plusSeconds(3600))
        _ <- w.run(repo.insertPlan(r, members))
        _ <- w.run(repo.start(w.org, r.id, uid, now))
        next = s.revision.copy(id = uid, number = 2)
        _ <- w.run(fleets.insertRevision(next))
        promote <- w.run(fleets.promote(w.org, s.fleet.id, next.id, 2L, now)).attempt
        removal <- w.run(fleets.removeMembership(w.org, s.fleet.id, s.membership.id, now)).attempt
        _ <- w.run(sql"update remnawave_fleet_rollout set state='SUCCEEDED' where id=${r.id}".update.run)
        afterwards <- w.run(fleets.promote(w.org, s.fleet.id, next.id, 2L, now))
      } yield {
        assert(promote.isLeft)
        assert(removal.isLeft)
        assertEquals(afterwards, true)
      }
    }
  }

  test("pause, resume and rollback requests only act in the states that allow them") {
    inWorld { w =>
      for {
        now <- IO.realTimeInstant
        s <- setup(w, "control", now)
        (r, members) = plan(w, s, now, now.plusSeconds(3600))
        _ <- w.run(repo.insertPlan(r, members))
        pausePlanned <- w.run(repo.requestPause(w.org, r.id, actor, now))
        _ <- w.run(repo.start(w.org, r.id, uid, now))
        paused <- w.run(repo.requestPause(w.org, r.id, actor, now))
        afterPause <- w.run(repo.rolloutById(r.id))
        resumed <- w.run(repo.resume(w.org, r.id, now))
        resumedTwice <- w.run(repo.resume(w.org, r.id, now))
        rollback <- w.run(repo.requestRollback(w.org, r.id, actor, FleetRollbackScope.AllCompleted, now))
        afterRollback <- w.run(repo.rolloutById(r.id))
      } yield {
        assertEquals(pausePlanned, false)
        assertEquals(paused, true)
        assertEquals(afterPause.map(_.state), Some(FleetRolloutState.Paused))
        assertEquals(resumed, true)
        assertEquals(resumedTwice, false)
        assertEquals(rollback, true)
        assertEquals(afterRollback.flatMap(_.rollbackRequestedScope), Some(FleetRollbackScope.AllCompleted))
      }
    }
  }

  test("only the lease holder may write, and an expired or foreign lease is fenced out") {
    inWorld { w =>
      for {
        now <- IO.realTimeInstant
        s <- setup(w, "fence", now)
        (r, members) = plan(w, s, now, now.plusSeconds(3600))
        _ <- w.run(repo.insertPlan(r, members))
        _ <- w.run(repo.start(w.org, r.id, uid, now))
        token = uid
        claimed <- w.run(repo.claimDue(uid, token, now, now.plusSeconds(120), 10))
        second <- w.run(repo.claimDue(uid, uid, now, now.plusSeconds(120), 10))
        running = claimed.head.copy(state = FleetRolloutState.Running, startedAt = Some(now))
        foreign <- w.run(repo.save(running, uid, now, clearPauseRequest = false, release = false))
        saved <- w.run(repo.save(running, token, now, clearPauseRequest = false, release = false))
        member = members.head.copy(state = FleetRolloutMemberState.Running, startedAt = Some(now))
        memberForeign <- w.run(repo.saveMember(member, uid, now))
        memberSaved <- w.run(repo.saveMember(member, token, now))
        action = RemnawaveFleetRolloutAction(uid, w.org, r.id, Some(member.id), rollback = false,
          FleetActionKind.DesiredState, 0, FleetActionState.Running, uid, None, None, None, None, None, None,
          Some(now), None, 1L, now)
        actionForeign <- w.run(repo.saveAction(action, uid, now))
        actionSaved <- w.run(repo.saveAction(action, token, now))
        actionDone <- w.run(repo.saveAction(action.copy(state = FleetActionState.Succeeded), token, now))
        actions <- w.run(repo.actions(r.id))
        expired <- w.run(repo.save(running, token, now.plusSeconds(600), clearPauseRequest = false, release = false))
        renewed <- w.run(repo.renewClaim(r.id, token, now, now.plusSeconds(240)))
        renewForeign <- w.run(repo.renewClaim(r.id, uid, now, now.plusSeconds(240)))
        released <- w.run(repo.save(running, token, now, clearPauseRequest = false, release = true))
        reclaimed <- w.run(repo.claimDue(uid, uid, now, now.plusSeconds(120), 10))
      } yield {
        assertEquals(claimed.map(_.id), List(r.id))
        assertEquals(second, Nil)
        assertEquals(foreign, false)
        assertEquals(saved, true)
        assertEquals(memberForeign, false)
        assertEquals(memberSaved, true)
        assertEquals(actionForeign, false)
        assertEquals(actionSaved, true)
        assertEquals(actionDone, true)
        assertEquals(actions.map(_.state), List(FleetActionState.Succeeded))
        assertEquals(expired, false)
        assertEquals(renewed, true)
        assertEquals(renewForeign, false)
        assertEquals(released, true)
        assertEquals(reclaimed.map(_.id), List(r.id))
      }
    }
  }

  test("a snapshot is immutable once stored") {
    inWorld { w =>
      for {
        now <- IO.realTimeInstant
        s <- setup(w, "immutable", now)
        (r, members) = plan(w, s, now, now.plusSeconds(3600))
        _ <- w.run(repo.insertPlan(r, members))
        update <- w.run(sql"update remnawave_fleet_rollout set input_snapshot='{}'::jsonb where id=${r.id}".update.run).attempt
        stored <- w.run(repo.rolloutById(r.id))
      } yield {
        assert(update.isLeft)
        assertEquals(stored.map(_.snapshot), Some(r.snapshot))
        assertEquals(stored.map(_.snapshotHash), Some(r.snapshot.hash))
      }
    }
  }

  test("manual assignment and desired intent are excluded, but live parent children are admitted and correlated") {
    inWorld { w => for {
      now <- IO.realTimeInstant
      s <- setup(w, "children", now)
      (r, members) = plan(w, s, now, now.plusSeconds(3600))
      _ <- w.run(repo.insertPlan(r, members))
      _ <- w.run(repo.start(w.org, r.id, uid, now))
      token = uid
      claimed <- w.run(repo.claimDue(uid, token, now, now.plusSeconds(120), 10))
      _ <- w.run(repo.save(claimed.head.copy(state = FleetRolloutState.Running), token, now, false, false))
      scoped = new PostgresFleetRolloutChildRunner(w.runner, r.id, token)
      profiles = new PostgresServerProfileRepository
      desired = new PostgresIntegrationDesiredStateRepository
      assignment = ServerProfileAssignment(uid, w.org, s.resourceId, s.content.serverProfileId,
        s.content.serverProfileRevisionId, 1, 1L, actor, now)
      intent = IntegrationDesiredState(uid, w.org, s.fleet.integrationId, s.nodeObjectId,
        IntegrationDesiredNodeState.Enabled, 1L, actor, now, now, None, None)
      manualAssign <- w.run(profiles.storeAssignment(assignment)).attempt
      manualDesired <- w.run(desired.save(intent, now)).attempt
      manualBusy <- w.run(profiles.hasActiveRun(w.org, s.resourceId))
      childBusy <- scoped.run(profiles.hasActiveRun(w.org, s.resourceId))
      _ <- scoped.run(profiles.storeAssignment(assignment))
      action = RemnawaveFleetRolloutAction(uid, w.org, r.id, Some(members.head.id), false,
        FleetActionKind.DesiredState, 40, FleetActionState.Running, uid, None, None, None, None,
        None, None, Some(now), None, 1L, now)
      _ <- w.run(repo.saveAction(action, token, now))
      _ <- scoped.run(desired.save(intent, now))
      parent <- w.run(sql"select fleet_rollout_parent_id from integration_desired_state where id=${intent.id}".query[UUID].unique)
      childId = uid
      request = uid
      _ <- w.run(sql"""insert into integration_action_execution(id,organization_id,integration_id,inventory_object_id,
        request_id,action_code,external_id_snapshot,display_name_snapshot,requested_by_user_id,status,created_at,
        updated_at,source,desired_state_id_snapshot,desired_state_version_snapshot)
        select $childId,${w.org},${s.fleet.integrationId},${s.nodeObjectId},$request,'NODE_ENABLE',external_id,
          display_name,$actor,'QUEUED',$now,$now,'DESIRED_STATE',${intent.id},1
        from integration_inventory_object where id=${s.nodeObjectId}""".update.run)
      journal <- w.run(repo.actions(r.id))
      _ <- w.run(sql"update remnawave_fleet_rollout set claim_deadline=clock_timestamp()-interval '1 second' where id=${r.id}".update.run)
      staleChild <- scoped.run(profiles.removeAssignment(w.org, s.resourceId)).attempt
    } yield {
      assert(manualAssign.isLeft)
      assert(manualDesired.isLeft)
      assert(manualBusy)
      assert(!childBusy)
      assertEquals(parent, r.id)
      assertEquals(journal.head.desiredStateActionId, Some(childId))
      assert(staleChild.isLeft)
    }}
  }

  test("terminal rollouts cannot be resumed even through a direct database update") {
    inWorld { w => for {
      now <- IO.realTimeInstant
      s <- setup(w, "terminal", now)
      (r, members) = plan(w, s, now, now.plusSeconds(3600))
      _ <- w.run(repo.insertPlan(r, members))
      _ <- w.run(repo.start(w.org, r.id, uid, now))
      _ <- w.run(sql"update remnawave_fleet_rollout set state='UNKNOWN' where id=${r.id}".update.run)
      replay <- w.run(sql"update remnawave_fleet_rollout set state='QUEUED' where id=${r.id}".update.run).attempt
      stored <- w.run(repo.rolloutById(r.id))
    } yield {
      assert(replay.isLeft)
      assertEquals(stored.map(_.state), Some(FleetRolloutState.Unknown))
    }}
  }

  test("the reviewed SSH source cannot be edited or replaced during an active rollout") {
    inWorld { w => for {
      now <- IO.realTimeInstant
      s <- setup(w, "source-pin", now)
      target <- w.run(new PostgresProvisioningTargetQuery().eligible(w.org, s.resourceId)).map(_.toOption.get)
      (raw, members) = plan(w, s, now, now.plusSeconds(3600))
      snapshot = raw.snapshot.copy(members = raw.snapshot.members.map(m => m.copy(baseline = m.baseline.copy(
        sourceConnectionId = Some(target.connectionId), sourceUpdatedAt = Some(target.connectionUpdatedAt)))))
      r = raw.copy(snapshot = snapshot, snapshotHash = snapshot.hash)
      _ <- w.run(repo.insertPlan(r, members))
      _ <- w.run(repo.start(w.org, r.id, uid, now))
      edit <- w.run(sql"update connection set updated_at=clock_timestamp() where id=${target.connectionId}".update.run).attempt
      remove <- w.run(sql"delete from external_ref where organization_id=${w.org} and resource_id=${s.resourceId}".update.run).attempt
    } yield {
      assert(edit.isLeft)
      assert(remove.isLeft)
    }}
  }

  test("a changed SSH source makes admission reject the immutable preview") {
    inWorld { w => for {
      now <- IO.realTimeInstant
      s <- setup(w, "source-changed", now)
      target <- w.run(new PostgresProvisioningTargetQuery().eligible(w.org, s.resourceId)).map(_.toOption.get)
      (raw, members) = plan(w, s, now, now.plusSeconds(3600))
      snapshot = raw.snapshot.copy(members = raw.snapshot.members.map(m => m.copy(baseline = m.baseline.copy(
        sourceConnectionId = Some(target.connectionId), sourceUpdatedAt = Some(target.connectionUpdatedAt)))))
      r = raw.copy(snapshot = snapshot, snapshotHash = snapshot.hash)
      _ <- w.run(repo.insertPlan(r, members))
      _ <- w.run(sql"update connection set updated_at=clock_timestamp() where id=${target.connectionId}".update.run)
      start <- w.run(repo.start(w.org, r.id, uid, now)).attempt
    } yield assert(start.left.exists {
      case e: application.integration.IntegrationError => e.code == "REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED"
      case _ => false
    })}
  }
}

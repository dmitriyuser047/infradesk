package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import domain.provisioning.ServerProfileDiff
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import scala.concurrent.duration._
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.{AuthorizationFixtures, ServerProfileFixtures}

/** The database guarantees of Stage25D: an immutable revision, a desired pointer that moves under an
  * optimistic check, one fleet per node, a member that must really be bound, and a verdict that only
  * lands while its sources still hold.
  */
final class RemnawaveFleetIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run

  private val repo = new PostgresRemnawaveFleetRepository
  private val query = new PostgresRemnawaveFleetQuery
  private def uid = UUID.randomUUID()
  private val actor = AuthorizationFixtures.ActorUserId

  private def finishWorld(w: ConfigurationDeploymentWorld)(body: IO[Unit]): IO[Unit] =
    body.guarantee(w.run(for {
      _ <- sql"set local session_replication_role='replica'".update.run
      _ <- sql"delete from audit_event where organization_id in (${w.org},${w.foreignOrg}) and action like 'REMNAWAVE_FLEET%'".update.run
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

  /** Everything a pinned revision has to reference, in one tenant. */
  private final case class Sources(integrationId: UUID, nodeObjectId: UUID, configObjectId: UUID,
    serverProfileId: UUID, serverRevisionId: UUID, configurationProfileId: UUID, configRevisionId: UUID,
    inbound: UUID, externalNode: UUID, externalProfile: UUID) {
    def content: FleetDesiredContent = FleetDesiredContent(serverProfileId, serverRevisionId, 1,
      ServerProfileFixtures.content.hash, configObjectId, externalProfile, configurationProfileId,
      configRevisionId, 1, "b" * 64, List(inbound), 2222, List("198.51.100.0/24"),
      IntegrationDesiredNodeState.Enabled).normalized.toOption.get
  }

  private def fixture(w: ConfigurationDeploymentWorld, node: ConfigurationDeploymentWorld.Node,
    organizationId: UUID, label: String, bind: Boolean = true): IO[Sources] = {
    val integrationId = uid
    val secretId = uid
    val sessionId = uid
    val nodeObjectId = uid
    val configObjectId = uid
    val serverProfileId = uid
    val serverRevisionId = uid
    val configurationProfileId = uid
    val configRevisionId = uid
    val inbound = uid
    val externalNode = uid
    val externalProfile = uid
    val now = Instant.now()
    val nonce = Array.fill[Byte](12)(3)
    val ciphertext = Array.fill[Byte](32)(4)
    val nodeSummary = s"""{"address":"192.0.2.10","port":2222,"state":"CONNECTED","isConnected":true,
      "isConnecting":false,"isDisabled":false,"lastStatusChange":null,"xrayVersion":null,"nodeVersion":null,
      "xrayUptimeSeconds":0,"trafficTrackingActive":false,"trafficLimitBytes":null,"trafficUsedBytes":null,
      "usersOnline":0,"countryCode":"NL","cpuCount":null,"cpuModel":null,"memoryTotalBytes":null,
      "activeConfigProfileUuid":"$externalProfile","tags":[],"providerUuid":null,"providerName":null,
      "activeInboundIds":["$inbound"]}""".replaceAll("\\s+", " ")
    val profileSummary = s"""{"viewPosition":0,"createdAt":"${now}","updatedAt":"${now}","nodeUuids":[],
      "configSha256":"${"b" * 64}","inbounds":[{"uuid":"$inbound","tag":"in","type":"VLESS","network":null,
      "security":null,"port":null}]}""".replaceAll("\\s+", " ")
    w.run(for {
      _ <- sql"""insert into integration_secret(id,organization_id,kind,nonce,ciphertext,created_at)
        values($secretId,$organizationId,'INTEGRATION_CREDENTIAL',$nonce,$ciphertext,$now)""".update.run
      _ <- sql"""insert into integration(id,organization_id,name,provider_type,base_url,enabled,secret_id,
        caddy_api_key_configured,created_at,updated_at,management_mode)
        values($integrationId,$organizationId,$label,'REMNAWAVE','https://panel.example.test',true,$secretId,
        false,$now,$now,'MANAGED_SELECTED')""".update.run
      _ <- sql"""insert into integration_sync_session(id,organization_id,integration_id,trigger,
        requested_by_user_id,started_at,recover_after_at,finished_at,status,nodes_count,hosts_count,
        config_profiles_count,deactivated_count)
        values($sessionId,$organizationId,$integrationId,'MANUAL',$actor,$now,$now,$now,'COMPLETED',1,0,1,0)""".update.run
      _ <- sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,
        external_id,display_name,summary_version,summary,is_active,first_seen_at,last_seen_at,
        last_seen_sync_session_id,created_at,updated_at)
        values($nodeObjectId,$organizationId,$integrationId,'NODE',${externalNode.toString},'edge-01',1,
        cast($nodeSummary as jsonb),true,$now,$now,$sessionId,$now,$now)""".update.run
      _ <- sql"""insert into integration_inventory_object(id,organization_id,integration_id,object_type,
        external_id,display_name,summary_version,summary,is_active,first_seen_at,last_seen_at,
        last_seen_sync_session_id,created_at,updated_at)
        values($configObjectId,$organizationId,$integrationId,'CONFIG_PROFILE',${externalProfile.toString},
        'Reality',1,cast($profileSummary as jsonb),true,$now,$now,$sessionId,$now,$now)""".update.run
      _ <- if (!bind) ().pure[org.typelevel.doobie.ConnectionIO] else
        sql"""insert into integration_resource_binding(id,organization_id,integration_id,inventory_object_id,
          resource_id,created_by_user_id,created_at,updated_at)
          values(${uid},$organizationId,$integrationId,$nodeObjectId,${node.resourceId},$actor,$now,$now)"""
          .update.run.void
      _ <- sql"""insert into server_profile(id,organization_id,code,name,description,archived,latest_revision,
        created_by_user_id,created_at,updated_at)
        values($serverProfileId,$organizationId,${"sp-" + label},'VPN Production',null,false,1,$actor,$now,$now)""".update.run
      _ <- sql"""insert into server_profile_revision(id,organization_id,profile_id,revision_number,
        schema_version,content,content_hash,created_by_user_id,created_at)
        values($serverRevisionId,$organizationId,$serverProfileId,1,1,
        cast(${ServerProfileFixtures.content.canonical} as jsonb),${ServerProfileFixtures.content.hash},$actor,$now)""".update.run
      _ <- sql"""insert into configuration_profile(id,organization_id,code,name,description,archived,
        latest_revision_number,created_at,updated_at,kind)
        values($configurationProfileId,$organizationId,${"cp-" + label},'Reality',null,false,1,$now,$now,
        'REMNAWAVE_CONFIG')""".update.run
      _ <- sql"""insert into configuration_revision(id,organization_id,profile_id,revision_number,
        template_text,created_by_user_id,created_at)
        values($configRevisionId,$organizationId,$configurationProfileId,1,'{}',$actor,$now)""".update.run
      // The pinned configuration hash lives with the encrypted payload, never in the revision row.
      _ <- sql"""insert into configuration_revision_secure_payload(revision_id,organization_id,profile_id,
        purpose,nonce,ciphertext,content_sha256,created_at)
        values($configRevisionId,$organizationId,$configurationProfileId,
        'infradesk/configuration/remnawave/v1',$nonce,${Array.fill[Byte](32)(9)},${"b" * 64},$now)""".update.run
      _ <- sql"""insert into integration_config_profile_binding(id,organization_id,integration_id,
        inventory_object_id,configuration_profile_id,created_by_user_id,created_at,updated_at)
        values(${uid},$organizationId,$integrationId,$configObjectId,$configurationProfileId,$actor,$now,$now)""".update.run
    } yield Sources(integrationId, nodeObjectId, configObjectId, serverProfileId, serverRevisionId,
      configurationProfileId, configRevisionId, inbound, externalNode, externalProfile))
  }

  private def fleetOf(organizationId: UUID, integrationId: UUID, code: String, now: Instant) =
    RemnawaveFleet(uid, organizationId, integrationId, code, "Production VPN", None, None, 1L,
      archived = false, actor, now, now)

  test("a revision is immutable, the desired pointer moves under a version check, and only its own") {
    inWorld { w =>
      for {
        node <- w.node("fleet-immutability")
        f <- fixture(w, node, w.org, "immutable")
        now <- IO.realTimeInstant
        fleet = fleetOf(w.org, f.integrationId, "production", now)
        other = fleetOf(w.org, f.integrationId, "staging", now)
        inserted <- w.run(repo.insertFleet(fleet))
        _ <- w.run(repo.insertFleet(other))
        duplicate <- w.run(repo.insertFleet(fleet.copy(id = uid)))
        first = RemnawaveFleetRevision(uid, w.org, fleet.id, 1, 1, f.content.hash, f.content, actor, now)
        _ <- w.run(repo.insertRevision(first))
        second = first.copy(id = uid, number = 2)
        _ <- w.run(repo.insertRevision(second))
        foreignRevision = first.copy(id = uid, fleetId = other.id, number = 1)
        _ <- w.run(repo.insertRevision(foreignRevision))
        update <- w.run(sql"update remnawave_fleet_revision set content_hash=${"a" * 64} where id=${first.id}"
          .update.run).attempt
        delete <- w.run(sql"delete from remnawave_fleet_revision where id=${first.id}".update.run).attempt
        // The pointer is the only thing that moves, and only with the expected version.
        promoted <- w.run(repo.promote(w.org, fleet.id, first.id, 1L, now))
        stale <- w.run(repo.promote(w.org, fleet.id, second.id, 1L, now))
        moved <- w.run(repo.promote(w.org, fleet.id, second.id, 2L, now))
        // A revision of another fleet may never become this fleet's desired state.
        stolen <- w.run(repo.promote(w.org, fleet.id, foreignRevision.id, 3L, now)).attempt
        stored <- w.run(repo.fleet(w.org, f.integrationId, fleet.id))
        revisions <- w.run(repo.revisions(w.org, fleet.id, 10))
        next <- w.run(repo.nextRevisionNumber(w.org, fleet.id))
      } yield {
        assertEquals(inserted, true)
        assertEquals(duplicate, false)
        assert(update.isLeft)
        assert(delete.isLeft)
        assertEquals(promoted, true)
        assertEquals(stale, false)
        assertEquals(moved, true)
        assert(stolen.isLeft || stolen == Right(false))
        assertEquals(stored.flatMap(_.desiredRevisionId), Some(second.id))
        assertEquals(stored.map(_.version), Some(3L))
        assertEquals(revisions.map(_.number), List(2, 1))
        assertEquals(next, 3)
        // The content survived the round trip through jsonb exactly.
        assertEquals(revisions.last.content, f.content)
      }
    }
  }

  test("a node belongs to one fleet, must really be bound, and never crosses a tenant") {
    inWorld { w =>
      for {
        node <- w.node("fleet-membership")
        unbound <- w.node("fleet-membership-unbound")
        foreignNode <- w.node("fleet-membership-foreign", w.foreignOrg)
        f <- fixture(w, node, w.org, "members")
        loose <- fixture(w, unbound, w.org, "loose", bind = false)
        foreign <- fixture(w, foreignNode, w.foreignOrg, "foreign")
        now <- IO.realTimeInstant
        first = fleetOf(w.org, f.integrationId, "europe", now)
        second = fleetOf(w.org, f.integrationId, "asia", now)
        _ <- w.run(repo.insertFleet(first) *> repo.insertFleet(second))
        member = RemnawaveFleetMembership(uid, w.org, first.id, f.integrationId, f.nodeObjectId,
          node.resourceId, 1L, actor, now)
        added <- w.run(repo.insertMembership(member, now))
        // The same node in a second fleet of the same integration is a conflict.
        conflict <- w.run(repo.insertMembership(member.copy(id = uid, fleetId = second.id), now)).attempt
        // A node with no binding cannot be a member at all.
        unboundAttempt <- w.run(repo.insertMembership(RemnawaveFleetMembership(uid, w.org, first.id,
          loose.integrationId, loose.nodeObjectId, unbound.resourceId, 1L, actor, now), now)).attempt
        // A membership that names a resource the node is not bound to is refused.
        mismatched <- w.run(repo.insertMembership(RemnawaveFleetMembership(uid, w.org, first.id,
          f.integrationId, f.nodeObjectId, unbound.resourceId, 1L, actor, now), now)).attempt
        // Nothing of another tenant can be referenced.
        crossTenant <- w.run(repo.insertMembership(RemnawaveFleetMembership(uid, w.org, first.id,
          foreign.integrationId, foreign.nodeObjectId, foreignNode.resourceId, 1L, actor, now), now)).attempt
        // The config profile of another tenant cannot be pinned by this tenant's revision.
        foreignPin <- w.run(repo.insertRevision(RemnawaveFleetRevision(uid, w.org, first.id, 1, 1,
          foreign.content.hash, foreign.content, actor, now))).attempt
        active <- w.run(repo.activeMembershipOf(w.org, f.integrationId, f.nodeObjectId))
        removed <- w.run(repo.removeMembership(w.org, first.id, member.id, now))
        afterRemoval <- w.run(repo.activeMembershipOf(w.org, f.integrationId, f.nodeObjectId))
        // Once released, the node may join another fleet.
        rejoined <- w.run(repo.insertMembership(member.copy(id = uid, fleetId = second.id), now))
        history <- w.run(repo.members(w.org, first.id))
      } yield {
        assertEquals(added, true)
        assert(conflict.isLeft || conflict == Right(false))
        assert(unboundAttempt.isLeft)
        assert(mismatched.isLeft)
        assert(crossTenant.isLeft)
        assert(foreignPin.isLeft)
        assertEquals(active.map(_.id), Some(member.id))
        assertEquals(removed, true)
        assertEquals(afterRemoval, None)
        assertEquals(rejoined, true)
        // The removed membership stays readable as history.
        assertEquals(history.map(_.id), List(member.id))
        assertEquals(history.head.active, false)
      }
    }
  }

  test("a due member is claimed once, and a verdict lands only while its sources still hold") {
    inWorld { w =>
      for {
        node <- w.node("fleet-claim")
        f <- fixture(w, node, w.org, "claims")
        now <- IO.realTimeInstant
        fleet = fleetOf(w.org, f.integrationId, "claimed", now)
        _ <- w.run(repo.insertFleet(fleet))
        revision = RemnawaveFleetRevision(uid, w.org, fleet.id, 1, 1, f.content.hash, f.content, actor, now)
        _ <- w.run(repo.insertRevision(revision))
        _ <- w.run(repo.promote(w.org, fleet.id, revision.id, 1L, now))
        member = RemnawaveFleetMembership(uid, w.org, fleet.id, f.integrationId, f.nodeObjectId,
          node.resourceId, 1L, actor, now)
        _ <- w.run(repo.insertMembership(member, now))
        token = uid
        claimed <- w.run(repo.claimDue(uid, token, now, now.plusSeconds(120), 10))
        // A second worker finds nothing: the lease is held.
        again <- w.run(repo.claimDue(uid, uid, now, now.plusSeconds(120), 10))
        held = claimed.find(_.id == member.id).get
        verdict = RemnawaveFleetNodeAssessment(uid, w.org, fleet.id, revision.id, member.id, held.version,
          f.nodeObjectId, node.resourceId, FleetCompliance.Drifted, FleetHealth.Degraded,
          List(FleetDriftReason.ConfigRevisionDrift, FleetDriftReason.NodePortDrift),
          List(FleetHealthReason.NodeDisconnected), List(FleetRolloutBlocker.UnknownRemoteState),
          Some(now), Some(now), Some(now), now)
        // A stale token cannot write a verdict.
        stale <- w.run(repo.saveAssessment(verdict, uid, now, now.plusSeconds(300)))
        saved <- w.run(repo.saveAssessment(verdict, token, now, now.plusSeconds(300)))
        stored <- w.run(repo.assessments(w.org, fleet.id))
        // The claim was released, so the member can be claimed again when it falls due.
        reclaimedBefore <- w.run(repo.claimDue(uid, uid, now, now.plusSeconds(120), 10))
        _ <- w.run(repo.markDue(w.org, Some(fleet.id), f.integrationId, now))
        nextToken = uid
        reclaimed <- w.run(repo.claimDue(uid, nextToken, now, now.plusSeconds(120), 10))
        // A verdict computed for a revision that is no longer desired is rejected.
        superseded = RemnawaveFleetRevision(uid, w.org, fleet.id, 2, 1, f.content.hash, f.content, actor, now)
        _ <- w.run(repo.insertRevision(superseded))
        _ <- w.run(repo.promote(w.org, fleet.id, superseded.id, 2L, now))
        outdated <- w.run(repo.saveAssessment(verdict.copy(id = uid), nextToken, now, now.plusSeconds(300)))
        summaries <- w.run(query.summaries(w.org, f.integrationId, List(fleet.id)))
      } yield {
        assertEquals(claimed.map(_.id), List(member.id))
        assertEquals(again, Nil)
        assertEquals(stale, false)
        assertEquals(saved, true)
        assertEquals(stored.map(_.compliance), List(FleetCompliance.Drifted))
        assertEquals(stored.head.driftReasons.toSet,
          Set[FleetDriftReason](FleetDriftReason.ConfigRevisionDrift, FleetDriftReason.NodePortDrift))
        assertEquals(stored.head.healthReasons, List(FleetHealthReason.NodeDisconnected))
        assertEquals(stored.head.rolloutBlockers, List(FleetRolloutBlocker.UnknownRemoteState))
        assertEquals(reclaimedBefore, Nil)
        assertEquals(reclaimed.map(_.id), List(member.id))
        assertEquals(outdated, false)
        // The summary counts the current desired revision only, so the member is now unknown again.
        assertEquals(summaries.get(fleet.id).map(_.totalNodes), Some(1))
        assertEquals(summaries.get(fleet.id).map(_.unknown), Some(1))
        assertEquals(summaries.get(fleet.id).flatMap(_.desiredRevisionNumber), Some(2))
      }
    }
  }

  test("stored evidence and member rows join the sources a verdict needs without any remote call") {
    inWorld { w =>
      for {
        node <- w.node("fleet-evidence")
        f <- fixture(w, node, w.org, "evidence")
        now <- IO.realTimeInstant
        fleet = fleetOf(w.org, f.integrationId, "evidence", now)
        _ <- w.run(repo.insertFleet(fleet))
        revision = RemnawaveFleetRevision(uid, w.org, fleet.id, 1, 1, f.content.hash, f.content, actor, now)
        _ <- w.run(repo.insertRevision(revision))
        _ <- w.run(repo.promote(w.org, fleet.id, revision.id, 1L, now))
        member = RemnawaveFleetMembership(uid, w.org, fleet.id, f.integrationId, f.nodeObjectId,
          node.resourceId, 1L, actor, now)
        _ <- w.run(repo.insertMembership(member, now))
        _ <- w.run(sql"""insert into server_profile_assignment(id,organization_id,resource_id,profile_id,
          revision_id,revision_number,version,assigned_by_user_id,assigned_at)
          values(${uid},${w.org},${node.resourceId},${f.serverProfileId},${f.serverRevisionId},1,1,$actor,$now)""".update.run)
        evidence <- w.run(query.storedEvidence(w.org, member.id, revision))
        rows <- w.run(query.memberRows(w.org, fleet.id))
        candidates <- w.run(query.candidates(w.org, f.integrationId, 100))
        provenance <- w.run(query.provenance(w.org, f.integrationId, node.resourceId, f.nodeObjectId))
        lastSync <- w.run(query.lastSuccessfulSyncAt(w.org, f.integrationId))
      } yield {
        val value = evidence.get
        assertEquals(value.bindingPresent, true)
        assertEquals(value.bindingResourceId, Some(node.resourceId))
        assertEquals(value.inventoryActive, true)
        assertEquals(value.configProfileAvailable, true)
        assertEquals(value.serverProfileAvailable, true)
        assertEquals(value.remoteConfigSha256, Some("b" * 64))
        assertEquals(value.assignment.map(_.revisionId), Some(f.serverRevisionId))
        assertEquals(value.desiredServerContent, Some(ServerProfileFixtures.content))
        assertEquals(value.node.flatMap(_.activeInboundIds), Some(List(f.inbound.toString)))
        assertEquals(value.node.flatMap(_.activeConfigProfileUuid), Some(f.externalProfile.toString))
        assertEquals(value.busy, false)
        // No Stage25C onboarding exists here, so the local installation is not claimed as managed.
        assertEquals(value.provenance, None)
        assertEquals(provenance, None)
        assertEquals(rows.map(_.membership.id), List(member.id))
        assertEquals(rows.head.localManaged, false)
        assertEquals(rows.head.actualServerRevisionNumber, Some(1))
        assertEquals(rows.head.actualConfigProfileExternalId, Some(f.externalProfile.toString))
        assertEquals(rows.head.connected, true)
        // The node already belongs to this fleet, so it is not offered as a candidate again.
        assertEquals(candidates.map(_.inventoryNodeId), List(f.nodeObjectId))
        assertEquals(candidates.head.eligible, false)
        assertEquals(candidates.head.blockedBy, Some("REMNAWAVE_FLEET_NODE_ALREADY_MEMBER"))
        assertEquals(candidates.head.currentFleetName, Some("Production VPN"))
        assert(lastSync.nonEmpty)
        assertEquals(ServerProfileDiff.assess(ServerProfileFixtures.content,
          ServerProfileFixtures.observed()).compliant, true)
      }
    }
  }

  /** The service on the real repositories: validation, promotion and membership as a person sees it. */
  private def service(w: ConfigurationDeploymentWorld) = {
    // The real audit recorder, so the journal entries are written inside the business transaction.
    val recorder = new application.audit.AuditRecorder[org.typelevel.doobie.ConnectionIO](
      new PostgresAuditEventRepository, new infrastructure.database.ConnectionIOIdGenerator,
      new infrastructure.database.ConnectionIOTimeProvider)
    val runner = new application.port.TransactionRunner[IO, org.typelevel.doobie.ConnectionIO] {
      override def run[A](program: org.typelevel.doobie.ConnectionIO[A]): IO[A] = w.run(program)
    }
    val cipher = integration.secret.RemnawaveConfigCipher.fromConfig(
      integration.ssh.SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" ->
        java.util.Base64.getEncoder.encodeToString(Array.fill[Byte](32)(13)))).toOption.get)
    val fleets = new application.integration.RemnawaveFleets[org.typelevel.doobie.ConnectionIO](repo, query,
      new PostgresIntegrationRepository, new PostgresIntegrationInventoryRepository,
      new PostgresIntegrationBindingRepository, new PostgresIntegrationConfigProfileRepository(cipher),
      new PostgresConfigurationProfileQuery, new PostgresServerProfileRepository,
      new PostgresIntegrationSyncStateRepository, recorder, runner,
      application.integration.FleetSettings(900.seconds, 300.seconds))
    fleets
  }

  private def desiredInput(f: Sources) = application.integration.FleetDesiredInput(f.serverProfileId, 1,
    f.configObjectId, 1, List(f.inbound), 2222, List("198.51.100.0/24"), IntegrationDesiredNodeState.Enabled)

  private def errorCode[A](result: Either[Throwable, A]): Option[String] =
    result.left.toOption.collect { case e: application.integration.IntegrationError => e.code }

  test("creating a fleet pins exact revisions, adds members and applies nothing to them") {
    inWorld { w =>
      val fleets = service(w)
      val context = application.auth.ActorContext(actor, w.org)
      for {
        node <- w.node("fleet-service-create")
        f <- fixture(w, node, w.org, "create")
        created <- fleets.create(context, f.integrationId, "production", "Production VPN", Some("Europe"),
          desiredInput(f), List(f.nodeObjectId))
        detail <- fleets.detail(w.org, f.integrationId, created.id)
        // Metadata only: no profile assigned, no desired state set, no deployment requested.
        assignments <- w.run(sql"select count(*) from server_profile_assignment where organization_id=${w.org}"
          .query[Int].unique)
        desiredStates <- w.run(sql"select count(*) from integration_desired_state where organization_id=${w.org}"
          .query[Int].unique)
        deployments <- w.run(sql"select count(*) from integration_config_deployment where organization_id=${w.org}"
          .query[Int].unique)
        duplicateCode <- fleets.create(context, f.integrationId, "production", "Another", None,
          desiredInput(f), Nil).attempt
        badInbound <- fleets.create(context, f.integrationId, "other", "Other", None,
          desiredInput(f).copy(activeInboundIds = List(uid)), Nil).attempt
        badCidr <- fleets.create(context, f.integrationId, "cidr", "Cidr", None,
          desiredInput(f).copy(panelCidrs = List("0.0.0.0/0")), Nil).attempt
        missingRevision <- fleets.create(context, f.integrationId, "rev", "Rev", None,
          desiredInput(f).copy(serverProfileRevisionNumber = 7), Nil).attempt
        auditActions <- w.run(sql"""select distinct action from audit_event
          where organization_id=${w.org} and action like 'REMNAWAVE_FLEET%' order by action"""
          .query[String].to[List])
      } yield {
        assertEquals(created.code, "production")
        assertEquals(detail.desired.map(_.number), Some(1))
        assertEquals(detail.desired.map(_.content.serverProfileRevisionId), Some(f.serverRevisionId))
        assertEquals(detail.desired.map(_.content.configRevisionHash), Some("b" * 64))
        assertEquals(detail.desired.map(_.content.externalConfigProfileId), Some(f.externalProfile))
        assertEquals(detail.members.map(_.membership.inventoryNodeId), List(f.nodeObjectId))
        assertEquals(detail.serverProfileName, Some("VPN Production"))
        assertEquals(assignments, 0)
        assertEquals(desiredStates, 0)
        assertEquals(deployments, 0)
        assertEquals(errorCode(duplicateCode), Some("REMNAWAVE_FLEET_CODE_TAKEN"))
        assertEquals(errorCode(badInbound), Some("REMNAWAVE_FLEET_INBOUND_INVALID"))
        assertEquals(errorCode(badCidr), Some("REMNAWAVE_FLEET_CIDR_INVALID"))
        assertEquals(errorCode(missingRevision), Some("REMNAWAVE_FLEET_SERVER_REVISION_NOT_FOUND"))
        assertEquals(auditActions, List("REMNAWAVE_FLEET_CREATED", "REMNAWAVE_FLEET_MEMBER_ADDED",
          "REMNAWAVE_FLEET_REVISION_CREATED"))
      }
    }
  }

  test("a newer server profile or configuration revision does not move the fleet on its own") {
    inWorld { w =>
      val fleets = service(w)
      val context = application.auth.ActorContext(actor, w.org)
      for {
        node <- w.node("fleet-service-pin")
        f <- fixture(w, node, w.org, "pin")
        created <- fleets.create(context, f.integrationId, "pinned", "Pinned", None, desiredInput(f), Nil)
        now <- IO.realTimeInstant
        // Someone publishes revision 2 of both referenced objects.
        secondServerRevision = uid
        _ <- w.run(sql"""insert into server_profile_revision(id,organization_id,profile_id,revision_number,
          schema_version,content,content_hash,created_by_user_id,created_at)
          values($secondServerRevision,${w.org},${f.serverProfileId},2,1,
          cast(${ServerProfileFixtures.disabled.canonical} as jsonb),${ServerProfileFixtures.disabled.hash},
          $actor,$now)""".update.run)
        _ <- w.run(sql"update server_profile set latest_revision=2 where id=${f.serverProfileId}".update.run)
        secondConfigRevision = uid
        _ <- w.run(sql"""insert into configuration_revision(id,organization_id,profile_id,revision_number,
          template_text,created_by_user_id,created_at)
          values($secondConfigRevision,${w.org},${f.configurationProfileId},2,'{}',$actor,$now)""".update.run)
        _ <- w.run(sql"""insert into configuration_revision_secure_payload(revision_id,organization_id,
          profile_id,purpose,nonce,ciphertext,content_sha256,created_at)
          values($secondConfigRevision,${w.org},${f.configurationProfileId},
          'infradesk/configuration/remnawave/v1',${Array.fill[Byte](12)(3)},${Array.fill[Byte](32)(9)},
          ${"c" * 64},$now)""".update.run)
        unchanged <- fleets.detail(w.org, f.integrationId, created.id)
        candidate <- fleets.createRevision(context, f.integrationId, created.id,
          desiredInput(f).copy(serverProfileRevisionNumber = 2, configRevisionNumber = 2),
          unchanged.fleet.version)
        afterCreate <- fleets.detail(w.org, f.integrationId, created.id)
        impact <- fleets.preview(w.org, f.integrationId, created.id, candidate.id)
        promoted <- fleets.promote(context, f.integrationId, created.id, candidate.id, afterCreate.fleet.version)
        // Promoting the revision that is already desired is the same state and not a second event.
        repeated <- fleets.promote(context, f.integrationId, created.id, candidate.id, promoted.version)
        afterPromote <- fleets.detail(w.org, f.integrationId, created.id)
      } yield {
        assertEquals(unchanged.desired.map(_.content.serverProfileRevisionNumber), Some(1))
        assertEquals(unchanged.desired.map(_.content.configRevisionNumber), Some(1))
        assertEquals(candidate.number, 2)
        // Still revision 1: creating a revision never promotes it.
        assertEquals(afterCreate.fleet.desiredRevisionId, unchanged.fleet.desiredRevisionId)
        assertEquals(afterCreate.revisions.map(_.number), List(2, 1))
        assertEquals(impact.current.map(_.number), Some(1))
        assertEquals(impact.candidate.content.serverProfileRevisionId, secondServerRevision)
        assertEquals(impact.candidate.content.configRevisionHash, "c" * 64)
        assertEquals(afterPromote.fleet.desiredRevisionId, Some(candidate.id))
        assertEquals(afterPromote.desired.map(_.content.configRevisionNumber), Some(2))
        assertEquals(repeated.version, promoted.version)
      }
    }
  }

  test("a refresh nudges one integration-level synchronization, never one per member") {
    inWorld { w =>
      val fleets = service(w)
      val context = application.auth.ActorContext(actor, w.org)
      for {
        node <- w.node("fleet-refresh")
        f <- fixture(w, node, w.org, "refresh")
        created <- fleets.create(context, f.integrationId, "refreshed", "Refreshed", None, desiredInput(f),
          List(f.nodeObjectId))
        due <- fleets.refresh(context, f.integrationId, created.id)
        sessions <- w.run(sql"select count(*) from integration_sync_session where organization_id=${w.org}"
          .query[Int].unique)
        schedules <- w.run(sql"""select count(*) from integration_sync_state
          where organization_id=${w.org} and integration_id=${f.integrationId}""".query[Int].unique)
      } yield {
        assertEquals(due, 1)
        // Only the fixture session exists: a refresh starts no synchronization of its own.
        assertEquals(sessions, 1)
        // One schedule row for the integration, whatever the number of members.
        assertEquals(schedules, 1)
      }
    }
  }
}

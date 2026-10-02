package ru.bitec.app.ops
package persistence.postgres

import application.port._
import application.provisioning._
import cats.effect.IO
import cats.syntax.all._
import domain.provisioning._
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import support.ServerProfileFixtures
import java.util.UUID

final class ServerProfilesIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run
  private class Services(w: ConfigurationDeploymentWorld) {
    val repository=new PostgresServerProfileRepository
    val runs=new PostgresProvisioningRunRepository
    val targets=new PostgresProvisioningTargetQuery
    val remote=new ServerProfileFixtures.Remote
    val profiles=new ServerProfiles[IO,ConnectionIO](repository,targets,runs,remote,w.ids,w.time,w.audit,w.runner,w.runner,ProvisioningSettings.Default)
    val approvals=new ProvisioningRuns[IO,ConnectionIO](runs,targets,w.ids,w.time,w.audit,w.runner,w.runner,ProvisioningSettings.Default,Some(profiles))
    val coordinator=new ServerProfileApplyCoordinator[ConnectionIO](repository,runs,targets,remote,w.runner,ProvisioningSettings.Default.leaseDuration)
    val readiness=new ProvisioningTransport[IO] {
      val calls=new java.util.concurrent.atomic.AtomicInteger(0)
      def preflight(c: domain.connection.Connection)=IO {calls.incrementAndGet(); ProvisioningStepResult(Map.empty,None,Some(true))}
      def verify(c: domain.connection.Connection)=IO {calls.incrementAndGet(); ProvisioningStepResult(Map.empty,None,Some(true))}
    }
    val worker=new ProvisioningWorker[ConnectionIO](runs,targets,readiness,w.runner,ProvisioningSettings.Default,
      Slf4jLogger.getLoggerFromName[IO]("test.server.profiles"),scope=Some(w.org),profileHandler=Some(coordinator))
  }
  test("immutable revisions, canonical no-op and concurrent revision creation produce exactly one audit per change") {
    run { w => val s=new Services(w)
      for {
        first <- s.profiles.create(w.actor,"baseline","Baseline",None,ServerProfileFixtures.content)
        same <- s.profiles.appendRevision(w.actor,first._1.id,first._2.content)
        changed=first._2.content.copy(packages=PackageModule(true,List("curl","jq")))
        parallel <- List.fill(6)(()).parTraverse(_ => s.profiles.appendRevision(w.actor,first._1.id,changed))
        immutableUpdate <- w.run(sql"update server_profile_revision set content_hash=${"0"*64} where id=${first._2.id}".update.run).attempt
        immutableDelete <- w.run(sql"delete from server_profile_revision where id=${first._2.id}".update.run).attempt
        detail <- s.profiles.detail(w.org,first._1.id)
        foreign <- s.profiles.detail(w.foreignOrg,first._1.id).attempt
        audits <- w.run(sql"select action from audit_event where organization_id=${w.org} and target_id=${first._1.id}".query[String].to[List])
      } yield {
        assertEquals(same.id,first._2.id)
        assertEquals(parallel.map(_.id).distinct.size,1)
        assertEquals(detail._2.map(_.number),List(2,1))
        assert(immutableUpdate.isLeft && immutableDelete.isLeft && foreign.isLeft)
        assertEquals(audits.sorted,List("SERVER_PROFILE_CREATED","SERVER_PROFILE_REVISION_CREATED"))
      }
    }
  }
  test("assignment is intent-only, pins revision and preserves old observation across change/removal") {
    run { w => val s=new Services(w)
      for {
        bare <- w.resource("without-ssh")
        node <- w.node("pin")
        first <- s.profiles.create(w.actor,"pin","Pinned",None,ServerProfileFixtures.content)
        intent <- s.profiles.assign(w.actor,bare,first._1.id,1)
        a <- s.profiles.assign(w.actor,node.resourceId,first._1.id,1)
        same <- s.profiles.assign(w.actor,node.resourceId,first._1.id,1)
        observed <- s.profiles.observe(w.actor,node.resourceId)
        latest <- s.profiles.appendRevision(w.actor,first._1.id,first._2.content.copy(packages=PackageModule(true,List("curl","jq"))))
        pinned <- s.profiles.automation(w.org,node.resourceId)
        changed <- s.profiles.assign(w.actor,node.resourceId,first._1.id,latest.number)
        old <- w.run(s.repository.observation(w.org,node.resourceId))
        stale <- s.profiles.automation(w.org,node.resourceId)
        _ <- w.run(sql"update resource set is_active=false where id=${node.resourceId}".update.run)
        removed <- s.profiles.unassign(w.actor,node.resourceId)
        preserved <- w.run(s.repository.observation(w.org,node.resourceId))
        foreign <- s.profiles.assign(w.foreignActor,bare,first._1.id,1).attempt
      } yield {
        assertEquals(intent.revisionNumber,1)
        // PostgreSQL stores microsecond precision; compare the persisted identity and pin.
        assertEquals(a.copy(assignedAt=same.assignedAt),same)
        assertEquals(pinned.assignment.map(_.revisionNumber),Some(1))
        assertEquals(changed.version,2L)
        assertEquals(old.map(_.id),Some(observed.id))
        assertEquals(stale.state,"UNOBSERVED")
        assert(removed.nonEmpty && preserved.nonEmpty && foreign.isLeft)
      }
    }
  }
  test("archive versus assignment cannot leave an archived profile assigned") {
    run { w => val s=new Services(w)
      for {
        node <- w.resource("archive-race")
        p <- s.profiles.create(w.actor,"archive","Archive",None,ServerProfileFixtures.disabled)
        results <- (s.profiles.archive(w.actor,p._1.id).attempt,s.profiles.assign(w.actor,node,p._1.id,1).attempt).parTupled
        state <- s.profiles.detail(w.org,p._1.id)
      } yield { assert(!(state._1.archived && state._3.nonEmpty)); assert(results._1.isLeft || results._2.isLeft) }
    }
  }
  test("approval races with pin changes safely; unassign/reassign cannot revive an old preview") {
    run { w => val s=new Services(w)
      for {
        node <- w.node("approval-race")
        p <- s.profiles.create(w.actor,"approval","Approval",None,ServerProfileFixtures.content)
        a <- s.profiles.assign(w.actor,node.resourceId,p._1.id,1)
        plan <- s.profiles.preview(w.actor,node.resourceId)
        next <- s.profiles.appendRevision(w.actor,p._1.id,p._2.content.copy(packages=PackageModule(true,List("curl","jq"))))
        raced <- (s.approvals.start(w.actor,plan.run.id,UUID.randomUUID()).attempt,
          s.profiles.assign(w.actor,node.resourceId,p._1.id,next.number).attempt).parTupled
        _ <- w.run(sql"update provisioning_run set status='FAILED', finished_at=now(), failure_code='TEST' where id=${plan.run.id} and status='QUEUED'".update.run)
        _ <- s.profiles.unassign(w.actor,node.resourceId)
        again <- s.profiles.assign(w.actor,node.resourceId,p._1.id,1)
        stale <- s.approvals.start(w.actor,plan.run.id,UUID.randomUUID()).attempt
      } yield {
        assert(raced._1.isLeft || raced._2.isLeft)
        assertNotEquals(a.id,again.id)
        assert(stale.isLeft)
      }
    }
  }
  test("shared engine stores ten ordered steps, skips only modules and links independent verification") {
    run { w => val s=new Services(w)
      s.remote.outcome.set(ProvisioningStepResult(Map("skipReason"->"NOT_MANAGED"),None,None,skipped=true))
      for {
        node <- w.node("apply-disabled")
        p <- s.profiles.create(w.actor,"apply","Apply",None,ServerProfileFixtures.disabled)
        _ <- s.profiles.assign(w.actor,node.resourceId,p._1.id,1)
        plan <- s.profiles.preview(w.actor,node.resourceId)
        request=UUID.randomUUID()
        approvals <- List.fill(4)(()).parTraverse(_ => s.approvals.start(w.actor,plan.run.id,request))
        active <- s.profiles.automation(w.org,node.resourceId)
        blocked <- s.profiles.observe(w.actor,node.resourceId).attempt
        _ <- s.worker.tick
        run <- s.approvals.detail(w.org,plan.run.id)
        automation <- s.profiles.automation(w.org,node.resourceId)
        audits <- w.run(sql"select count(*) from audit_event where organization_id=${w.org} and action='SERVER_PROFILE_APPLY_REQUESTED'".query[Long].unique)
      } yield {
        assertEquals(approvals.map(_.id).distinct,List(plan.run.id))
        assertEquals(active.state,"APPLYING"); assert(active.operationsBlocked && blocked.isLeft)
        assertEquals(run._1.state,ProvisioningRunState.Succeeded)
        assertEquals(run._2.map(_.position),(0 until 10).toList)
        assertEquals(run._2.count(_.state==ProvisioningStepState.Skipped),8)
        assertEquals(s.readiness.calls.get(),2)
        assertEquals(automation.state,"COMPLIANT")
        assertEquals(automation.observation.flatMap(_.verifiedRunId),Some(plan.run.id))
        assertEquals(audits,1L)
      }
    }
  }
  test("timeout/output uncertainty is UNKNOWN without replay and later manual observation clears terminal override") {
    run { w => val s=new Services(w)
      s.remote.outcome.set(ProvisioningStepResult(Map.empty,None,None,outputTruncated=true))
      for {
        node <- w.node("unknown-apply")
        p <- s.profiles.create(w.actor,"unknown","Unknown",None,ServerProfileFixtures.content)
        _ <- s.profiles.assign(w.actor,node.resourceId,p._1.id,1)
        plan <- s.profiles.preview(w.actor,node.resourceId)
        _ <- s.approvals.start(w.actor,plan.run.id,UUID.randomUUID())
        _ <- s.worker.tick
        before <- s.approvals.detail(w.org,plan.run.id)
        unknown <- s.profiles.automation(w.org,node.resourceId)
        _ <- s.worker.tick
        after <- s.approvals.detail(w.org,plan.run.id)
        _ <- s.profiles.observe(w.actor,node.resourceId)
        fresh <- s.profiles.automation(w.org,node.resourceId)
      } yield {
        assertEquals(before._1.state,ProvisioningRunState.Unknown)
        assertEquals(unknown.state,"UNKNOWN")
        assertEquals(before._2.map(_.attempt),after._2.map(_.attempt))
        assertEquals(fresh.state,"COMPLIANT")
      }
    }
  }
  test("a newer observation invalidates approval and completed work during observe cannot replace reviewed facts") {
    run { w => val s=new Services(w)
      for {
        node <- w.node("read-generation")
        p <- s.profiles.create(w.actor,"generation","Generation",None,ServerProfileFixtures.content)
        _ <- s.profiles.assign(w.actor,node.resourceId,p._1.id,1)
        old <- s.profiles.preview(w.actor,node.resourceId)
        manual <- s.profiles.observe(w.actor,node.resourceId)
        stale <- s.approvals.start(w.actor,old.run.id,UUID.randomUUID()).attempt
        plan <- s.profiles.preview(w.actor,node.resourceId)
        before <- w.run(s.repository.observation(w.org,node.resourceId))
        _ <- IO(s.remote.hook.set(s.approvals.start(w.actor,plan.run.id,UUID.randomUUID()) *>
          w.run(sql"update provisioning_run set status='FAILED', finished_at=clock_timestamp(), failure_code='TEST' where id=${plan.run.id}".update.run).void))
        raced <- s.profiles.observe(w.actor,node.resourceId).attempt
        after <- w.run(s.repository.observation(w.org,node.resourceId))
        _ <- IO(s.remote.hook.set(IO.unit))
      } yield {
        assert(stale.isLeft && raced.isLeft)
        assertNotEquals(manual.id,old.run.input.profileApply.get.observationId)
        assertEquals(before.map(_.id),after.map(_.id))
      }
    }
  }
  test("verification publication fences on the actual parent lease and rejects foreign tokens atomically") {
    run { w => val s=new Services(w)
      for {
        node <- w.node("verify-fence")
        p <- s.profiles.create(w.actor,"fence","Fence",None,ServerProfileFixtures.disabled)
        _ <- s.profiles.assign(w.actor,node.resourceId,p._1.id,1)
        plan <- s.profiles.preview(w.actor,node.resourceId)
        _ <- s.approvals.start(w.actor,plan.run.id,UUID.randomUUID())
        now <- IO.realTimeInstant
        token=UUID.randomUUID()
        claimed <- w.run(s.runs.claim(UUID.randomUUID(),token,now,now.plusSeconds(90),1,Some(w.org)))
        target <- w.run(s.targets.eligible(w.org,node.resourceId)).map(_.toOption.get)
        _ <- w.run(s.runs.beginStep(w.org,plan.run.id,token,ProvisioningStepKind.Verify,now))
        observation=application.port.ServerProfileRemoteObservation(ServerProfileFixtures.observed(),ServerProfileDiff.hashObservation(ServerProfileFixtures.observed()))
        result=ProvisioningStepResult(Map.empty,None,Some(true),profileObservation=Some(observation))
        bad <- w.run(s.coordinator.recordVerification(claimed.head,UUID.randomUUID(),target.connection,result,
          ProvisioningStepState.Succeeded,None,None,now)).attempt
        unchanged <- w.run(s.repository.observation(w.org,node.resourceId))
        _ <- w.run(sql"update provisioning_run set claim_until=clock_timestamp()-interval '1 second' where id=${plan.run.id}".update.run)
        expired <- w.run(s.coordinator.recordVerification(claimed.head,token,target.connection,result,
          ProvisioningStepState.Succeeded,None,None,now)).attempt
        journal <- s.approvals.detail(w.org,plan.run.id)
      } yield {
        assert(bad.isLeft && expired.isLeft)
        assertEquals(unchanged.map(_.id),Some(plan.run.input.profileApply.get.observationId))
        assertEquals(journal._2.find(_.kind==ProvisioningStepKind.Verify).map(_.state),Some(ProvisioningStepState.Running))
      }
    }
  }
  test("a failed profile apply remains visible after more than one history page of readiness checks") {
    run { w => val s=new Services(w)
      s.remote.outcome.set(ProvisioningStepResult(Map.empty,Some("PROVISIONING_PERMISSION_DENIED"),None))
      for {
        node <- w.node("exact-profile-status")
        p <- s.profiles.create(w.actor,"status","Status",None,ServerProfileFixtures.content)
        _ <- s.profiles.assign(w.actor,node.resourceId,p._1.id,1)
        plan <- s.profiles.preview(w.actor,node.resourceId)
        _ <- s.approvals.start(w.actor,plan.run.id,UUID.randomUUID())
        _ <- s.worker.tick
        now <- IO.realTimeInstant
        ids=List.fill(105)(UUID.randomUUID())
        _ <- w.run(ids.traverse_ { id =>
          val input=plan.run.input.copy(runKind=ProvisioningRunKind.ServerBaselineCheck,steps=ProvisioningStepKind.Baseline,profileApply=None)
          s.runs.insertPlan(plan.run.copy(id=id,input=input,createdAt=now,updatedAt=now)) *>
            sql"update provisioning_run set status='FAILED', request_id=${UUID.randomUUID()}, requested_by_user_id=${w.actor.userId}, failure_code='TEST', finished_at=$now where id=$id".update.run.void
        })
        state <- s.profiles.automation(w.org,node.resourceId)
      } yield { assertEquals(state.state,"APPLY_FAILED") }
    }
  }
}

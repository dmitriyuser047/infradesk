package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.configuration.{ConfigurationAssignmentDraft, ConfigurationDeploymentError}
import application.port.AuditEventRepository
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditCursor, AuditEvent}
import domain.configuration._
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Deployment snapshots, their lifecycle invariants and their fencing, against a real database. */
final class ConfigurationDeploymentIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run

  private val Path = "/etc/app/app.conf"

  private def code(result: Either[Throwable, _]): Option[String] =
    result.swap.toOption.collect { case error: ConfigurationDeploymentError => error.code }

  test("a deployment snapshots the exact assignment version, revision, explicit values and desired hash") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        deployment <- w.deployment(id)
        connectionUpdated <- w.run(sql"select updated_at from connection where id = ${node.connectionId}".query[Instant].unique)
      } yield {
        assertEquals(deployment.assignmentVersion, 1)
        assertEquals(deployment.profileRevisionNumber, 1)
        assertEquals(deployment.targetPath, Path)
        // Explicit values only; the default of `mode` stays in the immutable revision.
        assertEquals(deployment.values, List(ConfigurationVariableValue("domain", "a.example")))
        assertEquals(deployment.desiredSha256, ConfigurationDeployment.sha256("server_name a.example;\nmode ok;\n"))
        assertEquals(deployment.connectionUpdatedAt, connectionUpdated)
        assertEquals(deployment.state, ConfigurationDeploymentState.Queued)
        assertEquals(deployment.phase, ConfigurationDeploymentPhase.Precheck)
      }
    }
  }

  test("a later assignment change never alters a queued snapshot, and the worker refuses the stale one") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        _ <- w.revision(profile)
        _ <- w.assignments.update(w.actor, assignmentId, 1, ConfigurationAssignmentDraft(2, Path,
          List(ConfigurationVariableValue("domain", "b.example"))))
        before <- w.deployment(id)
        _ <- w.drain()
        after <- w.deployment(id)
      } yield {
        assertEquals(before.assignmentVersion, 1)
        assertEquals(before.profileRevisionNumber, 1)
        assertEquals(before.values, List(ConfigurationVariableValue("domain", "a.example")))
        assertEquals(after.state, ConfigurationDeploymentState.Failed)
        assertEquals(after.failureCode, Some("CONFIGURATION_ASSIGNMENT_CHANGED"))
        assert(!w.remote.exists(Path), "a stale snapshot must not reach the server")
      }
    }
  }

  test("a resource has at most one queued or running deployment") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        first <- w.assign(node, profile, Path, "domain" -> "a.example")
        second <- w.assign(node, profile, "/etc/app/other.conf", "domain" -> "b.example")
        _ <- w.deployments.request(w.actor, first, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        blocked <- w.deployments.request(w.actor, second, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID()).attempt
        rows <- w.run(sql"select count(*) from configuration_deployment where organization_id = ${w.org}".query[Long].unique)
      } yield {
        assertEquals(code(blocked), Some("CONFIGURATION_DEPLOYMENT_ALREADY_ACTIVE"))
        assertEquals(rows, 1L)
      }
    }
  }

  test("concurrent claims give a deployment to exactly one worker") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        now <- IO.realTimeInstant
        claims <- (1 to 8).toList.parTraverse(_ => w.run(w.deploymentRepository.claim(UUID.randomUUID(),
          UUID.randomUUID(), now, now.plusSeconds(60), 10, 10, Some(w.org))))
      } yield assertEquals(claims.flatten.map(_.id).filter(_ == id), List(id))
    }
  }

  test("an expired lease is reclaimed with a new token and the old token is fenced out everywhere") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        now <- IO.realTimeInstant
        oldToken = UUID.randomUUID()
        first <- w.run(w.deploymentRepository.claim(UUID.randomUUID(), oldToken, now, now.plusSeconds(30), 10, 10, Some(w.org)))
        early <- w.run(w.deploymentRepository.claim(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(10), now.plusSeconds(40), 10, 10, Some(w.org)))
        later = now.plusSeconds(31)
        newToken = UUID.randomUUID()
        second <- w.run(w.deploymentRepository.claim(UUID.randomUUID(), newToken, later, later.plusSeconds(30), 10, 10, Some(w.org)))
        repo = w.deploymentRepository
        staleRenew <- w.run(repo.renew(w.org, id, oldToken, later, later.plusSeconds(30)))
        staleAdvance <- w.run(repo.advance(w.org, id, oldToken, ConfigurationDeploymentPhase.Precheck,
          ConfigurationDeploymentPhase.Upload, later))
        staleRollback <- w.run(repo.enterRollback(w.org, id, oldToken, ConfigurationDeploymentPhase.Precheck, "X", later))
        staleFinish <- w.run(repo.finish(w.org, id, oldToken, ConfigurationDeploymentState.Succeeded, None, false, later))
        advanced <- w.run(repo.advance(w.org, id, newToken, ConfigurationDeploymentPhase.Precheck,
          ConfigurationDeploymentPhase.Upload, later))
        wrongPhase <- w.run(repo.advance(w.org, id, newToken, ConfigurationDeploymentPhase.Precheck,
          ConfigurationDeploymentPhase.Upload, later))
        finished <- w.run(repo.finish(w.org, id, newToken, ConfigurationDeploymentState.Failed, Some("X"), false, later))
        afterFinish <- w.run(repo.advance(w.org, id, newToken, ConfigurationDeploymentPhase.Upload,
          ConfigurationDeploymentPhase.Validate, later))
        row <- w.deployment(id)
      } yield {
        assertEquals(first.map(_.id), List(id))
        assertEquals(early, Nil)
        assertEquals(second.map(_.id), List(id))
        assertEquals(second.map(_.phase), List(ConfigurationDeploymentPhase.Precheck))
        assert(!staleRenew && !staleAdvance && !staleRollback && !staleFinish)
        assert(advanced && !wrongPhase && finished && !afterFinish)
        assertEquals(row.state, ConfigurationDeploymentState.Failed)
        assertEquals(row.leaseToken, None)
      }
    }
  }

  test("the request, its snapshot and its audit record commit together, or none of them does") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        failing = w.deploymentsWith(auditRecorder = new AuditRecorder[ConnectionIO](FailingAuditEvents, w.ids, w.time))
        failed <- failing.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID()).attempt
        none <- w.run(sql"select count(*) from configuration_deployment where organization_id = ${w.org}".query[Long].unique)
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        journal <- w.journal("CONFIGURATION_DEPLOYMENT")
      } yield {
        assert(failed.isLeft)
        assertEquals(none, 0L)
        assertEquals(journal, List("CONFIGURATION_DEPLOYMENT_REQUESTED" -> Some(id)))
      }
    }
  }

  test("the same request ID returns the same deployment and writes nothing twice") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        requestId = UUID.randomUUID()
        request = w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, requestId)
        ids <- List(request, request, request).parSequence
        again <- request
        rows <- w.run(sql"select count(*) from configuration_deployment where organization_id = ${w.org}".query[Long].unique)
        journal <- w.journal("CONFIGURATION_DEPLOYMENT")
      } yield {
        assertEquals(ids.distinct.size, 1)
        assertEquals(again, ids.head)
        assertEquals(rows, 1L)
        assertEquals(journal.size, 1)
      }
    }
  }

  test("only a source connection of the resource may deploy, and only as it was when approved") {
    run { w =>
      for {
        node <- w.node("node-a")
        other <- w.node("node-b")
        unrelated <- w.unrelatedConnection()
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        request = (connection: UUID) => w.deployments.request(w.actor, assignmentId, 1, connection,
          ExpectedRemoteState.Missing, ConfigurationExecutionPolicy.Default, UUID.randomUUID()).attempt
        foreignNode <- request(other.connectionId)
        notSource <- request(unrelated)
        missing <- request(UUID.randomUUID())
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        _ <- w.touchConnection(node.connectionId)
        _ <- w.drain()
        deployment <- w.deployment(id)
      } yield {
        assertEquals(code(foreignNode), Some("CONFIGURATION_DEPLOYMENT_CONNECTION_NOT_SOURCE"))
        assertEquals(code(notSource), Some("CONFIGURATION_DEPLOYMENT_CONNECTION_NOT_SOURCE"))
        assertEquals(code(missing), Some("CONFIGURATION_DEPLOYMENT_CONNECTION_NOT_FOUND"))
        assertEquals(deployment.state, ConfigurationDeploymentState.Failed)
        assertEquals(deployment.failureCode, Some("CONFIGURATION_DEPLOYMENT_CONNECTION_CHANGED"))
        assert(!w.remote.exists(Path))
      }
    }
  }

  test("another tenant can neither see, list, cancel nor deploy an assignment it does not own") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        detail <- w.deployments.detail(w.foreignOrg, id).attempt
        history <- w.deployments.history(w.foreignOrg, None, None, None, 50)
        cancel <- w.deployments.cancel(w.foreignActor, id).attempt
        request <- w.deployments.request(w.foreignActor, assignmentId, 1, node.connectionId,
          ExpectedRemoteState.Missing, ConfigurationExecutionPolicy.Default, UUID.randomUUID()).attempt
        summaries <- w.deployments.summaries(w.foreignOrg, List(assignmentId))
      } yield {
        assertEquals(code(detail), Some("CONFIGURATION_DEPLOYMENT_NOT_FOUND"))
        assertEquals(history, Nil)
        assertEquals(code(cancel), Some("CONFIGURATION_DEPLOYMENT_NOT_FOUND"))
        assertEquals(code(request), Some("CONFIGURATION_ASSIGNMENT_NOT_FOUND"))
        assertEquals(summaries.flatMap(_.active), Nil)
      }
    }
  }

  test("a queued deployment cancels at once; a finished one cannot be cancelled") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        _ <- w.deployments.cancel(w.actor, id)
        cancelled <- w.deployment(id)
        again <- w.deployments.cancel(w.actor, id).attempt
        journal <- w.journal("CONFIGURATION_DEPLOYMENT")
      } yield {
        assertEquals(cancelled.state, ConfigurationDeploymentState.Cancelled)
        assert(cancelled.finishedAt.isDefined)
        assertEquals(code(again), Some("CONFIGURATION_DEPLOYMENT_NOT_CANCELLABLE"))
        assertEquals(journal.map(_._1), List("CONFIGURATION_DEPLOYMENT_REQUESTED", "CONFIGURATION_DEPLOYMENT_CANCELLED"))
      }
    }
  }

  test("history pages by a stable cursor and summaries read a whole page at once") {
    run { w =>
      for {
        nodes <- (1 to 3).toList.traverse(i => w.node(s"node-$i"))
        profile <- w.profile()
        assigned <- nodes.traverse(node => w.assign(node, profile, Path, "domain" -> node.name).map(node -> _))
        ids <- assigned.traverse { case (node, assignmentId) =>
          w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
            ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        }
        first <- w.deployments.history(w.org, Some(profile), None, None, 2)
        cursor = first.last.deployment
        second <- w.deployments.history(w.org, Some(profile), None, Some(cursor.createdAt -> cursor.id), 2)
        byResource <- w.deployments.history(w.org, None, Some(nodes.head.resourceId), None, 10)
        summaries <- w.deployments.summaries(w.org, assigned.map(_._2))
      } yield {
        assertEquals((first ++ second).map(_.deployment.id).toSet, ids.toSet)
        assertEquals(first.size, 2)
        assertEquals(second.size, 1)
        assertEquals(byResource.map(_.resourceName), List("node-1"))
        assertEquals(first.head.actorName, "Integration fixture")
        assertEquals(summaries.map(_.assignmentId).toSet, assigned.map(_._2).toSet)
        assert(summaries.forall(summary => summary.active.isDefined && summary.lastSucceeded.isEmpty))
      }
    }
  }

  test("the schema refuses impossible lifecycle states") {
    run { w =>
      for {
        node <- w.node("node-a")
        profile <- w.profile()
        assignmentId <- w.assign(node, profile, Path, "domain" -> "a.example")
        id <- w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
          ConfigurationExecutionPolicy.Default, UUID.randomUUID())
        attempt = (statement: ConnectionIO[Int]) => w.run(statement).attempt.map(_.isLeft)
        leaseWhileQueued <- attempt(sql"""update configuration_deployment set lease_owner = gen_random_uuid(),
            lease_token = gen_random_uuid(), lease_expires_at = now() where id = $id""".update.run)
        finishedWhileQueued <- attempt(sql"update configuration_deployment set finished_at = now() where id = $id".update.run)
        succeededUnfinished <- attempt(sql"update configuration_deployment set state = 'SUCCEEDED' where id = $id".update.run)
        rollbackWithoutOrigin <- attempt(sql"update configuration_deployment set phase = 'ROLLBACK' where id = $id".update.run)
        freeFormState <- attempt(sql"update configuration_deployment set state = 'DONE' where id = $id".update.run)
        shellUnit <- attempt(sql"""update configuration_deployment set activation = 'SYSTEMD_RESTART',
            unit_name = 'x.service; reboot' where id = $id""".update.run)
        retainedStandalone <- attempt(sql"update configuration_deployment set backup_retained = true where id = $id".update.run)
      } yield assert(List(leaseWhileQueued, finishedWhileQueued, succeededUnfinished, rollbackWithoutOrigin,
        freeFormState, shellUnit, retainedStandalone).forall(identity))
    }
  }

  test("claiming respects the per-organization limit") {
    run { w =>
      for {
        nodes <- (1 to 3).toList.traverse(i => w.node(s"node-$i"))
        profile <- w.profile()
        _ <- nodes.traverse_ { node =>
          w.assign(node, profile, Path, "domain" -> node.name).flatMap(assignmentId =>
            w.deployments.request(w.actor, assignmentId, 1, node.connectionId, ExpectedRemoteState.Missing,
              ConfigurationExecutionPolicy.Default, UUID.randomUUID()))
        }
        now <- IO.realTimeInstant
        first <- w.run(w.deploymentRepository.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(60), 10, 2, Some(w.org)))
        second <- w.run(w.deploymentRepository.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(60), 10, 2, Some(w.org)))
      } yield {
        assertEquals(first.count(_.organizationId == w.org), 2)
        assertEquals(second.count(_.organizationId == w.org), 0)
      }
    }
  }

  private object FailingAuditEvents extends AuditEventRepository[ConnectionIO] {
    override def save(event: AuditEvent): ConnectionIO[Unit] =
      new IllegalStateException("audit unavailable").raiseError[ConnectionIO, Unit]
    override def saveAll(events: List[AuditEvent]): ConnectionIO[Unit] = save(events.head)
    override def listByOrganization(organizationId: UUID, before: Option[AuditCursor],
                                    limit: Int): ConnectionIO[List[AuditEvent]] = List.empty[AuditEvent].pure[ConnectionIO]
  }
}

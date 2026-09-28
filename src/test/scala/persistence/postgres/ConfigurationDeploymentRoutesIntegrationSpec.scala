package ru.bitec.app.ops
package persistence.postgres

import application.configuration.ConfigurationRollouts
import application.port.{RemoteConfigurationSession, RemoteConfigurationTransport}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationRole
import domain.connection.Connection
import infrastructure.http.{ConfigurationDeploymentRoutes, ConfigurationPromotionRoutes, ConfigurationRolloutRoutes}
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.log4cats.slf4j.Slf4jLogger
import support.{AuthorizationFixtures, InMemoryRemote}

import java.util.UUID

/** The deployment, promotion and rollout HTTP API over the real services and database: stable
  * codes for expected outcomes, the deploy capability on every remote route, and a generic 500
  * that never carries what went wrong inside.
  */
final class ConfigurationDeploymentRoutesIntegrationSpec extends FunSuite {
  private val Path = "/etc/app/app.conf"
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.configuration.routes")

  private final class Api(val w: ConfigurationDeploymentWorld, transport: RemoteConfigurationTransport[IO]) {
    val deployments = w.deploymentsWith(remoteTransport = transport)
    val rollouts = new ConfigurationRollouts[IO, ConnectionIO](w.assignmentRepository, w.assignmentQuery,
      w.profileQuery, w.sources, deployments, w.rolloutRepository, w.ids, w.time, w.audit, w.runner, w.runner, w.settings)
    private val routes = new ConfigurationDeploymentRoutes[ConnectionIO](deployments, AuthorizationFixtures.authorization, logger).routes <+>
      new ConfigurationPromotionRoutes[ConnectionIO](w.promotions, AuthorizationFixtures.authorization, logger).routes <+>
      new ConfigurationRolloutRoutes[ConnectionIO](rollouts, AuthorizationFixtures.authorization, logger).routes

    def call(method: Method, path: String, body: Option[Json] = None, role: OrganizationRole = OrganizationRole.Owner,
             org: UUID = w.org): IO[(Status, Json)] = {
      val request = Request[IO](method, Uri.unsafeFromString(s"/api/v1/organizations/$org$path"))
      AuthorizationFixtures.authorized(routes.orNotFound, role).run(body.fold(request)(request.withEntity(_)))
        .flatMap(response => response.as[Json].handleError(_ => Json.Null).map(response.status -> _))
    }
  }

  private def api(body: Api => IO[Unit], transport: Option[RemoteConfigurationTransport[IO]] = None): Unit =
    ConfigurationDeploymentWorld.run { w =>
      val memory = new InMemoryRemote
      body(new Api(w, transport.getOrElse(memory)))
    }

  private def code(json: Json): Option[String] = json.hcursor.get[String]("code").toOption

  private def fixture(a: Api) = for {
    node <- a.w.node("node-a")
    profile <- a.w.profile()
    assignmentId <- a.w.assign(node, profile, Path, "domain" -> "a.example")
  } yield (node, profile, assignmentId)

  private def deployBody(connection: UUID, requestId: UUID = UUID.randomUUID(), version: Int = 1,
                         execution: Json = Json.obj()): Json = Json.obj(
    "expectedAssignmentVersion" -> Json.fromInt(version), "connectionId" -> Json.fromString(connection.toString),
    "expectedRemoteSha256" -> Json.Null, "expectedRemoteMissing" -> Json.True,
    "requestId" -> Json.fromString(requestId.toString), "execution" -> execution)

  test("preview returns hashes and a diff; a member is refused every deployment and rollout route") {
    api { a =>
      for {
        setup <- fixture(a)
        (node, profile, assignmentId) = setup
        preview <- a.call(Method.POST, s"/configuration-assignments/$assignmentId/deployment-preview", Some(Json.obj(
          "expectedAssignmentVersion" -> Json.fromInt(1), "connectionId" -> Json.fromString(node.connectionId.toString))))
        member <- List(
          Method.POST -> s"/configuration-assignments/$assignmentId/deployment-preview",
          Method.POST -> s"/configuration-assignments/$assignmentId/deployments",
          Method.GET -> s"/configuration-deployments/${UUID.randomUUID()}",
          Method.POST -> s"/configuration-deployments/${UUID.randomUUID()}/cancel",
          Method.GET -> "/configuration-deployments",
          Method.GET -> s"/configuration-deployment-summaries?assignmentId=$assignmentId",
          Method.POST -> "/configuration-rollouts/preflight",
          Method.POST -> "/configuration-rollouts",
          Method.GET -> "/configuration-rollouts",
          Method.POST -> s"/configuration-profiles/$profile/assignment-promotions/preview"
        ).traverse { case (method, path) => a.call(method, path, Some(Json.obj()), OrganizationRole.Member).map(_._1) }
      } yield {
        assertEquals(preview._1, Status.Ok)
        val body = preview._2.hcursor
        assertEquals(body.downField("remote").get[Boolean]("exists"), Right(false))
        assertEquals(body.get[Boolean]("changed"), Right(true))
        assertEquals(body.downField("diff").get[Int]("addedLines"), Right(2))
        assertEquals(body.downField("connection").get[String]("id"), Right(node.connectionId.toString))
        assert(member.forall(_ == Status.Forbidden), clues(member))
      }
    }
  }

  test("preview and deploy refuse a foreign assignment, a non-source connection and a stale version") {
    api { a =>
      for {
        setup <- fixture(a)
        (node, _, assignmentId) = setup
        unrelated <- a.w.unrelatedConnection()
        previewPath = s"/configuration-assignments/$assignmentId/deployment-preview"
        body = (connection: UUID, version: Int) => Some(Json.obj("expectedAssignmentVersion" -> Json.fromInt(version),
          "connectionId" -> Json.fromString(connection.toString)))
        foreign <- a.call(Method.POST, previewPath, body(node.connectionId, 1), org = a.w.foreignOrg)
        notSource <- a.call(Method.POST, previewPath, body(unrelated, 1))
        stale <- a.call(Method.POST, previewPath, body(node.connectionId, 2))
        malformed <- a.call(Method.POST, "/configuration-assignments/not-a-uuid/deployment-preview", body(node.connectionId, 1))
        garbage <- a.call(Method.POST, previewPath, Some(Json.fromString("nope")))
      } yield {
        assertEquals((foreign._1, code(foreign._2)), (Status.NotFound, Some("CONFIGURATION_ASSIGNMENT_NOT_FOUND")))
        assertEquals((notSource._1, code(notSource._2)), (Status.Conflict, Some("CONFIGURATION_DEPLOYMENT_CONNECTION_NOT_SOURCE")))
        assertEquals((stale._1, code(stale._2)), (Status.Conflict, Some("CONFIGURATION_ASSIGNMENT_CHANGED")))
        assertEquals(malformed._1, Status.BadRequest)
        assertEquals(garbage._1, Status.BadRequest)
      }
    }
  }

  test("deploy is accepted once per request ID, conflicts with an active deployment and validates its policy") {
    api { a =>
      for {
        setup <- fixture(a)
        (node, profile, assignmentId) = setup
        second <- a.w.assign(node, profile, "/etc/app/other.conf", "domain" -> "b.example")
        requestId = UUID.randomUUID()
        path = s"/configuration-assignments/$assignmentId/deployments"
        first <- a.call(Method.POST, path, Some(deployBody(node.connectionId, requestId)))
        repeated <- a.call(Method.POST, path, Some(deployBody(node.connectionId, requestId)))
        busy <- a.call(Method.POST, s"/configuration-assignments/$second/deployments", Some(deployBody(node.connectionId)))
        badUnit <- a.call(Method.POST, path, Some(deployBody(node.connectionId, execution = Json.obj(
          "activation" -> Json.fromString("SYSTEMD_RESTART"), "unitName" -> Json.fromString("x.service; reboot")))))
        shellValidator <- a.call(Method.POST, path, Some(deployBody(node.connectionId, execution = Json.obj(
          "validator" -> Json.obj("executable" -> Json.fromString("/usr/sbin/nginx"),
            "args" -> Json.arr(Json.fromString("$(id)")))))))
        bothStates <- a.call(Method.POST, path, Some(deployBody(node.connectionId).deepMerge(
          Json.obj("expectedRemoteSha256" -> Json.fromString("a" * 64)))))
        id = first._2.hcursor.get[String]("deploymentId").toOption.get
        detail <- a.call(Method.GET, s"/configuration-deployments/$id")
        history <- a.call(Method.GET, s"/configuration-deployments?profileId=$profile&limit=1")
        summaries <- a.call(Method.GET, s"/configuration-deployment-summaries?assignmentId=$assignmentId&assignmentId=$second")
        cancel <- a.call(Method.POST, s"/configuration-deployments/$id/cancel")
        cancelAgain <- a.call(Method.POST, s"/configuration-deployments/$id/cancel")
        badLimit <- a.call(Method.GET, "/configuration-deployments?limit=0")
      } yield {
        assertEquals(first._1, Status.Accepted)
        assertEquals(first._2.hcursor.get[String]("state"), Right("QUEUED"))
        assertEquals((repeated._1, repeated._2.hcursor.get[String]("deploymentId").toOption), (Status.Accepted, Some(id)))
        assertEquals((busy._1, code(busy._2)), (Status.Conflict, Some("CONFIGURATION_DEPLOYMENT_ALREADY_ACTIVE")))
        assertEquals(List(badUnit._1, shellValidator._1, bothStates._1), List.fill(3)(Status.BadRequest))
        assertEquals(detail._1, Status.Ok)
        assertEquals(detail._2.hcursor.downField("resource").get[String]("name"), Right("node-a"))
        assertEquals(detail._2.hcursor.downField("events").downArray.get[String]("type"), Right("QUEUED"))
        assertEquals(history._2.hcursor.downField("items").as[List[Json]].map(_.size), Right(1))
        assertEquals(summaries._2.as[List[Json]].map(_.size), Right(2))
        assertEquals(cancel._1, Status.Ok)
        assertEquals((cancelAgain._1, code(cancelAgain._2)), (Status.Conflict, Some("CONFIGURATION_DEPLOYMENT_NOT_CANCELLABLE")))
        assertEquals(badLimit._1, Status.BadRequest)
      }
    }
  }

  test("an unexpected failure is a generic 500 that carries nothing from inside") {
    val broken = new RemoteConfigurationTransport[IO] {
      override def withSession[A](connection: Connection)(use: RemoteConfigurationSession[IO] => IO[A]): IO[A] =
        IO.raiseError(new IllegalStateException("password=hunter2 in /etc/shadow"))
    }
    api(a => for {
      setup <- fixture(a)
      (node, _, assignmentId) = setup
      response <- a.call(Method.POST, s"/configuration-assignments/$assignmentId/deployment-preview", Some(Json.obj(
        "expectedAssignmentVersion" -> Json.fromInt(1), "connectionId" -> Json.fromString(node.connectionId.toString))))
    } yield {
      assertEquals(response._1, Status.InternalServerError)
      assertEquals(code(response._2), Some("INTERNAL_ERROR"))
      assert(!response._2.noSpaces.contains("hunter2") && !response._2.noSpaces.contains("shadow"))
    }, Some(broken))
  }

  test("promotion preview, commit, stale selection, missing revision and incompatible values") {
    api { a =>
      for {
        setup <- fixture(a)
        (_, profile, assignmentId) = setup
        _ <- a.w.revision(profile, "# v2\nserver_name {{ domain }};\nmode {{ mode }};\n")
        selection = (version: Int) => Json.obj("revisionNumber" -> Json.fromInt(2), "assignments" -> Json.arr(Json.obj(
          "assignmentId" -> Json.fromString(assignmentId.toString), "expectedVersion" -> Json.fromInt(version))))
        base = s"/configuration-profiles/$profile/assignment-promotions"
        preview <- a.call(Method.POST, s"$base/preview", Some(selection(1)))
        stale <- a.call(Method.POST, base, Some(selection(5)))
        missing <- a.call(Method.POST, base, Some(selection(1).deepMerge(Json.obj("revisionNumber" -> Json.fromInt(9)))))
        committed <- a.call(Method.POST, base, Some(selection(1)))
        again <- a.call(Method.POST, base, Some(selection(1)))
        invalid <- a.call(Method.POST, base, Some(Json.obj("revisionNumber" -> Json.fromInt(0), "assignments" -> Json.arr())))
      } yield {
        assertEquals(preview._1, Status.Ok)
        assertEquals(preview._2.hcursor.get[Boolean]("compatible"), Right(true))
        assertEquals((stale._1, code(stale._2)), (Status.Conflict, Some("CONFIGURATION_PROMOTION_CONFLICT")))
        assertEquals((missing._1, code(missing._2)), (Status.NotFound, Some("CONFIGURATION_REVISION_NOT_FOUND")))
        assertEquals(committed._1, Status.Ok)
        assertEquals(committed._2.hcursor.downField("assignments").downArray.get[Int]("version"), Right(2))
        assertEquals(again._1, Status.Conflict)
        assertEquals(invalid._1, Status.BadRequest)
      }
    }
  }

  test("rollout preflight, launch, detail, history and cancel; invalid strategy bounds are refused") {
    api { a =>
      for {
        setup <- fixture(a)
        (node, profile, assignmentId) = setup
        target = Json.obj("assignmentId" -> Json.fromString(assignmentId.toString), "expectedVersion" -> Json.fromInt(1),
          "connectionId" -> Json.fromString(node.connectionId.toString))
        preflight <- a.call(Method.POST, "/configuration-rollouts/preflight", Some(Json.obj(
          "profileId" -> Json.fromString(profile.toString), "revisionNumber" -> Json.fromInt(1), "targets" -> Json.arr(target))))
        item = preflight._2.hcursor.downField("items").downArray
        approved = target.deepMerge(Json.obj(
          "connectionUpdatedAt" -> item.get[Json]("connectionUpdatedAt").toOption.get,
          "desiredSha256" -> item.get[Json]("desiredSha256").toOption.get,
          "expectedRemoteSha256" -> Json.Null, "expectedRemoteMissing" -> Json.True))
        create = (strategy: Json) => Json.obj("profileId" -> Json.fromString(profile.toString),
          "revisionNumber" -> Json.fromInt(1), "requestId" -> Json.fromString(UUID.randomUUID().toString),
          "strategy" -> strategy, "targets" -> Json.arr(approved))
        badBatch <- a.call(Method.POST, "/configuration-rollouts", Some(create(Json.obj("batchSize" -> Json.fromInt(0)))))
        badCanary <- a.call(Method.POST, "/configuration-rollouts", Some(create(Json.obj("canaryCount" -> Json.fromInt(2)))))
        badPause <- a.call(Method.POST, "/configuration-rollouts", Some(create(Json.obj("pauseSeconds" -> Json.fromInt(100000)))))
        created <- a.call(Method.POST, "/configuration-rollouts", Some(create(Json.obj("canaryCount" -> Json.fromInt(1)))))
        id = created._2.hcursor.get[String]("rolloutId").toOption.get
        detail <- a.call(Method.GET, s"/configuration-rollouts/$id")
        history <- a.call(Method.GET, s"/configuration-rollouts?profileId=$profile")
        foreign <- a.call(Method.GET, s"/configuration-rollouts/$id", org = a.w.foreignOrg)
        cancel <- a.call(Method.POST, s"/configuration-rollouts/$id/cancel", Some(Json.obj("rollbackApplied" -> Json.True)))
      } yield {
        assertEquals(preflight._1, Status.Ok)
        assertEquals(preflight._2.hcursor.get[Boolean]("ready"), Right(true))
        assertEquals(List(badBatch._1, badCanary._1, badPause._1), List.fill(3)(Status.BadRequest))
        assertEquals(created._1, Status.Accepted)
        assertEquals(detail._2.hcursor.downField("items").downArray.downField("resource").get[String]("name"), Right("node-a"))
        assertEquals(detail._2.hcursor.downField("strategy").get[Int]("canaryCount"), Right(1))
        assertEquals(history._2.hcursor.downField("items").as[List[Json]].map(_.size), Right(1))
        assertEquals((foreign._1, code(foreign._2)), (Status.NotFound, Some("CONFIGURATION_ROLLOUT_NOT_FOUND")))
        assertEquals((cancel._1, cancel._2.hcursor.get[Boolean]("rollbackRequested")), (Status.Ok, Right(true)))
      }
    }
  }

}

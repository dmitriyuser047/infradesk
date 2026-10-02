package ru.bitec.app.ops
package persistence.postgres

import application.provisioning._
import cats.effect.IO
import domain.auth.OrganizationRole
import infrastructure.http.ServerProfileRoutes
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.{Method,Request,Status,Uri}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.log4cats.slf4j.Slf4jLogger
import support.{AuthorizationFixtures,ServerProfileFixtures}
import java.util.UUID

final class ServerProfileRoutesIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run
  private class Api(w:ConfigurationDeploymentWorld) {
    val remote=new ServerProfileFixtures.Remote
    val service=new ServerProfiles[IO,ConnectionIO](new PostgresServerProfileRepository,new PostgresProvisioningTargetQuery,
      new PostgresProvisioningRunRepository,remote,w.ids,w.time,w.audit,w.runner,w.runner,ProvisioningSettings.Default)
    val routes=new ServerProfileRoutes(service,AuthorizationFixtures.authorization,Slf4jLogger.getLoggerFromName[IO]("test.profile.routes")).routes
    def call(method:Method,path:String,body:Option[Json]=None,role:OrganizationRole=OrganizationRole.Owner,
      org:UUID=w.org):IO[(Status,Json)] = {
      val request=Request[IO](method,Uri.unsafeFromString(s"/api/v1/organizations/$org$path"))
      AuthorizationFixtures.authorized(routes.orNotFound,role).run(body.fold(request)(request.withEntity(_)))
        .flatMap(r => r.as[Json].handleError(_ => Json.Null).map(r.status -> _))
    }
  }
  private val create=Json.obj("code" -> Json.fromString("baseline"),"name" -> Json.fromString("Baseline"),
    "content" -> ServerProfileFixtures.disabled.json)

  test("read-only members see profiles and automation without SSH; writes and remote reads require permissions") {
    run { w => val api=new Api(w)
      for {
        node <- w.node("profile-api")
        created <- api.call(Method.POST,"/server-profiles",Some(create))
        id=created._2.hcursor.downField("profile").get[String]("id").toOption.get
        list <- api.call(Method.GET,"/server-profiles",role=OrganizationRole.Member)
        detail <- api.call(Method.GET,s"/server-profiles/$id",role=OrganizationRole.Member)
        automation <- api.call(Method.GET,s"/resources/${node.resourceId}/server-profile/automation",role=OrganizationRole.Member)
        memberCreate <- api.call(Method.POST,"/server-profiles",Some(create),OrganizationRole.Member)
        memberObserve <- api.call(Method.POST,s"/resources/${node.resourceId}/server-profile/observe",role=OrganizationRole.Member)
        memberPreview <- api.call(Method.POST,s"/resources/${node.resourceId}/server-profile/preview",role=OrganizationRole.Member)
        foreign <- api.call(Method.GET,s"/server-profiles/$id",org=w.foreignOrg)
      } yield {
        assertEquals(created._1,Status.Created)
        assert(List(list,detail,automation).forall(_._1==Status.Ok))
        assert(List(memberCreate,memberObserve,memberPreview).forall(_._1==Status.Forbidden))
        assertEquals(foreign._1,Status.NotFound)
        assertEquals(api.remote.calls.get(),0)
      }
    }
  }
  test("closed HTTP bodies reject command injection, coerced revision numbers and invalid pagination") {
    run { w => val api=new Api(w)
      for {
        resource <- w.resource("profile-api-intent")
        extra <- api.call(Method.POST,"/server-profiles",Some(create.deepMerge(Json.obj("command" -> Json.fromString("id")))))
        created <- api.call(Method.POST,"/server-profiles",Some(create))
        id=created._2.hcursor.downField("profile").get[String]("id").toOption.get
        pin=Json.obj("profileId" -> Json.fromString(id),"revisionNumber" -> Json.fromInt(1))
        extraPin <- api.call(Method.PUT,s"/resources/$resource/server-profile",Some(pin.deepMerge(Json.obj("script" -> Json.fromString("id")))))
        wrongType <- api.call(Method.PUT,s"/resources/$resource/server-profile",Some(pin.deepMerge(Json.obj("revisionNumber" -> Json.fromString("1")))))
        assigned <- api.call(Method.PUT,s"/resources/$resource/server-profile",Some(pin))
        revision <- api.call(Method.POST,s"/server-profiles/$id/revisions",Some(Json.obj("content" -> ServerProfileFixtures.disabled.json,"extra" -> Json.True)))
        limit <- api.call(Method.GET,"/server-profiles?limit=abc")
        inUse <- api.call(Method.DELETE,s"/server-profiles/$id")
        removed <- api.call(Method.DELETE,s"/resources/$resource/server-profile")
        archived <- api.call(Method.DELETE,s"/server-profiles/$id")
      } yield {
        assert(List(extra,extraPin,wrongType,revision,limit).forall(_._1==Status.BadRequest))
        assertEquals(assigned._1,Status.Ok)
        assertEquals(inUse._1,Status.Conflict)
        assertEquals(removed._1,Status.NoContent)
        assertEquals(archived._1,Status.Ok)
        assertEquals(api.remote.calls.get(),0)
      }
    }
  }
  test("preview presents a readable target, ordered shared steps and actual endpoint") {
    run { w => val api=new Api(w)
      for {
        node <- w.node("readable-profile")
        profile <- api.service.create(w.actor,"readable","Human profile",None,ServerProfileFixtures.content)
        _ <- api.service.assign(w.actor,node.resourceId,profile._1.id,1)
        preview <- api.call(Method.POST,s"/resources/${node.resourceId}/server-profile/preview")
        json=preview._2
      } yield {
        assertEquals(preview._1,Status.Ok)
        assertEquals(json.hcursor.get[String]("connectionName"),Right("ssh readable-profile"))
        assertEquals(json.hcursor.get[String]("profileName"),Right("Human profile"))
        assertEquals(json.hcursor.get[String]("endpoint"),Right("https://node.example.com:8080"))
        assertEquals(json.hcursor.get[List[Json]]("steps").map(_.flatMap(_.hcursor.get[Int]("position").toOption)),Right((0 until 10).toList))
        assertEquals(api.remote.calls.get(),1)
      }
    }
  }
}

package ru.bitec.app.ops
package support

import application.port._
import cats.effect.IO
import domain.connection.Connection
import domain.provisioning._
import io.circe.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object ServerProfileFixtures {
  val content=ServerProfileContent(1,PackageModule(true,List("curl")),NetworkModule(true,true,Map("net.core.somaxconn" -> "4096")),
    LimitsModule(true,65536,65536,65536),FirewallModule(true,List(FirewallRule("ssh","tcp",22,List("ANY")))),
    ToggleModule(true),ToggleModule(true),CaddyModule(true,Some("node.example.com"),8080,"/var/www/infradesk/node","zstd",RedirectMode.HttpToHttps),
    SiteModule(true,Some("node.example.com"),"/var/www/infradesk/node","DEFAULT_PLACEHOLDER",1))
  val disabled=content.copy(packages=PackageModule(false,Nil),network=NetworkModule(false,false,Map.empty),
    limits=content.limits.copy(enabled=false),firewall=FirewallModule(false,Nil),fail2ban=ToggleModule(false),docker=ToggleModule(false),
    caddy=content.caddy.copy(enabled=false),site=content.site.copy(enabled=false))
  def observed(c: ServerProfileContent=content): Json = Json.obj(
    "packages" -> Json.obj("installed" -> Json.fromValues(List("ca-certificates","caddy","curl","docker-compose","docker.io","fail2ban","ufw").map(Json.fromString))),
    "network" -> Json.obj("sysctl" -> Json.fromFields(ServerProfileDiff.effectiveSysctl(c.network).toList.map {case(k,v)=>k->Json.fromString(v)}),
      "managedSysctl" -> Json.fromFields(ServerProfileDiff.effectiveSysctl(c.network).toList.map {case(k,v)=>k->Json.fromString(v)}),
      "managedFile" -> Json.True,"managedFileHash" -> Json.fromString(ServerProfileDiff.hashText(ServerProfileDiff.renderNetwork(c.network))),
      "bbr" -> Json.fromBoolean(c.network.bbr),"managedBbr" -> Json.fromBoolean(c.network.bbr)),
    "limits" -> Json.obj("managedNofileSoft" -> Json.fromString(c.limits.nofileSoft.toString),"managedNofileHard" -> Json.fromString(c.limits.nofileHard.toString),
      "systemdDefaultLimitNofile" -> Json.fromString(c.limits.systemdDefaultLimitNofile.toString),"managedFile" -> Json.True,
      "managedFileHash" -> Json.fromString(ServerProfileDiff.hashText(ServerProfileDiff.renderLimits(c.limits))),"managedSystemdDropin" -> Json.True,
      "managedSystemdDropinHash" -> Json.fromString(ServerProfileDiff.hashText(ServerProfileDiff.renderManagerLimits(c.limits)))),
    "firewall" -> Json.obj("enabled" -> Json.True,"managedRules" -> Json.fromValues(c.firewall.rules.map(r => Json.obj(
      "id"->Json.fromString(r.id),"protocol"->Json.fromString(r.protocol),"port"->Json.fromInt(r.port),"sources"->Json.fromValues(r.sources.map(Json.fromString))))),
      "foreignEquivalentAllows" -> Json.arr(),"sshSource" -> Json.fromString("192.0.2.4"),"sshServer" -> Json.fromString("198.51.100.2"),"sshPort" -> Json.fromInt(22)),
    "fail2ban" -> Json.obj("installed"->Json.True,"enabled"->Json.True,"active"->Json.True,"managedFile"->Json.True,
      "managedFileHash"->Json.fromString(ServerProfileDiff.hashText(ServerProfileDiff.Fail2banContent))),
    "docker" -> Json.obj("installed"->Json.True,"enabled"->Json.True,"active"->Json.True,"composeAvailable"->Json.True),
    "site" -> Json.obj("managedContentHash"->Json.fromString(ServerProfileDiff.placeholderHash(c.site)),"rootSafe"->Json.True,"managed"->Json.True),
    "caddy" -> Json.obj("installed"->Json.True,"enabled"->Json.True,"active"->Json.True,"managedConfigHash"->Json.fromString(ServerProfileDiff.renderedCaddyHash(c.caddy)),
      "managed"->Json.True,"httpsPort"->Json.fromInt(c.caddy.localHttpsPort),"listeningPorts"->Json.arr(Json.fromInt(c.caddy.localHttpsPort)))
  )
  final class Remote extends ServerProfileRemote[IO] {
    val facts=new AtomicReference[Json](observed())
    val hook=new AtomicReference[IO[Unit]](IO.unit)
    val outcome=new AtomicReference[ProvisioningStepResult](ProvisioningStepResult(Map.empty,None,None))
    val calls=new java.util.concurrent.atomic.AtomicInteger(0)
    def observe(connection: Connection,resourceId: UUID,desired: Option[ServerProfileContent]): IO[ServerProfileRemoteObservation] =
      hook.get() *> IO { calls.incrementAndGet(); val f=facts.get(); ServerProfileRemoteObservation(f,ServerProfileDiff.hashObservation(f)) }
    def applyModule(connection: Connection,resourceId: UUID,snapshot: ServerProfileApplySnapshot,kind: ProvisioningStepKind,
      context: ProfileExecutionContext): IO[ProvisioningStepResult] =
      if(kind==ProvisioningStepKind.Verify) observe(connection,resourceId,Some(snapshot.content)).map(o =>
        ProvisioningStepResult(Map.empty,None,Some(ServerProfileDiff.assess(snapshot.content,o.content).compliant),profileObservation=Some(o)))
      else IO.pure(outcome.get())
  }
}

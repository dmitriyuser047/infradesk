package ru.bitec.app.ops
package integration.ssh

import application.port.{ProfileExecutionContext, ProvisioningStepResult, RemoteConfigurationTransport}
import cats.effect.IO
import cats.syntax.all._
import domain.connection.Connection
import domain.provisioning.{FirewallModule,FirewallRule,ServerProfileContent,ServerProfileDiff}
import io.circe.Json
import java.util.UUID
import scala.concurrent.duration._

private[ssh] final case class ProfileFirewallRule(action: String, rule: FirewallRule, owned: Boolean)
private[ssh] object ProfileFirewall {
  /** Parse only supported canonical UFW forms. Unknown rules block, never become deletions. */
  def parse(raw: String, resourceId: UUID): Either[String,List[ProfileFirewallRule]] = {
    val prefix=s"infradesk:$resourceId:"
    val nonempty=raw.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    val header="Added user rules (see 'ufw status' for running firewall)"
    if(nonempty==List(header,"(None)") || nonempty==List(s"$header:","(None)"))
      return Right(Nil)
    if(nonempty.isEmpty || nonempty.exists(l => l!=header && l!=s"$header:" && !l.startsWith("ufw ")))
      return Left("FIREWALL_RULE_UNSUPPORTED")
    val lines=nonempty.filter(_.startsWith("ufw "))
    lines.traverse { line =>
      val commentIndex=line.indexOf(" comment ")
      val body=if(commentIndex<0) line else line.substring(0,commentIndex)
      val comment=if(commentIndex<0) "" else line.substring(commentIndex+9).stripPrefix("'").stripSuffix("'")
      val own=comment.startsWith(prefix) && comment.stripPrefix(prefix).matches("[a-z0-9][a-z0-9_-]{0,39}")
      val id=if(own) comment.stripPrefix(prefix) else "foreign"
      val full="^ufw (allow|deny|reject) from ([0-9a-fA-F.:/]+|any) to any port ([0-9]{1,5}) proto (tcp|udp)$".r
      val short="^ufw (allow|deny|reject) ([0-9]{1,5})/(tcp|udp)$".r
      val parsed=body match {
        case full(action,source,port,protocol) => Some((action,source,port,protocol))
        case short(action,port,protocol) => Some((action,"any",port,protocol))
        case _ => None
      }
      parsed.toRight("FIREWALL_RULE_UNSUPPORTED").flatMap { case (action,source,port,protocol) =>
        for {
          p <- port.toIntOption.filter(n => n>=1 && n<=65535).toRight("FIREWALL_RULE_UNSUPPORTED")
          s <- ServerProfileContent.canonicalFirewallSource(if(source=="any") "ANY" else source)
        } yield ProfileFirewallRule(action,FirewallRule(id,protocol,p,List(s)),own)
      }
    }
  }
  def aggregate(rules: List[FirewallRule]): List[FirewallRule] = rules.groupBy(r => (r.id,r.protocol,r.port))
    .toList.sortBy(_._1).map { case ((id,protocol,port),xs) => FirewallRule(id,protocol,port,xs.flatMap(_.sources).distinct.sorted) }
  def json(rule: FirewallRule): Json = Json.obj("id" -> Json.fromString(rule.id),"protocol" -> Json.fromString(rule.protocol),
    "port" -> Json.fromInt(rule.port),"sources" -> Json.fromValues(rule.sources.map(Json.fromString)))
  def equivalent(a: FirewallRule,b: FirewallRule): Boolean = a.protocol==b.protocol && a.port==b.port &&
    a.sources.distinct.sorted==b.sources.distinct.sorted
  def coveredByForeign(desired: FirewallRule,rules: List[ProfileFirewallRule]): Boolean = desired.sources.forall { source =>
    rules.exists(r => !r.owned && r.action=="allow" && r.rule.protocol==desired.protocol && r.rule.port==desired.port && r.rule.sources.contains(source))
  }
  def sshBlocked(rules: List[ProfileFirewallRule], source: String, port: Int): Boolean = rules.exists(r =>
    r.action!="allow" && r.rule.protocol=="tcp" && r.rule.port==port && r.rule.sources.exists(ServerProfileContent.sourceCovers(_,source)))
}

private[ssh] final class ProfileFirewall(transport: RemoteConfigurationTransport[IO], connection: Connection,
  commands: ProfileCommands, resourceId: UUID) {
  import ProfileFirewall._
  private def checked(executable: String,args: List[String]): IO[Unit] = commands.capture(executable,args,20.seconds)
    .flatMap(r => if(r.exitCode==0) IO.unit else IO.raiseError(ProfileRemoteFailure("PROVISIONING_FIREWALL_CHANGE_FAILED")))
  private def canary: IO[Unit] = transport.withSessionBounded(connection,128)(session =>
    new ProfileCommands(session,root=true).capture("id",List("-u"),5.seconds,privileged=false).flatMap(r =>
      if(r.exitCode==0 && r.stdout.trim.matches("[0-9]{1,8}")) IO.unit
      else IO.raiseError(ProfileRemoteFailure("PROVISIONING_FIREWALL_CANARY_FAILED",uncertain=true))))
    .timeoutTo(15.seconds,IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_TIMEOUT",uncertain=true)))
  private def current(source: String,port: Int): IO[List[ProfileFirewallRule]] = for {
    added <- commands.capture("ufw",List("show","added"))
    _ <- IO.raiseUnless(added.exitCode==0)(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
    rules <- parse(added.stdout,resourceId).leftMap(ProfileRemoteFailure(_)).liftTo[IO]
    _ <- IO.raiseWhen(rules.exists(r => r.owned && r.action!="allow"))(
      ProfileRemoteFailure("PROVISIONING_FIREWALL_RULE_UNSUPPORTED"))
    _ <- IO.raiseWhen(sshBlocked(rules,source,port))(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
  } yield rules
  def apply(desired: FirewallModule,reviewed: Option[Json]=None): IO[ProvisioningStepResult] = for {
    ssh <- commands.shell("printf '%s' \"$SSH_CONNECTION\"",privileged=false)
    fields=ssh.stdout.trim.split("\\s+").toList
    endpoint <- fields match {
      case source :: clientPort :: server :: serverPort :: Nil if !source.contains("/") && !server.contains("/") && ServerProfileContent.canonicalFirewallSource(source).isRight &&
        ServerProfileContent.canonicalFirewallSource(server).isRight =>
        serverPort.toIntOption.filter(p => p>=1 && p<=65535).map(p => source -> p).liftTo[IO](ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
      case _ => IO.raiseError[(String,Int)](ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
    }
    (source,port)=endpoint
    _ <- IO.raiseUnless(desired.rules.exists(r => r.protocol=="tcp" && r.port==port &&
      r.sources.exists(ServerProfileContent.sourceCovers(_,source))))(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
    old <- current(source,port)
    own=aggregate(old.filter(r => r.owned && r.action=="allow").map(_.rule))
    _ <- reviewed.traverse_ { facts =>
      val expected=facts.hcursor.get[List[Json]]("managedRules").toOption
      IO.raiseUnless(expected.exists(xs => ServerProfileDiff.hashObservation(Json.fromValues(xs))==
        ServerProfileDiff.hashObservation(Json.fromValues(own.map(json)))))(
        ProfileRemoteFailure("PROVISIONING_PROFILE_PLAN_CHANGED"))
    }
    ordered=desired.rules.sortBy(r => if(r.protocol=="tcp" && r.port==port && r.sources.exists(ServerProfileContent.sourceCovers(_,source))) 0 else 1)
    _ <- ordered.traverse_ { rule => rule.sources.traverse_ { s =>
      val exact=rule.copy(sources=List(s))
      current(source,port).flatMap { fresh =>
        if(fresh.exists(r => r.owned && r.action=="allow" && r.rule.id!=rule.id && equivalent(r.rule,exact)))
          IO.raiseError(ProfileRemoteFailure("PROVISIONING_FIREWALL_OWNERSHIP_COLLISION"))
        else if(fresh.exists(r => !r.owned && r.action=="allow" && equivalent(r.rule,exact)) ||
          fresh.exists(r => r.owned && r.action=="allow" && r.rule.id==rule.id && equivalent(r.rule,exact))) IO.unit
        else checked("ufw",argv(rule.id,rule.protocol,rule.port,s,delete=false))
      }
    }}
    _ <- canary
    status <- commands.capture("ufw",List("status"))
    _ <- IO.raiseUnless(status.exitCode==0)(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
    _ <- IO.raiseUnless(status.stdout.linesIterator.map(_.trim).exists(s => Set("Status: active","Status: inactive")(s)))(
      ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
    _ <- if(status.stdout.linesIterator.exists(_.trim=="Status: active")) IO.unit
      else checked("ufw",List("--force","enable")) *> canary
    // Exact ownership comments are essential: uncommented UFW deletion can remove a foreign rule.
    _ <- own.traverse_ { oldRule => oldRule.sources.traverse_ { s =>
      if(desired.rules.exists(r => r.id==oldRule.id && r.protocol==oldRule.protocol && r.port==oldRule.port && r.sources.contains(s))) IO.unit
      else current(source,port).flatMap { fresh =>
        if(fresh.exists(r => r.owned && r.action=="allow" && r.rule.id==oldRule.id && equivalent(r.rule,oldRule.copy(sources=List(s)))))
          checked("ufw",argv(oldRule.id,oldRule.protocol,oldRule.port,s,delete=true))
        else IO.unit
      }
    }}
    _ <- canary
  } yield ProvisioningStepResult(Map("rulesApplied" -> desired.rules.size.toString),None,None)

  private def argv(id: String,protocol: String,port: Int,source: String,delete: Boolean): List[String] =
    (if(delete) List("--force","delete") else Nil) ++ List("allow","from",if(source=="ANY") "any" else source,
      "to","any","port",port.toString,"proto",protocol,"comment",s"infradesk:$resourceId:$id")
}

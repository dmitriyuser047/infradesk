package ru.bitec.app.ops
package domain.provisioning

import io.circe.{ACursor, Json, JsonObject}
import io.circe.syntax._
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final case class ProfileChange(module: String, code: String, detail: Option[String] = None,
  before: Option[String] = None, after: Option[String] = None)
final case class ServerProfileAssessment(modules: List[(String, Boolean)], changes: List[ProfileChange]) {
  def compliant: Boolean = modules.forall(_._2)
}

/** Pure deterministic desired-vs-actual comparison. Observation payloads contain sanitized facts only. */
object ServerProfileDiff {
  def effectiveSysctl(network: NetworkModule): Map[String,String] = network.sysctl ++
    (if(network.bbr) Map("net.ipv4.tcp_congestion_control" -> "bbr") ++
      (if(network.sysctl.contains("net.core.default_qdisc")) Map.empty[String,String]
       else Map("net.core.default_qdisc" -> "fq")) else Map.empty[String,String])
  def renderNetwork(network: NetworkModule): String = "# InfraDesk managed: SERVER_PROFILE NETWORK v1\n" +
    effectiveSysctl(network).toList.sortBy(_._1).map { case (k,v) => s"$k = $v\n" }.mkString
  val Fail2banContent="# InfraDesk managed: SERVER_PROFILE FAIL2BAN v1\n[sshd]\nenabled = true\nbackend = systemd\n"
  def renderLimits(l: LimitsModule): String = s"# InfraDesk managed: SERVER_PROFILE LIMITS v1\n* soft nofile ${l.nofileSoft}\n* hard nofile ${l.nofileHard}\n"
  def renderManagerLimits(l: LimitsModule): String = s"# InfraDesk managed: SERVER_PROFILE SYSTEMD LIMITS v1\n[Manager]\nDefaultLimitNOFILE=${l.systemdDefaultLimitNofile}:${l.systemdDefaultLimitNofile}\n"
  def hashText(value: String): String = sha256(value)
  def requiredPackages(desired: ServerProfileContent, actual: Json): List[String] = {
    val installed = actual.hcursor.downField("packages").get[List[String]]("installed").getOrElse(Nil).toSet
    val dockerPresent = actual.hcursor.downField("docker").get[Boolean]("installed").getOrElse(false)
    val composePresent = actual.hcursor.downField("docker").get[Boolean]("composeAvailable").getOrElse(false)
    ((if (desired.packages.enabled) desired.packages.packages else Nil) ++
      (if (desired.firewall.enabled) List("ufw") else Nil) ++
      (if (desired.fail2ban.enabled) List("fail2ban") else Nil) ++
      (if (desired.docker.enabled && !dockerPresent) List("docker.io") else Nil) ++
      (if (desired.docker.enabled && !composePresent) List("docker-compose") else Nil) ++
      (if (desired.caddy.enabled && !actual.hcursor.downField("caddy").get[Boolean]("installed").getOrElse(false))
        List("caddy", "ca-certificates") else Nil)).distinct.sorted.filterNot(installed)
  }
  def assess(desired: ServerProfileContent, actual: Json): ServerProfileAssessment = {
    val modules = List.newBuilder[(String,Boolean)]
    val changes = List.newBuilder[ProfileChange]
    def obj(key: String): Option[JsonObject] = actual.hcursor.downField(key).focus.flatMap(_.asObject)
    def boolAt(path: String*): Option[Boolean] = path.foldLeft(actual.hcursor: ACursor)((c,k) => c.downField(k)).focus.flatMap(_.asBoolean)
    def stringAt(path: String*): Option[String] = path.foldLeft(actual.hcursor: ACursor)((c,k) => c.downField(k)).focus.flatMap(_.asString)
    def intAt(path: String*): Option[Int] = path.foldLeft(actual.hcursor: ACursor)((c,k) => c.downField(k)).focus.flatMap(_.asNumber.flatMap(_.toInt))
    def module(name: String, enabled: Boolean, findings: List[ProfileChange]): Unit = {
      val relevant = if (!enabled) Nil else findings.sortBy(c => (c.code,c.detail.getOrElse("")))
      modules += name -> relevant.isEmpty
      changes ++= relevant
    }
    val installed = obj("packages").flatMap(_.toMap.get("installed")).flatMap(_.asArray)
      .map(_.toList.flatMap(_.asString)).getOrElse(Nil).toSet
    val packageFindings=PackageProbeFinding.fromObservation(actual)
    module("packages",desired.packages.enabled || packageFindings.nonEmpty,
      (if(desired.packages.enabled) desired.packages.packages else Nil).filterNot(installed).map(p => ProfileChange("packages","PACKAGE_MISSING",Some(p))) ++
        packageFindings.map(f => ProfileChange("packages","PACKAGE_STATE_"+f.classification,Some(f.name),f.observedState,Some(f.suggestedAction))))

    val networkChanges = effectiveSysctl(desired.network).toList.sortBy(_._1).flatMap { case (key,value) =>
      val live = stringAt("network","sysctl",key)
      val managed = stringAt("network","managedSysctl",key)
      Option.when(live != Some(value) || managed != Some(value))(
        ProfileChange("network","SYSCTL_DRIFT",Some(key),live.orElse(managed),Some(value)))
    } ++ Option.when(desired.network.enabled && (!boolAt("network","managedFile").contains(true) ||
      stringAt("network","managedFileHash") != Some(sha256(renderNetwork(desired.network)))))(ProfileChange("network","NETWORK_CONFIG_DRIFT")) ++
      Option.when(desired.network.bbr && !boolAt("network","bbr").contains(true))(ProfileChange("network","BBR_DISABLED")) ++
      Option.when(desired.network.bbr && !boolAt("network","managedBbr").contains(true))(ProfileChange("network","BBR_CONFIG_DRIFT"))
    module("network",desired.network.enabled,networkChanges)

    val limitChanges = List(
      Option.when(desired.limits.enabled && !boolAt("limits","managedFile").contains(true))(ProfileChange("limits","LIMITS_CONFIG_UNMANAGED")),
      Option.when(desired.limits.enabled && !boolAt("limits","managedSystemdDropin").contains(true))(ProfileChange("limits","SYSTEMD_LIMITS_UNMANAGED")),
      Option.when(stringAt("limits","managedFileHash") != Some(sha256(renderLimits(desired.limits))))(ProfileChange("limits","LIMITS_CONFIG_DRIFT")),
      Option.when(stringAt("limits","managedSystemdDropinHash") != Some(sha256(renderManagerLimits(desired.limits))))(ProfileChange("limits","SYSTEMD_LIMITS_CONFIG_DRIFT")),
      Option.when(desired.limits.nofileSoft.toString != stringAt("limits","managedNofileSoft").getOrElse(""))(ProfileChange("limits","NOFILE_SOFT_DRIFT",None,stringAt("limits","managedNofileSoft"),Some(desired.limits.nofileSoft.toString))),
      Option.when(desired.limits.nofileHard.toString != stringAt("limits","managedNofileHard").getOrElse(""))(ProfileChange("limits","NOFILE_HARD_DRIFT",None,stringAt("limits","managedNofileHard"),Some(desired.limits.nofileHard.toString))),
      Option.when(desired.limits.systemdDefaultLimitNofile.toString != stringAt("limits","systemdDefaultLimitNofile").getOrElse(""))(ProfileChange("limits","SYSTEMD_NOFILE_DRIFT",None,stringAt("limits","systemdDefaultLimitNofile"),Some(desired.limits.systemdDefaultLimitNofile.toString)))
    ).flatten
    module("limits",desired.limits.enabled,limitChanges)

    val managedRules = obj("firewall").flatMap(_.toMap.get("managedRules")).flatMap(_.asArray).map(_.toList.flatMap(decodeRule)).getOrElse(Nil)
    val foreignEquivalent = obj("firewall").flatMap(_.toMap.get("foreignEquivalentAllows")).flatMap(_.asArray)
      .map(_.toList.flatMap(decodeRule).map(ruleSignature).toSet).getOrElse(Set.empty[String])
    val observedById = managedRules.groupBy(_.id)
    val desiredById = desired.firewall.rules.map(r => r.id -> r).toMap
    val fw = desired.firewall.rules.flatMap { rule =>
      observedById.get(rule.id) match {
        case Some(actualRules) if actualRules.size != 1 || ruleSignature(actualRules.head) != ruleSignature(rule) =>
          List(ProfileChange("firewall","FIREWALL_RULE_CHANGED",Some(rule.id),Some(actualRules.map(ruleDescription).sorted.mkString("; ").take(512)),Some(ruleDescription(rule))))
        case Some(_) => Nil
        case None if foreignEquivalent(ruleSignature(rule)) => Nil
        case None => List(ProfileChange("firewall","FIREWALL_RULE_MISSING",Some(rule.id),None,Some(ruleDescription(rule))))
      }
    } ++ managedRules.filterNot(r => desiredById.contains(r.id)).map(r => ProfileChange("firewall","FIREWALL_RULE_STALE",Some(r.id),Some(ruleDescription(r)),None)) ++
      Option.when(desired.firewall.enabled && !boolAt("firewall","enabled").contains(true))(ProfileChange("firewall","FIREWALL_DISABLED"))
    module("firewall",desired.firewall.enabled,fw)

    module("fail2ban",desired.fail2ban.enabled,List(
      Option.when(!boolAt("fail2ban","managedFile").contains(true) || stringAt("fail2ban","managedFileHash") != Some(sha256(Fail2banContent)))(ProfileChange("fail2ban","FAIL2BAN_CONFIG_DRIFT")),
      Option.when(!boolAt("fail2ban","installed").contains(true))(ProfileChange("fail2ban","FAIL2BAN_MISSING")),
      Option.when(!boolAt("fail2ban","enabled").contains(true))(ProfileChange("fail2ban","FAIL2BAN_NOT_ENABLED")),
      Option.when(!boolAt("fail2ban","active").contains(true))(ProfileChange("fail2ban","FAIL2BAN_NOT_RUNNING"))).flatten)

    module("docker",desired.docker.enabled,List(
      Option.when(!boolAt("docker","installed").contains(true))(ProfileChange("docker","DOCKER_MISSING")),
      Option.when(!boolAt("docker","enabled").contains(true))(ProfileChange("docker","DOCKER_NOT_ENABLED")),
      Option.when(!boolAt("docker","active").contains(true))(ProfileChange("docker","DOCKER_NOT_RUNNING")),
      Option.when(!boolAt("docker","composeAvailable").contains(true))(ProfileChange("docker","DOCKER_COMPOSE_MISSING"))).flatten)

    val expectedCaddy = renderedCaddyHash(desired.caddy)
    val caddyChanges = List(
      Option.when(desired.caddy.enabled && !boolAt("caddy","managed").contains(true))(ProfileChange("caddy","CADDY_CONFIG_UNMANAGED")),
      Option.when(!boolAt("caddy","installed").contains(true))(ProfileChange("caddy","CADDY_MISSING")),
      Option.when(!boolAt("caddy","enabled").contains(true))(ProfileChange("caddy","CADDY_NOT_ENABLED")),
      Option.when(!boolAt("caddy","active").contains(true))(ProfileChange("caddy","CADDY_NOT_RUNNING")),
      Option.when(desired.caddy.enabled && stringAt("caddy","managedConfigHash") != Some(expectedCaddy))(ProfileChange("caddy","CADDY_CONFIG_DRIFT")),
      Option.when(desired.caddy.enabled && (intAt("caddy","httpsPort") != Some(desired.caddy.localHttpsPort) ||
        !actual.hcursor.downField("caddy").get[List[Int]]("listeningPorts").toOption.exists(_.contains(desired.caddy.localHttpsPort))))(ProfileChange("caddy","CADDY_LISTENER_DRIFT"))).flatten
    module("caddy",desired.caddy.enabled,caddyChanges)

    val expectedSite = placeholderHash(desired.site)
    module("site",desired.site.enabled,List(
      Option.when(desired.site.enabled && !boolAt("site","managed").contains(true))(ProfileChange("site","SITE_CONTENT_UNMANAGED")),
      Option.when(desired.site.enabled && stringAt("site","managedContentHash") != Some(expectedSite))(ProfileChange("site","PLACEHOLDER_SITE_DRIFT")),
      Option.when(desired.site.enabled && !boolAt("site","rootSafe").contains(true))(ProfileChange("site","SITE_ROOT_UNSAFE"))).flatten)
    ServerProfileAssessment(modules.result(),changes.result().sortBy(c => (c.module,c.code,c.detail.getOrElse(""))))
  }

  def hashObservation(content: Json): String = sha256(canonical(content))
  def reviewedHash(assessment: ServerProfileAssessment): String = {
    val entries = assessment.changes.sortBy(c => (c.module,c.code,c.detail.getOrElse(""))).map(c =>
      Json.obj("module" -> Json.fromString(c.module),"code" -> Json.fromString(c.code),"detail" -> c.detail.fold(Json.Null)(Json.fromString),
        "before" -> c.before.fold(Json.Null)(Json.fromString),"after" -> c.after.fold(Json.Null)(Json.fromString)))
    sha256(Json.fromValues(entries).noSpaces)
  }
  def canonical(json: Json): String = json.arrayOrObject(
    json.noSpaces,
    values => values.map(canonical).sorted.mkString("[",",","]"),
    obj => Json.fromJsonObject(JsonObject.fromIterable(obj.toList.sortBy(_._1).map { case (k,v) => k -> normalize(v) })).noSpaces)
  private def normalize(json: Json): Json = json.arrayOrObject(json,
    values => Json.fromValues(values.map(normalize).sortBy(_.noSpaces)),
    obj => Json.fromJsonObject(JsonObject.fromIterable(obj.toList.sortBy(_._1).map { case (k,v) => k -> normalize(v) })))

  def renderedCaddyHash(c: CaddyModule): String = sha256(renderCaddy(c))
  def renderCaddy(c: CaddyModule): String = {
    val domain = c.domain.getOrElse("invalid.example")
    val redirect = if (c.redirect == RedirectMode.HttpToHttps) s"\nhttp://$domain {\n  redir https://$domain:${c.localHttpsPort}{uri} 308\n}\n" else "\n"
    s"# InfraDesk managed: SERVER_PROFILE CADDY v1\n{\n\thttps_port ${c.localHttpsPort}\n\tauto_https disable_redirects\n}\nhttps://$domain:${c.localHttpsPort} {\n\troot * ${c.siteRoot}\n\tencode ${c.compression}\n\tfile_server\n}\n$redirect"
  }
  def placeholderHtml(site: SiteModule): String = {
    val title = htmlEscape(site.domain.getOrElse("InfraDesk"))
    s"<!-- InfraDesk managed: SERVER_PROFILE SITE v1 -->\n<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>$title</title></head><body><main><h1>$title</h1><p>This server is managed by InfraDesk.</p></main></body></html>\n"
  }
  def placeholderHash(site: SiteModule): String = sha256(placeholderHtml(site))
  private def htmlEscape(s: String): String = s.flatMap {
    case '&' => "&amp;"; case '<' => "&lt;"; case '>' => "&gt;"; case '"' => "&quot;"; case '\'' => "&#39;"; case c => c.toString
  }
  private def sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  private def decodeRule(json: Json): Option[FirewallRule] = for {
    c <- json.asObject
    id <- c("id").flatMap(_.asString); protocol <- c("protocol").flatMap(_.asString)
    port <- c("port").flatMap(_.asNumber).flatMap(_.toInt)
    sources <- c("sources").flatMap(_.asArray).map(_.toList.flatMap(_.asString).sorted)
  } yield FirewallRule(id,protocol,port,sources)
  private def ruleSignature(rule: FirewallRule): String = s"${rule.protocol}/${rule.port}/${rule.sources.distinct.sorted.mkString(",")}"
  private def ruleDescription(rule: FirewallRule): String = s"${rule.protocol}/${rule.port} ${rule.sources.distinct.sorted.mkString(", ")}"
}

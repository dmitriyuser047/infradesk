package ru.bitec.app.ops
package domain.provisioning

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import cats.syntax.all._

/** Version 1 is deliberately a closed schema. It contains desired state, never commands or secrets. */
final case class PackageModule(enabled: Boolean, packages: List[String])
final case class NetworkModule(enabled: Boolean, bbr: Boolean, sysctl: Map[String, String])
final case class LimitsModule(enabled: Boolean, nofileSoft: Long, nofileHard: Long, systemdDefaultLimitNofile: Long)
final case class FirewallRule(id: String, protocol: String, port: Int, sources: List[String])
final case class FirewallModule(enabled: Boolean, rules: List[FirewallRule])
final case class ToggleModule(enabled: Boolean)
sealed trait RedirectMode { def code: String }
object RedirectMode {
  case object None extends RedirectMode { val code = "NONE" }
  case object HttpToHttps extends RedirectMode { val code = "HTTP_TO_HTTPS" }
  def parse(s: String): Option[RedirectMode] = List(None, HttpToHttps).find(_.code == s)
}
final case class CaddyModule(enabled: Boolean, domain: Option[String], localHttpsPort: Int, siteRoot: String,
  compression: String, redirect: RedirectMode)
final case class SiteModule(enabled: Boolean, domain: Option[String], root: String, template: String, templateVersion: Int)

final case class ServerProfileContent(schemaVersion: Int, packages: PackageModule, network: NetworkModule,
  limits: LimitsModule, firewall: FirewallModule, fail2ban: ToggleModule, docker: ToggleModule,
  caddy: CaddyModule, site: SiteModule) {
  def json: Json = ServerProfileContent.toJson(this)
  def canonical: String = json.noSpaces
  def hash: String = ServerProfileContent.sha256(canonical)
}

object ServerProfileContent {
  val MaxPackages = 64
  val MaxFirewallRules = 64
  val SysctlKeys = Set("net.core.default_qdisc", "net.ipv4.tcp_congestion_control", "net.core.somaxconn",
    "net.ipv4.tcp_max_syn_backlog", "net.ipv4.ip_local_port_range", "fs.file-max")
  private val pkg = "[a-z0-9][a-z0-9+.-]{0,127}".r
  private val ruleId = "[a-z0-9][a-z0-9_-]{0,39}".r
  private val slug = "[a-z0-9][a-z0-9-]{0,62}".r

  private def obj(fields: (String, Json)*): Json = Json.fromJsonObject(JsonObject.fromIterable(fields))
  private def str(s: String) = Json.fromString(s)
  private def bool(b: Boolean) = Json.fromBoolean(b)
  private def num(n: Long) = Json.fromLong(n)
  private def optionString(s: Option[String]) = s.fold(Json.Null)(str)

  def toJson(c: ServerProfileContent): Json = obj(
    "schemaVersion" -> num(c.schemaVersion),
    "packages" -> obj("enabled" -> bool(c.packages.enabled), "packages" -> Json.fromValues(c.packages.packages.sorted.map(str))),
    "network" -> obj("enabled" -> bool(c.network.enabled), "bbr" -> bool(c.network.bbr),
      "sysctl" -> Json.fromFields(c.network.sysctl.toList.sortBy(_._1).map { case (k,v) => k -> str(v) })),
    "limits" -> obj("enabled" -> bool(c.limits.enabled), "nofileSoft" -> num(c.limits.nofileSoft),
      "nofileHard" -> num(c.limits.nofileHard), "systemdDefaultLimitNofile" -> num(c.limits.systemdDefaultLimitNofile)),
    "firewall" -> obj("enabled" -> bool(c.firewall.enabled), "rules" -> Json.fromValues(c.firewall.rules.sortBy(_.id).map { r =>
      obj("id" -> str(r.id), "protocol" -> str(r.protocol), "port" -> num(r.port),
        "sources" -> Json.fromValues(r.sources.distinct.sorted.map(str)))
    })),
    "fail2ban" -> obj("enabled" -> bool(c.fail2ban.enabled)),
    "docker" -> obj("enabled" -> bool(c.docker.enabled)),
    "caddy" -> obj("enabled" -> bool(c.caddy.enabled), "domain" -> optionString(c.caddy.domain),
      "localHttpsPort" -> num(c.caddy.localHttpsPort), "siteRoot" -> str(c.caddy.siteRoot),
      "compression" -> str(c.caddy.compression), "redirect" -> str(c.caddy.redirect.code)),
    "site" -> obj("enabled" -> bool(c.site.enabled), "domain" -> optionString(c.site.domain),
      "root" -> str(c.site.root), "template" -> str(c.site.template), "templateVersion" -> num(c.site.templateVersion))
  )

  /** Parse with exact keys at every object level; unknown, malformed and unsafe values fail closed. */
  def parse(raw: String): Either[String, ServerProfileContent] = parse(raw,ufwUniversal=true)

  /** Immutable revisions/snapshots may still have the pre-normalization canonical hash.
    * Validate that exact representation before exposing the normalized domain content.
    */
  def parsePersisted(raw: String, contentHash: String): Either[String, ServerProfileContent] = for {
    content <- parse(raw)
    _ <- Either.cond(content.hash == contentHash ||
      parse(raw,ufwUniversal=false).exists(_.hash == contentHash),(),"PROFILE_CONTENT_HASH_INVALID")
  } yield content

  private def parse(raw: String, ufwUniversal: Boolean): Either[String, ServerProfileContent] = for {
    json <- io.circe.parser.parse(raw).left.map(_ => "PROFILE_INVALID_JSON")
    root <- exact(json, Set("schemaVersion","packages","network","limits","firewall","fail2ban","docker","caddy","site"))
    version <- int(root, "schemaVersion")
    _ <- Either.cond(version == 1, (), "PROFILE_SCHEMA_UNSUPPORTED")
    p <- exactField(root, "packages", Set("enabled","packages"))
    pe <- bool(p, "enabled"); ps <- strings(p, "packages")
    _ <- Either.cond(ps.size <= MaxPackages && ps.forall(x => pkg.matches(x)), (), "PROFILE_PACKAGES_INVALID")
    n <- exactField(root, "network", Set("enabled","bbr","sysctl"))
    ne <- bool(n, "enabled"); bbr <- bool(n, "bbr")
    sys <- field(n,"sysctl").flatMap(_.asObject.toRight("PROFILE_NETWORK_INVALID")).flatMap { o =>
      val values = o.toMap
      if (!values.keySet.subsetOf(SysctlKeys)) Left("PROFILE_SYSCTL_UNSUPPORTED")
      else values.toList.traverse { case (k,v) => v.asString.toRight("PROFILE_NETWORK_INVALID").flatMap(validateSysctl(k,_)).map(k -> _) }.map(_.toMap)
    }
    _ <- Either.cond(!bbr || !sys.get("net.ipv4.tcp_congestion_control").contains("cubic"), (), "PROFILE_SYSCTL_CONFLICT")
    l <- exactField(root, "limits", Set("enabled","nofileSoft","nofileHard","systemdDefaultLimitNofile"))
    le <- bool(l,"enabled"); soft <- long(l,"nofileSoft"); hard <- long(l,"nofileHard"); systemd <- long(l,"systemdDefaultLimitNofile")
    _ <- Either.cond(List(soft,hard,systemd).forall(x => x >= 1024 && x <= 16777216) && soft <= hard, (), "PROFILE_LIMITS_INVALID")
    f <- exactField(root,"firewall",Set("enabled","rules")); fe <- bool(f,"enabled")
    rules <- field(f,"rules").flatMap(_.asArray.toRight("PROFILE_FIREWALL_INVALID")).flatMap(_.toList.traverse(parseRule(_,ufwUniversal)))
    _ <- Either.cond(rules.size <= MaxFirewallRules && rules.map(_.id).distinct.size == rules.size, (), "PROFILE_FIREWALL_INVALID")
    fb <- exactField(root,"fail2ban",Set("enabled")); fbe <- bool(fb,"enabled")
    d <- exactField(root,"docker",Set("enabled")); de <- bool(d,"enabled")
    ca <- exactField(root,"caddy",Set("enabled","domain","localHttpsPort","siteRoot","compression","redirect"))
    cae <- bool(ca,"enabled"); domain <- optionalString(ca,"domain"); port <- int(ca,"localHttpsPort")
    siteRoot <- string(ca,"siteRoot"); compression <- string(ca,"compression"); redirectText <- string(ca,"redirect")
    _ <- Either.cond(port >= 1 && port <= 65535 && Set("gzip","zstd")(compression) && safeManagedRoot(siteRoot), (), "PROFILE_CADDY_INVALID")
    redirect <- RedirectMode.parse(redirectText).toRight("PROFILE_CADDY_INVALID")
    _ <- validateDomain(domain)
    _ <- Either.cond(!cae || domain.nonEmpty, (), "PROFILE_CADDY_DOMAIN_REQUIRED")
    s <- exactField(root,"site",Set("enabled","domain","root","template","templateVersion"))
    se <- bool(s,"enabled"); sd <- optionalString(s,"domain"); sr <- string(s,"root"); template <- string(s,"template"); tv <- int(s,"templateVersion")
    _ <- validateDomain(sd)
    _ <- Either.cond(safeManagedRoot(sr) && template == "DEFAULT_PLACEHOLDER" && tv == 1, (), "PROFILE_SITE_INVALID")
    _ <- Either.cond(!se || (cae && sd == domain && sr == siteRoot), (), "PROFILE_SITE_DEPENDENCY_INVALID")
  } yield ServerProfileContent(version, PackageModule(pe,ps.distinct.sorted), NetworkModule(ne,bbr,sys),
    LimitsModule(le,soft,hard,systemd), FirewallModule(fe,rules.sortBy(_.id)), ToggleModule(fbe), ToggleModule(de),
    CaddyModule(cae,domain,port,siteRoot,compression,redirect), SiteModule(se,sd,sr,template,tv))

  private def parseRule(j: Json, ufwUniversal: Boolean): Either[String, FirewallRule] = for {
    c <- exact(j,Set("id","protocol","port","sources"))
    id <- string(c,"id"); protocol <- string(c,"protocol"); port <- int(c,"port"); rawSources <- strings(c,"sources")
    sources <- rawSources.traverse(canonicalSource(_,ufwUniversal))
    _ <- Either.cond(ruleId.matches(id) && Set("tcp","udp")(protocol) && port >= 1 && port <= 65535 &&
      sources.nonEmpty && sources.size <= 32 && sources.distinct.size == sources.size, (), "PROFILE_FIREWALL_RULE_INVALID")
  } yield FirewallRule(id,protocol,port,sources.sorted)

  private def exact(j: Json, keys: Set[String]): Either[String, Map[String,Json]] = j.asObject.map(_.toMap)
    .toRight("PROFILE_OBJECT_REQUIRED").flatMap(m => Either.cond(m.keySet == keys,m,"PROFILE_UNKNOWN_OR_MISSING_FIELD"))
  private def exactField(m: Map[String,Json], k: String, keys: Set[String]): Either[String,Map[String,Json]] =
    m.get(k).toRight("PROFILE_FIELD_MISSING").flatMap(exact(_,keys))
  private def field(m: Map[String,Json], k: String) = m.get(k).toRight("PROFILE_FIELD_MISSING")
  private def bool(m: Map[String,Json], k: String) = field(m,k).flatMap(_.asBoolean.toRight("PROFILE_FIELD_INVALID"))
  private def string(m: Map[String,Json], k: String) = field(m,k).flatMap(_.asString.toRight("PROFILE_FIELD_INVALID"))
  private def int(m: Map[String,Json], k: String) = field(m,k).flatMap(_.asNumber.flatMap(_.toInt).toRight("PROFILE_FIELD_INVALID"))
  private def long(m: Map[String,Json], k: String) = field(m,k).flatMap(_.asNumber.flatMap(_.toLong).toRight("PROFILE_FIELD_INVALID"))
  private def strings(m: Map[String,Json], k: String) = field(m,k).flatMap(_.asArray.toRight("PROFILE_FIELD_INVALID"))
    .flatMap(_.toList.traverse(_.asString.toRight("PROFILE_FIELD_INVALID")))
  private def optionalString(m: Map[String,Json], k: String) = field(m,k).flatMap(j => if (j.isNull) Right(None) else j.asString.map(x => Some(x.toLowerCase(java.util.Locale.ROOT))).toRight("PROFILE_FIELD_INVALID"))
  private def validateSysctl(k: String, v: String): Either[String,String] = k match {
    case "net.core.default_qdisc" => Either.cond(Set("fq","fq_codel","pfifo_fast")(v),v,"PROFILE_SYSCTL_INVALID")
    case "net.ipv4.tcp_congestion_control" => Either.cond(Set("bbr","cubic")(v),v,"PROFILE_SYSCTL_INVALID")
    case "net.ipv4.ip_local_port_range" =>
      val range = if (v.matches("(?:0|[1-9][0-9]{0,4}) (?:0|[1-9][0-9]{0,4})")) v.split(" ").map(_.toInt).toList else Nil
      Either.cond(range match { case List(a,b) => a >= 1024 && b <= 65535 && a < b; case _ => false },v,"PROFILE_SYSCTL_INVALID")
    case "net.core.somaxconn" | "net.ipv4.tcp_max_syn_backlog" => numeric(v, 1, 1048576)
    case "fs.file-max" => numeric(v, 1, 16777216)
    case _ => Either.left("PROFILE_SYSCTL_INVALID")
  }
  private def numeric(v: String, min: Long, max: Long): Either[String,String] =
    Either.cond(v.matches("(?:0|[1-9][0-9]{0,8})") && scala.util.Try(v.toLong).toOption.exists(n => n >= min && n <= max),v,"PROFILE_SYSCTL_INVALID")
  private def validateDomain(d: Option[String]): Either[String,Unit] = Either.cond(d.forall(x =>
    x.length <= 253 && x.matches("(?i)(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")),(),"PROFILE_DOMAIN_INVALID")
  private def safeManagedRoot(root: String): Boolean = root.matches("/var/www/infradesk/[a-z0-9][a-z0-9-]{0,62}") && !root.contains("..")
  private def canonicalSource(s: String, ufwUniversal: Boolean = true): Either[String,String] = {
    if (s == "ANY") Right(s)
    else if (s.contains("%")) Left("PROFILE_FIREWALL_RULE_INVALID")
    else {
      val p = s.split("/",-1)
      if (p.length > 2) Left("PROFILE_FIREWALL_RULE_INVALID")
      else {
        val ip = p(0)
        val v4 = ip.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}") && ip.split("\\.").forall(x => x.toInt <= 255)
        val v6 = validV6(ip)
        if (!v4 && !v6) Left("PROFILE_FIREWALL_RULE_INVALID")
        else {
          val bits = if (v4) 32 else 128
          val prefix = if (p.length == 1) bits.toString else p(1)
          if (!prefix.matches("0|[1-9][0-9]{0,2}") || prefix.toInt > bits) Left("PROFILE_FIREWALL_RULE_INVALID")
          else {
            val bytes = if (v4) ip.split("\\.").map(_.toInt.toByte) else parseV6(ip)
            val mask = prefix.toInt
            val network = bytes.zipWithIndex.map { case (b,i) =>
              val used = (mask - i * 8).max(0).min(8)
              ((b & 0xff) & (if (used == 0) 0 else (0xff << (8 - used)) & 0xff)).toByte
            }
            val address = if (v4) network.map(_ & 0xff).mkString(".")
              else network.grouped(2).map(g => f"${((g(0) & 0xff) << 8) | (g(1) & 0xff)}%x").mkString(":")
            // UFW show added renders IPv4 /0 as its short, source-less form (ANY).
            // ANY is the managed UFW command representation, not a family-specific CIDR:
            // applying it uses `from any`. Keep IPv6 /0 distinct; short output cannot
            // prove an IPv6-only rule. Literal SSH coverage retains address families.
            Right(if (ufwUniversal && v4 && mask == 0) "ANY" else s"$address/${prefix.toInt}")
          }
        }
      }
    }
  }
  private def parseV6(ip: String): Array[Byte] = {
    val parts = ip.toLowerCase(java.util.Locale.ROOT).split("::",-1)
    val left = if (parts(0).isEmpty) List.empty[String] else parts(0).split(":",-1).toList
    val right = if (parts.length < 2 || parts(1).isEmpty) List.empty[String] else parts(1).split(":",-1).toList
    val all = if (parts.length == 1) left else left ++ List.fill(8 - left.size - right.size)("0") ++ right
    require(all.size == 8 && all.forall(_.nonEmpty))
    all.flatMap(x => { val n = Integer.parseInt(x,16); List((n >> 8).toByte,n.toByte) }).toArray
  }
  private def validV6(ip: String): Boolean = try {
    if (!ip.contains(":") || !ip.matches("[0-9a-fA-F:]+") || ip.contains(":::")) false
    else {
      val count = ip.sliding(2).count(_ == "::")
      if (count > 1) false
      else {
        val groups = ip.split("::",-1)
        val left = if (groups(0).isEmpty) List.empty[String] else groups(0).split(":",-1).toList
        val right = if (groups.length < 2 || groups(1).isEmpty) List.empty[String] else groups(1).split(":",-1).toList
        val all = if (groups.length == 1) left else left ++ List.fill(8-left.size-right.size)("0") ++ right
        val shape = (groups.length == 1 && left.size == 8) || (groups.length == 2 && left.size+right.size < 8)
        shape && all.size == 8 && all.forall(x => x.matches("[0-9a-fA-F]{1,4}"))
      }
    }
  } catch { case _: Exception => false }
  def isCanonicalSource(s: String): Boolean = canonicalSource(s).exists(_ == s)
  def canonicalFirewallSource(s: String): Either[String,String] = canonicalSource(s)
  /** Literal addresses only: firewall safety checks never resolve a hostname. */
  def sourceCovers(source: String, host: String): Boolean = {
    if (source == "ANY") canonicalSource(host).isRight
    else (canonicalSource(source,ufwUniversal=false),canonicalSource(host,ufwUniversal=false)) match {
      case (Right(network),Right(address)) if network.contains(":") == address.contains(":") =>
        def bytes(s: String): Array[Byte] = if (s.contains(":")) parseV6(s.takeWhile(_ != '/'))
          else s.takeWhile(_ != '/').split("\\.").map(_.toInt.toByte)
        val n=bytes(network); val a=bytes(address); val prefix=network.split("/")(1).toInt
        n.indices.forall { i => val used=(prefix-i*8).max(0).min(8)
          val mask=if(used==0) 0 else (0xff << (8-used))&0xff
          (n(i)&mask)==(a(i)&mask) }
      case _ => false
    }
  }
  private def sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
}

/** Closed JSON shape for observations. Remote output is reduced to these allowlisted facts before persistence. */
object ServerProfileObservationCodec {
  private val PackageName = "[a-z0-9][a-z0-9+.-]{0,127}"
  private val Hash = "[0-9a-f]{64}"
  def validate(json: Json): Either[String,Json] = {
    def exact(value: Option[Json], keys: Set[String]): Either[String,Json] = value.filter(_.asObject.exists(_.keys.toSet == keys)).toRight("PROVISIONING_OBSERVATION_INVALID")
    def child(parent: Json, key: String) = parent.hcursor.downField(key).focus
    def isBool(value: Json, key: String): Boolean = value.hcursor.get[Boolean](key).isRight
    def hashOrNull(value: Json, key: String): Boolean = value.hcursor.get[Option[String]](key).exists(_.forall(_.matches(Hash)))
    def allBools(value: Json, keys: List[String]): Boolean = keys.forall(isBool(value,_))
    def validMap(value: Json, key: String): Boolean = child(value,key).exists { j =>
      j.asObject.exists(o => o.keys.forall(ServerProfileContent.SysctlKeys.contains) &&
        o.values.forall(_.asString.exists(s => s.length <= 128 && s.matches("[A-Za-z0-9_.:+ -]*"))))
    }
    for {
      _ <- Either.cond(json.asObject.exists(_.keys.toSet == Set("packages","network","limits","firewall","fail2ban","docker","site","caddy")),(),"PROVISIONING_OBSERVATION_INVALID")
      packages <- exact(child(json,"packages"),Set("installed"))
      _ <- Either.cond(packages.hcursor.get[List[String]]("installed").exists(xs => xs.size <= 128 && xs.distinct == xs && xs.forall(_.matches(PackageName))),(),"PROVISIONING_OBSERVATION_INVALID")
      network <- exact(child(json,"network"),Set("sysctl","managedSysctl","managedFile","managedFileHash","bbr","managedBbr"))
      _ <- Either.cond(validMap(network,"sysctl") && validMap(network,"managedSysctl") && allBools(network,List("managedFile","bbr","managedBbr")) && hashOrNull(network,"managedFileHash"),(),"PROVISIONING_OBSERVATION_INVALID")
      limits <- exact(child(json,"limits"),Set("managedNofileSoft","managedNofileHard","systemdDefaultLimitNofile","managedFile","managedFileHash","managedSystemdDropin","managedSystemdDropinHash"))
      _ <- Either.cond(Set("managedNofileSoft","managedNofileHard","systemdDefaultLimitNofile").forall(k => limits.hcursor.get[Option[String]](k).exists(_.forall(_.matches("(?:0|[1-9][0-9]{0,7})(?::(?:0|[1-9][0-9]{0,7}))?")))) && allBools(limits,List("managedFile","managedSystemdDropin")) && hashOrNull(limits,"managedFileHash") && hashOrNull(limits,"managedSystemdDropinHash"),(),"PROVISIONING_OBSERVATION_INVALID")
      firewall <- exact(child(json,"firewall"),Set("enabled","managedRules","foreignEquivalentAllows","sshSource","sshServer","sshPort"))
      _ <- Either.cond(List("sshSource","sshServer").forall(k => firewall.hcursor.get[Option[String]](k).exists(_.forall(s => ServerProfileContent.canonicalFirewallSource(s).isRight))) &&
        child(firewall,"sshPort").exists(j => j.isNull || j.asNumber.flatMap(_.toInt).exists(p => p>=1 && p<=65535)),(),"PROVISIONING_OBSERVATION_INVALID")
      _ <- Either.cond(isBool(firewall,"enabled") && List("managedRules","foreignEquivalentAllows").forall(k => firewall.hcursor.get[List[Json]](k).exists(xs => xs.size <= ServerProfileContent.MaxFirewallRules && xs.forall(validRule))),(),"PROVISIONING_OBSERVATION_INVALID")
      fail <- exact(child(json,"fail2ban"),Set("installed","enabled","active","managedFile","managedFileHash"))
      docker <- exact(child(json,"docker"),Set("installed","enabled","active","composeAvailable"))
      _ <- Either.cond(allBools(fail,List("installed","enabled","active","managedFile")) && hashOrNull(fail,"managedFileHash") && allBools(docker,List("installed","enabled","active","composeAvailable")),(),"PROVISIONING_OBSERVATION_INVALID")
      site <- exact(child(json,"site"),Set("managedContentHash","rootSafe","managed"))
      caddy <- exact(child(json,"caddy"),Set("installed","enabled","active","managedConfigHash","managed","httpsPort","listeningPorts"))
      _ <- Either.cond(hashOrNull(site,"managedContentHash") && allBools(site,List("rootSafe","managed")) && allBools(caddy,List("installed","enabled","active","managed")) && hashOrNull(caddy,"managedConfigHash") &&
        child(caddy,"httpsPort").exists(j => j.isNull || j.asNumber.flatMap(_.toInt).exists(p => p>=1 && p<=65535)) &&
        child(caddy,"listeningPorts").flatMap(_.asArray).exists(xs => xs.size<=5 && xs.distinct.size==xs.size &&
          xs.forall(_.asNumber.flatMap(_.toInt).exists(p => p>=1 && p<=65535))),(),"PROVISIONING_OBSERVATION_INVALID")
    } yield json
  }
  private def validRule(json: Json): Boolean = json.asObject.exists { o =>
    o.keys.toSet == Set("id","protocol","port","sources") &&
      o("id").flatMap(_.asString).exists(_.matches("[a-z0-9][a-z0-9_-]{0,39}")) &&
      o("protocol").flatMap(_.asString).exists(Set("tcp","udp")) &&
      o("port").flatMap(_.asNumber).flatMap(_.toInt).exists(p => p>=1 && p<=65535) &&
      o("sources").flatMap(_.asArray).exists(xs => xs.nonEmpty && xs.size <= 32 && xs.distinct.size == xs.size && xs.forall(_.asString.exists(ServerProfileContent.isCanonicalSource)))
  }
}

final case class ServerProfile(id: java.util.UUID, organizationId: java.util.UUID, name: String, code: String,
  description: Option[String], archived: Boolean, latestRevision: Int, createdBy: java.util.UUID,
  createdAt: java.time.Instant, updatedAt: java.time.Instant)
final case class ServerProfileRevision(id: java.util.UUID, organizationId: java.util.UUID, profileId: java.util.UUID,
  number: Int, content: ServerProfileContent, contentHash: String, createdBy: java.util.UUID, createdAt: java.time.Instant)
final case class ServerProfileAssignment(id: java.util.UUID, organizationId: java.util.UUID, resourceId: java.util.UUID,
  profileId: java.util.UUID, revisionId: java.util.UUID, revisionNumber: Int, version: Long,
  assignedBy: java.util.UUID, assignedAt: java.time.Instant)
final case class ServerProfileObservation(id: java.util.UUID, organizationId: java.util.UUID, resourceId: java.util.UUID,
  sourceConnectionId: java.util.UUID, sourceUpdatedAt: java.time.Instant, assignmentId: Option[java.util.UUID],
  assignmentVersion: Option[Long], revisionId: Option[java.util.UUID], content: Json, contentHash: String,
  observedAt: java.time.Instant, verifiedRunId: Option[java.util.UUID] = None)

package ru.bitec.app.ops
package integration.remnawave

import cats.syntax.all._
import domain.configuration.CanonicalJson
import domain.integration._
import io.circe.{ACursor, HCursor, Json}
import io.circe.parser.parse

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Decodes Remnawave inventory responses into sanitized projections, strictly against the
  * upstream contract (`NodesSchema`, `HostsSchema`, `ConfigProfileSchema`).
  *
  * Only named fields are read. Everything else a response carries — a node's proxy URL, a profile's
  * Xray configuration, an inbound's raw definition, a host's transport parameters — is never
  * extracted, so it cannot reach the database, a log or an error.
  *
  * Contract drift is never absorbed: a required field that is missing or of the wrong type, a
  * nullable field that is missing, an identity that is not a UUID, or a list with one bad item
  * makes the whole listing invalid. Nothing is defaulted and no list is shortened; size is bounded
  * by the response byte and object limits instead.
  */
object RemnawaveInventory {
  private val MaxText = 255

  def nodes(body: String): Option[List[ObservedIntegrationObject]] =
    responseArray(body, None).flatMap(_.traverse(node))

  def hosts(body: String): Option[List[ObservedIntegrationObject]] =
    responseArray(body, None).flatMap(_.traverse(host))

  def configProfiles(body: String): Option[List[ObservedIntegrationObject]] =
    responseArray(body, Some("configProfiles")).flatMap(_.traverse(configProfile))

  /** `{ "response": [...] }`, or `{ "response": { <field>: [...] } }` for wrapped listings. */
  private def responseArray(body: String, nested: Option[String]): Option[List[HCursor]] =
    parse(body).toOption.flatMap { document =>
      val response = document.hcursor.downField("response")
      val array = nested.fold(response.focus)(field => response.downField(field).focus)
      array.flatMap(_.asArray).map(_.toList.map(_.hcursor))
    }

  private def node(c: HCursor): Option[ObservedIntegrationObject] = for {
    id <- uuid(c.downField("uuid"))
    name <- string(c.downField("name"))
    address <- string(c.downField("address"))
    port <- nullable(c.downField("port"))(int)
    connected <- bool(c.downField("isConnected"))
    connecting <- bool(c.downField("isConnecting"))
    disabled <- bool(c.downField("isDisabled"))
    lastStatusChange <- nullable(c.downField("lastStatusChange"))(instant)
    tracking <- bool(c.downField("isTrafficTrackingActive"))
    limit <- nullable(c.downField("trafficLimitBytes"))(count)
    used <- nullable(c.downField("trafficUsedBytes"))(count)
    country <- string(c.downField("countryCode"))
    tags <- list(c.downField("tags"))(string)
    activeProfile <- nullable(c.downField("configProfile").downField("activeConfigProfileUuid"))(uuid)
    activeInbounds <- list(c.downField("configProfile").downField("activeInbounds"))(inbound =>
      uuid(inbound.downField("uuid"))).filter(ids => ids.distinct.size == ids.size)
    providerUuid <- nullable(c.downField("providerUuid"))(uuid)
    providerName <- nullable(c.downField("provider"))(provider => string(provider.downField("name")))
    versions <- nullable(c.downField("versions"))(v =>
      (string(v.downField("xray")), string(v.downField("node"))).tupled)
    system <- nullable(c.downField("system"))(s => {
      val info = s.downField("info")
      (int(info.downField("cpus")), string(info.downField("cpuModel")), count(info.downField("memoryTotal"))).tupled
    })
    uptime <- count(c.downField("xrayUptime"))
    online <- count(c.downField("usersOnline"))
  } yield ObservedIntegrationObject(IntegrationObjectType.Node, id, name, RemnawaveNodeSummary(
    address = address,
    port = port,
    isConnected = connected,
    isConnecting = connecting,
    isDisabled = disabled,
    lastStatusChange = lastStatusChange,
    xrayVersion = versions.map(_._1),
    nodeVersion = versions.map(_._2),
    xrayUptimeSeconds = uptime,
    trafficTrackingActive = tracking,
    trafficLimitBytes = limit,
    trafficUsedBytes = used,
    usersOnline = online,
    countryCode = country,
    cpuCount = system.map(_._1),
    cpuModel = system.map(_._2),
    memoryTotalBytes = system.map(_._3),
    activeConfigProfileUuid = activeProfile,
    tags = tags,
    providerUuid = providerUuid,
    providerName = providerName,
    activeInboundIds = Some(activeInbounds)
  ))

  private def host(c: HCursor): Option[ObservedIntegrationObject] = for {
    id <- uuid(c.downField("uuid"))
    remark <- string(c.downField("remark"))
    address <- string(c.downField("address"))
    port <- int(c.downField("port"))
    disabled <- bool(c.downField("isDisabled"))
    hidden <- bool(c.downField("isHidden"))
    profile <- nullable(c.downField("inbound").downField("configProfileUuid"))(uuid)
    inbound <- nullable(c.downField("inbound").downField("configProfileInboundUuid"))(uuid)
    nodes <- list(c.downField("nodes"))(uuid)
    tags <- list(c.downField("tags"))(string)
    security <- string(c.downField("securityLayer"))
    description <- nullable(c.downField("serverDescription"))(string)
  } yield ObservedIntegrationObject(IntegrationObjectType.Host, id, remark, RemnawaveHostSummary(
    address, port, disabled, hidden, profile, inbound, nodes, tags, security, description))

  private def configProfile(c: HCursor): Option[ObservedIntegrationObject] = for {
    id <- uuid(c.downField("uuid"))
    name <- string(c.downField("name"))
    position <- int(c.downField("viewPosition"))
    created <- instant(c.downField("createdAt"))
    updated <- instant(c.downField("updatedAt"))
    nodes <- list(c.downField("nodes"))(node => uuid(node.downField("uuid")))
    inbounds <- list(c.downField("inbounds"))(inboundOf)
    config <- c.downField("config").focus.filter(_.isObject)
  } yield ObservedIntegrationObject(IntegrationObjectType.ConfigProfile, id, name,
    RemnawaveConfigProfileSummary(position, created, updated, nodes, inbounds,
      Some(CanonicalJson.sha256(config))))

  // Only identity and addressing of an inbound; its raw definition is never read.
  private def inboundOf(c: ACursor): Option[RemnawaveInboundSummary] = for {
    id <- uuid(c.downField("uuid"))
    tag <- string(c.downField("tag"))
    kind <- string(c.downField("type"))
    network <- nullable(c.downField("network"))(string)
    security <- nullable(c.downField("security"))(string)
    port <- nullable(c.downField("port"))(int)
  } yield RemnawaveInboundSummary(id, tag, kind, network, security, port)

  /** A canonical UUID, stored in its lower-case canonical form. Anything else is not an identity. */
  private[remnawave] def uuid(c: ACursor): Option[String] = c.focus.flatMap(_.asString).flatMap { value =>
    val lower = value.toLowerCase(java.util.Locale.ROOT)
    Try(UUID.fromString(value)).toOption.map(_.toString).filter(_ == lower)
  }

  /** A JSON string, shown as text: control characters removed, bounded for display. */
  private def string(c: ACursor): Option[String] =
    c.focus.flatMap(_.asString).map(_.filterNot(_.isControl).trim.take(MaxText))

  private def bool(c: ACursor): Option[Boolean] = c.focus.flatMap(_.asBoolean)

  /** A JSON integer in `Int` range. */
  private def int(c: ACursor): Option[Int] = c.focus.flatMap(_.asNumber).flatMap(_.toInt)

  /** A non-negative JSON number counted in whole units (bytes, seconds, users); a fraction is dropped. */
  private def count(c: ACursor): Option[Long] = c.focus.flatMap(_.asNumber).flatMap(_.toBigDecimal)
    .filter(value => value >= 0 && value <= BigDecimal(Long.MaxValue))
    .map(_.setScale(0, BigDecimal.RoundingMode.FLOOR).toLong)

  private def instant(c: ACursor): Option[Instant] =
    c.focus.flatMap(_.asString).flatMap(value => Try(Instant.parse(value)).toOption)

  /** Present and null is None; present with a valid value is Some; absent or invalid rejects. */
  private def nullable[A](c: ACursor)(read: ACursor => Option[A]): Option[Option[A]] = c.focus match {
    case None => None
    case Some(json) if json.isNull => Some(None)
    case Some(_) => read(c).map(Some(_))
  }

  /** Every item must be valid; the list is kept whole. */
  private def list[A](c: ACursor)(read: ACursor => Option[A]): Option[List[A]] =
    c.focus.flatMap(_.asArray).flatMap(_.toList.traverse((item: Json) => read(item.hcursor)))
}

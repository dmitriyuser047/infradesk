package ru.bitec.app.ops
package integration.remnawave

import cats.syntax.all._
import domain.integration._
import io.circe.{ACursor, HCursor, Json}
import io.circe.parser.parse

import java.time.Instant
import scala.util.Try

/** Decodes Remnawave inventory responses into sanitized projections.
  *
  * Only named fields are read. Everything else a response carries — a node's proxy URL, a profile's
  * Xray configuration, an inbound's raw definition, a host's transport parameters — is never
  * extracted, so it cannot reach the database, a log or an error. A required field that is missing
  * or of the wrong type makes the whole response invalid rather than half-understood.
  */
object RemnawaveInventory {
  private val MaxText = 255
  private val MaxId = 128
  private val MaxListItems = 200

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
      val array = response.focus.flatMap(_.asArray).orElse(nested.flatMap(field =>
        response.downField(field).focus.flatMap(_.asArray)))
      array.map(_.toList.map(_.hcursor))
    }

  private def node(c: HCursor): Option[ObservedIntegrationObject] = for {
    uuid <- id(c.downField("uuid"))
    name <- text(c.downField("name"))
    address <- text(c.downField("address"))
  } yield {
    val configProfile = c.downField("configProfile")
    val provider = c.downField("provider")
    ObservedIntegrationObject(IntegrationObjectType.Node, uuid, name, RemnawaveNodeSummary(
      address = address,
      port = int(c.downField("port")),
      isConnected = bool(c.downField("isConnected")),
      isConnecting = bool(c.downField("isConnecting")),
      isDisabled = bool(c.downField("isDisabled")),
      lastStatusChange = instant(c.downField("lastStatusChange")),
      xrayVersion = text(c.downField("xrayVersion")),
      nodeVersion = text(c.downField("nodeVersion")),
      xrayUptimeSeconds = long(c.downField("xrayUptime")),
      trafficTrackingActive = bool(c.downField("isTrafficTrackingActive")),
      trafficLimitBytes = long(c.downField("trafficLimitBytes")),
      trafficUsedBytes = long(c.downField("trafficUsedBytes")),
      usersOnline = long(c.downField("usersOnline")),
      countryCode = text(c.downField("countryCode")),
      cpuCount = int(c.downField("cpuCount")),
      cpuModel = text(c.downField("cpuModel")),
      totalRam = text(c.downField("totalRam")),
      activeConfigProfileUuid = id(configProfile.downField("activeConfigProfileUuid"))
        .orElse(id(c.downField("activeConfigProfileUuid"))),
      tags = strings(c.downField("tags")),
      providerUuid = id(c.downField("providerUuid")).orElse(id(provider.downField("uuid"))),
      providerName = text(provider.downField("name"))
    ))
  }

  private def host(c: HCursor): Option[ObservedIntegrationObject] = for {
    uuid <- id(c.downField("uuid"))
    remark <- text(c.downField("remark"))
    address <- text(c.downField("address"))
  } yield {
    val inbound = c.downField("inbound")
    val tags = strings(c.downField("tags")) match {
      case Nil => text(c.downField("tag")).toList
      case values => values
    }
    ObservedIntegrationObject(IntegrationObjectType.Host, uuid, remark, RemnawaveHostSummary(
      address = address,
      port = int(c.downField("port")),
      isDisabled = bool(c.downField("isDisabled")),
      isHidden = bool(c.downField("isHidden")),
      configProfileUuid = id(inbound.downField("configProfileUuid")),
      configProfileInboundUuid = id(inbound.downField("configProfileInboundUuid")),
      nodeUuids = references(c.downField("nodes")),
      tags = tags,
      securityLayer = text(c.downField("securityLayer")),
      serverDescription = text(c.downField("serverDescription"))
    ))
  }

  private def configProfile(c: HCursor): Option[ObservedIntegrationObject] = for {
    uuid <- id(c.downField("uuid"))
    name <- text(c.downField("name"))
    inbounds <- c.downField("inbounds").focus match {
      case None => Some(Nil)
      case Some(json) if json.isNull => Some(Nil)
      case Some(json) => json.asArray.flatMap(_.toList.take(MaxListItems).traverse(value => inboundOf(value.hcursor)))
    }
  } yield ObservedIntegrationObject(IntegrationObjectType.ConfigProfile, uuid, name, RemnawaveConfigProfileSummary(
    viewPosition = int(c.downField("viewPosition")),
    createdAt = instant(c.downField("createdAt")),
    updatedAt = instant(c.downField("updatedAt")),
    nodeUuids = references(c.downField("nodes")),
    inbounds = inbounds
  ))

  // Only identity and addressing of an inbound; its raw definition is never read.
  private def inboundOf(c: HCursor): Option[RemnawaveInboundSummary] = for {
    uuid <- id(c.downField("uuid"))
    tag <- text(c.downField("tag"))
  } yield RemnawaveInboundSummary(uuid, tag, text(c.downField("type")), text(c.downField("network")),
    text(c.downField("security")), int(c.downField("port")))

  private def id(c: ACursor): Option[String] =
    c.focus.flatMap(_.asString).map(_.trim).filter(value => value.nonEmpty && value.length <= MaxId && !value.exists(_.isControl))

  private def text(c: ACursor): Option[String] =
    c.focus.flatMap(json => json.asString.orElse(json.asNumber.map(_.toString)))
      .map(value => value.filterNot(_.isControl).trim.take(MaxText)).filter(_.nonEmpty)

  private def bool(c: ACursor): Boolean = c.focus.flatMap(_.asBoolean).getOrElse(false)

  private def int(c: ACursor): Option[Int] = long(c).filter(_ <= Int.MaxValue).map(_.toInt)

  /** A non-negative whole number, given as a JSON number or a numeric string. */
  private def long(c: ACursor): Option[Long] = c.focus.flatMap { json =>
    json.asNumber.flatMap(_.toLong).orElse(json.asString.flatMap(value => Try(BigDecimal(value.trim)).toOption
      .filter(_.isValidLong).map(_.toLong)))
  }.filter(_ >= 0)

  private def instant(c: ACursor): Option[Instant] =
    c.focus.flatMap(_.asString).flatMap(value => Try(Instant.parse(value)).toOption)

  private def strings(c: ACursor): List[String] =
    c.focus.flatMap(_.asArray).map(_.toList.flatMap(_.asString).map(_.filterNot(_.isControl).trim.take(64))
      .filter(_.nonEmpty).take(MaxListItems)).getOrElse(Nil)

  /** Related objects listed either as UUID strings or as objects carrying a `uuid`. */
  private def references(c: ACursor): List[String] =
    c.focus.flatMap(_.asArray).map(_.toList.flatMap { json: Json =>
      json.asString.orElse(json.hcursor.downField("uuid").focus.flatMap(_.asString))
    }.map(_.trim).filter(value => value.nonEmpty && value.length <= MaxId).distinct.take(MaxListItems)).getOrElse(Nil)
}

package ru.bitec.app.ops
package serialization.integration

import cats.syntax.all._
import domain.integration._
import io.circe.{Decoder, HCursor, Json}
import io.circe.syntax._

/** The stored and published shape of a sanitized summary. It holds only the typed projection's
  * fields; there is no place in it for raw provider data.
  */
object IntegrationSummaryJson {
  val Version = 1

  def encode(summary: IntegrationObjectSummary): Json = summary match {
    case node: RemnawaveNodeSummary => Json.obj(
      "address" -> node.address.asJson,
      "port" -> node.port.asJson,
      "state" -> node.state.code.asJson,
      "isConnected" -> node.isConnected.asJson,
      "isConnecting" -> node.isConnecting.asJson,
      "isDisabled" -> node.isDisabled.asJson,
      "lastStatusChange" -> node.lastStatusChange.map(_.toString).asJson,
      "xrayVersion" -> node.xrayVersion.asJson,
      "nodeVersion" -> node.nodeVersion.asJson,
      "xrayUptimeSeconds" -> node.xrayUptimeSeconds.asJson,
      "trafficTrackingActive" -> node.trafficTrackingActive.asJson,
      "trafficLimitBytes" -> node.trafficLimitBytes.asJson,
      "trafficUsedBytes" -> node.trafficUsedBytes.asJson,
      "usersOnline" -> node.usersOnline.asJson,
      "countryCode" -> node.countryCode.asJson,
      "cpuCount" -> node.cpuCount.asJson,
      "cpuModel" -> node.cpuModel.asJson,
      "memoryTotalBytes" -> node.memoryTotalBytes.asJson,
      "activeConfigProfileUuid" -> node.activeConfigProfileUuid.asJson,
      "tags" -> node.tags.asJson,
      "providerUuid" -> node.providerUuid.asJson,
      "providerName" -> node.providerName.asJson)
    case host: RemnawaveHostSummary => Json.obj(
      "address" -> host.address.asJson,
      "port" -> host.port.asJson,
      "isDisabled" -> host.isDisabled.asJson,
      "isHidden" -> host.isHidden.asJson,
      "configProfileUuid" -> host.configProfileUuid.asJson,
      "configProfileInboundUuid" -> host.configProfileInboundUuid.asJson,
      "nodeUuids" -> host.nodeUuids.asJson,
      "tags" -> host.tags.asJson,
      "securityLayer" -> host.securityLayer.asJson,
      "serverDescription" -> host.serverDescription.asJson)
    case profile: RemnawaveConfigProfileSummary => Json.obj(
      "viewPosition" -> profile.viewPosition.asJson,
      "createdAt" -> profile.createdAt.toString.asJson,
      "updatedAt" -> profile.updatedAt.toString.asJson,
      "nodeUuids" -> profile.nodeUuids.asJson,
      "inbounds" -> profile.inbounds.map(inbound => Json.obj(
        "uuid" -> inbound.uuid.asJson,
        "tag" -> inbound.tag.asJson,
        "type" -> inbound.inboundType.asJson,
        "network" -> inbound.network.asJson,
        "security" -> inbound.security.asJson,
        "port" -> inbound.port.asJson)).asJson)
  }

  def decode(objectType: IntegrationObjectType, json: Json): Decoder.Result[IntegrationObjectSummary] = {
    val c = json.hcursor
    objectType match {
      case IntegrationObjectType.Node => node(c)
      case IntegrationObjectType.Host => host(c)
      case IntegrationObjectType.ConfigProfile => profile(c)
    }
  }

  private def node(c: HCursor): Decoder.Result[IntegrationObjectSummary] = for {
    address <- c.get[String]("address")
    port <- c.get[Option[Int]]("port")
    connected <- c.get[Boolean]("isConnected")
    connecting <- c.get[Boolean]("isConnecting")
    disabled <- c.get[Boolean]("isDisabled")
    lastStatusChange <- c.get[Option[java.time.Instant]]("lastStatusChange")
    xray <- c.get[Option[String]]("xrayVersion")
    nodeVersion <- c.get[Option[String]]("nodeVersion")
    uptime <- c.get[Long]("xrayUptimeSeconds")
    tracking <- c.get[Boolean]("trafficTrackingActive")
    limit <- c.get[Option[Long]]("trafficLimitBytes")
    used <- c.get[Option[Long]]("trafficUsedBytes")
    online <- c.get[Long]("usersOnline")
    country <- c.get[String]("countryCode")
    cpuCount <- c.get[Option[Int]]("cpuCount")
    cpuModel <- c.get[Option[String]]("cpuModel")
    ram <- c.get[Option[Long]]("memoryTotalBytes")
    profile <- c.get[Option[String]]("activeConfigProfileUuid")
    tags <- c.get[List[String]]("tags")
    providerUuid <- c.get[Option[String]]("providerUuid")
    providerName <- c.get[Option[String]]("providerName")
  } yield RemnawaveNodeSummary(address, port, connected, connecting, disabled, lastStatusChange, xray, nodeVersion,
    uptime, tracking, limit, used, online, country, cpuCount, cpuModel, ram, profile, tags, providerUuid, providerName)

  private def host(c: HCursor): Decoder.Result[IntegrationObjectSummary] = for {
    address <- c.get[String]("address")
    port <- c.get[Int]("port")
    disabled <- c.get[Boolean]("isDisabled")
    hidden <- c.get[Boolean]("isHidden")
    profile <- c.get[Option[String]]("configProfileUuid")
    inbound <- c.get[Option[String]]("configProfileInboundUuid")
    nodes <- c.get[List[String]]("nodeUuids")
    tags <- c.get[List[String]]("tags")
    security <- c.get[String]("securityLayer")
    description <- c.get[Option[String]]("serverDescription")
  } yield RemnawaveHostSummary(address, port, disabled, hidden, profile, inbound, nodes, tags, security, description)

  private def profile(c: HCursor): Decoder.Result[IntegrationObjectSummary] = for {
    position <- c.get[Int]("viewPosition")
    created <- c.get[java.time.Instant]("createdAt")
    updated <- c.get[java.time.Instant]("updatedAt")
    nodes <- c.get[List[String]]("nodeUuids")
    inbounds <- c.downField("inbounds").values.toList.flatten.traverse { value =>
      val i = value.hcursor
      for {
        uuid <- i.get[String]("uuid")
        tag <- i.get[String]("tag")
        kind <- i.get[String]("type")
        network <- i.get[Option[String]]("network")
        security <- i.get[Option[String]]("security")
        port <- i.get[Option[Int]]("port")
      } yield RemnawaveInboundSummary(uuid, tag, kind, network, security, port)
    }
  } yield RemnawaveConfigProfileSummary(position, created, updated, nodes, inbounds)
}

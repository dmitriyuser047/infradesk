package ru.bitec.app.ops
package integration.remnawave

import domain.integration._
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import java.util.UUID
import scala.util.Try

/** Generation differences stay here. Optional provider fields do not create a new adapter. */
private[remnawave] sealed abstract class RemnawaveNodeApi(val code: String, credentialField: String) {
  def installationData(body: String): Option[NodeInstallationData] = parse(body).toOption.flatMap(
    _.hcursor.downField("response").get[String](credentialField).toOption)
    .filter(value => value.nonEmpty && value.length <= 64 * 1024 && !value.exists(_.isControl))
    .filter(validCredentialPayload)
    .map(NodeInstallationData.fromSecretKey)

  private def validCredentialPayload(value: String): Boolean = Try {
    val bytes = java.util.Base64.getDecoder.decode(value)
    val json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    parse(json).toOption.exists { document =>
      List("nodeCertPem", "nodeKeyPem", "caCertPem", "jwtPublicKey").forall(field =>
        document.hcursor.get[String](field).toOption.exists(v => v.nonEmpty && v.length <= 16 * 1024))
    }
  }.getOrElse(false)
}

private[remnawave] object RemnawaveNodeApi {
  case object ProfilePubKey extends RemnawaveNodeApi("PROFILE_PUBKEY", "pubKey")
  case object ProfileSecretKey extends RemnawaveNodeApi("PROFILE_SECRET_KEY", "secretKey")
  val all: List[RemnawaveNodeApi] = List(ProfilePubKey, ProfileSecretKey)

  final case class Release(version: String, commit: String, adapter: RemnawaveNodeApi,addressUpdate: Boolean = false,
    protocolProfileCreate: Boolean = false)
  // This catalog is release evidence, not branching on every patch. Unreviewed releases are absent.
  lazy val releases: Map[String, Release] = {
    val stream = Option(getClass.getResourceAsStream("/integration/remnawave/node-api-releases.json"))
      .getOrElse(throw new IllegalStateException("Remnawave compatibility catalog is missing"))
    val text = try scala.io.Source.fromInputStream(stream, "UTF-8").mkString finally stream.close()
    val rows = parse(text).toOption.flatMap(_.asArray).getOrElse(
      throw new IllegalStateException("Remnawave compatibility catalog is invalid"))
    rows.map { row =>
      val c = row.hcursor
      val release = for {
        version <- c.get[String]("version").toOption
        commit <- c.get[String]("commit").toOption.filter(_.matches("[0-9a-f]{40}"))
        generation <- c.get[String]("generation").toOption
        adapter <- all.find(_.code == generation)
      } yield Release(version, commit, adapter,c.downField("sourceSha256").get[String]("libs/contract/commands/nodes/update.command.ts").toOption
        .exists(Set("09f076b8c88521153c43fa5d8a040baa22e693e1761d4df33619ecc2cc8c090c","d14b0e6a45bb74d330e60a3d5a075f11f9479b8bb589f0c452c4a1d3ff1e2c60")),
        c.downField("sourceSha256").get[String]("libs/contract/commands/config-profiles/create-config-profile.command.ts")
          .contains("b2cf9fc9cf3548c78b0bd00c951de976a630905f4983bca9c596946421d80e08"))
      val r = release.getOrElse(throw new IllegalStateException("Remnawave compatibility catalog is invalid"))
      r.version -> r
    }.toMap
  }

  def releaseFromMetadata(body: String): Option[Release] = parse(body).toOption.flatMap { json =>
    val c = json.hcursor.downField("response")
    for {
      version <- c.get[String]("version").toOption
      release <- releases.get(version.stripPrefix("v"))
      commit <- c.downField("git").downField("backend").get[String]("commitSha").toOption
        .filter(raw => raw.matches("[0-9a-f]{7,40}") && release.commit.startsWith(raw))
    } yield release
  }

  val writeCapabilities: Set[NodeProvisioningCapability] = Set(
    NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData,
    NodeProvisioningCapability.ConfigProfile, NodeProvisioningCapability.CreateReconciliation)

  def correlationTag(id: UUID): String = "ID:" + id.toString.replace("-", "").toUpperCase(java.util.Locale.ROOT)

  def validIntent(intent: NodeCreateIntent): Boolean = intent.name == intent.name.trim &&
    intent.name.length >= 3 && intent.name.length <= 30 && !intent.name.exists(_.isControl) &&
    validAddress(intent.address) && intent.port >= 1 && intent.port <= 65535 &&
    intent.activeInboundIds.distinct.size == intent.activeInboundIds.size && intent.activeInboundIds.size <= 256

  private def validAddress(address: String): Boolean = address.length >= 2 && address.length <= 253 &&
    (address.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*") ||
      (address.contains(":") && address.matches("[0-9a-fA-F:]+") &&
        Try(java.net.InetAddress.getByName(address)).toOption.exists(_.isInstanceOf[java.net.Inet6Address])))

  def createPayload(intent: NodeCreateIntent): Json = Json.obj(
    "name" -> Json.fromString(intent.name), "address" -> Json.fromString(intent.address),
    "port" -> Json.fromInt(intent.port), "configProfile" -> Json.obj(
      "activeConfigProfileUuid" -> Json.fromString(intent.configProfileId.toString),
      "activeInbounds" -> Json.arr(intent.activeInboundIds.map(id => Json.fromString(id.toString)): _*)),
    "tags" -> Json.arr(Json.fromString(correlationTag(intent.correlationId))))

  def matches(node: ProvisionedNode, intent: NodeCreateIntent): Boolean =
    node.name == intent.name && node.address == intent.address && node.port.contains(intent.port) &&
      node.configProfileId.contains(intent.configProfileId) &&
      node.activeInboundIds.toSet == intent.activeInboundIds.toSet &&
      node.correlationTags.contains(correlationTag(intent.correlationId))

  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption.filter(_.toString == raw)
  def node(c: HCursor): Option[ProvisionedNode] = for {
    id <- c.get[String]("uuid").toOption.flatMap(uuid)
    name <- c.get[String]("name").toOption.filter(v => v.nonEmpty && v.length <= 255 && !v.exists(_.isControl))
    address <- c.get[String]("address").toOption.filter(v => v.nonEmpty && v.length <= 253 && !v.exists(_.isControl))
    port <- c.get[Option[Int]]("port").toOption.filter(_.forall(v => v >= 1 && v <= 65535))
      .filter(_ => c.downField("port").succeeded)
    connected <- c.get[Boolean]("isConnected").toOption
    connecting <- c.get[Boolean]("isConnecting").toOption
    disabled <- c.get[Boolean]("isDisabled").toOption
    profileRaw <- c.downField("configProfile").get[Option[String]]("activeConfigProfileUuid").toOption
      .filter(_ => c.downField("configProfile").downField("activeConfigProfileUuid").succeeded)
    profile <- profileRaw.fold[Option[Option[UUID]]](Some(None))(s => uuid(s).map(Some(_)))
    inboundRows <- c.downField("configProfile").get[List[Json]]("activeInbounds").toOption
    inbounds <- sequence(inboundRows.map(_.hcursor.get[String]("uuid").toOption.flatMap(uuid)))
      .filter(ids => ids.distinct.size == ids.size)
    tags <- c.get[List[String]]("tags").toOption.filter(_.size <= 10)
  } yield ProvisionedNode(id, name, address, port, connected, connecting, disabled, profile, inbounds, tags)

  def nodes(body: String): Option[List[ProvisionedNode]] = parse(body).toOption.flatMap(
    _.hcursor.get[List[Json]]("response").toOption).flatMap(rows => sequence(rows.map(row => node(row.hcursor))))
    .filter(nodes => nodes.map(_.externalId).distinct.size == nodes.size)

  private def sequence[A](items: List[Option[A]]): Option[List[A]] =
    items.foldRight(Option(List.empty[A]))((item, rest) => for { head <- item; tail <- rest } yield head :: tail)
}

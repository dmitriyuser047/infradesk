package ru.bitec.app.ops
package infrastructure.http

import application.notification.{
  GetNotificationChannel,
  ListNotificationChannels,
  NotificationChannelManagement
}
import application.port.{
  IdGenerator,
  NotificationChannelRepository,
  NotificationChannelSecret,
  NotificationChannelSecretRepository,
  TimeProvider,
  TransactionRunner
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.notification.NotificationChannel
import integration.notification.NotificationChannelCipher
import integration.ssh.SecretEncryptionConfig
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}

import java.time.Instant
import java.util.{Base64, UUID}

/** Notification channels over HTTP: what a response may carry, what a request may ask for, and
  * who is allowed to ask at all.
  */
final class NotificationChannelRoutesSpec extends FunSuite {
  import CirceEntityDecoder._
  import CirceEntityEncoder._

  private val orgA = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val orgB = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val BotToken = "123456:AA-very-secret-bot-token"
  private val WebhookUrl = "https://hooks.example.test/t/secret-path"

  /** A request body for either operation. Only a create carries `enabled`; an update has no
    * lifecycle field at all, so the tests spell out which one they are sending.
    */
  private def telegramBody(
    name: String = "Ops Telegram",
    chatId: Option[String] = Some("-1001234567890"),
    botToken: Option[String] = Some(BotToken),
    events: List[String] = List("INCIDENT_OPENED", "INCIDENT_RESOLVED"),
    reasons: List[String] = List("THRESHOLD", "NO_DATA"),
    enabled: Option[Boolean] = None
  ): Json = Json.obj(
    "name" -> Json.fromString(name),
    "type" -> Json.fromString("TELEGRAM"),
    "events" -> Json.arr(events.map(Json.fromString): _*),
    "reasons" -> Json.arr(reasons.map(Json.fromString): _*),
    "telegram" -> Json.obj(
      "chatId" -> chatId.fold(Json.Null)(Json.fromString),
      "botToken" -> botToken.fold(Json.Null)(Json.fromString)
    )
  ).mapObject(fields =>
    enabled.fold(fields)(value => fields.add("enabled", Json.fromBoolean(value))))

  private def webhookBody(
    name: String = "Automation",
    url: Option[String] = Some(WebhookUrl),
    events: List[String] = List("INCIDENT_OPENED"),
    reasons: List[String] = List("THRESHOLD"),
    enabled: Option[Boolean] = None
  ): Json = Json.obj(
    "name" -> Json.fromString(name),
    "type" -> Json.fromString("WEBHOOK"),
    "events" -> Json.arr(events.map(Json.fromString): _*),
    "reasons" -> Json.arr(reasons.map(Json.fromString): _*),
    "webhook" -> Json.obj("url" -> url.fold(Json.Null)(Json.fromString))
  ).mapObject(fields =>
    enabled.fold(fields)(value => fields.add("enabled", Json.fromBoolean(value))))

  test("creates a Telegram channel and answers with its configuration but never its token") {
    val fixture = new ChannelFixture
    val (status, body) = fixture.post(fixture.path(orgA), telegramBody())

    assertEquals(status, Status.Created)
    assertEquals(body.hcursor.get[String]("name"), Right("Ops Telegram"))
    assertEquals(body.hcursor.get[String]("type"), Right("TELEGRAM"))
    assertEquals(body.hcursor.get[Boolean]("enabled"), Right(true))
    assertEquals(body.hcursor.get[List[String]]("events"),
      Right(List("INCIDENT_OPENED", "INCIDENT_RESOLVED")))
    assertEquals(body.hcursor.get[List[String]]("reasons"), Right(List("THRESHOLD", "NO_DATA")))
    // The chat is addressing and comes back; the token is a credential and only its existence does.
    assertEquals(body.hcursor.downField("config").get[String]("chatId"), Right("-1001234567890"))
    assertEquals(body.hcursor.downField("config").get[Boolean]("credentialConfigured"), Right(true))
    val rendered = body.noSpaces
    assert(!rendered.contains(BotToken), "the response carried the bot token")
    assert(!rendered.contains("botToken"), "the response carried a token field")
    assert(!rendered.contains("*"), "the response carried a fake masked secret")
  }

  test("a webhook channel never shows its URL, because the URL is the credential") {
    val fixture = new ChannelFixture
    val (status, body) = fixture.post(fixture.path(orgA), webhookBody())

    assertEquals(status, Status.Created)
    assertEquals(body.hcursor.get[String]("type"), Right("WEBHOOK"))
    assertEquals(body.hcursor.downField("config").get[Boolean]("credentialConfigured"), Right(true))
    assertEquals(body.hcursor.downField("config").get[Option[String]]("chatId"), Right(None))
    assert(!body.noSpaces.contains("secret-path"), "the response carried the webhook URL")
    assert(!body.noSpaces.contains("hooks.example.test"))
  }

  test("the credential is stored encrypted, and the plaintext appears nowhere else") {
    val fixture = new ChannelFixture
    fixture.post(fixture.path(orgA), telegramBody())

    val stored = fixture.secrets.head
    assert(!new String(stored.ciphertext, "UTF-8").contains(BotToken))
    assertEquals(stored.nonce.length, 12)
    // What the application can read back is the credential, and only through the cipher.
    assertEquals(fixture.cipher.decrypt(stored),
      domain.notification.NotificationChannelCredential.TelegramBotToken(BotToken))
    assert(!fixture.channelsAsJson.contains(BotToken), "a channel row carried the token")
  }

  test("the listing reads every channel of the tenant and nothing of another one") {
    val fixture = new ChannelFixture
    fixture.post(fixture.path(orgA), telegramBody(name = "Zulu"))
    fixture.post(fixture.path(orgA), webhookBody(name = "Alpha"))
    fixture.post(fixture.path(orgB), telegramBody(name = "Foreign"))

    val (status, body) = fixture.get(fixture.path(orgA))
    assertEquals(status, Status.Ok)
    val names = body.asArray.toList.flatten.flatMap(_.hcursor.get[String]("name").toOption)
    assertEquals(names, List("Alpha", "Zulu"))
    assert(!body.noSpaces.contains("Foreign"))
    assert(!body.noSpaces.contains(BotToken))
    // One read for the whole page, whatever it contains.
    assertEquals(fixture.transactions, 4)
  }

  test("an edit without a new secret keeps the stored one") {
    val fixture = new ChannelFixture
    val created = fixture.post(fixture.path(orgA), telegramBody())._2
    val id = UUID.fromString(created.hcursor.get[String]("id").toOption.get)
    val secretBefore = fixture.secrets.head

    val (status, body) = fixture.put(fixture.path(orgA, id),
      telegramBody(name = "Renamed", botToken = None, events = List("INCIDENT_OPENED")))

    assertEquals(status, Status.Ok)
    assertEquals(body.hcursor.get[String]("name"), Right("Renamed"))
    assertEquals(body.hcursor.get[List[String]]("events"), Right(List("INCIDENT_OPENED")))
    assertEquals(fixture.secrets.map(_.id), List(secretBefore.id))
    assertEquals(fixture.channel(id).secretId, secretBefore.id)
  }

  test("a new secret replaces the old one and the old one does not outlive it") {
    val fixture = new ChannelFixture
    val id = fixture.createdId(telegramBody())
    val before = fixture.secrets.head.id

    fixture.put(fixture.path(orgA, id), telegramBody(botToken = Some("999:rotated-token")))

    val after = fixture.channel(id).secretId
    assertNotEquals(after, before)
    assertEquals(fixture.secrets.map(_.id), List(after))
    assertEquals(fixture.cipher.decrypt(fixture.secrets.head),
      domain.notification.NotificationChannelCredential.TelegramBotToken("999:rotated-token"))
  }

  test("changing the channel type requires a credential of the new type") {
    val fixture = new ChannelFixture
    val id = fixture.createdId(telegramBody())

    val withoutCredential = fixture.put(fixture.path(orgA, id), webhookBody(url = None))
    assertEquals(withoutCredential._1, Status.BadRequest)
    assertEquals(withoutCredential._2.hcursor.get[String]("code"),
      Right("NOTIFICATION_CREDENTIAL_REQUIRED"))
    assertEquals(fixture.channel(id).channelType.code, "TELEGRAM")

    val withCredential = fixture.put(fixture.path(orgA, id), webhookBody())
    assertEquals(withCredential._1, Status.Ok)
    assertEquals(withCredential._2.hcursor.get[String]("type"), Right("WEBHOOK"))
    assertEquals(withCredential._2.hcursor.downField("config").get[Option[String]]("chatId"),
      Right(None))
  }

  test("a channel cannot be created without a credential, whatever its type") {
    val fixture = new ChannelFixture

    for (body <- List(telegramBody(botToken = None), webhookBody(url = None))) {
      val result = fixture.post(fixture.path(orgA), body)
      assertEquals(result._1, Status.BadRequest)
      assertEquals(result._2.hcursor.get[String]("code"), Right("NOTIFICATION_CREDENTIAL_REQUIRED"))
    }
    assertEquals(fixture.channels, List.empty[NotificationChannel])
  }

  test("rejects unknown codes, empty subscriptions, a missing chat and a URL that is not one") {
    val fixture = new ChannelFixture
    val rejected = List(
      telegramBody(events = List("INCIDENT_FLAPPED")),
      telegramBody(reasons = List("MOON_PHASE")),
      telegramBody(events = Nil),
      telegramBody(reasons = Nil),
      telegramBody(chatId = None),
      telegramBody(chatId = Some("   ")),
      telegramBody(name = " "),
      webhookBody(url = Some("ftp://example.test/hook")),
      webhookBody(url = Some("not-a-url")),
      Json.obj("name" -> Json.fromString("x"), "type" -> Json.fromString("EMAIL"),
        "enabled" -> Json.fromBoolean(true), "events" -> Json.arr(), "reasons" -> Json.arr()),
      Json.obj()
    )

    for (body <- rejected) {
      val result = fixture.post(fixture.path(orgA), body)
      assertEquals(result._1, Status.BadRequest, clues(body.noSpaces))
    }
    assertEquals(fixture.channels, List.empty[NotificationChannel])
    assertEquals(fixture.audit.recorded, List.empty[domain.audit.AuditEvent])
  }

  test("enable and disable are the lifecycle, and both are journalled") {
    val fixture = new ChannelFixture
    val id = fixture.createdId(telegramBody(enabled = Some(true)))

    val disabled = fixture.post(s"${fixture.path(orgA, id)}/disable", Json.obj())
    assertEquals(disabled._1, Status.Ok)
    assertEquals(disabled._2.hcursor.get[Boolean]("enabled"), Right(false))
    assertEquals(fixture.channel(id).enabled, false)

    val enabled = fixture.post(s"${fixture.path(orgA, id)}/enable", Json.obj())
    assertEquals(enabled._2.hcursor.get[Boolean]("enabled"), Right(true))
    assertEquals(fixture.channel(id).enabled, true)

    assertEquals(fixture.audit.recorded.map(event => (event.action.code, event.targetType.code)),
      List(
        ("NOTIFICATION_CHANNEL_CREATED", "NOTIFICATION_CHANNEL"),
        ("NOTIFICATION_CHANNEL_DISABLED", "NOTIFICATION_CHANNEL"),
        ("NOTIFICATION_CHANNEL_ENABLED", "NOTIFICATION_CHANNEL")
      ))
    assertEquals(fixture.audit.recorded.map(_.targetId).distinct, List(Some(id)))
    assertEquals(fixture.audit.recorded.map(_.actorUserId).distinct,
      List(support.AuthorizationFixtures.ActorUserId))
  }

  test("asking for the state a channel is already in changes nothing and claims nothing") {
    val fixture = new ChannelFixture
    val enabledId = fixture.createdId(telegramBody(enabled = Some(true)))
    val disabledId = fixture.createdId(webhookBody(enabled = Some(false)))
    val before = fixture.channels.map(channel => channel.id -> channel.updatedAt).toMap

    val stillEnabled = fixture.post(s"${fixture.path(orgA, enabledId)}/enable", Json.obj())
    val stillDisabled = fixture.post(s"${fixture.path(orgA, disabledId)}/disable", Json.obj())

    assertEquals(stillEnabled._1, Status.Ok)
    assertEquals(stillEnabled._2.hcursor.get[Boolean]("enabled"), Right(true))
    assertEquals(stillDisabled._1, Status.Ok)
    assertEquals(stillDisabled._2.hcursor.get[Boolean]("enabled"), Right(false))
    // The answer is the row as it stands, not a timestamp the database does not hold.
    assertEquals(stillEnabled._2.hcursor.get[String]("updatedAt"),
      Right(before(enabledId).toString))
    assertEquals(stillDisabled._2.hcursor.get[String]("updatedAt"),
      Right(before(disabledId).toString))
    assertEquals(fixture.channels.map(channel => channel.id -> channel.updatedAt).toMap, before)
    // A transition that did not happen is not journalled as one.
    assertEquals(fixture.audit.recorded.map(_.action.code),
      List("NOTIFICATION_CHANNEL_CREATED", "NOTIFICATION_CHANNEL_CREATED"))
  }

  test("a real transition moves the timestamp and is journalled once") {
    val fixture = new ChannelFixture
    val id = fixture.createdId(telegramBody(enabled = Some(false)))
    val created = fixture.channel(id).updatedAt
    fixture.at(created.plusSeconds(60))

    val enabled = fixture.post(s"${fixture.path(orgA, id)}/enable", Json.obj())
    assertEquals(enabled._2.hcursor.get[Boolean]("enabled"), Right(true))
    assertEquals(fixture.channel(id).updatedAt, created.plusSeconds(60))
    assertEquals(enabled._2.hcursor.get[String]("updatedAt"),
      Right(created.plusSeconds(60).toString))

    fixture.at(created.plusSeconds(120))
    val disabled = fixture.post(s"${fixture.path(orgA, id)}/disable", Json.obj())
    assertEquals(disabled._2.hcursor.get[Boolean]("enabled"), Right(false))
    assertEquals(fixture.channel(id).updatedAt, created.plusSeconds(120))

    assertEquals(fixture.audit.recorded.map(_.action.code), List("NOTIFICATION_CHANNEL_CREATED",
      "NOTIFICATION_CHANNEL_ENABLED", "NOTIFICATION_CHANNEL_DISABLED"))
  }

  test("an update changes the configuration and never the lifecycle") {
    val fixture = new ChannelFixture
    val disabled = fixture.createdId(telegramBody(enabled = Some(false)))
    val enabled = fixture.createdId(webhookBody(name = "Automation", enabled = Some(true)))

    // A body that asks for the lifecycle is refused rather than quietly obeyed or dropped.
    val asksToEnable = fixture.put(fixture.path(orgA, disabled),
      telegramBody(name = "Renamed", enabled = Some(true)))
    val asksToDisable = fixture.put(fixture.path(orgA, enabled),
      webhookBody(name = "Renamed", enabled = Some(false)))

    assertEquals(asksToEnable._1, Status.BadRequest)
    assertEquals(asksToDisable._1, Status.BadRequest)
    assertEquals(fixture.channel(disabled).enabled, false)
    assertEquals(fixture.channel(enabled).enabled, true)

    // Without it the very same change is an ordinary update, and the lifecycle stays where it was.
    assertEquals(fixture.put(fixture.path(orgA, disabled), telegramBody(name = "Renamed"))._1,
      Status.Ok)
    assertEquals(fixture.channel(disabled).enabled, false)
    assertEquals(fixture.channel(disabled).name, "Renamed")
    assertEquals(fixture.audit.recorded.map(_.action.code), List("NOTIFICATION_CHANNEL_CREATED",
      "NOTIFICATION_CHANNEL_CREATED", "NOTIFICATION_CHANNEL_UPDATED"))
  }

  test("a request carries the configuration of one channel type only") {
    val fixture = new ChannelFixture
    val telegramWithWebhook = telegramBody().deepMerge(Json.obj(
      "webhook" -> Json.obj("url" -> Json.fromString(WebhookUrl))))
    val webhookWithTelegram = webhookBody().deepMerge(Json.obj(
      "telegram" -> Json.obj("chatId" -> Json.fromString("-100777"))))

    for (body <- List(telegramWithWebhook, webhookWithTelegram)) {
      val result = fixture.post(fixture.path(orgA), body)
      assertEquals(result._1, Status.BadRequest, clues(body.noSpaces))
      assertEquals(result._2.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
    }
    // Nothing was written, and nothing was journalled on the way.
    assertEquals(fixture.channels, List.empty[NotificationChannel])
    assertEquals(fixture.secrets, List.empty[NotificationChannelSecret])
    assertEquals(fixture.audit.recorded, List.empty[domain.audit.AuditEvent])

    val id = fixture.createdId(telegramBody())
    assertEquals(fixture.put(fixture.path(orgA, id), telegramWithWebhook)._1, Status.BadRequest)
    assertEquals(fixture.audit.recorded.map(_.action.code), List("NOTIFICATION_CHANNEL_CREATED"))
  }

  test("a channel is created switched on unless the request says otherwise") {
    val fixture = new ChannelFixture

    assertEquals(fixture.post(fixture.path(orgA), telegramBody())._2.hcursor.get[Boolean]("enabled"),
      Right(true))
    assertEquals(
      fixture.post(fixture.path(orgA), webhookBody(enabled = Some(false)))._2
        .hcursor.get[Boolean]("enabled"),
      Right(false))
  }

  test("the journal records the change, never the credential") {
    val fixture = new ChannelFixture
    val id = fixture.createdId(telegramBody())
    fixture.put(fixture.path(orgA, id), telegramBody(botToken = Some("999:rotated")))

    assertEquals(fixture.audit.recorded.map(_.action.code),
      List("NOTIFICATION_CHANNEL_CREATED", "NOTIFICATION_CHANNEL_UPDATED"))
    val journal = fixture.audit.recorded.mkString(" ")
    assert(!journal.contains(BotToken))
    assert(!journal.contains("rotated"))
    assert(!journal.contains("hooks.example.test"))
  }

  test("a member reaches none of it, and no channel changes on the way") {
    val fixture = new ChannelFixture(role = OrganizationRole.Member)
    val id = UUID.randomUUID()

    for (result <- List(
      fixture.get(fixture.path(orgA)),
      fixture.get(fixture.path(orgA, id)),
      fixture.post(fixture.path(orgA), telegramBody()),
      fixture.put(fixture.path(orgA, id), telegramBody()),
      fixture.post(s"${fixture.path(orgA, id)}/enable", Json.obj()),
      fixture.post(s"${fixture.path(orgA, id)}/disable", Json.obj())
    )) assertEquals(result._1, Status.Forbidden)

    assertEquals(fixture.channels, List.empty[NotificationChannel])
    assertEquals(fixture.transactions, 0)
  }

  test("a channel of another organization is not found, not forbidden") {
    val fixture = new ChannelFixture
    val foreign = fixture.createdId(telegramBody(), organizationId = orgB)

    // The path names this tenant; the identifier belongs to another one.
    val byId = fixture.get(fixture.path(orgA, foreign))
    assertEquals(byId._1, Status.NotFound)
    assertEquals(byId._2.hcursor.get[String]("code"), Right("NOTIFICATION_CHANNEL_NOT_FOUND"))
    assertEquals(fixture.put(fixture.path(orgA, foreign), telegramBody())._1, Status.NotFound)
    assertEquals(fixture.post(s"${fixture.path(orgA, foreign)}/disable", Json.obj())._1, Status.NotFound)
    assertEquals(fixture.channel(foreign).enabled, true)
  }

  test("a malformed path is a bad request, and an unknown channel is not found") {
    val fixture = new ChannelFixture

    assertEquals(fixture.get("/api/v1/organizations/nope/notification-channels")._1, Status.BadRequest)
    assertEquals(fixture.get(s"${fixture.path(orgA)}/nope")._1, Status.BadRequest)
    assertEquals(fixture.get(fixture.path(orgA, UUID.randomUUID()))._1, Status.NotFound)
  }

  private final class ChannelFixture(role: OrganizationRole = OrganizationRole.Owner) {
    private val key = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
    val cipher: NotificationChannelCipher = NotificationChannelCipher.fromConfig(
      SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key))
        .toOption.get)

    private var stored = List.empty[NotificationChannel]
    private var storedSecrets = List.empty[NotificationChannelSecret]
    var transactions = 0

    def channels: List[NotificationChannel] = stored
    def secrets: List[NotificationChannelSecret] = storedSecrets
    def channel(id: UUID): NotificationChannel = stored.find(_.id == id).get
    def channelsAsJson: String = stored.mkString(" ")

    private val repository = new NotificationChannelRepository[IO] {
      override def listByOrganization(organizationId: UUID): IO[List[NotificationChannel]] =
        IO(stored.filter(_.organizationId == organizationId).sortBy(channel => (channel.name, channel.id)))
      override def findById(organizationId: UUID, id: UUID): IO[Option[NotificationChannel]] =
        IO(stored.find(value => value.organizationId == organizationId && value.id == id))
      override def findByIdForUpdate(organizationId: UUID, id: UUID): IO[Option[NotificationChannel]] =
        findById(organizationId, id)
      override def save(channel: NotificationChannel): IO[Unit] =
        IO { stored = stored.filterNot(_.id == channel.id) :+ channel }
    }

    private val secretRepository = new NotificationChannelSecretRepository[IO] {
      override def save(secret: NotificationChannelSecret): IO[Unit] =
        IO { storedSecrets = storedSecrets :+ secret }
      override def find(organizationId: UUID, id: UUID): IO[Option[NotificationChannelSecret]] =
        IO(storedSecrets.find(value => value.organizationId == organizationId && value.id == id))
      override def delete(organizationId: UUID, id: UUID): IO[Unit] =
        IO { storedSecrets = storedSecrets.filterNot(value =>
          value.organizationId == organizationId && value.id == id) }
    }

    private val ids = new IdGenerator[IO] { override def nextId: IO[UUID] = IO(UUID.randomUUID()) }
    private var clock = Instant.parse("2026-09-25T10:00:00Z")
    def at(moment: Instant): Unit = clock = moment
    private val time = new TimeProvider[IO] { override def now: IO[Instant] = IO(clock) }
    private val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] = IO { transactions += 1 } *> program
    }

    val (audit, auditRecorder) = support.TestAuditRecorder.recording

    private val management =
      new NotificationChannelManagement[IO](repository, secretRepository, ids, time, cipher,
        auditRecorder)

    private val routes = support.AuthorizationFixtures.authorized(
      new NotificationChannelRoutes[IO](
        new ListNotificationChannels[IO](repository),
        new GetNotificationChannel[IO](repository),
        management,
        runner,
        support.AuthorizationFixtures.authorization
      ).routes.orNotFound,
      role = role
    )

    def path(organizationId: UUID): String =
      s"/api/v1/organizations/$organizationId/notification-channels"
    def path(organizationId: UUID, id: UUID): String = s"${path(organizationId)}/$id"

    def createdId(body: Json, organizationId: UUID = orgA): UUID =
      UUID.fromString(post(path(organizationId), body)._2.hcursor.get[String]("id").toOption.get)

    def get(uri: String): (Status, Json) = run(Request[IO](Method.GET, Uri.unsafeFromString(uri)))
    def post(uri: String, body: Json): (Status, Json) =
      run(Request[IO](Method.POST, Uri.unsafeFromString(uri)).withEntity(body))
    def put(uri: String, body: Json): (Status, Json) =
      run(Request[IO](Method.PUT, Uri.unsafeFromString(uri)).withEntity(body))

    private def run(request: Request[IO]): (Status, Json) = {
      val response = routes.run(request).unsafeRunSync()
      response.status -> response.as[Json].attempt.unsafeRunSync().getOrElse(Json.obj())
    }
  }
}

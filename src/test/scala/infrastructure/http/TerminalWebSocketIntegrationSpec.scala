package ru.bitec.app.ops
package infrastructure.http

import application.connection.OpenSshTerminal
import application.port.{ConnectionRepository, TransactionRunner}
import cats.data.Kleisli
import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import domain.auth.{AuthenticatedUser, OrganizationRole}
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import fs2.{Chunk, Stream}
import integration.ssh.{InteractiveSshTerminal, SshAuthentication, SshAuthenticationProvider, SshClient, SshCommandResult, SshConnectionConfig, SshSession, TerminalSize}
import munit.FunSuite
import org.http4s.{Request, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import application.port.ConnectionRepository
import com.comcast.ip4s.{Host, Port}

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{ArrayBlockingQueue, CompletionStage, TimeUnit}
import scala.concurrent.duration._

final class TerminalWebSocketIntegrationSpec extends FunSuite {
  private val organizationId = UUID.fromString("21000000-0000-0000-0000-000000000001")
  private val connectionId = UUID.fromString("22000000-0000-0000-0000-000000000001")
  private val connection = Connection(connectionId, organizationId, ConnectionScope.Organization, "SSH", "ssh",
    "SSH test", ConnectionConfig(Map("host" -> "127.0.0.1", "username" -> "test",
      "hostKeyFingerprint" -> "SHA256:test")), Some("env:test"), true, Instant.EPOCH, Instant.EPOCH)

  test("authenticated same-origin WebSocket bridges terminal bytes, resize and resource cleanup") {
    val receivedInput = Ref.of[IO, Vector[Byte]](Vector.empty).unsafeRunSync()
    val resized = Ref.of[IO, Option[TerminalSize]](None).unsafeRunSync()
    val released = Ref.of[IO, Int](0).unsafeRunSync()
    val ssh = new FakeSshClient(receivedInput, resized, released)
    val repository = new ConnectionRepository[IO] {
      override def findById(org: UUID, id: UUID): IO[Option[Connection]] =
        IO.pure(Option.when(org == organizationId && id == connectionId)(connection))
      override def findByOrganization(org: UUID): IO[List[Connection]] = IO.pure(List(connection))
      override def save(value: Connection): IO[Unit] = IO.unit
      override def saveIfUnmodified(value: Connection, expectedUpdatedAt: Instant): IO[Boolean] = IO.pure(true)
    }
    val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] = program
    }
    val credentials = new SshAuthenticationProvider[IO] {
      override def resolve(value: Connection): IO[SshAuthentication] = IO.pure(SshAuthentication.Password("test"))
    }
    val terminal = OpenSshTerminal.create(repository, runner, ssh, credentials, 2).unsafeRunSync()
    val authorization = support.AuthorizationFixtures.authorization
    val routes = new TerminalRoutes[IO](terminal, infrastructure.config.TerminalConfig.default, authorization)
    val response = withServer(routes, OrganizationRole.Owner) { base =>
      val client = HttpClient.newHttpClient()
      val terminalUri = base.resolve(s"/api/v1/organizations/$organizationId/connections/$connectionId/terminal")

      val unauthenticated = client.send(java.net.http.HttpRequest.newBuilder(terminalUri).GET().build(),
        java.net.http.HttpResponse.BodyHandlers.ofString())
      assertEquals(unauthenticated.statusCode(), 401)

      val wrongOrigin = client.send(java.net.http.HttpRequest.newBuilder(terminalUri)
        .header("Cookie", "infradesk_session=test-session")
        .header("Origin", "https://attacker.example.test")
        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
      assertEquals(wrongOrigin.statusCode(), 403)

      val otherConnection = base.resolve(s"/api/v1/organizations/$organizationId/connections/${UUID.randomUUID()}/terminal")
      val absent = client.send(java.net.http.HttpRequest.newBuilder(otherConnection)
        .header("Cookie", "infradesk_session=test-session")
        .header("Origin", s"http://127.0.0.1:${base.getPort}")
        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
      assertEquals(absent.statusCode(), 404)
      val nonWebSocket = client.send(java.net.http.HttpRequest.newBuilder(terminalUri)
        .header("Cookie", "infradesk_session=test-session")
        .header("Origin", s"http://127.0.0.1:${base.getPort}")
        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
      assertEquals(nonWebSocket.statusCode(), 426)

      val listener = new Frames
      val origin = s"http://${base.getHost}:${base.getPort}"
      val socket = client.newWebSocketBuilder()
        .header("Cookie", "infradesk_session=test-session")
        .header("Origin", origin)
        .subprotocols("infradesk-terminal-v1")
        .buildAsync(URI.create(terminalUri.toString.replaceFirst("^http", "ws")), listener)
        .get(10, TimeUnit.SECONDS)
      val ready = listener.text.poll(10, TimeUnit.SECONDS)
      assert(Option(ready).exists(_.contains("\"type\":\"ready\"")), clues(ready))
      val output = listener.binary.poll(10, TimeUnit.SECONDS)
      assertEquals(new String(output, StandardCharsets.UTF_8), "terminal-output")

      socket.sendBinary(ByteBuffer.wrap("stdin".getBytes(StandardCharsets.UTF_8)), true)
        .get(5, TimeUnit.SECONDS)
      socket.sendText("{\"type\":\"resize\",\"columns\":120,\"rows\":40}", true)
        .get(5, TimeUnit.SECONDS)
      eventually(5.seconds) {
        assertEquals(new String(receivedInput.get.unsafeRunSync().toArray, StandardCharsets.UTF_8), "stdin")
        assertEquals(resized.get.unsafeRunSync(), Some(TerminalSize(120, 40)))
      }
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS)
      eventually(5.seconds) { assertEquals(released.get.unsafeRunSync(), 1) }
      client.close()
      ()
    }
    assertEquals(response, ())

    withServer(routes, OrganizationRole.Member) { base =>
      val client = HttpClient.newHttpClient()
      val uri = base.resolve(s"/api/v1/organizations/$organizationId/connections/$connectionId/terminal")
      val denied = client.send(java.net.http.HttpRequest.newBuilder(uri)
        .header("Cookie", "infradesk_session=test-session")
        .header("Origin", s"http://127.0.0.1:${base.getPort}")
        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
      assertEquals(denied.statusCode(), 403)
      client.close()
    }
  }

  private def withServer[A](routes: TerminalRoutes[IO], role: OrganizationRole)(use: URI => A): A = {
    val server = EmberServerBuilder.default[IO]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(0).get)
      .withHttpWebSocketApp { builder: WebSocketBuilder2[IO] =>
        val terminalApp = routes.routes(builder).orNotFound
        Kleisli { request: Request[IO] =>
          if (!request.cookies.exists(cookie => cookie.name == "infradesk_session" && cookie.content == "test-session"))
            IO.pure(Response[IO](Status.Unauthorized))
          else terminalApp.run(OrganizationAuthorization.withContext(request,
            OrganizationAccessContext(AuthenticatedUser(UUID.fromString("23000000-0000-0000-0000-000000000001"),
              "test@example.test", "Test"), organizationId, role)))
        }
      }
      .build
    server.use { running => IO.blocking(use(URI.create(
      s"http://${running.address.getHostString}:${running.address.getPort}"))) }.unsafeRunSync()
  }

  private def eventually(timeout: scala.concurrent.duration.FiniteDuration)(assertion: => Unit): Unit = {
    val deadline = System.nanoTime() + timeout.toNanos
    var last: Option[Throwable] = None
    while (System.nanoTime() < deadline) {
      try {
        assertion
        return
      } catch {
        case error: Throwable => last = Some(error); Thread.sleep(50)
      }
    }
    throw last.getOrElse(new AssertionError("condition did not become true"))
  }

  private final class FakeSshClient(
    input: Ref[IO, Vector[Byte]],
    resized: Ref[IO, Option[TerminalSize]],
    released: Ref[IO, Int]
  ) extends SshClient[IO] {
    override def probeHostKey(config: SshConnectionConfig): IO[String] = IO.pure("SHA256:test")
    override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                               (use: SshSession[IO] => IO[A]): IO[A] = IO.raiseError(new UnsupportedOperationException)
    override def terminal(config: SshConnectionConfig, authentication: SshAuthentication,
                          size: TerminalSize): Resource[IO, InteractiveSshTerminal[IO]] =
      Resource.make(IO.pure(new InteractiveSshTerminal[IO] {
        override val output: Stream[IO, Byte] =
          Stream.emits("terminal-output".getBytes(StandardCharsets.UTF_8)) ++ Stream.never[IO]
        override def write(bytes: Chunk[Byte]): IO[Unit] = input.update(_ ++ bytes.toVector)
        override def resize(next: TerminalSize): IO[Unit] = resized.set(Some(next))
      }))(_ => released.update(_ + 1))
  }

  private final class Frames extends WebSocket.Listener {
    val text = new ArrayBlockingQueue[String](8)
    val binary = new ArrayBlockingQueue[Array[Byte]](8)
    override def onOpen(webSocket: WebSocket): Unit = webSocket.request(1)
    override def onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[_] = {
      text.offer(data.toString)
      webSocket.request(1)
      null
    }
    override def onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage[_] = {
      val bytes = new Array[Byte](data.remaining())
      data.get(bytes)
      binary.offer(bytes)
      webSocket.request(1)
      null
    }
  }
}

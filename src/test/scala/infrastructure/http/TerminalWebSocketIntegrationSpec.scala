package ru.bitec.app.ops
package infrastructure.http

import application.connection.OpenSshTerminal
import application.port.{ConnectionRepository, TransactionRunner}
import cats.data.Kleisli
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import domain.auth.{AuthenticatedUser, OrganizationRole}
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import fs2.{Chunk, Stream}
import integration.ssh.{InteractiveSshTerminal, SshAuthentication, SshAuthenticationProvider, SshClient, SshCommandResult, SshConnectionConfig, SshSession, TerminalSize}
import munit.FunSuite
import org.http4s.{Header, Headers, Request, Response, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import com.comcast.ip4s.{Host, Port}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.ci.CIString

import java.net.URI
import java.net.http.{HttpClient, WebSocket, WebSocketHandshakeException}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{ArrayBlockingQueue, CompletionStage, ExecutionException, TimeUnit}
import scala.concurrent.duration._
import scala.util.Try

final class TerminalWebSocketIntegrationSpec extends FunSuite {
  override val munitTimeout: Duration = 30.seconds
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
    val live = liveTerminals(new LifecycleRepository(IO.pure(domain.terminal.TerminalRenewResult.Renewed(Instant.now().plusSeconds(45)))),
      infrastructure.config.TerminalConfig.default)
    val routes = new TerminalRoutes[IO](terminal, infrastructure.config.TerminalConfig.default, authorization,
      Slf4jLogger.getLoggerFromName[IO]("test.terminal"), live)
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
      assert(nonWebSocket.body().contains("TERMINAL_WEBSOCKET_REQUIRED"))

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
      assertEquals(socket.getSubprotocol, "infradesk-terminal-v1")
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
      socket.sendText("{\"type\":\"close\"}", true).get(5, TimeUnit.SECONDS)
      assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1000, "CLIENT_CLOSE"))
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

  private val protocolCases: List[(String, WebSocket => Unit, Option[String], Int)] = List(
    ("oversized binary", socket => {
      socket.sendBinary(ByteBuffer.wrap(new Array[Byte](33)), true).get(5, TimeUnit.SECONDS); ()
    }, Some("FRAME_TOO_LARGE"), 1009),
    ("oversized control", socket => {
      socket.sendText("x" * 81, true).get(5, TimeUnit.SECONDS); ()
    }, Some("CONTROL_TOO_LARGE"), 1009),
    ("fragmented binary", socket => {
      socket.sendBinary(ByteBuffer.wrap(Array[Byte](1)), false).get(5, TimeUnit.SECONDS); ()
    }, None, 1002),
    ("fragmented text", socket => {
      socket.sendText("{", false).get(5, TimeUnit.SECONDS); ()
    }, None, 1002),
    ("malformed JSON", socket => { socket.sendText("{", true).get(5, TimeUnit.SECONDS); () }, Some("INVALID_CONTROL"), 1002),
    ("unknown control", socket => {
      socket.sendText("{\"type\":\"unknown\"}", true).get(5, TimeUnit.SECONDS); ()
    }, Some("INVALID_CONTROL"), 1002),
    ("zero columns", socket => {
      socket.sendText("{\"type\":\"resize\",\"columns\":0,\"rows\":10}", true).get(5, TimeUnit.SECONDS); ()
    }, Some("INVALID_SIZE"), 1002),
    ("excessive size", socket => {
      socket.sendText("{\"type\":\"resize\",\"columns\":501,\"rows\":24}", true).get(5, TimeUnit.SECONDS); ()
    }, Some("INVALID_SIZE"), 1002)
  )

  protocolCases.foreach { case (name, send, expectedCode, closeCode) =>
    test(s"$name is rejected with close $closeCode and releases the terminal") {
      val fixture = terminalFixture(testConfig.copy(maxFrameBytes = 32, maxControlBytes = 80))
      withServer(fixture.routes, OrganizationRole.Owner) { base =>
        withSocket(base) { (socket, listener) =>
          send(socket)
          expectedCode.foreach { code =>
            val message = listener.text.poll(5, TimeUnit.SECONDS)
            assert(Option(message).exists(_.contains(s"\"code\":\"$code\"")), clues(message))
          }
          val closed = listener.closed.poll(5, TimeUnit.SECONDS)
          assert(Option(closed).exists(_._1 == closeCode), clues(closed))
          eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
          assertEquals(fixture.input.get.unsafeRunSync(), Vector.empty[Byte])
        }
      }
    }
  }

  test("optional subprotocol, Ping/Pong and ordered binary input keep the session valid") {
    val fixture = terminalFixture()
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base, requestProtocol = false) { (socket, listener) =>
        assertEquals(socket.getSubprotocol, "")
        // The shell's first output follows `ready` at once. Waiting for it keeps the server's own
        // write of that frame from racing its automatic Pong: the client would read the two
        // interleaved and drop the connection, which is a property of the test, not the protocol.
        val first = listener.binary.poll(5, TimeUnit.SECONDS)
        assertEquals(Option(first).map(new String(_, StandardCharsets.UTF_8)), Some("terminal-output"))
        socket.sendPing(ByteBuffer.wrap(Array[Byte](1))).get(5, TimeUnit.SECONDS)
        assertEquals(listener.pongs.poll(5, TimeUnit.SECONDS), java.lang.Boolean.TRUE)
        assertEquals(fixture.input.get.unsafeRunSync(), Vector.empty[Byte])
        socket.sendPong(ByteBuffer.wrap(Array[Byte](2))).get(5, TimeUnit.SECONDS)
        socket.sendBinary(ByteBuffer.wrap("first".getBytes(StandardCharsets.UTF_8)), true).get(5, TimeUnit.SECONDS)
        socket.sendBinary(ByteBuffer.wrap("second".getBytes(StandardCharsets.UTF_8)), true).get(5, TimeUnit.SECONDS)
        eventually(5.seconds) {
          assertEquals(new String(fixture.input.get.unsafeRunSync().toArray, StandardCharsets.UTF_8), "firstsecond")
        }
        assertEquals(listener.closed.poll(), null)
        assertEquals(listener.errors.poll(), null)
      }
      eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
    }
  }

  test("capacity is reserved before Upgrade and becomes available after disconnect") {
    val fixture = terminalFixture(testConfig.copy(maxConcurrentSessions = 1))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (_, _) =>
        val client = HttpClient.newHttpClient()
        try {
          val rejected = intercept[ExecutionException] {
            socketBuilder(client, base, requestProtocol = true).buildAsync(terminalUri(base), new Frames).get(5, TimeUnit.SECONDS)
          }
          val response = rejected.getCause.asInstanceOf[WebSocketHandshakeException].getResponse
          assertEquals(response.statusCode(), 503)
          val direct = capacityResponse(fixture.routes).unsafeRunSync()
          assertEquals(direct.status, Status.ServiceUnavailable)
          assert(direct.as[String].unsafeRunSync().contains("TERMINAL_CAPACITY"))
          assertEquals(fixture.released.get.unsafeRunSync(), 0)
        } finally client.close()
      }
      eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      withSocket(base) { (_, _) => () }
      eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 2) }
    }
  }

  test("failed SSH open finalizes partial resources and returns the capacity permit") {
    val attempts = Ref.of[IO, Int](0).unsafeRunSync()
    val open = attempts.getAndUpdate(_ + 1).flatMap {
      case 0 => IO.raiseError[Unit](new integration.ssh.SshTransportFailure.AuthenticationFailed(new Exception("test failure")))
      case _ => IO.unit
    }
    val fixture = terminalFixture(testConfig.copy(maxConcurrentSessions = 1), beforeOpen = open)
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      val client = HttpClient.newHttpClient()
      val listener = new Frames
      val socket = socketBuilder(client, base, requestProtocol = true)
        .buildAsync(terminalUri(base), listener).get(5, TimeUnit.SECONDS)
      try {
        val message = listener.text.poll(5, TimeUnit.SECONDS)
        assert(Option(message).exists(_.contains("\"code\":\"SSH_UNAVAILABLE\"")), clues(message))
        assert(Option(listener.closed.poll(5, TimeUnit.SECONDS)).exists(_._1 == 1011))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      } finally { socket.abort(); client.close() }
      withSocket(base) { (_, _) => () }
      eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 2) }
    }
  }

  test("idle timeout releases a silent terminal and Ping/Pong does not extend idle activity") {
    val fixture = terminalFixture(testConfig.copy(idleTimeout = 300.millis, maxLifetime = 2.seconds),
      output = Stream.never[IO])
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (socket, listener) =>
        socket.sendPing(ByteBuffer.wrap(Array[Byte](1))).get(5, TimeUnit.SECONDS)
        val closed = listener.closed.poll(5, TimeUnit.SECONDS)
        assertEquals(closed, (1000, "IDLE_TIMEOUT"))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }

  test("maximum lifetime closes an active terminal and releases its resources") {
    val output = Stream.awakeEvery[IO](50.millis).flatMap(_ => Stream.emit('.'.toByte))
    val fixture = terminalFixture(testConfig.copy(idleTimeout = 300.millis, maxLifetime = 800.millis),
      output = output)
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (_, listener) =>
        assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1000, "MAX_LIFETIME"))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }

  test("SSH output failure is reported safely with close 1011 and releases resources") {
    val output = Stream.sleep_[IO](100.millis) ++ Stream.raiseError[IO](new java.io.IOException("test output failure"))
    val fixture = terminalFixture(output = output)
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (_, listener) =>
        val message = listener.text.poll(5, TimeUnit.SECONDS)
        assert(Option(message).exists(_.contains("SSH_IO_FAILED")), clues(message))
        assert(!message.contains("test output failure"))
        assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1011, "SSH_FAILURE"))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }

  test("SSH input failure is reported safely with close 1011 and releases resources") {
    val fixture = terminalFixture(beforeWrite = IO.raiseError(new java.io.IOException("test input failure")))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (socket, listener) =>
        socket.sendBinary(ByteBuffer.wrap(Array[Byte](1)), true).get(5, TimeUnit.SECONDS)
        val message = listener.text.poll(5, TimeUnit.SECONDS)
        assert(Option(message).exists(_.contains("SSH_IO_FAILED")), clues(message))
        assert(!message.contains("test input failure"))
        assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1011, "SSH_FAILURE"))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }

  test("failed WebSocket handshake releases the reserved permit without opening SSH") {
    val fixture = terminalFixture(testConfig.copy(maxConcurrentSessions = 1))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      val socket = new java.net.Socket(base.getHost, base.getPort)
      socket.setSoTimeout(5000)
      try {
        val request = List(
          s"GET ${terminalUri(base).getPath} HTTP/1.1", s"Host: ${base.getHost}:${base.getPort}",
          "Connection: Upgrade", "Upgrade: websocket", "Sec-WebSocket-Version: 12",
          "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", s"Origin: http://${base.getHost}:${base.getPort}",
          "Cookie: infradesk_session=test-session", "", ""
        ).mkString("\r\n")
        socket.getOutputStream.write(request.getBytes(StandardCharsets.US_ASCII))
        socket.getOutputStream.flush()
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII))
        assertEquals(reader.readLine(), "HTTP/1.1 400 Bad Request")
        assertEquals(fixture.released.get.unsafeRunSync(), 0)
      } finally socket.close()
      withSocket(base) { (_, _) => () }
      eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
    }
  }

  test("WebSocket build failure and cancellation release their reserved permits") {
    val fixture = terminalFixture(testConfig.copy(maxConcurrentSessions = 1))
    val failure = new IllegalStateException("test builder failure")
    val failed = WebSocketBuilder2[IO].flatMap { builder =>
      fixture.routes.routes(builder.withOnNonWebSocketRequest(IO.raiseError(failure))).orNotFound.run(upgradeRequest).attempt
    }.unsafeRunSync()
    assertEquals(failed, Left(failure))
    val firstPermit = fixture.terminal.reserveCapacity.unsafeRunSync()
    assert(firstPermit.nonEmpty)
    firstPermit.get.release.unsafeRunSync()
    val canceled = for {
      building <- Deferred[IO, Unit]
      builder <- WebSocketBuilder2[IO]
      fiber <- fixture.routes.routes(builder.withOnNonWebSocketRequest(building.complete(()) *> IO.never))
        .orNotFound.run(upgradeRequest).start
      _ <- building.get.timeout(3.seconds)
      _ <- fiber.cancel.timeout(3.seconds)
    } yield ()
    canceled.unsafeRunSync()
    val nextPermit = fixture.terminal.reserveCapacity.unsafeRunSync()
    assert(nextPermit.nonEmpty)
    nextPermit.get.release.unsafeRunSync()
    assertEquals(fixture.released.get.unsafeRunSync(), 0)
  }

  test("valid resize extends idle activity until the maximum lifetime") {
    val fixture = terminalFixture(testConfig.copy(idleTimeout = 350.millis, maxLifetime = 1100.millis),
      output = Stream.never[IO])
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (socket, listener) =>
        for (_ <- 1 to 9) {
          Thread.sleep(100)
          socket.sendText("{\"type\":\"resize\",\"columns\":100,\"rows\":30}", true).get(5, TimeUnit.SECONDS)
        }
        assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1000, "MAX_LIFETIME"))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }

  List(domain.terminal.TerminalCloseReason.AuthSessionEnded,
    domain.terminal.TerminalCloseReason.PermissionRevoked,
    domain.terminal.TerminalCloseReason.ConnectionChanged).foreach { reason =>
    test(s"durable heartbeat closes revoked session ${reason.code} with policy code and releases SSH") {
      val repository = new LifecycleRepository(IO.pure(domain.terminal.TerminalRenewResult.Revoked(reason)))
      val fixture = terminalFixture(testConfig.copy(heartbeatInterval = 50.millis),
        sessionRepository = Some(repository))
      withServer(fixture.routes, OrganizationRole.Owner) { base =>
        withSocket(base) { (_, listener) =>
          assertEquals(repository.transitions.get.unsafeRunSync().take(2), Vector("OPENING", "ACTIVE"))
          assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1008, reason.code))
          eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
        }
      }
    }
  }
  test("durable heartbeat database failure fails closed with 1011 and releases SSH") {
    val repository = new LifecycleRepository(IO.raiseError(new IllegalStateException("test database unavailable")))
    val fixture = terminalFixture(testConfig.copy(heartbeatInterval = 50.millis),
      sessionRepository = Some(repository))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (_, listener) =>
        assertEquals(listener.closed.poll(10, TimeUnit.SECONDS), (1011, "SESSION_VALIDATION_FAILED"))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }
  test("a renewal that never answers is bounded: three timed-out attempts fail closed before the lease could expire") {
    val attempts = Ref.of[IO, Int](0).unsafeRunSync()
    val repository = new LifecycleRepository(attempts.update(_ + 1) *> IO.never)
    val fixture = terminalFixture(testConfig.copy(heartbeatInterval = 50.millis),
      output = Stream.never[IO], sessionRepository = Some(repository))
    val worstCase = infrastructure.config.TerminalConfig.RenewWorstCase
    // The default lease leaves room for a heartbeat plus every attempt and pause, with margin.
    assert(infrastructure.config.TerminalConfig.default.heartbeatInterval + worstCase +
      infrastructure.config.TerminalConfig.RenewSafetyMargin <= infrastructure.config.TerminalConfig.default.leaseDuration)
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (_, listener) =>
        val started = System.nanoTime()
        assertEquals(listener.closed.poll(worstCase.toSeconds + 10, TimeUnit.SECONDS), (1011, "SESSION_VALIDATION_FAILED"))
        val elapsed = (System.nanoTime() - started).nanos
        assert(elapsed < worstCase + 3.seconds, s"closed after $elapsed, expected within $worstCase")
        assertEquals(attempts.get.unsafeRunSync(), 3)
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }
  test("a transient heartbeat database failure keeps the WebSocket and shell alive") {
    val attempts = Ref.of[IO, Int](0).unsafeRunSync()
    val renewal = attempts.getAndUpdate(_ + 1).flatMap {
      case 0 => IO.raiseError(new IllegalStateException("temporary database interruption"))
      case _ => IO.pure(domain.terminal.TerminalRenewResult.Renewed(Instant.now().plusSeconds(45)))
    }
    val repository = new LifecycleRepository(renewal)
    val fixture = terminalFixture(testConfig.copy(heartbeatInterval = 50.millis),
      output = Stream.never[IO], sessionRepository = Some(repository))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (socket, listener) =>
        eventually(5.seconds) { assert(attempts.get.unsafeRunSync() >= 2) }
        assertEquals(listener.closed.poll(100, TimeUnit.MILLISECONDS), null)
        socket.sendBinary(ByteBuffer.wrap("still alive".getBytes(StandardCharsets.UTF_8)), true).get(5, TimeUnit.SECONDS)
        eventually(5.seconds) { assert(fixture.input.get.unsafeRunSync().nonEmpty) }
      }
    }
  }
  test("heartbeat renewals do not count as user activity") {
    val repository = new LifecycleRepository(IO.pure(domain.terminal.TerminalRenewResult.Renewed(Instant.now().plusSeconds(45))))
    val fixture = terminalFixture(testConfig.copy(heartbeatInterval = 50.millis,
      idleTimeout = 300.millis), output = Stream.never[IO], sessionRepository = Some(repository))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withSocket(base) { (_, listener) =>
        assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1000, "IDLE_TIMEOUT"))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }

  test("a socket lost without Close leaves the shell running and the same login session resumes it") {
    val output = Queue.unbounded[IO, Option[Chunk[Byte]]].unsafeRunSync()
    val opened = Ref.of[IO, Int](0).unsafeRunSync()
    val fixture = terminalFixture(testConfig.copy(detachTimeout = 10.seconds),
      output = Stream.fromQueueNoneTerminatedChunk(output), opened = Some(opened))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      val sessionId = openAndLose(base, fixture)
      output.offer(Some(Chunk.array("while away".getBytes(StandardCharsets.UTF_8)))).unsafeRunSync()
      withConnection(base, resume = Some(sessionId)) { (socket, listener) =>
        val ready = listener.text.poll(5, TimeUnit.SECONDS)
        assert(Option(ready).exists(value => value.contains("\"type\":\"ready\"") && value.contains("\"resumed\":true") &&
          value.contains("\"outputTruncated\":false") && value.contains(sessionId)), clues(ready))
        assertEquals(Option(listener.binary.poll(5, TimeUnit.SECONDS)).map(new String(_, StandardCharsets.UTF_8)), Some("while away"))
        socket.sendBinary(ByteBuffer.wrap("next".getBytes(StandardCharsets.UTF_8)), true).get(5, TimeUnit.SECONDS)
        eventually(5.seconds) { assertEquals(new String(fixture.input.get.unsafeRunSync().toArray, StandardCharsets.UTF_8), "next") }
        assertEquals(opened.get.unsafeRunSync(), 1)
        assertEquals(fixture.released.get.unsafeRunSync(), 0)
        socket.sendText("{\"type\":\"close\"}", true).get(5, TimeUnit.SECONDS)
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
    }
  }

  test("a bare Close frame only detaches the shell; the close control ends it") {
    val fixture = terminalFixture(testConfig.copy(detachTimeout = 10.seconds), output = Stream.never[IO])
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      val sessionId = withConnection(base) { (socket, listener) =>
        val id = readySessionId(listener)
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "going").get(5, TimeUnit.SECONDS)
        id
      }
      eventually(5.seconds) {
        assert(liveShell(fixture, sessionId).flatMap(_.flatTraverse(_.detachedFor)).unsafeRunSync().nonEmpty)
      }
      assertEquals(fixture.released.get.unsafeRunSync(), 0)
      withConnection(base, resume = Some(sessionId)) { (socket, listener) =>
        assert(Option(listener.text.poll(5, TimeUnit.SECONDS)).exists(_.contains("\"resumed\":true")))
        socket.sendText("{\"type\":\"close\"}", true).get(5, TimeUnit.SECONDS)
        assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1000, "CLIENT_CLOSE"))
        eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      }
      expectNotResumable(base, sessionId)
    }
  }

  test("only the shell's own login session can resume it, and nothing can after the detach timeout") {
    val repository = new LifecycleRepository(IO.pure(domain.terminal.TerminalRenewResult.Renewed(Instant.now().plusSeconds(45))))
    val fixture = terminalFixture(testConfig.copy(detachTimeout = 1.second), output = Stream.never[IO],
      sessionRepository = Some(repository))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      val sessionId = openAndLose(base, fixture)
      expectNotResumable(base, sessionId, cookie = "other-session")
      expectNotResumable(base, UUID.randomUUID().toString)
      assertEquals(fixture.released.get.unsafeRunSync(), 0)
      eventually(5.seconds) { assertEquals(fixture.released.get.unsafeRunSync(), 1) }
      assert(repository.transitions.get.unsafeRunSync().contains("DETACH_TIMEOUT"))
      expectNotResumable(base, sessionId)
    }
  }

  test("a resumed socket supersedes the one still attached, whose late Close cannot end the shell") {
    val fixture = terminalFixture(testConfig.copy(detachTimeout = 10.seconds), output = Stream.never[IO])
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      withConnection(base) { (first, firstFrames) =>
        val sessionId = readySessionId(firstFrames)
        withConnection(base, resume = Some(sessionId)) { (second, secondFrames) =>
          assert(Option(secondFrames.text.poll(5, TimeUnit.SECONDS)).exists(_.contains("\"resumed\":true")))
          assertEquals(firstFrames.closed.poll(5, TimeUnit.SECONDS), (1000, "SESSION_RESUMED"))
          Try(first.sendClose(WebSocket.NORMAL_CLOSURE, "late").get(5, TimeUnit.SECONDS))
          second.sendBinary(ByteBuffer.wrap("still mine".getBytes(StandardCharsets.UTF_8)), true).get(5, TimeUnit.SECONDS)
          eventually(5.seconds) {
            assertEquals(new String(fixture.input.get.unsafeRunSync().toArray, StandardCharsets.UTF_8), "still mine")
          }
          assertEquals(fixture.released.get.unsafeRunSync(), 0)
          assertEquals(secondFrames.closed.poll(), null)
        }
      }
    }
  }

  test("output while detached keeps only the newest bytes and the resume reports the gap") {
    val output = Queue.unbounded[IO, Option[Chunk[Byte]]].unsafeRunSync()
    val fixture = terminalFixture(testConfig.copy(detachTimeout = 10.seconds, detachedOutputBytes = 4096),
      output = Stream.fromQueueNoneTerminatedChunk(output))
    withServer(fixture.routes, OrganizationRole.Owner) { base =>
      val sessionId = openAndLose(base, fixture)
      List('a', 'b', 'c').foreach(letter => output.offer(Some(Chunk.array(Array.fill(3000)(letter.toByte)))).unsafeRunSync())
      eventually(5.seconds) {
        assertEquals(liveShell(fixture, sessionId).flatMap(_.traverse(_.pendingBytes)).unsafeRunSync(), Some(3000L))
      }
      withConnection(base, resume = Some(sessionId)) { (_, listener) =>
        assert(Option(listener.text.poll(5, TimeUnit.SECONDS)).exists(_.contains("\"outputTruncated\":true")))
        assertEquals(Option(listener.binary.poll(5, TimeUnit.SECONDS)).map(new String(_, StandardCharsets.UTF_8)), Some("c" * 3000))
      }
    }
  }

  private def liveShell(fixture: TerminalFixture, sessionId: String): IO[Option[LiveTerminal]] =
    fixture.live.resumable(UUID.fromString(sessionId), organizationId, connectionId, userId, loginSession, Instant.EPOCH)

  /** Opens a shell and drops its socket without a Close frame; returns once the shell is detached. */
  private def openAndLose(base: URI, fixture: TerminalFixture): String = {
    val sessionId = withConnection(base) { (_, listener) => readySessionId(listener) }
    eventually(5.seconds) {
      assert(liveShell(fixture, sessionId).flatMap(_.flatTraverse(_.detachedFor)).unsafeRunSync().nonEmpty)
    }
    sessionId
  }

  private def readySessionId(listener: Frames): String = {
    val ready = listener.text.poll(5, TimeUnit.SECONDS)
    assert(Option(ready).exists(value => value.contains("\"type\":\"ready\"") && value.contains("\"resumed\":false")), clues(ready))
    io.circe.parser.parse(ready).toOption.get.hcursor.get[String]("sessionId").toOption.get
  }

  private def expectNotResumable(base: URI, sessionId: String, cookie: String = "test-session"): Unit =
    withConnection(base, resume = Some(sessionId), cookie = cookie) { (_, listener) =>
      val message = listener.text.poll(5, TimeUnit.SECONDS)
      assert(Option(message).exists(_.contains("\"code\":\"SESSION_NOT_RESUMABLE\"")), clues(message))
      assertEquals(listener.closed.poll(5, TimeUnit.SECONDS), (1000, "SESSION_NOT_RESUMABLE"))
    }

  /** A socket that is aborted afterwards: the server sees it vanish without a Close frame. */
  private def withConnection[A](base: URI, resume: Option[String] = None, cookie: String = "test-session")
    (use: (WebSocket, Frames) => A): A = {
    val client = HttpClient.newHttpClient()
    val listener = new Frames
    val socket = socketBuilder(client, base, requestProtocol = true, cookie)
      .buildAsync(terminalUri(base, resume), listener).get(5, TimeUnit.SECONDS)
    try use(socket, listener) finally { socket.abort(); client.close() }
  }

  private final class LifecycleRepository(renewal: IO[domain.terminal.TerminalRenewResult])
    extends application.port.TerminalSessionRepository[IO] {
    val transitions = Ref.of[IO, Vector[String]](Vector.empty).unsafeRunSync()
    def claim(value: domain.terminal.TerminalSession, userLimit: Int, orgLimit: Int): IO[domain.terminal.TerminalClaimResult] =
      transitions.update(_ :+ "OPENING").as(domain.terminal.TerminalClaimResult.Claimed(value))
    def activate(org: UUID, id: UUID, token: UUID, now: Instant, until: Instant): IO[Boolean] =
      transitions.update(_ :+ "ACTIVE").as(true)
    def renew(org: UUID, id: UUID, token: UUID, now: Instant, until: Instant): IO[domain.terminal.TerminalRenewResult] = renewal
    def closeOwned(org: UUID, id: UUID, token: UUID, now: Instant, reason: domain.terminal.TerminalCloseReason): IO[Boolean] =
      transitions.update(_ :+ reason.code).as(true)
    def revoke(org: UUID, id: UUID, now: Instant): IO[Boolean] = IO.pure(true)
    def reapExpired(now: Instant, limit: Int): IO[Int] = IO.pure(0)
  }

  private final case class TerminalFixture(routes: TerminalRoutes[IO], input: Ref[IO, Vector[Byte]],
                                           released: Ref[IO, Int], terminal: OpenSshTerminal[IO], live: LiveTerminals[IO])

  /** Sockets that vanish in these tests leave their shell detached only briefly. */
  private val testConfig = infrastructure.config.TerminalConfig.default.copy(detachTimeout = 300.millis)
  private val userId = UUID.fromString("23000000-0000-0000-0000-000000000001")
  private val loginSession = UUID.fromString("24000000-0000-0000-0000-000000000001")
  private val otherLoginSession = UUID.fromString("24000000-0000-0000-0000-000000000002")

  private def liveTerminals(repository: application.port.TerminalSessionRepository[IO],
    config: infrastructure.config.TerminalConfig): LiveTerminals[IO] =
    LiveTerminals.resource(new application.terminal.TerminalSessionLifecycle(repository,
      new TransactionRunner[IO, IO] { override def run[A](program: IO[A]): IO[A] = program },
      UUID.randomUUID(), 4, 32, config.leaseDuration, Slf4jLogger.getLoggerFromName[IO]("test.terminal.lifecycle")),
      config, Slf4jLogger.getLoggerFromName[IO]("test.terminal.live")).allocated.unsafeRunSync()._1

  private def terminalFixture(
    config: infrastructure.config.TerminalConfig = testConfig,
    output: Stream[IO, Byte] = Stream.emits("terminal-output".getBytes(StandardCharsets.UTF_8)) ++ Stream.never[IO],
    beforeOpen: IO[Unit] = IO.unit,
    beforeWrite: IO[Unit] = IO.unit,
    sessionRepository: Option[application.port.TerminalSessionRepository[IO]] = None,
    opened: Option[Ref[IO, Int]] = None
  ): TerminalFixture = {
    val input = Ref.of[IO, Vector[Byte]](Vector.empty).unsafeRunSync()
    val resized = Ref.of[IO, Option[TerminalSize]](None).unsafeRunSync()
    val released = Ref.of[IO, Int](0).unsafeRunSync()
    val ssh = new FakeSshClient(input, resized, released, output, beforeOpen, beforeWrite, opened)
    val repository = new ConnectionRepository[IO] {
      override def findById(org: UUID, id: UUID): IO[Option[Connection]] =
        IO.pure(Option.when(org == organizationId && id == connectionId)(connection))
      override def findByOrganization(org: UUID): IO[List[Connection]] = IO.pure(List(connection))
      override def save(value: Connection): IO[Unit] = IO.unit
      override def saveIfUnmodified(value: Connection, expectedUpdatedAt: Instant): IO[Boolean] = IO.pure(true)
    }
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]): IO[A] = program }
    val credentials = new SshAuthenticationProvider[IO] {
      override def resolve(value: Connection): IO[SshAuthentication] = IO.pure(SshAuthentication.Password("test"))
    }
    val terminal = OpenSshTerminal.create(repository, runner, ssh, credentials, config.maxConcurrentSessions).unsafeRunSync()
    val durableRepository = sessionRepository.getOrElse(new LifecycleRepository(
      IO.pure(domain.terminal.TerminalRenewResult.Renewed(Instant.now().plusSeconds(45)))))
    val live = liveTerminals(durableRepository, config)
    TerminalFixture(new TerminalRoutes[IO](terminal, config, support.AuthorizationFixtures.authorization,
      Slf4jLogger.getLoggerFromName[IO]("test.terminal"), live), input, released, terminal, live)
  }

  private def terminalUri(base: URI, resume: Option[String] = None): URI = URI.create(base.resolve(
    s"/api/v1/organizations/$organizationId/connections/$connectionId/terminal${resume.fold("")("?resume=" + _)}")
    .toString.replaceFirst("^http", "ws"))

  private def socketBuilder(client: HttpClient, base: URI, requestProtocol: Boolean,
    cookie: String = "test-session"): WebSocket.Builder = {
    val builder = client.newWebSocketBuilder().header("Cookie", s"infradesk_session=$cookie")
      .header("Origin", s"http://${base.getHost}:${base.getPort}")
    if (requestProtocol) builder.subprotocols("infradesk-terminal-v1") else builder
  }

  private def withSocket[A](base: URI, requestProtocol: Boolean = true)(use: (WebSocket, Frames) => A): A = {
    val client = HttpClient.newHttpClient()
    val listener = new Frames
    val socket = socketBuilder(client, base, requestProtocol).buildAsync(terminalUri(base), listener).get(5, TimeUnit.SECONDS)
    try {
      val ready = listener.text.poll(5, TimeUnit.SECONDS)
      assert(Option(ready).exists(_.contains("\"type\":\"ready\"")), clues(ready))
      val sessionId = io.circe.parser.parse(ready).toOption.get.hcursor.get[String]("sessionId").toOption.get
      assertEquals(UUID.fromString(sessionId).toString, sessionId)
      try use(socket, listener)
      catch {
        // A send that fails after the client gave up says only "Output closed"; the reason is here.
        case failure: Throwable =>
          Option(listener.errors.peek()).foreach(failure.addSuppressed)
          throw failure
      }
    } finally { socket.abort(); client.close() }
  }

  private def capacityResponse(routes: TerminalRoutes[IO]): IO[Response[IO]] = WebSocketBuilder2[IO].flatMap { builder =>
    routes.routes(builder).orNotFound.run(upgradeRequest)
  }

  private def upgradeRequest: Request[IO] = {
    val request = Request[IO](uri = Uri.unsafeFromString(
      s"/api/v1/organizations/$organizationId/connections/$connectionId/terminal"), headers = Headers(
      Header.Raw(CIString("Host"), "127.0.0.1"), Header.Raw(CIString("Origin"), "http://127.0.0.1"),
      Header.Raw(CIString("Upgrade"), "websocket"), Header.Raw(CIString("Connection"), "Upgrade"),
      Header.Raw(CIString("Sec-WebSocket-Version"), "13"),
      Header.Raw(CIString("Sec-WebSocket-Key"), "dGhlIHNhbXBsZSBub25jZQ==")))
    OrganizationAuthorization.withContext(request,
      OrganizationAccessContext(AuthenticatedUser(UUID.randomUUID(), "test@example.test", "Test"), organizationId, OrganizationRole.Owner, Some(UUID.randomUUID())))
  }

  private def withServer[A](routes: TerminalRoutes[IO], role: OrganizationRole)(use: URI => A): A = {
    val server = EmberServerBuilder.default[IO]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(0).get)
      .withHttpWebSocketApp { builder: WebSocketBuilder2[IO] =>
        val terminalApp = routes.routes(builder).orNotFound
        Kleisli { request: Request[IO] =>
          // Two login sessions of the same user: only the one that opened a shell may resume it.
          request.cookies.find(_.name == "infradesk_session").map(_.content).collect {
            case "test-session" => loginSession
            case "other-session" => otherLoginSession
          } match {
            case None => IO.pure(Response[IO](Status.Unauthorized))
            case Some(login) => terminalApp.run(OrganizationAuthorization.withContext(request,
              OrganizationAccessContext(AuthenticatedUser(userId, "test@example.test", "Test"), organizationId, role, Some(login))))
          }
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
    released: Ref[IO, Int],
    outputStream: Stream[IO, Byte] = Stream.emits("terminal-output".getBytes(StandardCharsets.UTF_8)) ++ Stream.never[IO],
    beforeOpen: IO[Unit] = IO.unit,
    beforeWrite: IO[Unit] = IO.unit,
    opened: Option[Ref[IO, Int]] = None
  ) extends SshClient[IO] {
    override def probeHostKey(config: SshConnectionConfig): IO[String] = IO.pure("SHA256:test")
    override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                               (use: SshSession[IO] => IO[A]): IO[A] = IO.raiseError(new UnsupportedOperationException)
    override def terminal(config: SshConnectionConfig, authentication: SshAuthentication,
                          size: TerminalSize): Resource[IO, InteractiveSshTerminal[IO]] =
      Resource.make(opened.traverse_(_.update(_ + 1)))(_ => released.update(_ + 1)).evalMap { _ => beforeOpen.as(new InteractiveSshTerminal[IO] {
        override val output: Stream[IO, Byte] = outputStream
        override def write(bytes: Chunk[Byte]): IO[Unit] = beforeWrite *> input.update(_ ++ bytes.toVector)
        override def resize(next: TerminalSize): IO[Unit] = resized.set(Some(next))
      }) }
  }

  private final class Frames extends WebSocket.Listener {
    val text = new ArrayBlockingQueue[String](8)
    val binary = new ArrayBlockingQueue[Array[Byte]](64)
    val closed = new ArrayBlockingQueue[(Int, String)](2)
    val pongs = new ArrayBlockingQueue[java.lang.Boolean](8)
    /** What made the client give up on the connection, so a failure names its cause. */
    val errors = new ArrayBlockingQueue[Throwable](2)
    override def onError(webSocket: WebSocket, error: Throwable): Unit = { errors.offer(error); () }
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
    override def onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage[_] = {
      closed.offer((statusCode, reason))
      null
    }
    override def onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage[_] = {
      pongs.offer(java.lang.Boolean.TRUE)
      webSocket.request(1)
      null
    }
  }
}

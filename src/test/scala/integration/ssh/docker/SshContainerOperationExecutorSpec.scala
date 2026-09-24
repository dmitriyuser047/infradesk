package ru.bitec.app.ops
package integration.ssh.docker

import application.operation.ResourceOperationFailure
import application.port.ResourceOperationTarget
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.operation.ResourceOperationCode
import integration.ssh._
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class SshContainerOperationExecutorSpec extends FunSuite {
  private val id = "73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d"

  test("uses exactly one session and one fixed command for every operation") {
    val cases = List(
      ResourceOperationCode.ContainerStart -> s"docker start $id",
      ResourceOperationCode.ContainerStop -> s"docker stop --time 10 $id",
      ResourceOperationCode.ContainerRestart -> s"docker restart --time 10 $id"
    )
    cases.foreach { case (operation, expected) =>
      val calls = Ref.of[IO, List[String]](Nil).unsafeRunSync()
      val sessions = Ref.of[IO, Int](0).unsafeRunSync()
      new SshContainerOperationExecutor[IO](new Client(calls, sessions), Auth)
        .execute(target(id), operation).unsafeRunSync()
      assertEquals(calls.get.unsafeRunSync(), List(expected))
      assertEquals(sessions.get.unsafeRunSync(), 1)
    }
  }

  test("rejects unsafe and non-Docker ids before opening SSH") {
    val calls = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val failure = intercept[ResourceOperationFailure] {
      new SshContainerOperationExecutor[IO](new Client(calls, sessions), Auth)
        .execute(target("abc; shutdown -h now"), ResourceOperationCode.ContainerStop).unsafeRunSync()
    }
    assertEquals(failure.code, "INVALID_CONTAINER_ID")
    assertEquals(sessions.get.unsafeRunSync(), 0)
  }

  test("maps a nonzero exit to a safe operation-specific failure without retry") {
    val calls = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val failure = intercept[ResourceOperationFailure] {
      new SshContainerOperationExecutor[IO](new Client(calls, sessions, 1), Auth)
        .execute(target(id), ResourceOperationCode.ContainerRestart).unsafeRunSync()
    }
    assertEquals(failure.code, "DOCKER_OPERATION_FAILED")
    assertEquals(failure.safeMessage, "Docker container restart failed")
    assertEquals(calls.get.unsafeRunSync().size, 1)
  }

  private object Auth extends SshAuthenticationProvider[IO] {
    def resolve(connection: Connection): IO[SshAuthentication] = IO.pure(SshAuthentication.Password("secret"))
  }
  private final class Client(calls: Ref[IO, List[String]], sessions: Ref[IO, Int], exit: Int = 0) extends SshClient[IO] {
    def probeHostKey(config: SshConnectionConfig): IO[String] =
      IO.raiseError(new IllegalStateException("host probing is not part of this test"))
    def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)(use: SshSession[IO] => IO[A]): IO[A] =
      sessions.update(_ + 1) *> use(new SshSession[IO] {
        def execute(command: String): IO[SshCommandResult] =
          calls.update(_ :+ command).as(SshCommandResult(exit, "", "sensitive remote stderr", "fingerprint"))
      })
  }
  private def target(externalId: String) = ResourceOperationTarget(Connection(
    UUID.randomUUID(), UUID.randomUUID(), ConnectionScope.Organization, "SSH", "ssh", "SSH",
    ConnectionConfig(Map("host" -> "host", "port" -> "22", "username" -> "user",
      "connectTimeoutSeconds" -> "5", "commandTimeoutSeconds" -> "30")), Some("ENV:SECRET"),
    isActive = true, Instant.EPOCH, Instant.EPOCH), "CONTAINER", externalId)
}

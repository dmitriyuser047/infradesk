package ru.bitec.app.ops
package integration.ssh

import application.discovery.DiscoveredExternalIdentity
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.resource.ResourceData
import domain.resource.container.{ContainerSpec, ContainerStatus}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class SshConnectorSpec extends FunSuite {

  private val HostKeyFingerprint = "SHA256:test-host-key"
  private val FullContainerId = "73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d"
  private val DockerContainersCommand = "docker ps --all --no-trunc --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.State}}'"

  test("uses the discovered host key for Docker discovery and preserves container identity") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(calls),
      new FixedAuthenticationProvider
    )

    val result = connector.discover(connection).unsafeRunSync()
    val recordedCalls = calls.get.unsafeRunSync()

    assertEquals(recordedCalls.map(_.command), List("hostname", DockerContainersCommand))
    assertEquals(
      recordedCalls.map(_.config.hostKeyFingerprint),
      List(None, Some(HostKeyFingerprint))
    )

    assertEquals(result.resources.size, 2)
    assertEquals(
      result.completeExternalTypes,
      Set(SshConnector.NodeExternalType, SshConnector.ContainerExternalType)
    )

    val container = result.resources.tail.head

    assertEquals(container.externalId, FullContainerId)
    assertEquals(
      container.data,
      ResourceData(
        Some(ContainerSpec(Some("backend:1.0"))),
        Some(ContainerStatus(Some("exited")))
      )
    )
    assertEquals(
      container.parentExternalIdentity,
      Some(
        DiscoveredExternalIdentity(
          SshConnector.NodeExternalType,
          SshConnector.NodeExternalId
        )
      )
    )
  }

  test("returns a node-only partial snapshot when Docker discovery is unavailable") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(
        calls,
        dockerResult = SshCommandResult(1, "", "docker: permission denied", HostKeyFingerprint)
      ),
      new FixedAuthenticationProvider
    )

    val result = connector.discover(connection).unsafeRunSync()

    assertEquals(result.resources.map(_.externalType), List(SshConnector.NodeExternalType))
    assertEquals(result.completeExternalTypes, Set(SshConnector.NodeExternalType))
  }

  test("fails discovery when hostname cannot be obtained") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(
        calls,
        hostnameResult = SshCommandResult(1, "", "hostname unavailable", HostKeyFingerprint)
      ),
      new FixedAuthenticationProvider
    )

    intercept[IllegalStateException] {
      connector.discover(connection).unsafeRunSync()
    }
  }

  private val connection = Connection(
    id = UUID.fromString("60000000-0000-0000-0000-000000000003"),
    organizationId = UUID.fromString("20000000-0000-0000-0000-000000000001"),
    scope = ConnectionScope.Environment(
      UUID.fromString("30000000-0000-0000-0000-000000000001"),
      UUID.fromString("40000000-0000-0000-0000-000000000001")
    ),
    connectorType = SshConnector.ConnectorType,
    code = "TEST_NODE",
    name = "Test node",
    config = ConnectionConfig(
      Map(
        "host" -> "test.example",
        "username" -> "root"
      )
    ),
    secretRef = Some("env:TEST_SSH_PASSWORD"),
    isActive = true,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )

  private final case class ExecuteCall(
                                         config: SshConnectionConfig,
                                         command: String
                                       )

  private final class RecordingSshClient(
                                           calls: Ref[IO, List[ExecuteCall]],
                                           hostnameResult: SshCommandResult = SuccessfulHostnameResult,
                                           dockerResult: SshCommandResult = SuccessfulDockerResult
                                         )
    extends SshClient[IO] {

    override def execute(
                          config: SshConnectionConfig,
                          authentication: SshAuthentication,
                          command: String
                        ): IO[SshCommandResult] =
      calls.update(_ :+ ExecuteCall(config, command)) *> response(command)

    private def response(command: String): IO[SshCommandResult] =
      command match {
        case "hostname" =>
          IO.pure(hostnameResult)

        case DockerContainersCommand =>
          IO.pure(dockerResult)

        case other =>
          IO.raiseError(new IllegalArgumentException(s"Unexpected SSH command: $other"))
      }
  }

  private final class FixedAuthenticationProvider extends SshAuthenticationProvider[IO] {

    override def resolve(connection: Connection): IO[SshAuthentication] =
      IO.pure(SshAuthentication.Password("test-password"))
  }

  private val SuccessfulHostnameResult =
    SshCommandResult(0, "test-node\n", "", HostKeyFingerprint)

  private val SuccessfulDockerResult =
    SshCommandResult(
      0,
      s"$FullContainerId\tbackend\tbackend:1.0\texited\n",
      "",
      HostKeyFingerprint
    )
}

package ru.bitec.app.ops
package integration.ssh

import application.discovery.DiscoveredExternalIdentity
import application.port.{ResourceConnectorFailure, ResourceConnectorFailureCode}
import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.resource.ResourceData
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeSpec, NodeStatus}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class SshConnectorSpec extends FunSuite {

  private val NodeDiscoveryCommand = integration.ssh.node.SshNodeInventoryCollector.commandFor("test.example")
  private val HostKeyFingerprint = "SHA256:test-host-key"
  private val FullContainerId = "73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d"
  private val DockerContainersCommand = "docker ps --all --no-trunc --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.State}}'"

  test("discovers typed inventory using one SSH connection and two ordered commands") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(calls, sessions),
      new FixedAuthenticationProvider
    )

    val result = connector.discover(connection).unsafeRunSync()
    val recordedCalls = calls.get.unsafeRunSync()

    assertEquals(recordedCalls.map(_.command), List(NodeDiscoveryCommand, DockerContainersCommand))
    assertEquals(sessions.get.unsafeRunSync(), 1)
    assertEquals(recordedCalls.map(_.config.hostKeyFingerprint),
      List(Some(HostKeyFingerprint), Some(HostKeyFingerprint)))
    assertEquals(result.connectionConfig, connection.config)

    assertEquals(result.resources.size, 2)
    assertEquals(
      result.completeExternalTypes,
      Set(SshConnector.NodeExternalType, SshConnector.ContainerExternalType)
    )

    assertEquals(
      result.resources.head.data,
      ResourceData(
        Some(NodeSpec(
          hostname = "test-node",
          operatingSystem = Some("Linux"),
          architecture = Some("x86_64"),
          cpuCores = Some(4),
          memoryMb = Some(8192),
          distribution = Some("Ubuntu 24.04 LTS"),
          kernelVersion = Some("6.8.0"),
          cpuModel = Some("AMD EPYC")
        )),
        Some(NodeStatus(
          online = true,
          cpuUsagePercent = Some(BigDecimal("12.500000")),
          memoryUsagePercent = Some(BigDecimal("37.500000")),
          uptimeSeconds = Some(123456)
        ))
      )
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
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(
        calls,
        sessions,
        dockerResult = SshCommandResult(1, "", "docker: permission denied", HostKeyFingerprint)
      ),
      new FixedAuthenticationProvider
    )

    val result = connector.discover(connection).unsafeRunSync()

    assertEquals(result.resources.map(_.externalType), List(SshConnector.NodeExternalType))
    assertEquals(result.completeExternalTypes, Set(SshConnector.NodeExternalType))
    assertEquals(sessions.get.unsafeRunSync(), 1)
    assertEquals(calls.get.unsafeRunSync().map(_.command), List(NodeDiscoveryCommand, DockerContainersCommand))
  }

  test("treats successful empty Docker output as a complete authoritative snapshot") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(calls, sessions,
        dockerResult = SshCommandResult(0, "", "", HostKeyFingerprint)),
      new FixedAuthenticationProvider
    )

    val result = connector.discover(connection).unsafeRunSync()
    assertEquals(result.resources.map(_.externalType), List(SshConnector.NodeExternalType))
    assertEquals(result.completeExternalTypes,
      Set(SshConnector.NodeExternalType, SshConnector.ContainerExternalType))
  }

  test("rejects malformed successful Docker output without returning a partial snapshot") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(calls, sessions,
        dockerResult = SshCommandResult(0, "id\tmissing-fields\n", "", HostKeyFingerprint)),
      new FixedAuthenticationProvider
    )

    intercept[docker.DockerInventoryParseError] {
      connector.discover(connection).unsafeRunSync()
    }
  }

  test("keeps a pinned fingerprint without reconnecting") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val pinned = connection.copy(config = connection.config.updated(
      SshConnectionConfig.HostKeyFingerprintKey, HostKeyFingerprint
    ))
    val result = new SshConnector[IO](new RecordingSshClient(calls, sessions), new FixedAuthenticationProvider)
      .discover(pinned).unsafeRunSync()

    assertEquals(sessions.get.unsafeRunSync(), 1)
    assertEquals(calls.get.unsafeRunSync().map(_.config.hostKeyFingerprint),
      List(Some(HostKeyFingerprint), Some(HostKeyFingerprint)))
    assertEquals(result.connectionConfig, pinned.config)
  }

  test("refuses an untrusted connection before resolving credentials or opening SSH") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val resolutions = Ref.of[IO, Int](0).unsafeRunSync()
    val untrusted = connection.copy(config = ConnectionConfig(
      connection.config.values - SshConnectionConfig.HostKeyFingerprintKey))
    val authentication = new SshAuthenticationProvider[IO] {
      override def resolve(connection: Connection): IO[SshAuthentication] =
        resolutions.update(_ + 1).as(SshAuthentication.Password("must-not-be-read"))
    }

    val error = intercept[ResourceConnectorFailure] {
      new SshConnector[IO](new RecordingSshClient(calls, sessions), authentication)
        .discover(untrusted).unsafeRunSync()
    }

    assertEquals(error.code, ResourceConnectorFailureCode.SshHostKeyNotTrusted)
    assertEquals(resolutions.get.unsafeRunSync(), 0)
    assertEquals(sessions.get.unsafeRunSync(), 0)
  }

  test("node command failure skips Docker and releases the SSH session") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val releases = Ref.of[IO, Int](0).unsafeRunSync()
    val client = new RecordingSshClient(calls, sessions, releases,
      nodeResult = SshCommandResult(1, "", "node failed", HostKeyFingerprint))

    intercept[IllegalStateException] {
      new SshConnector[IO](client, new FixedAuthenticationProvider).discover(connection).unsafeRunSync()
    }
    assertEquals(sessions.get.unsafeRunSync(), 1)
    assertEquals(calls.get.unsafeRunSync().map(_.command), List(NodeDiscoveryCommand))
    assertEquals(releases.get.unsafeRunSync(), 1)
  }

  test("fails discovery when hostname cannot be obtained") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(
        calls,
        sessions,
        nodeResult = SshCommandResult(0, "operating_system\tLinux\n", "", HostKeyFingerprint)
      ),
      new FixedAuthenticationProvider
    )

    intercept[node.NodeInventoryParseError] {
      connector.discover(connection).unsafeRunSync()
    }
  }

  test("keeps malformed optional node metrics empty") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(
        calls,
        sessions,
        nodeResult = SshCommandResult(
          0,
          "hostname\ttest-node\noperating_system\t\narchitecture\t\ncpu_cores\tnot-a-number\nmemory_mb\t\ncpu_usage_percent\tbroken\nmemory_usage_percent\tnot-a-number\nuptime_seconds\tunknown\n",
          "",
          HostKeyFingerprint
        )
      ),
      new FixedAuthenticationProvider
    )

    val nodeData = connector.discover(connection).unsafeRunSync().resources.head.data

    assertEquals(
      nodeData,
      ResourceData(
        Some(NodeSpec("test-node", None, None, None, None)),
        Some(NodeStatus(true, None, None, None))
      )
    )
  }

  test("rejects node metric values outside the percentage range") {
    val calls = Ref.of[IO, List[ExecuteCall]](Nil).unsafeRunSync()
    val sessions = Ref.of[IO, Int](0).unsafeRunSync()
    val connector = new SshConnector[IO](
      new RecordingSshClient(
        calls,
        sessions,
        nodeResult = SshCommandResult(
          0,
          "hostname\ttest-node\ncpu_usage_percent\t100.000001\nmemory_usage_percent\t-0.1\n",
          "",
          HostKeyFingerprint
        )
      ),
      new FixedAuthenticationProvider
    )

    val nodeData = connector.discover(connection).unsafeRunSync().resources.head.data

    assertEquals(
      nodeData,
      ResourceData(
        Some(NodeSpec("test-node", None, None, None, None)),
        Some(NodeStatus(true, None, None, None))
      )
    )
  }

  test("fails discovery when Docker SSH execution raises an error") {
    val releases = Ref.of[IO, Int](0).unsafeRunSync()
    val connector = new SshConnector[IO](
      new FailingDockerSshClient(releases),
      new FixedAuthenticationProvider
    )

    intercept[IllegalStateException] {
      connector.discover(connection).unsafeRunSync()
    }
    assertEquals(releases.get.unsafeRunSync(), 1)
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
        "username" -> "root",
        SshConnectionConfig.HostKeyFingerprintKey -> HostKeyFingerprint
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
                                           sessions: Ref[IO, Int],
                                           releases: Ref[IO, Int] = Ref.of[IO, Int](0).unsafeRunSync(),
                                           nodeResult: SshCommandResult = SuccessfulNodeDiscoveryResult,
                                           dockerResult: SshCommandResult = SuccessfulDockerResult
                                         )
    extends SshClient[IO] {

    override def probeHostKey(config: SshConnectionConfig): IO[String] =
      IO.raiseError(new IllegalStateException("host probing is not part of this test"))

    override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                               (use: SshSession[IO] => IO[A]): IO[A] =
      Resource.make(sessions.update(_ + 1))(_ => releases.update(_ + 1)).use { _ =>
        use(new SshSession[IO] {
          override def execute(command: String): IO[SshCommandResult] =
            calls.update(_ :+ ExecuteCall(config, command)) *> response(command)
        })
      }

    private def response(command: String): IO[SshCommandResult] =
      command match {
        case NodeDiscoveryCommand =>
          IO.pure(nodeResult)

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

  private final class FailingDockerSshClient(releases: Ref[IO, Int]) extends SshClient[IO] {

    override def probeHostKey(config: SshConnectionConfig): IO[String] =
      IO.raiseError(new IllegalStateException("host probing is not part of this test"))

    override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                               (use: SshSession[IO] => IO[A]): IO[A] =
      Resource.make(IO.unit)(_ => releases.update(_ + 1)).use(_ => use(new SshSession[IO] {
        override def execute(command: String): IO[SshCommandResult] = command match {
        case NodeDiscoveryCommand =>
          IO.pure(SuccessfulNodeDiscoveryResult)
        case DockerContainersCommand =>
          IO.raiseError(new IllegalStateException("Simulated SSH transport failure"))
        case other =>
          IO.raiseError(new IllegalArgumentException(s"Unexpected SSH command: $other"))
        }
      }))
  }

  test("maps only typed SSH transport failures to safe connector diagnostics") {
    val raw = "password=super-secret host=10.0.0.1"
    val cases = List[(SshTransportFailure, String, String)](
      (new SshTransportFailure.ConnectTimeout(new RuntimeException(raw)),
        ResourceConnectorFailureCode.SshConnectTimeout, "SSH connection timed out"),
      (new SshTransportFailure.ConnectionRefused(new RuntimeException(raw)),
        ResourceConnectorFailureCode.SshConnectionRefused, "SSH connection was refused"),
      (new SshTransportFailure.AuthenticationFailed(new RuntimeException(raw)),
        ResourceConnectorFailureCode.SshAuthenticationFailed, "SSH authentication failed"),
      (new SshTransportFailure.HostKeyMismatch(new SshHostKeyMismatch(new RuntimeException(raw))),
        ResourceConnectorFailureCode.SshHostKeyMismatch, "SSH host key has changed"),
      (new SshTransportFailure.CommandTimeout(new RuntimeException(raw)),
        ResourceConnectorFailureCode.SshCommandTimeout, "SSH command timed out"),
      (new SshTransportFailure.CommandOutputLimitExceeded(new RuntimeException(raw)),
        ResourceConnectorFailureCode.SshCommandOutputLimit,
        "SSH command output exceeded the allowed limit"),
      (new SshTransportFailure.ConnectionFailed(new RuntimeException(raw)),
        ResourceConnectorFailureCode.SshConnectionFailed, "SSH connection failed")
    )

    cases.foreach { case (transport, code, message) =>
      val client = new SshClient[IO] {
        override def probeHostKey(config: SshConnectionConfig): IO[String] =
          IO.raiseError(new IllegalStateException("host probing is not part of this test"))

        override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                                   (use: SshSession[IO] => IO[A]): IO[A] = IO.raiseError(transport)
      }
      val error = intercept[ResourceConnectorFailure] {
        new SshConnector[IO](client, new FixedAuthenticationProvider).discover(connection).unsafeRunSync()
      }
      assertEquals(error.code, code)
      assertEquals(error.safeMessage, message)
      assertEquals(error.getCause, transport)
      assert(!error.safeMessage.contains(raw))
    }

    val unknown = new IllegalStateException(raw)
    val client = new SshClient[IO] {
      override def probeHostKey(config: SshConnectionConfig): IO[String] =
        IO.raiseError(new IllegalStateException("host probing is not part of this test"))

      override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                                 (use: SshSession[IO] => IO[A]): IO[A] = IO.raiseError(unknown)
    }
    assertEquals(intercept[IllegalStateException] {
      new SshConnector[IO](client, new FixedAuthenticationProvider).discover(connection).unsafeRunSync()
    }, unknown)
  }

  private val SuccessfulNodeDiscoveryResult =
    SshCommandResult(
      0,
      "hostname\ttest-node\noperating_system\tLinux\ndistribution\tUbuntu 24.04 LTS\n" +
        "kernel_version\t6.8.0\narchitecture\tx86_64\ncpu_model\tAMD EPYC\ncpu_cores\t4\n" +
        "memory_mb\t8192\ncpu_usage_percent\t12.500000\nmemory_usage_percent\t37.500000\n" +
        "uptime_seconds\t123456\n",
      "",
      HostKeyFingerprint
    )

  private val SuccessfulDockerResult =
    SshCommandResult(
      0,
      s"$FullContainerId\tbackend\tbackend:1.0\texited\n",
      "",
      HostKeyFingerprint
    )
}

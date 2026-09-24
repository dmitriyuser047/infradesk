package ru.bitec.app.ops
package integration.ssh

import application.connector.SyncSessionPolicy
import application.operation.ResourceOperationPolicy
import domain.connection.{Connection, ConnectionScope, SshConnectionSettings}
import munit.FunSuite

import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.UUID
import scala.concurrent.duration._

final class SshConnectionSyncBudgetSpec extends FunSuite {

  private val budget = new SshConnectionSyncBudget

  test("the budget covers the connect timeout and both sequential commands") {
    // The connector opens one session and then runs the node and the Docker inventory in turn.
    assertEquals(budget.maxAttemptDuration(ssh(connect = 10, command = 1200)),
      (10 + 2 * 1200).seconds)
    assertEquals(budget.maxAttemptDuration(ssh(connect = 5, command = 30)), (5 + 60).seconds)
  }

  test("a connector this adapter does not run contributes nothing") {
    assertEquals(budget.maxAttemptDuration(ssh(connect = 10, command = 1200)
      .copy(connectorType = "DOCKER")), Duration.Zero)
    // Settings that cannot be read run no command at all.
    assertEquals(budget.maxAttemptDuration(ssh(connect = 10, command = 30)
      .copy(config = domain.connection.ConnectionConfig(Map.empty))), Duration.Zero)
  }

  test("the recovery horizon keeps a floor and always outlives the attempt it measures") {
    assertEquals(SyncSessionPolicy.recoverAfter(Duration.Zero), 15.minutes)
    assertEquals(SyncSessionPolicy.recoverAfter(70.seconds), 15.minutes)
    assertEquals(SyncSessionPolicy.recoverAfter((10 + 2 * 1200).seconds), (2410 + 60).seconds)
    assert(SyncSessionPolicy.recoverAfter(14.minutes + 30.seconds) >= 15.minutes)

    // A long SSH attempt is measured by its own budget, never by the former fixed horizon.
    val long = budget.maxAttemptDuration(ssh(connect = 10, command = 1200))
    assert(SyncSessionPolicy.recoverAfter(long) > long)
  }

  test("the boundary belongs to the session that still holds it") {
    val at = Instant.parse("2026-09-24T10:00:00Z")

    assertEquals(SyncSessionPolicy.isRecoverable(at, at), false)
    assertEquals(SyncSessionPolicy.isRecoverable(at.minusMillis(1), at), true)
    assertEquals(SyncSessionPolicy.isRecoverable(at.plusMillis(1), at), false)
  }

  test("long-running nginx routes outlive every legal SSH attempt and recovery margin") {
    val maximum = budget.maxAttemptDuration(ssh(
      SshConnectionSettings.MaxConnectTimeoutSeconds,
      SshConnectionSettings.MaxCommandTimeoutSeconds
    ))
    val syncHorizon = SyncSessionPolicy.recoverAfter(maximum)
    val operationHorizon = ResourceOperationPolicy.staleAfter(
      (SshConnectionSettings.MaxConnectTimeoutSeconds +
        SshConnectionSettings.MaxCommandTimeoutSeconds).seconds)
    val nginx = Files.readString(Paths.get("deploy", "nginx", "infradesk.conf"),
      StandardCharsets.UTF_8)
    val longTimeoutSeconds = "proxy_read_timeout ([0-9]+)s;".r
      .findAllMatchIn(nginx).map(_.group(1).toLong).max.seconds

    assertEquals(maximum, 41.minutes)
    assertEquals(syncHorizon, 42.minutes)
    assertEquals(operationHorizon, 22.minutes)
    assert(longTimeoutSeconds > syncHorizon)
    assert(longTimeoutSeconds > operationHorizon)
    assert(nginx.contains("connections/[^/]+/sync$"))
    assert(nginx.contains("operations/[^/]+/executions$"))
    assertEquals("proxy_read_timeout 2700s;".r.findAllIn(nginx).length, 2)
    assertEquals("proxy_send_timeout 2700s;".r.findAllIn(nginx).length, 2)
  }

  private def ssh(connect: Int, command: Int): Connection = {
    val at = Instant.parse("2026-09-24T10:00:00Z")
    Connection(UUID.randomUUID(), UUID.randomUUID(), ConnectionScope.Organization,
      SshConnector.ConnectorType, "ssh", "SSH",
      SshConnectionSettings.toConnectionConfig(
        SshConnectionSettings("node.example.test", 22, "root", None, connect, command)),
      None, isActive = true, at, at)
  }
}

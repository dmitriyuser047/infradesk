package ru.bitec.app.ops
package domain.integration

import munit.FunSuite
import java.time.Instant
import java.util.UUID

final class IntegrationConfigStatusSpec extends FunSuite {
  private val at = Instant.parse("2026-09-30T10:00:00Z")
  private val a = "a" * 64
  private val b = "b" * 64
  private val c = "c" * 64
  private def deployment(status: IntegrationConfigDeploymentStatus) =
    IntegrationConfigDeployment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
      UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, UUID.randomUUID(), UUID.randomUUID(),
      status, a, b, at, finishedAt = Some(at))

  test("local changes and remote drift are distinct observed states") {
    assertEquals(IntegrationConfigStatus.derive(true, at, Some(a), b, Some(a), None),
      IntegrationConfigStatus.LocalChanges)
    assertEquals(IntegrationConfigStatus.derive(true, at, Some(c), b, Some(a), None),
      IntegrationConfigStatus.RemoteDrift)
    assertEquals(IntegrationConfigStatus.derive(true, at, Some(b), b, Some(a), None),
      IntegrationConfigStatus.InSync)
  }

  test("success waits for a fresh observation and failure is explicit") {
    val success = deployment(IntegrationConfigDeploymentStatus.Succeeded)
    assertEquals(IntegrationConfigStatus.derive(true, at, Some(a), b, Some(b), Some(success)),
      IntegrationConfigStatus.WaitingRefresh)
    assertEquals(IntegrationConfigStatus.derive(true, at.plusSeconds(1), Some(b), b, Some(b), Some(success)),
      IntegrationConfigStatus.InSync)
    assertEquals(IntegrationConfigStatus.derive(true, at, Some(a), b, Some(a),
      Some(deployment(IntegrationConfigDeploymentStatus.Failed))), IntegrationConfigStatus.DeploymentFailed)
  }
}

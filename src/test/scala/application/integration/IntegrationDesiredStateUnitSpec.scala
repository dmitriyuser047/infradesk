package ru.bitec.app.ops
package application.integration

import application.port.DesiredStateCandidate
import domain.integration._
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class IntegrationDesiredStateUnitSpec extends FunSuite {
  import IntegrationDesiredNodeState.{Disabled, Enabled}
  import IntegrationDesiredStateStatus._
  import IntegrationDesiredStateWorker.Decision

  private val seen = Instant.parse("2026-09-30T10:00:00Z")
  private def candidate(state: IntegrationDesiredNodeState, observedDisabled: Boolean,
    lastAction: Option[(IntegrationActionStatus, Option[Instant])] = None, lastAttempt: Option[Instant] = None,
    active: Boolean = false, objectActive: Boolean = true, unknown: Option[Instant] = None) =
    DesiredStateCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), state, 1L,
      lastAttempt, objectActive, observedDisabled, seen, active, lastAction, unknown)
  private def status(c: DesiredStateCandidate) = IntegrationDesiredStateStatus.derive(c.state,
    Facts(c.objectActive, c.observedDisabled, c.lastSeenAt, c.lastAction))

  test("status follows from intent, observation and the latest remediation, in that order of precedence") {
    val before = Some(seen.minusSeconds(5)); val after = Some(seen.plusSeconds(5))
    assertEquals(status(candidate(Enabled, observedDisabled = false)), Compliant)
    assertEquals(status(candidate(Disabled, observedDisabled = true)), Compliant)
    assertEquals(status(candidate(Enabled, observedDisabled = true)), Drifted)
    assertEquals(status(candidate(Disabled, observedDisabled = false)), Drifted)
    assertEquals(status(candidate(Enabled, observedDisabled = true, objectActive = false)), Unavailable)
    assertEquals(status(candidate(Enabled, observedDisabled = true, Some(IntegrationActionStatus.Queued -> None))), Applying)
    assertEquals(status(candidate(Enabled, observedDisabled = true, Some(IntegrationActionStatus.Running -> None))), Applying)
    // Not yet observed: the observation began before (or exactly when) the action finished.
    assertEquals(status(candidate(Enabled, true, Some(IntegrationActionStatus.Succeeded -> after))), WaitingRefresh)
    assertEquals(status(candidate(Enabled, true, Some(IntegrationActionStatus.Succeeded -> Some(seen)))), WaitingRefresh)
    assertEquals(status(candidate(Enabled, true, Some(IntegrationActionStatus.Unknown -> after))), WaitingRefresh)
    assertEquals(status(candidate(Enabled, true, Some(IntegrationActionStatus.Failed -> after))), RemediationFailed)
    // Observed afterwards: the observation alone decides.
    assertEquals(status(candidate(Enabled, true, Some(IntegrationActionStatus.Succeeded -> before))), Drifted)
    assertEquals(status(candidate(Enabled, false, Some(IntegrationActionStatus.Unknown -> before))), Compliant)
    assertEquals(status(candidate(Enabled, true, Some(IntegrationActionStatus.Failed -> before))), Drifted)
  }

  test("drift leads to one enable or disable, never a restart, and only on a fresh observation") {
    assertEquals(IntegrationDesiredStateWorker.decide(candidate(Enabled, observedDisabled = true)),
      Decision.Remediate(IntegrationActionCode.NodeEnable))
    assertEquals(IntegrationDesiredStateWorker.decide(candidate(Disabled, observedDisabled = false)),
      Decision.Remediate(IntegrationActionCode.NodeDisable))
    assertEquals(IntegrationDesiredStateWorker.decide(candidate(Enabled, observedDisabled = false)), Decision.Compliant)
    val waiting = List(
      candidate(Enabled, true, lastAttempt = Some(seen)),
      candidate(Enabled, true, lastAttempt = Some(seen.plusSeconds(1))),
      candidate(Enabled, true, active = true),
      candidate(Enabled, true, unknown = Some(seen)),
      candidate(Enabled, true, objectActive = false),
      candidate(Enabled, true, Some(IntegrationActionStatus.Queued -> None)),
      candidate(Enabled, true, Some(IntegrationActionStatus.Unknown -> Some(seen.plusSeconds(1)))),
      candidate(Enabled, true, Some(IntegrationActionStatus.Failed -> Some(seen.plusSeconds(1)))))
    waiting.foreach(c => assertEquals(IntegrationDesiredStateWorker.decide(c), Decision.Wait, c.toString))
    // A newer observation than the last attempt and than every unobserved outcome reopens the decision.
    assertEquals(IntegrationDesiredStateWorker.decide(candidate(Enabled, true,
      Some(IntegrationActionStatus.Failed -> Some(seen.minusSeconds(1))), lastAttempt = Some(seen.minusSeconds(10)),
      unknown = Some(seen.minusSeconds(20)))), Decision.Remediate(IntegrationActionCode.NodeEnable))
    assert(IntegrationDesiredNodeState.All.forall(state => List(true, false).forall(disabled =>
      !IntegrationDesiredNodeState.remediation(state, disabled).contains(IntegrationActionCode.NodeRestart))))
  }

  test("a manual action contradicts an intent only when it moves the node away from it") {
    import IntegrationActionCode._
    assert(IntegrationDesiredNodeState.contradicts(Enabled, NodeDisable))
    assert(IntegrationDesiredNodeState.contradicts(Disabled, NodeEnable))
    List(Enabled -> NodeEnable, Disabled -> NodeDisable, Enabled -> NodeRestart, Disabled -> NodeRestart)
      .foreach { case (state, action) => assert(!IntegrationDesiredNodeState.contradicts(state, action), s"$state $action") }
  }

  test("the desired-state worker is built without any way to reach a provider") {
    val parameters = classOf[IntegrationDesiredStateWorker[cats.effect.IO]].getConstructors.flatMap(_.getParameterTypes)
      .map(_.getName).toSet
    val forbidden = Set(classOf[IntegrationProviderRegistry[cats.effect.IO]], classOf[IntegrationProvider[cats.effect.IO]],
      classOf[application.port.IntegrationCryptography], classOf[application.port.IntegrationSecretRepository[cats.effect.IO]],
      classOf[application.port.IntegrationRepository[cats.effect.IO]], classOf[org.http4s.client.Client[cats.effect.IO]])
      .map(_.getName)
    assertEquals(parameters.intersect(forbidden), Set.empty[String])
    assert(parameters.contains(classOf[application.port.IntegrationDesiredStateRepository[cats.effect.IO]].getName))
  }
}

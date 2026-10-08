package ru.bitec.app.ops
package application.integration

import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.effect.syntax.all._
import cats.syntax.all._
import domain.integration._
import java.time.Instant
import java.util.UUID
import org.typelevel.log4cats.Logger
import scala.concurrent.duration._

final case class RemnawaveFleetObserverSettings(enabled: Boolean = true, pollInterval: FiniteDuration = 10.seconds,
  batchSize: Int = 20, maxConcurrency: Int = 2, claimLease: FiniteDuration = 120.seconds,
  observationTimeout: FiniteDuration = 45.seconds, recheckInterval: FiniteDuration = 300.seconds,
  staleAfter: FiniteDuration = 900.seconds) {
  require(pollInterval > Duration.Zero && batchSize > 0 && maxConcurrency > 0 &&
    claimLease > observationTimeout * 2 + 5.seconds && recheckInterval > Duration.Zero && staleAfter > Duration.Zero)
}

/** Computes drift for fleet members and writes nothing else.
  *
  * This observer performs read-only observations only: it never applies a server profile, deploys a
  * configuration, executes a node action, touches a firewall or restarts anything, however obvious
  * the drift is. Remote reads happen outside every transaction, and a result is persisted only
  * while the desired revision, the membership and the binding it was computed from still hold -
  * otherwise it is discarded and the member is rescheduled.
  */
final class RemnawaveFleetObserver[Tx[_]: MonadThrow](
  repo: RemnawaveFleetRepository[Tx], query: RemnawaveFleetQuery[Tx],
  targets: ProvisioningTargetQuery[Tx], remote: RemnawaveNodeRemote[IO],
  runner: TransactionRunner[IO, Tx], logger: Logger[IO], settings: RemnawaveFleetObserverSettings,
  owner: UUID = UUID.randomUUID(), clock: IO[Instant] = IO.realTimeInstant,
  imageLifecycle: Option[(RemnawaveNodeImageRemote[IO], RemnawaveFleetUpgradeRepository[Tx])] = None) {

  def run: IO[Nothing] = (tick.handleErrorWith(_ => logger.warn("remnawave.fleet.poll_failed")) *>
    IO.sleep(settings.pollInterval)).foreverM

  def tick: IO[Unit] = if (!settings.enabled) IO.unit else for {
    now <- clock
    token <- IO(UUID.randomUUID())
    claimed <- runner.run(repo.claimDue(owner, token, now, now.plusMillis(settings.claimLease.toMillis),
      settings.batchSize))
    // Bounded concurrency: a fleet of a hundred servers never opens a hundred SSH connections.
    _ <- claimed.parTraverseN(settings.maxConcurrency)(member => observe(member, token).handleErrorWith(_ =>
      logger.warn(s"remnawave.fleet.member_failed fleetId=${member.fleetId} membershipId=${member.id}")))
  } yield ()

  private def observe(member: RemnawaveFleetMembership, token: UUID): IO[Unit] = for {
    now <- clock
    // TX1: the desired revision, the member and every stored source, as one consistent snapshot.
    snapshot <- runner.run(for {
      fleet <- repo.fleet(member.organizationId, member.integrationId, member.fleetId)
      revision <- fleet.flatMap(_.desiredRevisionId).flatTraverse(id =>
        repo.revision(member.organizationId, member.fleetId, id))
      stored <- revision.flatTraverse(value => query.storedEvidence(member.organizationId, member.id, value))
      target <- targets.eligible(member.organizationId, member.resourceId)
    } yield (revision, stored, target))
    (revision, stored, target) = snapshot
    _ <- (revision, stored) match {
      case (Some(desired), Some(evidence)) =>
        val connection = target.toOption.map(_.connection)
        val managed = evidence.provenance.isDefined
        // The exact SSH source this observation will use. TX2 proves it is still the current one,
        // so a connection that was edited, replaced or made ineligible meanwhile is never read as
        // if it had produced this evidence.
        val pin = FleetSourcePin.of(target)
        for {
          // The remote read happens here: no transaction is open and nothing is written.
          local <- (connection, evidence.provenance) match {
            case (Some(value), Some(provenance)) => remote.observe(value,
              RemnawaveNodeRemoteSpec(provenance.onboardingId, member.resourceId, provenance.externalNodeId,
                desired.content.nodePort, provenance.imageReference, desired.content.panelCidrs,provenance.tlsCertificateId))
              .timeout(settings.observationTimeout).attempt.map(_.toOption)
            case _ => IO.pure(None)
          }
          image <- (connection, evidence.provenance, imageLifecycle) match {
            case (Some(value), Some(provenance), Some((images, _))) => images.observeImage(value,
              RemnawaveNodeRemoteSpec(provenance.onboardingId, member.resourceId, provenance.externalNodeId,
                desired.content.nodePort, provenance.imageReference, desired.content.panelCidrs,provenance.tlsCertificateId))
              .timeout(settings.observationTimeout).attempt.map(_.toOption)
            case _ => IO.pure(None)
          }
          at <- clock
          verdict = FleetAssessor.assess(RemnawaveFleetObserver.evidenceOf(evidence, desired.content, local,
            local.as(at), localObservationFailed = managed && local.isEmpty,
            trustedSsh = target.isRight), at, settings.staleAfter)
          assessment = RemnawaveFleetNodeAssessment(UUID.randomUUID(), member.organizationId, member.fleetId,
            desired.id, member.id, member.version, member.inventoryNodeId, member.resourceId,
            verdict.compliance, verdict.health, verdict.driftReasons, verdict.healthReasons,
            verdict.rolloutBlockers, evidence.inventoryObservedAt,
            evidence.serverObservation.map(_.observedAt), local.as(at), at)
          // TX2: the verdict is written only while everything it rests on is still the same - the
          // lease, the membership version, the desired revision, the binding and the SSH source.
          // It reads the database and performs no remote call.
          saved <- runner.run(for {
            current <- targets.eligible(member.organizationId, member.resourceId)
            result <- if (FleetSourcePin.of(current) != pin) false.pure[Tx]
              else repo.saveAssessment(assessment, token, at,
                at.plusMillis(settings.recheckInterval.toMillis))
            _ <- (result, image, evidence.provenance, target.toOption, imageLifecycle) match {
              case (true, Some(observation), Some(provenance), Some(source), Some((_, upgrades))) =>
                upgrades.saveObservation(FleetNodeImageObservation(member.organizationId, member.fleetId,
                  member.id, member.version, member.inventoryNodeId, member.resourceId, provenance.onboardingId,
                  source.connectionId, source.connectionUpdatedAt, observation, at))
              case _ => ().pure[Tx]
            }
          } yield result)
          _ <- if (saved) logger.info(s"remnawave.fleet.assessed organizationId=${member.organizationId} " +
            s"integrationId=${member.integrationId} fleetId=${member.fleetId} revisionId=${desired.id} " +
            s"membershipId=${member.id} resourceId=${member.resourceId} inventoryNodeId=${member.inventoryNodeId} " +
            s"compliance=${verdict.compliance.code} health=${verdict.health.code}")
          // A discarded result is never written as current. The reschedule is fenced too, so a
          // worker that lost its lease changes nothing here and the owner decides when to look next.
          else logger.info(s"remnawave.fleet.discarded fleetId=${member.fleetId} membershipId=${member.id} " +
            s"revisionId=${desired.id} reason=SOURCE_CHANGED") *>
            runner.run(repo.reschedule(member, token, at, at.plusMillis(settings.pollInterval.toMillis))).void
        } yield ()
      // Nothing to judge against yet, or the member is gone: release the claim and look again later.
      case _ => runner.run(repo.reschedule(member, token, now,
        now.plusMillis(settings.recheckInterval.toMillis))).void
    }
  } yield ()
}

/** The identity of the SSH source an observation used, in the same terms Stage25A pins a
  * provisioning plan: the connection and the version of it. `None` means no eligible source, which
  * is itself a pin - a connection that becomes eligible during a read also invalidates the result.
  */
final case class FleetSourcePin(connectionId: Option[UUID], connectionUpdatedAt: Option[Instant])
object FleetSourcePin {
  def of(target: Either[String, ProvisioningTarget]): FleetSourcePin = target.toOption.fold(
    FleetSourcePin(None, None))(value => FleetSourcePin(Some(value.connectionId), Some(value.connectionUpdatedAt)))
}

object RemnawaveFleetObserver {
  /** Joins what the database holds with what one read-only observation saw. Pure. */
  def evidenceOf(stored: FleetStoredEvidence, desired: FleetDesiredContent,
    local: Option[RemnawaveNodeLocalEvidence], localObservedAt: Option[Instant], localObservationFailed: Boolean,
    trustedSsh: Boolean): FleetMemberEvidence = FleetMemberEvidence(
    desired = desired,
    resourceId = stored.membership.resourceId,
    desiredServerContent = stored.desiredServerContent,
    bindingPresent = stored.bindingPresent,
    bindingResourceId = stored.bindingResourceId,
    resourceActive = stored.resourceActive,
    serverProfileAvailable = stored.serverProfileAvailable,
    configProfileAvailable = stored.configProfileAvailable,
    node = stored.node,
    inventoryActive = stored.inventoryActive,
    inventoryObservedAt = stored.inventoryObservedAt,
    remoteConfigSha256 = stored.remoteConfigSha256,
    assignment = stored.assignment,
    serverObservation = stored.serverObservation,
    desiredStateRecord = stored.desiredStateRecord,
    localManaged = stored.provenance.isDefined,
    localEvidence = local,
    localObservedAt = localObservedAt,
    localObservationFailed = localObservationFailed,
    trustedSsh = trustedSsh,
    // A node Stage25C never installed cannot have its reviewed contract confirmed here.
    apiContractConfirmed = stored.provenance.exists(_.provisioningReady),
    busy = stored.busy)
}

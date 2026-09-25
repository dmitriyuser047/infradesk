package ru.bitec.app.ops
package application.notification

import application.port.{
  NotificationDeliveryRepository,
  NotificationSendResult,
  NotificationSender,
  TimeProvider,
  TransactionRunner
}
import cats.{MonadThrow, Parallel}
import cats.effect.{Async, Temporal}
import cats.effect.implicits._
import cats.syntax.all._
import domain.notification.NotificationDelivery
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** Delivers the notification outbox.
  *
  * One tick works in waves of at most `maxConcurrency` deliveries: it claims a wave in a short
  * transaction, performs its network calls outside any transaction, finishes each delivery in its
  * own short transaction, and only then claims the next wave, up to the tick budget. A claimed
  * delivery therefore never waits its turn while the lease is already running, so the lease has to
  * cover one request rather than a whole batch, and a crash freezes only one wave.
  *
  * No database row is locked while an HTTP request is in flight, and every completion is fenced by
  * the claim, so an instance whose lease expired can no longer change the delivery someone else
  * took over.
  *
  * The guarantee is at-least-once: a process that dies between a successful request and its
  * completion transaction leaves the delivery claimed until the lease expires, and it is then
  * retried. The event id is stable across attempts so the receiver can deduplicate.
  */
final class NotificationDispatcher[F[_]: Async, Tx[_]: MonadThrow](
  deliveries: NotificationDeliveryRepository[Tx],
  sender: NotificationSender[F],
  transactionRunner: TransactionRunner[F, Tx],
  timeProvider: TimeProvider[F],
  logger: Logger[F],
  maxConcurrency: Int,
  dispatcherInstanceId: UUID,
  claimLease: FiniteDuration,
  maxAttempts: Long
) {

  require(maxConcurrency > 0, "maxConcurrency must be positive")
  require(maxAttempts > 0, "maxAttempts must be positive")

  def tick(limit: Int): F[Unit] = dispatchWaves(limit)

  /** Claims and delivers one wave at a time until the tick budget is spent or nothing is due. */
  private def dispatchWaves(remaining: Int): F[Unit] = {
    val waveSize = math.min(maxConcurrency, remaining)
    if (waveSize <= 0) ().pure[F]
    else
      transactionRunner.run(
        deliveries.claimPending(dispatcherInstanceId, waveSize, claimLease.toSeconds)
      ).flatMap { claimed =>
        if (claimed.isEmpty) ().pure[F]
        else
          logInfo(
            s"notification.dispatch.started dispatcherInstanceId=$dispatcherInstanceId claimed=${claimed.size}"
          ) *> dispatch(claimed) *>
            // A partial wave means the queue is drained; a full one may have left work behind.
            (if (claimed.size < waveSize) ().pure[F] else dispatchWaves(remaining - claimed.size))
      }
  }

  def run(pollInterval: FiniteDuration, limit: Int): F[Nothing] =
    (tick(limit).handleErrorWith(error =>
      logError(
        s"notification.dispatch.failed dispatcherInstanceId=$dispatcherInstanceId errorType=${error.getClass.getSimpleName}"
      )
    ) *> Temporal[F].sleep(pollInterval)).foreverM

  /** A wave is never larger than `maxConcurrency`, so every claimed delivery starts at once. */
  private def dispatch(claimed: List[NotificationDelivery]): F[Unit] =
    Parallel.parTraverse_(claimed) { delivery =>
      deliver(delivery).handleErrorWith(error => logError(
        s"notification.dispatch.failed ${context(delivery)} errorType=${error.getClass.getSimpleName}"
      ))
    }

  private def deliver(delivery: NotificationDelivery): F[Unit] =
    sender.send(NotificationEvent.from(delivery)).flatMap {
      case NotificationSendResult.Sent =>
        complete(delivery, "notification.sent", attempt(delivery)) { now =>
          deliveries.markSent(delivery.organizationId, delivery.id, dispatcherInstanceId, now)
        }

      case NotificationSendResult.PermanentFailure(code) =>
        complete(delivery, s"notification.dead errorCode=$code reason=permanent", attempt(delivery)) { now =>
          deliveries.markDead(delivery.organizationId, delivery.id, dispatcherInstanceId,
            attempt(delivery), code, now)
        }

      case NotificationSendResult.RetryableFailure(code) if attempt(delivery) >= maxAttempts =>
        complete(delivery, s"notification.dead errorCode=$code reason=max_attempts", attempt(delivery)) { now =>
          deliveries.markDead(delivery.organizationId, delivery.id, dispatcherInstanceId,
            attempt(delivery), code, now)
        }

      case NotificationSendResult.RetryableFailure(code) =>
        val delay = NotificationRetryPolicy.delaySeconds(attempt(delivery))
        complete(delivery, s"notification.retry.scheduled errorCode=$code delaySeconds=$delay", attempt(delivery)) { now =>
          deliveries.reschedule(delivery.organizationId, delivery.id, dispatcherInstanceId,
            attempt(delivery), now.plusSeconds(delay), code, now)
        }
    }

  /** The completion transaction is short and runs after the request, never around it. */
  private def complete(
    delivery: NotificationDelivery,
    event: String,
    attemptCount: Long
  )(update: java.time.Instant => Tx[Boolean]): F[Unit] =
    timeProvider.now
      .flatMap(now => transactionRunner.run(update(now)))
      .flatMap {
        case true => logInfo(s"$event ${context(delivery)} attempt=$attemptCount")
        case false =>
          logWarn(s"notification.claim.lost ${context(delivery)} attempt=$attemptCount")
      }

  private def attempt(delivery: NotificationDelivery): Long = delivery.attemptCount + 1

  private def context(delivery: NotificationDelivery): String =
    s"deliveryId=${delivery.id} organizationId=${delivery.organizationId} " +
      s"incidentId=${delivery.incidentId} eventType=${delivery.eventType.code} channelType=${delivery.channelType.code}"

  private def logInfo(message: String): F[Unit] =
    logger.info(message).handleErrorWith(_ => ().pure[F])

  private def logWarn(message: String): F[Unit] =
    logger.warn(message).handleErrorWith(_ => ().pure[F])

  private def logError(message: String): F[Unit] =
    logger.error(message).handleErrorWith(_ => ().pure[F])
}

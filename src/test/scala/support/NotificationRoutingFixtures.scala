package ru.bitec.app.ops
package support

import application.port.{NotificationChannelRoutingQuery, NotificationRoute, NotificationRoutingKey}
import cats.Applicative
import cats.syntax.all._

/** Routing that finds no channel, for the specs that are about the legacy webhook, the outbox
  * mechanics or the monitoring transaction rather than about where an event goes.
  */
final class NoNotificationRouting[F[_]: Applicative] extends NotificationChannelRoutingQuery[F] {

  /** What was asked for, in the order it was asked, so a spec can assert the number of calls. */
  private var asked: List[Set[NotificationRoutingKey]] = Nil

  def questions: List[Set[NotificationRoutingKey]] = synchronized(asked)

  override def matching(keys: Set[NotificationRoutingKey]): F[List[NotificationRoute]] = {
    synchronized { asked = asked :+ keys }
    List.empty[NotificationRoute].pure[F]
  }
}

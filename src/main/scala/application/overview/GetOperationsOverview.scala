package ru.bitec.app.ops
package application.overview

import application.port.{
  AttentionPage,
  HistoryEventQuery,
  HistoryEventView,
  NavigationQueryRepository,
  OperationsOverviewQuery,
  OperationsOverviewSummary,
  ProjectRepository,
  TimeProvider
}
import cats.Monad
import cats.syntax.all._
import domain.connection.ConnectionScope

import java.time.Duration
import java.util.UUID

/** The operations overview of one scope: what there is, what needs attention, what happened.
  *
  * A representation over the existing model, not a model of its own: nothing here is stored,
  * every part is read from the tables that own the state, and no host is contacted.
  */
final case class OperationsOverview(
  scope: ConnectionScope,
  summary: OperationsOverviewSummary,
  attention: AttentionPage,
  recentActivity: List[HistoryEventView],
  operationsHorizon: Duration
)

/** The fixed limits of the overview. Not configurable: they bound the cost of one request. */
object OperationsOverviewPolicy {

  /** An operation outcome is a current problem for this long, unless a newer one replaced it.
    *
    * Terminal operations never change again, so without a horizon a failure from last month
    * would stay on the overview forever. The timeline keeps it after that.
    */
  val OperationsHorizon: Duration = Duration.ofHours(24)

  val AttentionLimit: Int = 20

  val ActivityLimit: Int = 15
}

/** Loads the overview in one read-only snapshot: at most one scope check, then three statements. */
final class GetOperationsOverview[Tx[_]: Monad](
  overview: OperationsOverviewQuery[Tx],
  history: HistoryEventQuery[Tx],
  projects: ProjectRepository[Tx],
  navigation: NavigationQueryRepository[Tx],
  time: TimeProvider[Tx]
) {
  import OperationsOverviewPolicy._

  /** None when the project or environment is not an active part of this organization. */
  def execute(organizationId: UUID, scope: ConnectionScope): Tx[Option[OperationsOverview]] =
    scopeExists(organizationId, scope).flatMap {
      case false => none[OperationsOverview].pure[Tx]
      case true =>
        for {
          now <- time.now
          since = now.minus(OperationsHorizon)
          summary <- overview.summary(organizationId, scope, since)
          attention <- overview.attention(organizationId, scope, since, AttentionLimit)
          activity <- history.listByScope(organizationId, scope, ActivityLimit)
        } yield Some(OperationsOverview(scope, summary, attention, activity, OperationsHorizon))
    }

  private def scopeExists(organizationId: UUID, scope: ConnectionScope): Tx[Boolean] = scope match {
    case ConnectionScope.Organization => true.pure[Tx]
    case ConnectionScope.Project(projectId) =>
      projects.findActiveById(organizationId, projectId).map(_.isDefined)
    case ConnectionScope.Environment(projectId, environmentId) =>
      // The context is found only while the environment and its project are active.
      navigation.findEnvironmentContext(organizationId, environmentId)
        .map(_.exists(_.project.id == projectId))
  }
}

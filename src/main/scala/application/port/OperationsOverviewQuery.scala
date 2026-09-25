package ru.bitec.app.ops
package application.port

import domain.connection.ConnectionScope

import java.time.Instant
import java.util.UUID

/** How much infrastructure a scope holds and in which state, counted by the database.
  *
  * Every number is derived from the rows that own the state — resources, connections and their
  * sync sessions, incidents, operation executions — at the moment of the read. None of it is
  * stored.
  */
final case class OperationsOverviewSummary(
  nodes: NodeSummary,
  containers: ContainerSummary,
  connections: ConnectionSummary,
  incidents: IncidentSummary,
  operations: OperationSummary
)

/** Active nodes by the `online` flag inventory last wrote; neither flag is counted as unknown. */
final case class NodeSummary(total: Int, online: Int, offline: Int)

/** Active containers by the Docker state inventory last wrote; any other state is neither. */
final case class ContainerSummary(total: Int, running: Int, stopped: Int)

/** Active connections by the outcome of their latest finished synchronization. */
final case class ConnectionSummary(total: Int, healthy: Int, failing: Int, neverSynced: Int)

final case class IncidentSummary(open: Int, threshold: Int, noData: Int)

/** Resources whose latest operation inside the recent horizon did not succeed: FAILED only while
  * the resource is active, UNKNOWN whether or not inventory still sees it.
  */
final case class OperationSummary(failed: Int, unknown: Int)

/** What needs a person now, most important first.
  *
  * The rank is the only priority there is: the query orders by it, the API returns it and the UI
  * displays the list in the order it receives.
  */
sealed abstract class AttentionKind(val code: String, val rank: Int)

object AttentionKind {

  /** Nobody knows whether the command ran on the host: the only state that needs a human check. */
  case object OperationUnknown extends AttentionKind("OPERATION_UNKNOWN", 1)

  /** Monitoring says a resource is unhealthy, or cannot see it at all. */
  case object Incident extends AttentionKind("INCIDENT", 2)

  /** The last inventory reported the node offline. */
  case object NodeOffline extends AttentionKind("NODE_OFFLINE", 3)

  /** InfraDesk's picture of a connection's infrastructure is no longer refreshed. */
  case object SyncFailed extends AttentionKind("SYNC_FAILED", 4)

  /** A command was refused or failed; the host is in the state it was before. */
  case object OperationFailed extends AttentionKind("OPERATION_FAILED", 5)

  val All: List[AttentionKind] =
    List(OperationUnknown, Incident, NodeOffline, SyncFailed, OperationFailed)

  def fromCode(code: String): Either[IllegalArgumentException, AttentionKind] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported attention kind '$code'"))
}

/** The resource an attention item or its navigation points at. */
final case class OverviewResourceView(
  id: UUID,
  name: String,
  resourceTypeCode: String,
  environmentId: UUID
)

final case class OverviewConnectionView(id: UUID, name: String)

/** One current problem: typed references and safe codes, never a rendered message. */
sealed trait AttentionItem {
  def kind: AttentionKind
  def id: UUID
  def occurredAt: Instant
}

final case class IncidentAttention(
  id: UUID,
  occurredAt: Instant,
  reason: String,
  metricCode: String,
  resource: OverviewResourceView
) extends AttentionItem {
  override val kind: AttentionKind = AttentionKind.Incident
}

/** The latest operation of a resource ended FAILED or UNKNOWN; the kind keeps them apart. */
final case class OperationAttention(
  kind: AttentionKind,
  id: UUID,
  occurredAt: Instant,
  operationCode: String,
  errorCode: Option[String],
  errorMessage: Option[String],
  resource: OverviewResourceView
) extends AttentionItem

final case class NodeOfflineAttention(
  occurredAt: Instant,
  resource: OverviewResourceView
) extends AttentionItem {
  override val kind: AttentionKind = AttentionKind.NodeOffline
  override def id: UUID = resource.id
}

/** The latest finished synchronization of a connection failed; the id is that session's. */
final case class SyncFailureAttention(
  id: UUID,
  occurredAt: Instant,
  errorCode: Option[String],
  errorMessage: Option[String],
  connection: OverviewConnectionView
) extends AttentionItem {
  override val kind: AttentionKind = AttentionKind.SyncFailed
}

/** A bounded page of attention items and how many there are in total. */
final case class AttentionPage(items: List[AttentionItem], total: Int)

/** The read side of the operations overview: one bounded statement per section.
  *
  * The scope is the existing organization / project / environment hierarchy; callers validate
  * that its identifiers belong to the organization before asking.
  */
trait OperationsOverviewQuery[F[_]] {

  def summary(organizationId: UUID, scope: ConnectionScope, operationsSince: Instant): F[OperationsOverviewSummary]

  def attention(
    organizationId: UUID,
    scope: ConnectionScope,
    operationsSince: Instant,
    limit: Int
  ): F[AttentionPage]
}

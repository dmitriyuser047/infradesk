package ru.bitec.app.ops
package bootstrap

import application.port.{
  AuditEventRepository,
  HistoryEventQuery,
  HistoryEventRepository,
  AuthSessionRepository,
  ConnectionRepository,
  ConnectionScheduleRepository,
  ConnectionSecretRepository,
  EnvironmentRepository,
  ExternalRefRepository,
  IncidentRepository,
  MetricObservationRepository,
  MonitorEvaluationQuery,
  MonitorRuleRepository,
  MonitorRuleStateRepository,
  NotificationDeliveryRepository,
  NavigationQueryRepository,
  OperationsOverviewQuery,
  OrganizationMembershipRepository,
  OrganizationRepository,
  OperationExecutionRepository,
  ProjectRepository,
  ReadinessCheck,
  ResourceRepository,
  ResourceOperationTargetQuery,
  ResourceTypeRepository,
  SyncSessionRepository,
  TransactionRunner,
  UserAccountRepository
}
import cats.effect.IO
import infrastructure.database.{DoobieReadOnlySnapshotRunner, DoobieTransactionRunner, PostgresReadinessCheck}
import org.typelevel.doobie.{ConnectionIO, Transactor}
import serialization.resource.{ResourceDataCodec, ResourceDefinitionRegistry}
import serialization.resource.container.ContainerResourceDataCodec
import serialization.resource.node.NodeResourceDataCodec
import persistence.postgres.{
  PostgresAuditEventRepository,
  PostgresHistoryEventQuery,
  PostgresHistoryEventRepository,
  PostgresOperationsOverviewQuery,
  PostgresAuthSessionRepository,
  PostgresConnectionRepository,
  PostgresConnectionScheduleRepository,
  PostgresConnectionSecretRepository,
  PostgresEnvironmentRepository,
  PostgresExternalRefRepository,
  PostgresIncidentRepository,
  PostgresMetricObservationRepository,
  PostgresMonitorEvaluationQuery,
  PostgresMonitorRuleRepository,
  PostgresMonitorRuleStateRepository,
  PostgresNavigationQueryRepository,
  PostgresNotificationDeliveryRepository,
  PostgresOrganizationMembershipRepository,
  PostgresOrganizationRepository,
  PostgresOperationExecutionRepository,
  PostgresProjectRepository,
  PostgresResourceRepository,
  PostgresResourceOperationTargetQuery,
  PostgresResourceTypeRepository,
  PostgresSyncSessionRepository,
  PostgresUserAccountRepository,
  ResourceDataJsonCodec
}

/** Database-level dependencies shared by every consumer of a single transactor.
  *
  * This record exists for assembly only: composition modules read its fields and hand the
  * individual ports to constructors. It is never passed into a business object.
  */
final case class PersistenceComponents(
  transactionRunner: TransactionRunner[IO, ConnectionIO],
  /** One read-only snapshot, for read models composed of several statements. */
  readOnlySnapshotRunner: TransactionRunner[IO, ConnectionIO],
  readinessCheck: ReadinessCheck[IO],
  resourceRepository: ResourceRepository[ConnectionIO],
  resourceTypeRepository: ResourceTypeRepository[ConnectionIO],
  organizationRepository: OrganizationRepository[ConnectionIO],
  projectRepository: ProjectRepository[ConnectionIO],
  environmentRepository: EnvironmentRepository[ConnectionIO],
  connectionRepository: ConnectionRepository[ConnectionIO],
  connectionScheduleRepository: ConnectionScheduleRepository[ConnectionIO],
  connectionSecretRepository: ConnectionSecretRepository[ConnectionIO],
  externalRefRepository: ExternalRefRepository[ConnectionIO],
  syncSessionRepository: SyncSessionRepository[ConnectionIO],
  metricObservationRepository: MetricObservationRepository[ConnectionIO],
  monitorRuleRepository: MonitorRuleRepository[ConnectionIO],
  monitorRuleStateRepository: MonitorRuleStateRepository[ConnectionIO],
  monitorEvaluationQuery: MonitorEvaluationQuery[ConnectionIO],
  incidentRepository: IncidentRepository[ConnectionIO],
  notificationDeliveryRepository: NotificationDeliveryRepository[ConnectionIO],
  navigationQueryRepository: NavigationQueryRepository[ConnectionIO],
  userAccountRepository: UserAccountRepository[ConnectionIO],
  authSessionRepository: AuthSessionRepository[ConnectionIO],
  membershipRepository: OrganizationMembershipRepository[ConnectionIO],
  auditEventRepository: AuditEventRepository[ConnectionIO],
  historyEventRepository: HistoryEventRepository[ConnectionIO],
  historyEventQuery: HistoryEventQuery[ConnectionIO],
  operationsOverviewQuery: OperationsOverviewQuery[ConnectionIO]
  ,operationExecutionRepository: OperationExecutionRepository[ConnectionIO]
  ,resourceOperationTargetQuery: ResourceOperationTargetQuery[ConnectionIO]
)

/** Builds every PostgreSQL-backed port on top of one transactor. */
object PersistenceModule {

  /** The single registration point for resource types: one line per type, nothing else in the
    * generic persistence path changes when a type is added.
    */
  val resourceTypeCodecs: List[ResourceDataCodec] =
    List(
      NodeResourceDataCodec,
      ContainerResourceDataCodec
    )

  /** Built once per runtime, before the database pool is opened; duplicate codes fail startup. */
  def resourceDefinitionRegistry: Either[IllegalArgumentException, ResourceDefinitionRegistry] =
    ResourceDefinitionRegistry.build(resourceTypeCodecs)

  def build(xa: Transactor[IO], resourceTypes: ResourceDefinitionRegistry): PersistenceComponents =
    PersistenceComponents(
      transactionRunner = new DoobieTransactionRunner(xa),
      readOnlySnapshotRunner = new DoobieReadOnlySnapshotRunner(xa),
      readinessCheck = new PostgresReadinessCheck(xa),
      resourceRepository = new PostgresResourceRepository(new ResourceDataJsonCodec(resourceTypes)),
      resourceTypeRepository = new PostgresResourceTypeRepository,
      organizationRepository = new PostgresOrganizationRepository,
      projectRepository = new PostgresProjectRepository,
      environmentRepository = new PostgresEnvironmentRepository,
      connectionRepository = new PostgresConnectionRepository,
      connectionScheduleRepository = new PostgresConnectionScheduleRepository,
      connectionSecretRepository = new PostgresConnectionSecretRepository,
      externalRefRepository = new PostgresExternalRefRepository,
      syncSessionRepository = new PostgresSyncSessionRepository,
      metricObservationRepository = new PostgresMetricObservationRepository,
      monitorRuleRepository = new PostgresMonitorRuleRepository,
      monitorRuleStateRepository = new PostgresMonitorRuleStateRepository,
      monitorEvaluationQuery = new PostgresMonitorEvaluationQuery,
      incidentRepository = new PostgresIncidentRepository,
      notificationDeliveryRepository = new PostgresNotificationDeliveryRepository,
      navigationQueryRepository = new PostgresNavigationQueryRepository,
      userAccountRepository = new PostgresUserAccountRepository,
      authSessionRepository = new PostgresAuthSessionRepository,
      membershipRepository = new PostgresOrganizationMembershipRepository,
      auditEventRepository = new PostgresAuditEventRepository,
      historyEventRepository = new PostgresHistoryEventRepository,
      historyEventQuery = new PostgresHistoryEventQuery,
      operationsOverviewQuery = new PostgresOperationsOverviewQuery,
      operationExecutionRepository = new PostgresOperationExecutionRepository,
      resourceOperationTargetQuery = new PostgresResourceOperationTargetQuery
    )
}

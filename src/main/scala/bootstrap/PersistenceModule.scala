package ru.bitec.app.ops
package bootstrap

import application.port.{
  AuditEventRepository,
  HistoryEventQuery,
  HistoryEventRepository,
  AuthSessionRepository,
  LoginThrottleRepository,
  SecurityEventQuery,
  SecurityEventRepository,
  ConnectionRepository,
  ConnectionScheduleRepository,
  ConnectionSecretRepository,
  EnvironmentRepository,
  ExternalRefRepository,
  IncidentListQuery,
  InfrastructureContextQuery,
  ConfigurationProfileQuery,
  ConfigurationProfileRepository,
  IncidentRepository,
  MetricObservationRepository,
  MonitorEvaluationQuery,
  MonitorRuleRepository,
  MonitorRuleStateRepository,
  NotificationChannelRepository,
  NotificationChannelDispatchQuery,
  NotificationChannelRoutingQuery,
  NotificationChannelSecretRepository,
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
  PostgresIncidentListQuery,
  PostgresInfrastructureContextQuery,
  PostgresConfigurationProfileQuery,
  PostgresConfigurationProfileRepository,
  PostgresHistoryEventRepository,
  PostgresOperationsOverviewQuery,
  PostgresAuthSessionRepository,
  PostgresLoginThrottleRepository,
  PostgresSecurityEventRepository,
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
  PostgresNotificationChannelRepository,
  PostgresNotificationChannelDispatchQuery,
  PostgresNotificationChannelRoutingQuery,
  PostgresNotificationChannelSecretRepository,
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
  terminalSessionRepository: application.port.TerminalSessionRepository[ConnectionIO],
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
  incidentListQuery: IncidentListQuery[ConnectionIO],
  infrastructureContextQuery: InfrastructureContextQuery[ConnectionIO],
  configurationProfileRepository: ConfigurationProfileRepository[ConnectionIO],
  configurationProfileQuery: ConfigurationProfileQuery[ConnectionIO],
  notificationDeliveryRepository: NotificationDeliveryRepository[ConnectionIO],
  notificationChannelRepository: NotificationChannelRepository[ConnectionIO],
  notificationChannelSecretRepository: NotificationChannelSecretRepository[ConnectionIO],
  notificationChannelRoutingQuery: NotificationChannelRoutingQuery[ConnectionIO],
  notificationChannelDispatchQuery: NotificationChannelDispatchQuery[ConnectionIO],
  navigationQueryRepository: NavigationQueryRepository[ConnectionIO],
  userAccountRepository: UserAccountRepository[ConnectionIO],
  authSessionRepository: AuthSessionRepository[ConnectionIO],
  loginThrottleRepository: LoginThrottleRepository[ConnectionIO],
  securityEventRepository: SecurityEventRepository[ConnectionIO],
  securityEventQuery: SecurityEventQuery[ConnectionIO],
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
      terminalSessionRepository = new persistence.postgres.PostgresTerminalSessionRepository(new PostgresAuditEventRepository),
      transactionRunner = new DoobieTransactionRunner(xa),
      readOnlySnapshotRunner = new DoobieReadOnlySnapshotRunner(xa),
      readinessCheck = new PostgresReadinessCheck(xa),
      resourceRepository = new PostgresResourceRepository(new ResourceDataJsonCodec(resourceTypes)),
      infrastructureContextQuery = new PostgresInfrastructureContextQuery(new ResourceDataJsonCodec(resourceTypes)),
      configurationProfileRepository = new PostgresConfigurationProfileRepository,
      configurationProfileQuery = new PostgresConfigurationProfileQuery,
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
      incidentListQuery = new PostgresIncidentListQuery,
      notificationDeliveryRepository = new PostgresNotificationDeliveryRepository,
      notificationChannelRepository = new PostgresNotificationChannelRepository,
      notificationChannelSecretRepository = new PostgresNotificationChannelSecretRepository,
      notificationChannelRoutingQuery = new PostgresNotificationChannelRoutingQuery,
      notificationChannelDispatchQuery = new PostgresNotificationChannelDispatchQuery,
      navigationQueryRepository = new PostgresNavigationQueryRepository,
      userAccountRepository = new PostgresUserAccountRepository,
      authSessionRepository = new PostgresAuthSessionRepository,
      loginThrottleRepository = new PostgresLoginThrottleRepository,
      securityEventRepository = new PostgresSecurityEventRepository,
      securityEventQuery = new PostgresSecurityEventRepository,
      membershipRepository = new PostgresOrganizationMembershipRepository,
      auditEventRepository = new PostgresAuditEventRepository,
      historyEventRepository = new PostgresHistoryEventRepository,
      historyEventQuery = new PostgresHistoryEventQuery,
      operationsOverviewQuery = new PostgresOperationsOverviewQuery,
      operationExecutionRepository = new PostgresOperationExecutionRepository,
      resourceOperationTargetQuery = new PostgresResourceOperationTargetQuery
    )
}

package ru.bitec.app.ops
package bootstrap

import application.port.{
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
  NavigationQueryRepository,
  OrganizationMembershipRepository,
  OrganizationRepository,
  ProjectRepository,
  ReadinessCheck,
  ResourceRepository,
  ResourceTypeRepository,
  SyncSessionRepository,
  TransactionRunner,
  UserAccountRepository
}
import cats.effect.IO
import infrastructure.database.{DoobieTransactionRunner, PostgresReadinessCheck}
import org.typelevel.doobie.{ConnectionIO, Transactor}
import persistence.postgres.{
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
  PostgresOrganizationMembershipRepository,
  PostgresOrganizationRepository,
  PostgresProjectRepository,
  PostgresResourceRepository,
  PostgresResourceTypeRepository,
  PostgresSyncSessionRepository,
  PostgresUserAccountRepository
}

/** Database-level dependencies shared by every consumer of a single transactor.
  *
  * This record exists for assembly only: composition modules read its fields and hand the
  * individual ports to constructors. It is never passed into a business object.
  */
final case class PersistenceComponents(
  transactionRunner: TransactionRunner[IO, ConnectionIO],
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
  navigationQueryRepository: NavigationQueryRepository[ConnectionIO],
  userAccountRepository: UserAccountRepository[ConnectionIO],
  authSessionRepository: AuthSessionRepository[ConnectionIO],
  membershipRepository: OrganizationMembershipRepository[ConnectionIO]
)

/** Builds every PostgreSQL-backed port on top of one transactor. */
object PersistenceModule {

  def build(xa: Transactor[IO]): PersistenceComponents =
    PersistenceComponents(
      transactionRunner = new DoobieTransactionRunner(xa),
      readinessCheck = new PostgresReadinessCheck(xa),
      resourceRepository = new PostgresResourceRepository,
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
      navigationQueryRepository = new PostgresNavigationQueryRepository,
      userAccountRepository = new PostgresUserAccountRepository,
      authSessionRepository = new PostgresAuthSessionRepository,
      membershipRepository = new PostgresOrganizationMembershipRepository
    )
}

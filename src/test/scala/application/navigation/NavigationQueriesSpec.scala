package ru.bitec.app.ops
package application.navigation

import application.port.{EnvironmentRepository, OrganizationRepository, ProjectRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.enviroment.{Environment, EnvironmentKind}
import domain.organization.Organization
import domain.project.Project
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class NavigationQueriesSpec extends FunSuite {
  test("ListProjects distinguishes an unknown organization from an empty project list") {
    val noOrganization = ListProjects[IO](
      new InMemoryOrganizationRepository(List.empty),
      new InMemoryProjectRepository(List.empty)
    )
    val knownWithoutProjects = ListProjects[IO](
      new InMemoryOrganizationRepository(List(organizationA)),
      new InMemoryProjectRepository(List.empty)
    )

    assertEquals(noOrganization.execute(OrganizationAId).unsafeRunSync(), None)
    assertEquals(knownWithoutProjects.execute(OrganizationAId).unsafeRunSync(), Some(List.empty))
  }

  test("ListProjects returns only active projects for the selected organization") {
    val query = ListProjects[IO](
      new InMemoryOrganizationRepository(List(organizationA)),
      new InMemoryProjectRepository(List(projectA, inactiveProjectA, projectB))
    )

    assertEquals(query.execute(OrganizationAId).unsafeRunSync(), Some(List(projectA)))
  }

  test("ListEnvironments returns None for missing and foreign projects") {
    val query = ListEnvironments[IO](
      new InMemoryOrganizationRepository(List(organizationA)),
      new InMemoryProjectRepository(List(projectB)),
      new InMemoryEnvironmentRepository(List(environmentB))
    )

    assertEquals(query.execute(OrganizationAId, UnknownProjectId).unsafeRunSync(), None)
    assertEquals(query.execute(OrganizationAId, ProjectBId).unsafeRunSync(), None)
  }

  test("ListEnvironments distinguishes a valid empty project and scopes environments to it") {
    val emptyQuery = ListEnvironments[IO](
      new InMemoryOrganizationRepository(List(organizationA)),
      new InMemoryProjectRepository(List(projectA)),
      new InMemoryEnvironmentRepository(List.empty)
    )
    val scopedQuery = ListEnvironments[IO](
      new InMemoryOrganizationRepository(List(organizationA)),
      new InMemoryProjectRepository(List(projectA)),
      new InMemoryEnvironmentRepository(List(environmentA, environmentB, inactiveEnvironmentA))
    )

    assertEquals(emptyQuery.execute(OrganizationAId, ProjectAId).unsafeRunSync(), Some(List.empty))
    assertEquals(scopedQuery.execute(OrganizationAId, ProjectAId).unsafeRunSync(), Some(List(environmentA)))
  }

  private final class InMemoryOrganizationRepository(
    organizations: List[Organization]
  ) extends OrganizationRepository[IO] {
    override def findActiveById(id: UUID): IO[Option[Organization]] =
      IO.pure(organizations.find(organization => organization.id == id && organization.isActive))
  }

  private final class InMemoryProjectRepository(
    projects: List[Project]
  ) extends ProjectRepository[IO] {
    override def findActiveByOrganization(organizationId: UUID): IO[List[Project]] =
      IO.pure(projects.filter(project => project.organizationId == organizationId && project.isActive))

    override def findActiveById(
      organizationId: UUID,
      projectId: UUID
    ): IO[Option[Project]] =
      IO.pure(projects.find(project =>
        project.organizationId == organizationId && project.id == projectId && project.isActive
      ))
  }

  private final class InMemoryEnvironmentRepository(
    environments: List[Environment]
  ) extends EnvironmentRepository[IO] {
    override def findActiveByProject(
      organizationId: UUID,
      projectId: UUID
    ): IO[List[Environment]] =
      IO.pure(environments.filter(environment =>
        environment.organizationId == organizationId && environment.projectId == projectId && environment.isActive
      ))
  }

  private val OrganizationAId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val OrganizationBId = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val ProjectAId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val ProjectBId = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val UnknownProjectId = UUID.fromString("20000000-0000-0000-0000-000000000099")
  private val EnvironmentAId = UUID.fromString("30000000-0000-0000-0000-000000000001")
  private val EnvironmentBId = UUID.fromString("30000000-0000-0000-0000-000000000002")
  private val Now = Instant.parse("2026-09-22T12:00:00Z")

  private val organizationA = Organization(OrganizationAId, "A", "Organization A", true, Now, Now)
  private val projectA = Project(ProjectAId, OrganizationAId, "A", "Project A", None, true, Now, Now)
  private val inactiveProjectA = projectA.copy(id = UUID.fromString("20000000-0000-0000-0000-000000000003"), isActive = false)
  private val projectB = Project(ProjectBId, OrganizationBId, "B", "Project B", None, true, Now, Now)
  private val environmentA = Environment(EnvironmentAId, OrganizationAId, ProjectAId, "prod", "Production", EnvironmentKind.Prod, true, Now, Now)
  private val inactiveEnvironmentA = environmentA.copy(id = UUID.fromString("30000000-0000-0000-0000-000000000003"), isActive = false)
  private val environmentB = Environment(EnvironmentBId, OrganizationBId, ProjectBId, "prod", "Production", EnvironmentKind.Prod, true, Now, Now)
}

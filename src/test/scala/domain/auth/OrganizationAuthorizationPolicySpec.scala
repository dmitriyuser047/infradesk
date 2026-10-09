package ru.bitec.app.ops
package domain.auth

import munit.FunSuite

final class OrganizationAuthorizationPolicySpec extends FunSuite {
  test("organization operators have bounded operational capabilities and administrators manage members") {
    assertEquals(OrganizationAuthorizationPolicy.permissions(OrganizationRole.Operator), Set[OrganizationPermission](
      OrganizationPermission.ReadOrganization, OrganizationPermission.RunConnectionSync,
      OrganizationPermission.ExecuteOperations, OrganizationPermission.ManageMonitoring))
    assert(!OrganizationAuthorizationPolicy.allows(OrganizationRole.Operator, OrganizationPermission.OpenTerminal))
    assert(!OrganizationAuthorizationPolicy.allows(OrganizationRole.Member, OrganizationPermission.ManageMembers))
    assert(OrganizationAuthorizationPolicy.allows(OrganizationRole.Administrator, OrganizationPermission.ManageMembers))
  }

  test("an owner carries every capability this version defines") {
    OrganizationPermission.All.foreach(permission =>
      assert(
        OrganizationAuthorizationPolicy.allows(OrganizationRole.Owner, permission),
        s"owner is missing ${permission.code}"
      )
    )
    assertEquals(
      OrganizationAuthorizationPolicy.permissions(OrganizationRole.Owner),
      OrganizationPermission.All.toSet
    )
    OrganizationPermission.All.foreach(permission =>
      assertEquals(OrganizationAuthorizationPolicy.require(OrganizationRole.Owner, permission), Right(()))
    )
  }

  test("a member reads the organization and manages nothing") {
    assert(OrganizationAuthorizationPolicy.allows(OrganizationRole.Member,
      OrganizationPermission.ReadOrganization))

    List(
      OrganizationPermission.ManageWorkspace,
      OrganizationPermission.ManageConnections,
      OrganizationPermission.RunConnectionSync,
      OrganizationPermission.ManageMonitoring,
      OrganizationPermission.ViewAudit,
      OrganizationPermission.ExecuteOperations
    ).foreach(permission =>
      assertEquals(
        OrganizationAuthorizationPolicy.allows(OrganizationRole.Member, permission),
        false,
        s"member unexpectedly allowed ${permission.code}"
      )
    )
    assertEquals(
      OrganizationAuthorizationPolicy.permissions(OrganizationRole.Member),
      Set[OrganizationPermission](OrganizationPermission.ReadOrganization)
    )
    assertEquals(
      OrganizationAuthorizationPolicy.require(
        OrganizationRole.Member,
        OrganizationPermission.ManageMonitoring
      ),
      Left(OrganizationPermissionDenied(OrganizationPermission.ManageMonitoring))
    )
  }

  test("permission codes round-trip and reject unknown values") {
    OrganizationPermission.All.foreach(permission =>
      assertEquals(OrganizationPermission.fromCode(permission.code), Right(permission))
    )
    assert(OrganizationPermission.fromCode("MANAGE_USERS").isLeft)
    // Two roles, and no role-specific detail leaks through the denial type.
    assertEquals(OrganizationRole.fromCode("ADMIN").isLeft, true)
    assertEquals(
      OrganizationPermissionDenied(OrganizationPermission.ViewAudit).permission,
      OrganizationPermission.ViewAudit
    )
  }
}

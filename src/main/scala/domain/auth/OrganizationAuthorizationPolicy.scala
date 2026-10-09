package ru.bitec.app.ops
package domain.auth

/** Which capabilities each organization role carries.
  *
  * The policy is a pure function of the role that the membership lookup already returned, so a
  * permission check costs no query: this version has no permission tables and no custom roles.
  */
object OrganizationAuthorizationPolicy {

  private val ownerPermissions: Set[OrganizationPermission] = OrganizationPermission.All.toSet

  // A member reads the organization and changes nothing.
  private val memberPermissions: Set[OrganizationPermission] =
    Set(OrganizationPermission.ReadOrganization)

  def permissions(role: OrganizationRole): Set[OrganizationPermission] = role match {
    case OrganizationRole.Owner => ownerPermissions
    case OrganizationRole.Administrator => ownerPermissions
    case OrganizationRole.Operator => Set(OrganizationPermission.ReadOrganization,
      OrganizationPermission.RunConnectionSync, OrganizationPermission.ExecuteOperations,
      OrganizationPermission.ManageMonitoring)
    case OrganizationRole.Member => memberPermissions
  }

  def allows(role: OrganizationRole, permission: OrganizationPermission): Boolean =
    permissions(role).contains(permission)

  def require(
    role: OrganizationRole,
    permission: OrganizationPermission
  ): Either[OrganizationPermissionDenied, Unit] =
    Either.cond(allows(role, permission), (), OrganizationPermissionDenied(permission))
}

package ru.bitec.app.ops
package domain.auth

/** What an operation needs, expressed as a capability rather than as a URL or a role.
  *
  * Routes and use cases name the permission they require; the policy decides which role carries
  * it. A new privileged operation therefore declares its capability next to its handler instead
  * of being added to a central list of owner-only paths.
  */
sealed trait OrganizationPermission {
  def code: String
}

object OrganizationPermission {
  case object ManageMembers extends OrganizationPermission { val code = "MANAGE_MEMBERS" }

  /** Reading anything inside an organization the user is a member of. */
  case object ReadOrganization extends OrganizationPermission {
    override val code: String = "READ_ORGANIZATION"
  }

  /** Creating and changing projects and environments. */
  case object ManageWorkspace extends OrganizationPermission {
    override val code: String = "MANAGE_WORKSPACE"
  }

  /** Creating, changing and removing connections, including their secrets. */
  case object ManageConnections extends OrganizationPermission {
    override val code: String = "MANAGE_CONNECTIONS"
  }

  /** Running a synchronization by hand, as opposed to configuring one. */
  case object RunConnectionSync extends OrganizationPermission {
    override val code: String = "RUN_CONNECTION_SYNC"
  }

  /** Creating and changing monitor rules. */
  case object ManageMonitoring extends OrganizationPermission {
    override val code: String = "MANAGE_MONITORING"
  }

  /** Reading the audit journal. */
  case object ViewAudit extends OrganizationPermission {
    override val code: String = "VIEW_AUDIT"
  }

  /** Configuring where notifications go: channels, their subscriptions and their credentials.
    *
    * A channel holds a credential and decides who learns about an incident, so reading the list
    * requires the same capability as changing it: this is settings, not reporting.
    */
  case object ManageNotifications extends OrganizationPermission {
    override val code: String = "MANAGE_NOTIFICATIONS"
  }

  case object ManageIntegrations extends OrganizationPermission {
    override val code: String = "MANAGE_INTEGRATIONS"
  }

  case object ExecuteOperations extends OrganizationPermission {
    override val code: String = "EXECUTE_OPERATIONS"
  }

  case object OpenTerminal extends OrganizationPermission {
    override val code: String = "OPEN_TERMINAL"
  }

  /** Reading and changing configuration profiles and their revisions.
    *
    * Server configuration is sensitive operational information even without secrets, so reading
    * a profile requires the same capability as changing it.
    */
  case object ManageConfigurations extends OrganizationPermission {
    override val code: String = "MANAGE_CONFIGURATIONS"
  }

  /** Reading remote configuration and applying desired state over SSH. */
  case object DeployConfigurations extends OrganizationPermission {
    override val code: String = "DEPLOY_CONFIGURATIONS"
  }

  val All: List[OrganizationPermission] = List(
    ManageMembers,
    ReadOrganization,
    ManageWorkspace,
    ManageConnections,
    RunConnectionSync,
    ManageMonitoring,
    ViewAudit,
    ManageNotifications,
    ManageIntegrations,
    ExecuteOperations,
    OpenTerminal,
    ManageConfigurations,
    DeployConfigurations
  )

  def fromCode(code: String): Either[IllegalArgumentException, OrganizationPermission] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported organization permission '$code'"))
}

/** Raised when a member of the organization lacks the capability an operation requires. */
final case class OrganizationPermissionDenied(permission: OrganizationPermission)
  extends RuntimeException(s"Operation requires permission '${permission.code}'")

package ru.bitec.app.ops
package domain.auth

import java.time.Instant
import java.util.UUID

sealed trait OrganizationRole {
  def code: String
}

object OrganizationRole {
  case object Owner extends OrganizationRole { val code = "OWNER" }
  case object Member extends OrganizationRole { val code = "MEMBER" }

  def fromCode(code: String): Either[IllegalArgumentException, OrganizationRole] = code match {
    case Owner.code => Right(Owner)
    case Member.code => Right(Member)
    case other => Left(new IllegalArgumentException(s"Unknown organization role: $other"))
  }
}

final case class OrganizationMembership(
  userId: UUID,
  organizationId: UUID,
  role: OrganizationRole,
  isActive: Boolean,
  createdAt: Instant,
  updatedAt: Instant
)

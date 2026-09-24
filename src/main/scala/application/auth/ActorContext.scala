package ru.bitec.app.ops
package application.auth

import java.util.UUID

/** Who is performing a mutation, and in which organization.
  *
  * Mutation use cases take this explicitly instead of reaching for a current user: there is no
  * ambient actor, so a new operation cannot forget to say on whose behalf it runs.
  */
final case class ActorContext(userId: UUID, organizationId: UUID)

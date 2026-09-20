package ru.bitec.app.ops
package application.port

import ru.bitec.app.ops.domain.externalref.ExternalRef

import java.util.UUID
import scala.language.higherKinds

trait ExternalRefRepository[F[_]] {

  def findByExternalIdentity(
                              organizationId: UUID,
                              connectionId: UUID,
                              externalType: String,
                              externalId: String
                            ): F[Option[ExternalRef]]

  def save(externalRef: ExternalRef): F[Unit]
}
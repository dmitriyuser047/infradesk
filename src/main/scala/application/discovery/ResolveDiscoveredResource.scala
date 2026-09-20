package ru.bitec.app.ops
package application.discovery

import application.port.ExternalRefRepository
import domain.connection.Connection
import domain.externalref.ExternalRef

import cats.Functor
import cats.syntax.all._

sealed trait DiscoveredResourceResolution

object DiscoveredResourceResolution {
  final case class Existing(externalRef: ExternalRef) extends DiscoveredResourceResolution
  case object New extends DiscoveredResourceResolution
}

final class ResolveDiscoveredResource[Tx[_]: Functor](
                                                       externalRefRepository: ExternalRefRepository[Tx]
                                                     ) {

  def execute(
               connection: Connection,
               discovered: DiscoveredResource
             ): Tx[DiscoveredResourceResolution] =
    externalRefRepository
      .findByExternalIdentity(
        connection.organizationId,
        connection.id,
        discovered.externalType,
        discovered.externalId
      )
      .map {
        case Some(externalRef) =>
          DiscoveredResourceResolution.Existing(externalRef)

        case None =>
          DiscoveredResourceResolution.New
      }
}
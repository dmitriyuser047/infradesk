package ru.bitec.app.ops
package application.resource

import application.port.{ExternalRefRepository, ResourceRepository}
import domain.externalref.ExternalRef
import domain.resource.Resource

import cats.Monad
import cats.syntax.all._

final class PersistExternalResource[Tx[_]: Monad](
                                                   resourceRepository: ResourceRepository[Tx],
                                                   externalRefRepository: ExternalRefRepository[Tx]
                                                 ) {

  def execute(resource: Resource, externalRef: ExternalRef): Tx[Unit] =
    for {
      _ <- resourceRepository.save(resource)
      _ <- externalRefRepository.save(externalRef)
    } yield ()
}
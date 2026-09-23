package ru.bitec.app.ops
package persistence.postgres

import bootstrap.PersistenceModule

/** The resource type registration the application ships with, so integration tests exercise the
  * same registry the composition root builds.
  */
object ProductionResourceCodec {

  val codec: ResourceDataJsonCodec =
    new ResourceDataJsonCodec(
      PersistenceModule.resourceDefinitionRegistry.fold(throw _, identity)
    )

  def resourceRepository: PostgresResourceRepository = new PostgresResourceRepository(codec)
}

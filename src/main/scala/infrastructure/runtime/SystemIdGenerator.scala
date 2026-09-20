package ru.bitec.app.ops
package infrastructure.runtime

import application.port.IdGenerator
import cats.effect.IO

import java.util.UUID

final class SystemIdGenerator extends IdGenerator[IO] {
  override def nextId: IO[UUID] =
    IO(UUID.randomUUID())
}
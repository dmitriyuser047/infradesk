package ru.bitec.app.ops
package infrastructure.runtime

import application.port.TimeProvider
import cats.effect.IO

import java.time.Instant

final class SystemTimeProvider extends TimeProvider[IO] {
  override def now: IO[Instant] = IO(Instant.now())
}
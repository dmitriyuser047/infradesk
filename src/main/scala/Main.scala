package ru.bitec.app.ops

import bootstrap.InfraDeskApplication
import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {
  override def run: IO[Unit] = InfraDeskApplication.run
}

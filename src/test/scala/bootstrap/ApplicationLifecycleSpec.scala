package ru.bitec.app.ops
package bootstrap

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import scala.concurrent.duration._

/** The scheduler runs inside the server resource scope: it is not a detached fiber, and the
  * server is always released when the scheduler loop ends or the application is cancelled.
  */
final class ApplicationLifecycleSpec extends FunSuite {

  test("cancelling the runtime releases the server resource") {
    val program = for {
      acquired <- Deferred[IO, Unit]
      released <- Ref[IO].of(false)
      server = Resource.make(acquired.complete(()).void)(_ => released.set(true))
      fiber <- InfraDeskApplication.serve(server, IO.never[Nothing]).start
      _ <- acquired.get
      _ <- fiber.cancel
      wasReleased <- released.get
    } yield wasReleased

    assert(program.unsafeRunSync())
  }

  test("a failing scheduler loop releases the server resource and fails the runtime") {
    val failure = new IllegalStateException("scheduler stopped")
    val program = for {
      released <- Ref[IO].of(false)
      server = Resource.make(IO.unit)(_ => released.set(true))
      outcome <- InfraDeskApplication.serve(server, IO.raiseError[Nothing](failure)).attempt
      wasReleased <- released.get
    } yield (outcome, wasReleased)

    val (outcome, wasReleased) = program.unsafeRunSync()
    assertEquals(outcome, Left(failure))
    assert(wasReleased)
  }

  test("the scheduler only runs after the server resource is acquired") {
    val program = for {
      events <- Ref[IO].of(List.empty[String])
      server = Resource.make(events.update(_ :+ "server.acquired"))(_ => events.update(_ :+ "server.released"))
      _ <- InfraDeskApplication.serve(server, events.update(_ :+ "scheduler.started") *> IO.never[Nothing])
        .timeoutTo(1.second, IO.unit)
      recorded <- events.get
    } yield recorded

    assertEquals(program.unsafeRunSync(), List("server.acquired", "scheduler.started", "server.released"))
  }
}

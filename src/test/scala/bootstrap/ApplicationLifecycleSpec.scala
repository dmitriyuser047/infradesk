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
      fiber <- InfraDeskApplication.serve(server, List(IO.never[Nothing])).start
      _ <- acquired.get
      _ <- fiber.cancel
      wasReleased <- released.get
    } yield wasReleased

    assert(program.unsafeRunSync())
  }

  test("cancelling the runtime cancels every background worker") {
    val program = for {
      started <- Ref[IO].of(Set.empty[String])
      cancelled <- Ref[IO].of(Set.empty[String])
      server = Resource.make(IO.unit)(_ => IO.unit)
      worker = (name: String) =>
        (started.update(_ + name) *> IO.never[Nothing]).onCancel(cancelled.update(_ + name))
      fiber <- InfraDeskApplication.serve(server, List(worker("scheduler"), worker("dispatcher"))).start
      _ <- IO.sleep(200.milliseconds)
      _ <- fiber.cancel
      startedNames <- started.get
      cancelledNames <- cancelled.get
    } yield (startedNames, cancelledNames)

    val (startedNames, cancelledNames) = program.unsafeRunSync()
    assertEquals(startedNames, Set("scheduler", "dispatcher"))
    assertEquals(cancelledNames, Set("scheduler", "dispatcher"))
  }

  test("a failing worker cancels its siblings and fails the runtime") {
    val failure = new IllegalStateException("dispatcher stopped")
    val program = for {
      cancelled <- Ref[IO].of(false)
      released <- Ref[IO].of(false)
      server = Resource.make(IO.unit)(_ => released.set(true))
      sibling = IO.never[Nothing].onCancel(cancelled.set(true))
      failing = IO.sleep(100.milliseconds) *> IO.raiseError[Nothing](failure)
      outcome <- InfraDeskApplication.serve(server, List(sibling, failing)).attempt
      wasCancelled <- cancelled.get
      wasReleased <- released.get
    } yield (outcome, wasCancelled, wasReleased)

    val (outcome, wasCancelled, wasReleased) = program.unsafeRunSync()
    assertEquals(outcome, Left(failure))
    assert(wasCancelled)
    assert(wasReleased)
  }

  test("a failing scheduler loop releases the server resource and fails the runtime") {
    val failure = new IllegalStateException("scheduler stopped")
    val program = for {
      released <- Ref[IO].of(false)
      server = Resource.make(IO.unit)(_ => released.set(true))
      outcome <- InfraDeskApplication.serve(server, List(IO.raiseError[Nothing](failure))).attempt
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
      _ <- InfraDeskApplication.serve(server, List(events.update(_ :+ "scheduler.started") *> IO.never[Nothing]))
        .timeoutTo(1.second, IO.unit)
      recorded <- events.get
    } yield recorded

    assertEquals(program.unsafeRunSync(), List("server.acquired", "scheduler.started", "server.released"))
  }
}

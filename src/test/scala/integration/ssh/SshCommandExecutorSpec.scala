package ru.bitec.app.ops
package integration.ssh

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

final class SshCommandExecutorSpec extends FunSuite {
  private val policy = SshCommandExecutionPolicy(stdoutMaxBytes = 8, stderrMaxBytes = 6)
  private val executor = new SshCommandExecutor[IO](policy)

  test("captures stdout and stderr under their limits and releases the command") {
    val command = new FakeCommand("stdout", "error", Some(0))
    val result = executor.execute(command, timeoutSeconds = 1).unsafeRunSync()
    assertEquals(result, CapturedSshCommand(0, "stdout", "error"))
    assertEquals(command.closeCount.get(), 1)
  }

  test("drains stdout and stderr while the remote command is still running") {
    val drained = new CountDownLatch(2)
    val command = new RunningSshCommand {
      val closeCount = new AtomicInteger(0)
      override val stdout: InputStream = signallingBytes("stdout", drained)
      override val stderr: InputStream = signallingBytes("error", drained)
      override def await(timeoutSeconds: Int): Option[Int] =
        if (drained.await(1, TimeUnit.SECONDS)) Some(0)
        else throw new IllegalStateException("streams were not drained concurrently")
      override def close(): Unit = closeCount.incrementAndGet()
    }

    assertEquals(executor.execute(command, 1).unsafeRunSync(), CapturedSshCommand(0, "stdout", "error"))
  }

  test("fails with a typed error when stdout exceeds its limit") {
    val command = new FakeCommand("123456789", "", Some(0))
    intercept[SshTransportFailure.CommandOutputLimitExceeded] {
      executor.execute(command, 1).unsafeRunSync()
    }
    assertEquals(command.closeCount.get(), 1)
  }

  test("fails with a typed error when stderr exceeds its limit") {
    val command = new FakeCommand("", "1234567", Some(0))
    intercept[SshTransportFailure.CommandOutputLimitExceeded] {
      executor.execute(command, 1).unsafeRunSync()
    }
    assertEquals(command.closeCount.get(), 1)
  }

  test("opt-in bounded capture truncates while continuing to drain and flags the result") {
    val bounded = new SshCommandExecutor[IO](policy.copy(truncateOverflow = true))
    val command = new FakeCommand("12345678901234567890", "abcdefghi", Some(0))
    val result = bounded.execute(command, 1).unsafeRunSync()
    assertEquals(result.stdout, "12345678")
    assertEquals(result.stderr, "abcdef")
    assert(result.stdoutTruncated)
    assert(result.stderrTruncated)
    assertEquals(command.closeCount.get(), 1)
  }

  test("turns an unfinished command into a typed timeout and releases it") {
    val command = new FakeCommand("", "", None)
    intercept[SshTransportFailure.CommandTimeout] {
      executor.execute(command, 1).unsafeRunSync()
    }
    assertEquals(command.closeCount.get(), 1)
  }

  test("keeps output-limit failure primary when close also fails") {
    val closeFailure = new IllegalStateException("close failed")
    val command = new FakeCommand("123456789", "", Some(0), Some(closeFailure))
    val failure = intercept[SshTransportFailure.CommandOutputLimitExceeded] {
      executor.execute(command, 1).unsafeRunSync()
    }
    assertEquals(failure.getSuppressed.toList, List(closeFailure))
    assertEquals(command.closeCount.get(), 1)
  }

  private final class FakeCommand(
    out: String,
    err: String,
    exit: Option[Int],
    closeFailure: Option[Throwable] = None
  ) extends RunningSshCommand {
    val closeCount = new AtomicInteger(0)
    override val stdout: InputStream = bytes(out)
    override val stderr: InputStream = bytes(err)
    override def await(timeoutSeconds: Int): Option[Int] = exit
    override def close(): Unit = {
      closeCount.incrementAndGet()
      closeFailure.foreach(throw _)
    }
  }

  private def bytes(value: String): InputStream =
    new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))

  private def signallingBytes(value: String, drained: CountDownLatch): InputStream =
    new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)) {
      private val signalled = new AtomicBoolean(false)
      override def read(buffer: Array[Byte], offset: Int, length: Int): Int = {
        val result = super.read(buffer, offset, length)
        if (result == -1 && signalled.compareAndSet(false, true)) drained.countDown()
        result
      }
    }
}

package ru.bitec.app.ops
package integration.ssh.node

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.sys.process._
import scala.util.Try

/** The collector extracts its values remotely, so the extraction itself is what has to be tested:
  * a field picked by the wrong pattern never reaches the parser as anything but a plausible
  * string. These run the real awk program against real `/proc/cpuinfo` shapes.
  */
final class SshNodeInventoryCollectorSpec extends FunSuite {

  test("the processor name wins over the numeric model that precedes it") {
    // Exactly what a Ryzen host reports: `model` is 97 and comes first, `model name` follows.
    val amd = run(
      """processor	: 0
        |vendor_id	: AuthenticAMD
        |cpu family	: 25
        |model		: 97
        |model name	: AMD Ryzen 9 7950X3D 16-Core Processor
        |stepping	: 2
        |cpu MHz		: 4200.000
        |""".stripMargin
    )

    assertEquals(amd, Some("AMD Ryzen 9 7950X3D 16-Core Processor"))
    assertNotEquals(amd, Some("97"))
  }

  test("the same holds for an Intel host, so the fix is not vendor-specific") {
    val intel = run(
      """processor	: 0
        |vendor_id	: GenuineIntel
        |cpu family	: 6
        |model		: 85
        |model name	: Intel(R) Xeon(R) CPU E5-2680 v4 @ 2.40GHz
        |stepping	: 7
        |""".stripMargin
    )

    assertEquals(intel, Some("Intel(R) Xeon(R) CPU E5-2680 v4 @ 2.40GHz"))
  }

  test("only the first processor is reported, whatever the core count") {
    val twoCores = run(
      """processor	: 0
        |model		: 97
        |model name	: AMD Ryzen 9 7950X3D 16-Core Processor
        |
        |processor	: 1
        |model		: 97
        |model name	: AMD Ryzen 9 7950X3D 16-Core Processor
        |""".stripMargin
    )

    assertEquals(twoCores, Some("AMD Ryzen 9 7950X3D 16-Core Processor"))
  }

  test("an ARM board falls back to its Hardware line") {
    // These files carry no `model name` at all, which is why the fallback exists.
    val arm = run(
      """processor	: 0
        |BogoMIPS	: 108.00
        |Features	: fp asimd evtstrm
        |Hardware	: BCM2835
        |Revision	: c03111
        |""".stripMargin
    )

    assertEquals(arm, Some("BCM2835"))
  }

  test("a file without a processor name yields nothing rather than a wrong value") {
    // Tolerant inventory: an absent optional field stays absent and the rest still arrives.
    assertEquals(run("processor\t: 0\nmodel\t\t: 97\ncpu family\t: 25\n"), None)
    assertEquals(run(""), None)
  }

  test("the command asks for the processor name once, in the single composite call") {
    val command = SshNodeInventoryCollector.Command

    assertEquals("/proc/cpuinfo".r.findAllIn(command).length, 1)
    assert(command.contains(SshNodeInventoryCollector.CpuModelProgram))
    // The numeric `model` field must not be selectable any more.
    assert(!command.contains("model name|hardware|model"))
    // IGNORECASE is a gawk extension and silently does nothing under the mawk a Debian host uses.
    assert(!command.contains("IGNORECASE"))
  }

  /** Runs the collector's own awk program over a `/proc/cpuinfo` fixture. */
  private def run(cpuinfo: String): Option[String] = {
    assume(awkAvailable, "POSIX awk is required to exercise the inventory command")

    val fixture = Files.createTempFile("cpuinfo", ".txt")
    // The program goes through a file rather than an argument: quoting an awk program as a
    // process argument is not portable, and what is under test is the program, not the quoting.
    val program = Files.createTempFile("cpu-model", ".awk")
    try {
      Files.write(fixture, cpuinfo.getBytes(StandardCharsets.UTF_8))
      Files.write(program, SshNodeInventoryCollector.CpuModelProgram.getBytes(StandardCharsets.UTF_8))
      val output = Seq("awk", "-f", program.toString, fixture.toString).!!
      Option(output.trim).filter(_.nonEmpty)
    } finally {
      Files.deleteIfExists(fixture)
      Files.deleteIfExists(program)
      ()
    }
  }

  private lazy val awkAvailable: Boolean =
    Try(Seq("awk", "BEGIN { exit 0 }").! == 0).getOrElse(false)
}

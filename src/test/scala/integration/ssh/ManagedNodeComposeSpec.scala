package ru.bitec.app.ops
package integration.ssh

import application.port.RemnawaveNodeRemoteSpec
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import munit.FunSuite

/** Runs the actual Compose parser, without a daemon, image pull, container or mocked executable. */
final class ManagedNodeComposeSpec extends FunSuite {
  override val munitTimeout = scala.concurrent.duration.DurationInt(60).seconds
  private def execute(argv: List[String]): Int = {
    // Diagnostics may contain env data: discard without attaching them to assertions.
    val process = new ProcessBuilder(argv: _*).redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly()
      fail("Compose validation exceeded its bounded timeout")
    }
    process.exitValue()
  }
  test("real Compose resolves service env_file in the intended project for install, repair, switch and rollback") {
    assertEquals(execute(List("docker", "compose", "version")), 0, "Real Docker Compose is required")
    val root = Files.createTempDirectory("infradesk-compose-regression-")
    try {
      List("initial-staging", "existing-repair", "image-switch", "image-rollback").foreach { context =>
        val project = Files.createDirectory(root.resolve(context))
        val candidateDir = Files.createDirectory(project.resolve(".infradesk-test"))
        val candidate = candidateDir.resolve("candidate")
        Files.writeString(project.resolve(".env"), "SECRET_KEY=dGVzdC1vbmx5\nNODE_PORT=2222\n", UTF_8)
        val spec = RemnawaveNodeRemoteSpec(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
          2222, "remnawave/node:3.4.1", List("192.0.2.1/32"))
        Files.writeString(candidate, SshRemnawaveNodeRemote.renderCompose(spec), UTF_8)
        assert(!Files.exists(candidateDir.resolve(".env")))
        val command = ManagedNodeCompose.validation(project.toString, candidate.toString)
        assertEquals(execute(command), 0, context)
        val directoryOption = command.indexOf("--project-directory")
        val negative = command.take(directoryOption) ++ command.drop(directoryOption + 2)
        assert(execute(negative) != 0, s"Negative control must reproduce the env_file regression: $context")
      }
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
      finally paths.close()
    }
  }
}

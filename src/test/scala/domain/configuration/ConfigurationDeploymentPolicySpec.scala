package ru.bitec.app.ops
package domain.configuration

import munit.FunSuite

import java.util.UUID

final class ConfigurationDeploymentPolicySpec extends FunSuite {
  private val octal = (text: String) => Integer.parseInt(text, 8)

  private def policy(activation: ConfigurationActivation = ConfigurationActivation.None, unit: Option[String] = None,
                     validator: Option[ConfigurationValidator] = None, mode: Int = octal("644")) =
    ConfigurationExecutionPolicy(activation, unit, validator, mode)

  private def valid(value: ConfigurationExecutionPolicy): Boolean = ConfigurationExecutionPolicy.validate(value).isRight

  test("systemd unit names are strict, and activation requires one") {
    List("xray.service", "nginx.service", "my-app.service", "getty@tty1.service", "a_b:c.service")
      .foreach(unit => assert(valid(policy(ConfigurationActivation.SystemdRestart, Some(unit))), unit))
    List("xray", "x.service; reboot", "x.service && id", "$(id).service", "a b.service", "x.service\n",
      "\"x\".service", "-x.service", "x|y.service", "`id`.service", "x.socket")
      .foreach(unit => assert(!valid(policy(ConfigurationActivation.SystemdReload, Some(unit))), unit))
    assert(!valid(policy(ConfigurationActivation.SystemdRestart, None)))
    assert(!valid(policy(ConfigurationActivation.None, Some("xray.service"))))
  }

  test("a validator is an absolute executable with literal arguments and only two placeholders") {
    val nginx = ConfigurationValidator("/usr/sbin/nginx", List("-t", "-c", "{candidate}"))
    val xray = ConfigurationValidator("/usr/local/bin/xray", List("run", "-test", "-config", "{candidate}"))
    assert(valid(policy(validator = Some(nginx))))
    assert(valid(policy(validator = Some(xray))))
    assert(valid(policy(validator = Some(ConfigurationValidator("/opt/check", List("--against={target}", "a b", "it's"))))))
    List("nginx", "./nginx", "/usr/../bin/sh", "/usr/sbin/nginx -t", "/usr/sbin/ng;x", "/usr/sbin/$x", "")
      .foreach(executable => assert(!valid(policy(validator = Some(ConfigurationValidator(executable, Nil)))), executable))
    List("{{ domain }}", "{other}", "$HOME", "$(id)", "`id`", "a\nb", "nul\u0000", "{candidate", "x}", "\u007f")
      .foreach(arg => assert(!valid(policy(validator = Some(ConfigurationValidator("/bin/check", List(arg))))), arg))
    assert(!valid(policy(validator = Some(ConfigurationValidator("/bin/check", List.fill(33)("a"))))))
    assert(!valid(policy(validator = Some(ConfigurationValidator("/bin/check", List("a" * 1025))))))
  }

  test("placeholders become the server-generated paths and nothing else is substituted") {
    val validator = ConfigurationValidator("/opt/check", List("-c", "{candidate}", "--target={target}", "{{x}}"))
    assertEquals(validator.argv("/etc/a/.infradesk-1.tmp", "/etc/a/a.conf"),
      List("-c", "/etc/a/.infradesk-1.tmp", "--target=/etc/a/a.conf", "{{x}}"))
  }

  test("new file modes are plain permission bits") {
    assert(valid(policy(mode = octal("600"))))
    assert(valid(policy(mode = octal("777"))))
    assert(!valid(policy(mode = octal("4755"))))
    assert(!valid(policy(mode = octal("1777"))))
    assert(!valid(policy(mode = -1)))
  }

  test("artifacts sit next to the target and are named only by the deployment") {
    val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
    assertEquals(ConfigurationDeployment.artifactPaths("/etc/xray/config.json", id),
      ConfigurationDeployment.ArtifactPaths(s"/etc/xray/.infradesk-$id.tmp", s"/etc/xray/.infradesk-$id.bak"))
  }

  test("the line diff is a real unified diff with context, not a prefix trim") {
    val before = (1 to 30).map(i => s"line $i").mkString("", "\n", "\n")
    val after = before.replace("line 2\n", "line two\n").replace("line 28\n", "line twenty-eight\n")
    val diff = ConfigurationLineDiff.between(before, after)
    assertEquals((diff.addedLines, diff.removedLines, diff.truncated, diff.approximate), (2, 2, false, false))
    assert(diff.text.contains("@@ -1,5 +1,5 @@"), diff.text)
    assert(diff.text.contains("-line 2\n+line two\n"), diff.text)
    assert(diff.text.contains("-line 28\n+line twenty-eight\n"), diff.text)
    assert(!diff.text.contains("line 15"), "unchanged lines far from a change are not shown")
  }

  test("the line diff handles empty sides, insertions, deletions and final newlines") {
    assertEquals(ConfigurationLineDiff.between("", "a\nb\n").addedLines, 2)
    assertEquals(ConfigurationLineDiff.between("a\nb\n", "").removedLines, 2)
    assertEquals(ConfigurationLineDiff.between("a\nb\n", "a\nb\n").text, "")
    val inserted = ConfigurationLineDiff.between("a\nc\n", "a\nb\nc\n")
    assertEquals((inserted.addedLines, inserted.removedLines), (1, 0))
    assert(ConfigurationLineDiff.between("a\n", "a").text.contains("newline at end of file"))
  }

  test("a huge diff is bounded and still reports counts") {
    val before = (1 to 5000).map(i => s"old $i").mkString("\n")
    val after = (1 to 5000).map(i => s"new $i").mkString("\n")
    val diff = ConfigurationLineDiff.between(before, after)
    assert(diff.truncated)
    assert(diff.text.length <= ConfigurationLineDiff.MaxOutputBytes)
    assertEquals((diff.addedLines, diff.removedLines), (5000, 5000))
    val bounded = ConfigurationLineDiff.between((1 to 900).mkString("\n"), (1 to 900).map(_ + 1).mkString("\n"))
    assert(bounded.text.linesIterator.size <= ConfigurationLineDiff.MaxOutputLines)
  }
}

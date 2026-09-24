package ru.bitec.app.ops
package domain.connection

import munit.FunSuite

final class SshConnectionSettingsSpec extends FunSuite {

  test("valid timeout values and their maximum boundaries are accepted") {
    assertEquals(parsed(10, 30).map(_.connectTimeoutSeconds), Right(10))
    val maximum = parsed(SshConnectionSettings.MaxConnectTimeoutSeconds,
      SshConnectionSettings.MaxCommandTimeoutSeconds)
    assertEquals(maximum.map(_.connectTimeoutSeconds),
      Right(SshConnectionSettings.MaxConnectTimeoutSeconds))
    assertEquals(maximum.map(_.commandTimeoutSeconds),
      Right(SshConnectionSettings.MaxCommandTimeoutSeconds))
  }

  test("zero, negative and values over the production maximum are rejected") {
    List(0, -1, SshConnectionSettings.MaxConnectTimeoutSeconds + 1).foreach { value =>
      assert(parsed(value, 30).isLeft, s"accepted connect timeout $value")
    }
    List(0, -1, SshConnectionSettings.MaxCommandTimeoutSeconds + 1).foreach { value =>
      assert(parsed(10, value).isLeft, s"accepted command timeout $value")
    }
  }

  private def parsed(connect: Int, command: Int): Either[IllegalArgumentException, SshConnectionSettings] =
    SshConnectionSettings.from(ConnectionConfig(Map(
      "host" -> "node.example.test",
      "port" -> "22",
      "username" -> "root",
      "connectTimeoutSeconds" -> connect.toString,
      "commandTimeoutSeconds" -> command.toString
    )))
}

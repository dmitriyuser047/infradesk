package ru.bitec.app.ops
package infrastructure.config

import scala.concurrent.duration._
import scala.util.Try

final case class TerminalConfig(
  initialColumns: Int = 80,
  initialRows: Int = 24,
  maxColumns: Int = 500,
  maxRows: Int = 200,
  maxFrameBytes: Int = 65536,
  maxControlBytes: Int = 8192,
  idleTimeout: FiniteDuration = 30.minutes,
  maxLifetime: FiniteDuration = 2.hours,
  maxConcurrentSessions: Int = 16
) {
  require(initialColumns > 0 && initialColumns <= maxColumns)
  require(initialRows > 0 && initialRows <= maxRows)
  require(maxColumns <= 500 && maxRows <= 200)
  require(maxFrameBytes > 0 && maxFrameBytes <= 65536)
  require(maxControlBytes > 0 && maxControlBytes <= 8192)
  require(idleTimeout > Duration.Zero && maxLifetime >= idleTimeout)
  require(maxConcurrentSessions > 0 && maxConcurrentSessions <= 256)
}

object TerminalConfig {
  val default: TerminalConfig = TerminalConfig()

  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, TerminalConfig] =
    for {
      columns <- bounded(values, "INFRADESK_TERMINAL_INITIAL_COLUMNS", default.initialColumns, 1, 500)
      rows <- bounded(values, "INFRADESK_TERMINAL_INITIAL_ROWS", default.initialRows, 1, 200)
      maxColumns <- bounded(values, "INFRADESK_TERMINAL_MAX_COLUMNS", default.maxColumns, columns, 500)
      maxRows <- bounded(values, "INFRADESK_TERMINAL_MAX_ROWS", default.maxRows, rows, 200)
      frameBytes <- bounded(values, "INFRADESK_TERMINAL_MAX_FRAME_BYTES", default.maxFrameBytes, 1, 65536)
      controlBytes <- bounded(values, "INFRADESK_TERMINAL_MAX_CONTROL_BYTES", default.maxControlBytes, 1, 8192)
      idleSeconds <- bounded(values, "INFRADESK_TERMINAL_IDLE_TIMEOUT_SECONDS", default.idleTimeout.toSeconds.toInt, 1, 7200)
      lifetimeSeconds <- bounded(values, "INFRADESK_TERMINAL_MAX_LIFETIME_SECONDS", default.maxLifetime.toSeconds.toInt, idleSeconds, 86400)
      sessions <- bounded(values, "INFRADESK_TERMINAL_MAX_CONCURRENT_SESSIONS", default.maxConcurrentSessions, 1, 256)
    } yield TerminalConfig(columns, rows, maxColumns, maxRows, frameBytes, controlBytes,
      idleSeconds.seconds, lifetimeSeconds.seconds, sessions)

  private def bounded(values: Map[String, String], key: String, default: Int, min: Int, max: Int):
    Either[IllegalArgumentException, Int] = values.get(key) match {
    case None => Right(default)
    case Some(raw) => Try(raw.trim.toInt).toOption.filter(value => value >= min && value <= max)
      .toRight(new IllegalArgumentException(s"Invalid $key: expected integer $min..$max"))
  }
}

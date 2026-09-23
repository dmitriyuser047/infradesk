package ru.bitec.app.ops
package integration.ssh

/** Startup snapshot for legacy env: secret references; never prints values. */
final class EnvironmentSecrets private (values: Map[String, String]) {
  def get(name: String): Option[String] = values.get(name).filter(_.nonEmpty)
  override def toString: String = "EnvironmentSecrets(<redacted>)"
}

object EnvironmentSecrets {
  def fromEnvironment(values: Map[String, String]): EnvironmentSecrets = new EnvironmentSecrets(values)
}

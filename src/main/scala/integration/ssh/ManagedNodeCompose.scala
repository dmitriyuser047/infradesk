package ru.bitec.app.ops
package integration.ssh

/** Candidate files live below private staging; service env_file paths belong to the installation. */
private[ssh] object ManagedNodeCompose {
  def validation(projectDirectory: String, candidate: String = "{candidate}"): List[String] =
    List("docker", "compose", "--project-directory", projectDirectory, "-f", candidate,
      "--env-file", s"$projectDirectory/.env", "config", "-q")
}

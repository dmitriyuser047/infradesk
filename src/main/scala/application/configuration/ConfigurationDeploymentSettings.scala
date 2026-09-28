package ru.bitec.app.ops
package application.configuration

import scala.concurrent.duration._

/** Every bound of remote configuration work. Nothing a deployment does waits without a limit.
  *
  * @param maxConcurrency           deployments one backend instance runs at the same time
  * @param perOrganizationLimit     running deployments one organization may occupy across instances
  * @param maxRemoteFileBytes       the largest target file read or written; larger files are refused
  * @param validatorTimeout         one run of the structured validator
  * @param activationTimeout        one `systemctl reload|restart`
  * @param healthTimeout            how long `systemctl is-active` may take to report active
  * @param overallTimeout           from the first claim to the end of verification
  * @param maxTransientAttempts     retries after SSH is unreachable, before a deployment gives up
  * @param transientBackoff         wait before a retry after a transient failure
  * @param backupCleanupAttempts    removal attempts of a retained rollout backup before a warning
  */
final case class ConfigurationDeploymentSettings(
  enabled: Boolean = true,
  pollInterval: FiniteDuration = 2.seconds,
  maxConcurrency: Int = 4,
  perOrganizationLimit: Int = 2,
  leaseDuration: FiniteDuration = 2.minutes,
  maxRemoteFileBytes: Int = 2 * 1024 * 1024,
  sftpTimeout: FiniteDuration = 30.seconds,
  validatorTimeout: FiniteDuration = 60.seconds,
  activationTimeout: FiniteDuration = 90.seconds,
  healthTimeout: FiniteDuration = 30.seconds,
  healthPollInterval: FiniteDuration = 1.second,
  overallTimeout: FiniteDuration = 15.minutes,
  maxTransientAttempts: Int = 5,
  transientBackoff: FiniteDuration = 15.seconds,
  backupCleanupAttempts: Int = 5,
  shutdownGrace: FiniteDuration = 60.seconds,
  preflightParallelism: Int = 4
) {
  require(maxConcurrency >= 1 && perOrganizationLimit >= 1 && maxRemoteFileBytes >= 1 &&
    maxTransientAttempts >= 0 && backupCleanupAttempts >= 1 && preflightParallelism >= 1,
    "Invalid configuration deployment settings")

  /** A lease is renewed well before it can lapse under a slow database round trip. */
  def heartbeat: FiniteDuration = leaseDuration / 4
}

object ConfigurationDeploymentSettings {
  val Default: ConfigurationDeploymentSettings = ConfigurationDeploymentSettings()
}

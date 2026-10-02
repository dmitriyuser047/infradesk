package ru.bitec.app.ops
package application.provisioning

import scala.concurrent.duration._

final case class ProvisioningSettings(enabled: Boolean = true, pollInterval: FiniteDuration = 2.seconds,
  batchSize: Int = 10, maxConcurrency: Int = 2, leaseDuration: FiniteDuration = 900.seconds,
  stepTimeout: FiniteDuration = 300.seconds) {
  require(batchSize >= 1 && maxConcurrency >= 1 && leaseDuration > stepTimeout && leaseDuration > 4.seconds)
  def heartbeat: FiniteDuration = leaseDuration / 4
}
object ProvisioningSettings { val Default: ProvisioningSettings = ProvisioningSettings() }

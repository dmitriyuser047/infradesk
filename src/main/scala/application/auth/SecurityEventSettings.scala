package ru.bitec.app.ops
package application.auth

import scala.concurrent.duration._

final case class SecurityEventSettings(retention: FiniteDuration)
object SecurityEventSettings { val default = SecurityEventSettings(90.days) }

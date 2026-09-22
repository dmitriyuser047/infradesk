package ru.bitec.app.ops
package domain.monitor

sealed trait MonitorRuleStatus {
  def code: String
}

object MonitorRuleStatus {

  case object Ok extends MonitorRuleStatus {
    override val code: String = "OK"
  }

  case object Pending extends MonitorRuleStatus {
    override val code: String = "PENDING"
  }

  case object Firing extends MonitorRuleStatus {
    override val code: String = "FIRING"
  }

  def fromCode(code: String): Either[IllegalArgumentException, MonitorRuleStatus] =
    code match {
      case Ok.code =>
        Right(Ok)
      case Pending.code =>
        Right(Pending)
      case Firing.code =>
        Right(Firing)
      case unknown =>
        Left(new IllegalArgumentException(s"Unsupported monitor rule status '$unknown'"))
    }
}

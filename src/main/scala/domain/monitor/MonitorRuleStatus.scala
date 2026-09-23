package ru.bitec.app.ops
package domain.monitor

sealed trait MonitorRuleStatus {
  def code: String
}

object MonitorRuleStatus {

  /** The latest fresh observation satisfies the rule. */
  case object Ok extends MonitorRuleStatus {
    override val code: String = "OK"
  }

  /** The condition is violated but has not lasted for the configured duration yet. */
  case object Pending extends MonitorRuleStatus {
    override val code: String = "PENDING"
  }

  /** The condition has been violated for the configured duration. */
  case object Firing extends MonitorRuleStatus {
    override val code: String = "FIRING"
  }

  /** No observation fresh enough to judge the condition. */
  case object NoData extends MonitorRuleStatus {
    override val code: String = "NO_DATA"
  }

  val All: List[MonitorRuleStatus] = List(Ok, Pending, Firing, NoData)

  def fromCode(code: String): Either[IllegalArgumentException, MonitorRuleStatus] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported monitor rule status '$code'"))
}

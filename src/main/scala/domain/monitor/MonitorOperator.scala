package ru.bitec.app.ops
package domain.monitor

sealed trait MonitorOperator {
  def code: String

  def matches(value: BigDecimal, threshold: BigDecimal): Boolean
}

object MonitorOperator {

  case object GreaterThan extends MonitorOperator {
    override val code: String = "GREATER_THAN"

    override def matches(value: BigDecimal, threshold: BigDecimal): Boolean =
      value > threshold
  }

  case object GreaterThanOrEqual extends MonitorOperator {
    override val code: String = "GREATER_THAN_OR_EQUAL"

    override def matches(value: BigDecimal, threshold: BigDecimal): Boolean =
      value >= threshold
  }

  case object LessThan extends MonitorOperator {
    override val code: String = "LESS_THAN"

    override def matches(value: BigDecimal, threshold: BigDecimal): Boolean =
      value < threshold
  }

  case object LessThanOrEqual extends MonitorOperator {
    override val code: String = "LESS_THAN_OR_EQUAL"

    override def matches(value: BigDecimal, threshold: BigDecimal): Boolean =
      value <= threshold
  }

  val All: List[MonitorOperator] =
    List(GreaterThan, GreaterThanOrEqual, LessThan, LessThanOrEqual)

  def fromCode(code: String): Either[IllegalArgumentException, MonitorOperator] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported monitor operator '$code'"))
}

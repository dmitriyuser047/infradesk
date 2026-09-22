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

  def fromCode(code: String): Either[IllegalArgumentException, MonitorOperator] =
    code match {
      case GreaterThan.code =>
        Right(GreaterThan)
      case unknown =>
        Left(new IllegalArgumentException(s"Unsupported monitor operator '$unknown'"))
    }
}

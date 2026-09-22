package ru.bitec.app.ops
package domain.monitor

sealed trait MonitorOperator {
  def code: String
}

object MonitorOperator {

  case object GreaterThan extends MonitorOperator {
    override val code: String = "GREATER_THAN"
  }

  def fromCode(code: String): Either[IllegalArgumentException, MonitorOperator] =
    code match {
      case GreaterThan.code =>
        Right(GreaterThan)
      case unknown =>
        Left(new IllegalArgumentException(s"Unsupported monitor operator '$unknown'"))
    }
}

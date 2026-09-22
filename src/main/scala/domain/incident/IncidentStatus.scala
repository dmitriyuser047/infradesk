package ru.bitec.app.ops
package domain.incident

sealed trait IncidentStatus {
  def code: String
}

object IncidentStatus {

  case object Open extends IncidentStatus {
    override val code: String = "OPEN"
  }

  case object Resolved extends IncidentStatus {
    override val code: String = "RESOLVED"
  }

  def fromCode(code: String): Either[IllegalArgumentException, IncidentStatus] =
    code match {
      case Open.code => Right(Open)
      case Resolved.code => Right(Resolved)
      case unknown => Left(new IllegalArgumentException(s"Unsupported incident status '$unknown'"))
    }
}

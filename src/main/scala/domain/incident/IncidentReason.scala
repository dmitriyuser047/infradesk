package ru.bitec.app.ops
package domain.incident

/** Why an incident was opened. Incidents opened before monitoring v2 are threshold violations. */
sealed trait IncidentReason {
  def code: String
}

object IncidentReason {

  case object ThresholdViolation extends IncidentReason {
    override val code: String = "THRESHOLD"
  }

  case object NoData extends IncidentReason {
    override val code: String = "NO_DATA"
  }

  val All: List[IncidentReason] = List(ThresholdViolation, NoData)

  def fromCode(code: String): Either[IllegalArgumentException, IncidentReason] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported incident reason '$code'"))
}

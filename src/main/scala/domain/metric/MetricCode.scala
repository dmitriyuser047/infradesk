package ru.bitec.app.ops
package domain.metric

sealed trait MetricCode {
  def code: String

  /** Percentage metrics are bounded to 0..100, which constrains monitor rule thresholds. */
  def isPercentage: Boolean
}

object MetricCode {

  case object CpuUsagePercent extends MetricCode {
    override val code: String = "CPU_USAGE_PERCENT"
    override val isPercentage: Boolean = true
  }

  case object MemoryUsagePercent extends MetricCode {
    override val code: String = "MEMORY_USAGE_PERCENT"
    override val isPercentage: Boolean = true
  }

  val All: List[MetricCode] = List(CpuUsagePercent, MemoryUsagePercent)

  def fromCode(code: String): Either[IllegalArgumentException, MetricCode] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported metric code '$code'"))
}

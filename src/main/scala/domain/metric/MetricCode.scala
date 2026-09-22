package ru.bitec.app.ops
package domain.metric

sealed trait MetricCode {
  def code: String
}

object MetricCode {

  case object CpuUsagePercent extends MetricCode {
    override val code: String = "CPU_USAGE_PERCENT"
  }

  case object MemoryUsagePercent extends MetricCode {
    override val code: String = "MEMORY_USAGE_PERCENT"
  }

  def fromCode(code: String): Either[IllegalArgumentException, MetricCode] =
    code match {
      case CpuUsagePercent.code =>
        Right(CpuUsagePercent)
      case MemoryUsagePercent.code =>
        Right(MemoryUsagePercent)
      case unknown =>
        Left(new IllegalArgumentException(s"Unsupported metric code '$unknown'"))
    }
}

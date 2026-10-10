package ru.bitec.app.ops
package domain.metric

sealed trait MetricCode {
  def code: String

  /** Percentage metrics are bounded to 0..100, which constrains monitor rule thresholds. */
  def isPercentage: Boolean

  /** The unit a value is told in, the same one the interface shows. */
  final def unit: String =
    if (isPercentage) "%"
    else if (code.endsWith("_BYTES_PER_SECOND")) "B/s"
    else if (code.endsWith("_BYTES")) "B"
    else if (code.endsWith("_MILLISECONDS")) "ms"
    else if (code.endsWith("_PER_SECOND")) "/s"
    else if (code == "TLS_DAYS_REMAINING") "d"
    else if (code == "DISK_TEMPERATURE_CELSIUS") "°C"
    else ""
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

  case object DiskUsagePercent extends MetricCode {
    override val code: String = "DISK_USAGE_PERCENT"
    override val isPercentage: Boolean = true
  }

  case object DiskFreeBytes extends MetricCode {
    override val code: String = "DISK_FREE_BYTES"
    override val isPercentage: Boolean = false
  }

  case object InodeUsagePercent extends MetricCode {
    override val code: String = "INODE_USAGE_PERCENT"
    override val isPercentage: Boolean = true
  }

  case object SwapUsagePercent extends MetricCode {
    override val code: String = "SWAP_USAGE_PERCENT"
    override val isPercentage: Boolean = true
  }

  case object SwapUsedBytes extends MetricCode {
    override val code: String = "SWAP_USED_BYTES"
    override val isPercentage: Boolean = false
  }

  case object LoadAverage1 extends MetricCode {
    override val code: String = "LOAD_AVERAGE_1"
    override val isPercentage: Boolean = false
  }

  case object LoadAverage5 extends MetricCode {
    override val code: String = "LOAD_AVERAGE_5"
    override val isPercentage: Boolean = false
  }

  case object LoadAverage15 extends MetricCode {
    override val code: String = "LOAD_AVERAGE_15"
    override val isPercentage: Boolean = false
  }

  case object LoadPerCore extends MetricCode {
    override val code: String = "LOAD_PER_CORE"
    override val isPercentage: Boolean = false
  }

  case object CpuIowaitPercent extends MetricCode {
    override val code: String = "CPU_IOWAIT_PERCENT"
    override val isPercentage: Boolean = true
  }

  case object DiskReadBytesPerSecond extends MetricCode {
    override val code: String = "DISK_READ_BYTES_PER_SECOND"
    override val isPercentage: Boolean = false
  }

  case object DiskWriteBytesPerSecond extends MetricCode {
    override val code: String = "DISK_WRITE_BYTES_PER_SECOND"
    override val isPercentage: Boolean = false
  }

  case object DiskLatencyMilliseconds extends MetricCode {
    override val code: String = "DISK_LATENCY_MILLISECONDS"
    override val isPercentage: Boolean = false
  }

  case object DiskBusyPercent extends MetricCode {
    override val code: String = "DISK_BUSY_PERCENT"
    override val isPercentage: Boolean = true
  }

  case object NetworkReceiveBytesPerSecond extends MetricCode {
    override val code: String = "NETWORK_RECEIVE_BYTES_PER_SECOND"
    override val isPercentage: Boolean = false
  }

  case object NetworkTransmitBytesPerSecond extends MetricCode {
    override val code: String = "NETWORK_TRANSMIT_BYTES_PER_SECOND"
    override val isPercentage: Boolean = false
  }

  case object NetworkErrorsPerSecond extends MetricCode {
    override val code: String = "NETWORK_ERRORS_PER_SECOND"
    override val isPercentage: Boolean = false
  }

  case object NetworkDropsPerSecond extends MetricCode {
    override val code: String = "NETWORK_DROPS_PER_SECOND"
    override val isPercentage: Boolean = false
  }

  case object ContainerRestartCount extends MetricCode {
    override val code: String = "CONTAINER_RESTART_COUNT"
    override val isPercentage: Boolean = false
  }

  case object ContainerHealthy extends MetricCode {
    override val code: String = "CONTAINER_HEALTHY"
    override val isPercentage: Boolean = false
  }

  case object TlsDaysRemaining extends MetricCode {
    override val code: String = "TLS_DAYS_REMAINING"
    override val isPercentage: Boolean = false
  }

  case object ServiceAvailable extends MetricCode {
    override val code: String = "SERVICE_AVAILABLE"
    override val isPercentage: Boolean = false
  }

  case object ServiceResponseMilliseconds extends MetricCode {
    override val code: String = "SERVICE_RESPONSE_MILLISECONDS"
    override val isPercentage: Boolean = false
  }

  case object DiskTemperatureCelsius extends MetricCode {
    override val code: String = "DISK_TEMPERATURE_CELSIUS"
    override val isPercentage: Boolean = false
  }

  case object DiskWearPercent extends MetricCode {
    override val code: String = "DISK_WEAR_PERCENT"
    override val isPercentage: Boolean = true
  }

  case object DiskMediaErrors extends MetricCode {
    override val code: String = "DISK_MEDIA_ERRORS"
    override val isPercentage: Boolean = false
  }

  case object DiskHealthy extends MetricCode {
    override val code: String = "DISK_HEALTHY"
    override val isPercentage: Boolean = false
  }

  val All: List[MetricCode] = List(CpuUsagePercent, MemoryUsagePercent, DiskUsagePercent, DiskFreeBytes, InodeUsagePercent, SwapUsagePercent, SwapUsedBytes, LoadAverage1, LoadAverage5, LoadAverage15, LoadPerCore, CpuIowaitPercent, DiskReadBytesPerSecond, DiskWriteBytesPerSecond, DiskLatencyMilliseconds, DiskBusyPercent, NetworkReceiveBytesPerSecond, NetworkTransmitBytesPerSecond, NetworkErrorsPerSecond, NetworkDropsPerSecond, ContainerRestartCount, ContainerHealthy, TlsDaysRemaining, ServiceAvailable, ServiceResponseMilliseconds, DiskTemperatureCelsius, DiskWearPercent, DiskMediaErrors, DiskHealthy)

  def fromCode(code: String): Either[IllegalArgumentException, MetricCode] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported metric code '$code'"))
}

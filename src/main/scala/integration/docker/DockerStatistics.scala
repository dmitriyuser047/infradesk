package ru.bitec.app.ops
package integration.docker

import com.github.dockerjava.api.model.Statistics
import domain.metric.MetricCode

/** CPU is a fraction of host capacity, consistent with SSH inventory. */
object DockerStatistics {
  def metrics(value: Statistics): Map[String, BigDecimal] = {
    val cpu = for {
      current <- Option(value.getCpuStats)
      previous <- Option(value.getPreCpuStats)
      currentUsage <- Option(current.getCpuUsage).flatMap(v => Option(v.getTotalUsage)).map(_.longValue)
      previousUsage <- Option(previous.getCpuUsage).flatMap(v => Option(v.getTotalUsage)).map(_.longValue)
      currentSystem <- Option(current.getSystemCpuUsage).map(_.longValue)
      previousSystem <- Option(previous.getSystemCpuUsage).map(_.longValue)
      if currentSystem > previousSystem && currentUsage >= previousUsage
      percent = BigDecimal(currentUsage - previousUsage) * 100 / BigDecimal(currentSystem - previousSystem)
      if percent <= 100
    } yield MetricCode.CpuUsagePercent.code -> percent
    val memory = for {
      stats <- Option(value.getMemoryStats)
      used <- Option(stats.getUsage).map(_.longValue)
      limit <- Option(stats.getLimit).map(_.longValue)
      cache = Option(stats.getStats).flatMap(v => Option(v.getTotalInactiveFile).orElse(Option(v.getInactiveFile))).map(_.longValue).filter(v => v >= 0 && v <= used).getOrElse(0L)
      if used >= 0 && limit > 0 && used <= limit
    } yield MetricCode.MemoryUsagePercent.code -> (BigDecimal(used - cache) * 100 / BigDecimal(limit))
    List(cpu, memory).flatten.toMap
  }
}

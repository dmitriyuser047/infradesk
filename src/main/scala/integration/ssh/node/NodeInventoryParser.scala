package ru.bitec.app.ops
package integration.ssh.node

import scala.util.Try

final case class NodeInventoryParseError(message: String) extends RuntimeException(message)

object NodeInventoryParser {

  def parse(stdout: String): Either[NodeInventoryParseError, NodeInventory] = {
    val values = stdout.linesIterator.foldLeft(Map.empty[String, String]) { (current, line) =>
      line.split("\\t", 2).toList match {
        case key :: value :: Nil => current.updated(key, value.trim)
        case _ => current
      }
    }

    values.get("hostname").flatMap(optionalValue).toRight(
      NodeInventoryParseError("SSH node inventory returned an empty hostname")
    ).map { hostname =>
      NodeInventory(
        hostname = hostname,
        operatingSystem = values.get("operating_system").flatMap(optionalValue),
        distribution = values.get("distribution").flatMap(optionalValue),
        kernelVersion = values.get("kernel_version").flatMap(optionalValue),
        architecture = values.get("architecture").flatMap(optionalValue),
        cpuModel = values.get("cpu_model").flatMap(optionalValue),
        cpuCores = values.get("cpu_cores").flatMap(parsePositiveInt),
        memoryMb = values.get("memory_mb").flatMap(parseNonNegativeLong),
        cpuUsagePercent = values.get("cpu_usage_percent").flatMap(parsePercentage),
        memoryUsagePercent = values.get("memory_usage_percent").flatMap(parsePercentage),
        uptimeSeconds = values.get("uptime_seconds").flatMap(parseNonNegativeLong),
        telemetry = values.get("telemetry").flatMap(value => {
          import serialization.resource.ResourceTelemetryJson._
          io.circe.parser.decode[domain.metric.ResourceTelemetry](value).toOption
        }).getOrElse(domain.metric.ResourceTelemetry())
      )
    }
  }

  private def optionalValue(value: String): Option[String] =
    Option(value.trim).filter(_.nonEmpty)

  private def parsePositiveInt(value: String): Option[Int] =
    optionalValue(value).flatMap(_.toIntOption).filter(_ > 0)

  private def parseNonNegativeLong(value: String): Option[Long] =
    optionalValue(value).flatMap(_.toLongOption).filter(_ >= 0)

  private def parsePercentage(value: String): Option[BigDecimal] =
    optionalValue(value)
      .flatMap(value => Try(BigDecimal(value)).toOption)
      .filter(value => value >= BigDecimal(0) && value <= BigDecimal(100))
}

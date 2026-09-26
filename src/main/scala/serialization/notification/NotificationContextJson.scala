package ru.bitec.app.ops
package serialization.notification

import domain.metric.MetricCode
import domain.monitor.MonitorOperator
import domain.notification.NotificationContext
import io.circe.{Json, parser}

/** How a {@link NotificationContext} is stored in the outbox and read back.
  *
  * Hand-written rather than derived: the project depends on circe-core, not circe-generic, and the
  * enums are persisted by their stable codes so a stored row survives a rename of a Scala case
  * object. A field that a newer version added and an older row lacks decodes to its empty value,
  * so the snapshot is forward tolerant on read.
  */
object NotificationContextJson {

  def encode(context: NotificationContext): String =
    Json.obj(
      "serverName" -> Json.fromString(context.serverName),
      "resourceTypeName" -> Json.fromString(context.resourceTypeName),
      "environmentName" -> context.environmentName.fold(Json.Null)(Json.fromString),
      "projectName" -> context.projectName.fold(Json.Null)(Json.fromString),
      "metricCode" -> Json.fromString(context.metricCode.code),
      "operator" -> Json.fromString(context.operator.code),
      "threshold" -> Json.fromBigDecimal(context.threshold),
      "currentValue" -> context.currentValue.fold(Json.Null)(Json.fromBigDecimal),
      "durationSeconds" -> context.durationSeconds.fold(Json.Null)(Json.fromLong)
    ).noSpaces

  def decode(value: String): Either[Throwable, NotificationContext] =
    for {
      json <- parser.parse(value)
      cursor = json.hcursor
      serverName <- cursor.get[String]("serverName")
      resourceTypeName <- cursor.getOrElse[String]("resourceTypeName")("")
      environmentName <- cursor.get[Option[String]]("environmentName")
      projectName <- cursor.get[Option[String]]("projectName")
      metricRaw <- cursor.get[String]("metricCode")
      metricCode <- MetricCode.fromCode(metricRaw)
      operatorRaw <- cursor.get[String]("operator")
      operator <- MonitorOperator.fromCode(operatorRaw)
      threshold <- cursor.get[BigDecimal]("threshold")
      currentValue <- cursor.get[Option[BigDecimal]]("currentValue")
      durationSeconds <- cursor.get[Option[Long]]("durationSeconds")
    } yield NotificationContext(serverName, resourceTypeName, environmentName, projectName,
      metricCode, operator, threshold, currentValue, durationSeconds)
}

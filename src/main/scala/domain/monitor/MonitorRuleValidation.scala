package ru.bitec.app.ops
package domain.monitor

import domain.metric.MetricCode

/** Rejected by the business rules of a monitor rule, whatever the caller is. */
final case class InvalidMonitorRule(override val getMessage: String)
  extends IllegalArgumentException(getMessage)

/** Business constraints of a monitor rule, enforced for every caller and not only over HTTP. */
object MonitorRuleValidation {

  val PercentageMinimum: BigDecimal = BigDecimal(0)
  val PercentageMaximum: BigDecimal = BigDecimal(100)

  def validate(
    metricCode: MetricCode,
    threshold: BigDecimal,
    forSeconds: Long,
    noDataSeconds: Long
  ): Either[InvalidMonitorRule, Unit] =
    for {
      _ <- check(forSeconds >= 0, "forSeconds must not be negative")
      _ <- check(noDataSeconds >= 0, "noDataSeconds must not be negative")
      _ <- check(
        !metricCode.isPercentage ||
          (threshold >= PercentageMinimum && threshold <= PercentageMaximum),
        s"threshold for ${metricCode.code} must be between $PercentageMinimum and $PercentageMaximum"
      )
    } yield ()

  private def check(condition: Boolean, message: String): Either[InvalidMonitorRule, Unit] =
    if (condition) Right(()) else Left(InvalidMonitorRule(message))
}

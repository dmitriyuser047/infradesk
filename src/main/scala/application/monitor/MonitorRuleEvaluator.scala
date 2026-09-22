package ru.bitec.app.ops
package application.monitor

import domain.resource.Resource

import java.time.Instant

trait MonitorRuleEvaluator[F[_]] {
  def execute(resources: List[Resource], evaluatedAt: Instant): F[Unit]
}

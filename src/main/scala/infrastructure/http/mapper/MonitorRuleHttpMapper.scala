package ru.bitec.app.ops
package infrastructure.http.mapper
import domain.monitor.MonitorRule
import infrastructure.http.dto.MonitorRuleResponse
object MonitorRuleHttpMapper { def toResponse(r:MonitorRule)=MonitorRuleResponse(r.id,r.resourceId,r.metricCode.code,r.operator.code,r.threshold,r.forSeconds,r.enabled,r.createdAt,r.updatedAt) }

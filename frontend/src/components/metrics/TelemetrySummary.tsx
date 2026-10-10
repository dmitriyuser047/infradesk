import { useI18n } from '../../i18n'
import type { ResourceTelemetry } from '../../types/metric'
import { getMetricLabel } from '../monitoring/monitorRulePresentation'
import { formatMetricValue } from './formatters'

const deviceKinds = ['filesystem', 'disk', 'network', 'service', 'hardware'] as const

/**
 * The latest telemetry sample of a resource: resource-wide readings first, then each device. Only
 * readings that were collected are shown; a missing one is never drawn as zero.
 */
export function TelemetrySummary({ telemetry }: { telemetry: ResourceTelemetry | undefined }) {
  const i18n = useI18n()
  const t = i18n.t.metrics.telemetry
  const values = (metrics: Record<string, number>) => <dl className="telemetry-values">
    {Object.entries(metrics).map(([code, value]) => <div key={code}>
      <dt>{getMetricLabel(code, i18n)}</dt><dd>{formatMetricValue(code, value, i18n)}</dd>
    </div>)}
  </dl>
  return <div className="telemetry-summary">
    <p className="section-description">{t.description}</p>
    {telemetry && Object.keys(telemetry.metrics).length > 0 ? values(telemetry.metrics)
      : <p className="metric-empty">{i18n.t.metrics.noObservations}</p>}
    {deviceKinds.map(kind => {
      const devices = telemetry?.devices.filter(device => device.kind === kind) ?? []
      // Drive health is the one group worth explaining when empty: why it usually is.
      if (devices.length === 0 && kind !== 'hardware') return null
      return <details className="operation-disclosure" key={kind}><summary>{t.groups[kind]} · {devices.length}</summary>
        {devices.length === 0 ? <p className="metric-empty">{t.smartUnavailable}</p>
          : devices.map(device => <section key={device.name} className="telemetry-device"><h4><code>{device.name}</code></h4>
            {values(device.metrics)}</section>)}
        {kind === 'service' ? <p className="section-description">{t.servicesNote}</p> : null}
      </details>
    })}
  </div>
}

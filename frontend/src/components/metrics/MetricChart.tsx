import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import { useI18n } from '../../i18n'
import { metricUnit, type MetricPoint } from '../../types/metric'
import { formatMetricValue } from './formatters'

interface MetricChartProps {
  title: string
  data: readonly MetricPoint[]
  metricCode?: string
  /** Longer than a day: axis ticks carry the date, not only the time. */
  longRange?: boolean
}

export function MetricChart({ title, data, metricCode = 'CPU_USAGE_PERCENT', longRange = false }: MetricChartProps) {
  const i18n = useI18n()
  const unit = metricUnit(metricCode)
  if (data.length === 0) {
    return <p className="metric-empty">{i18n.t.metrics.noObservations}</p>
  }

  return (
    <div className="metric-chart" aria-label={i18n.t.metrics.history(title)}>
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ top: 10, right: 12, bottom: 0, left: -18 }}>
          <CartesianGrid stroke="var(--chart-grid)" strokeDasharray="3 3" vertical={false} />
          <XAxis
            dataKey="timestamp"
            axisLine={false}
            tickLine={false}
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            tickFormatter={(value: string) => longRange ? i18n.format.dateTime(value) : i18n.format.time(value)}
            minTickGap={24}
          />
          <YAxis
            domain={unit === '%' ? [0, 100] : [0, 'auto']}
            axisLine={false}
            tickLine={false}
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            tickFormatter={(value: number) => formatMetricValue(metricCode, value, i18n)}
          />
          <Tooltip
            contentStyle={{
              border: '1px solid var(--border)',
              borderRadius: 8,
              backgroundColor: 'var(--surface)',
              color: 'var(--text)',
            }}
            cursor={{ stroke: 'var(--accent)', strokeWidth: 1 }}
            labelFormatter={(label) => i18n.format.dateTime(String(label))}
            formatter={(value) => [formatMetricValue(metricCode, Number(value), i18n), title]}
          />
          <Line
            type="monotone"
            dataKey="value"
            stroke="var(--accent)"
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 4, strokeWidth: 0 }}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

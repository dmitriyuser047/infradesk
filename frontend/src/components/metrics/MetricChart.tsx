import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import type { MetricPoint } from '../../types/metric'
import { formatMetricTime } from './formatters'

interface MetricChartProps {
  title: string
  data: readonly MetricPoint[]
}

export function MetricChart({ title, data }: MetricChartProps) {
  if (data.length === 0) {
    return <p className="metric-empty">No metric observations in the selected period.</p>
  }

  return (
    <div className="metric-chart" aria-label={`${title} history`}>
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ top: 10, right: 12, bottom: 0, left: -18 }}>
          <CartesianGrid stroke="var(--chart-grid)" strokeDasharray="3 3" vertical={false} />
          <XAxis
            dataKey="timestamp"
            axisLine={false}
            tickLine={false}
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            tickFormatter={formatMetricTime}
          />
          <YAxis
            domain={[0, 100]}
            axisLine={false}
            tickLine={false}
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            tickFormatter={(value: number) => `${value}%`}
          />
          <Tooltip
            contentStyle={{
              border: '1px solid var(--border)',
              borderRadius: 6,
              backgroundColor: 'var(--surface)',
              color: 'var(--text)',
            }}
            cursor={{ stroke: 'var(--accent)', strokeWidth: 1 }}
            labelFormatter={(label) => formatMetricTime(String(label))}
            formatter={(value) => [`${Number(value).toFixed(1)}%`, title]}
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

import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { I18nProvider } from '../../i18n'
import type { OverviewSummaryResponse } from '../../types/overview'
import { OverviewDistribution } from './OverviewDistribution'

const summary: OverviewSummaryResponse = {
  nodes: { total: 4, online: 2, offline: 1 },
  containers: { total: 7, running: 3, stopped: 2 },
  connections: { total: 5, healthy: 1, failing: 1, neverSynced: 2 },
  incidents: { open: 0, threshold: 0, noData: 0 },
  operations: { failed: 0, unknown: 0 },
}

function render(data = summary, locale: 'en' | 'ru' = 'en') {
  return renderToStaticMarkup(<I18nProvider initialLocale={locale}><MemoryRouter>
    <OverviewDistribution summary={data} resourcesPath="/resources?project=p&environment=e"
      connectionsPath="/connections?project=p&environment=e" />
  </MemoryRouter></I18nProvider>)
}

describe('overview snapshot distributions', () => {
  it('renders Russian chart labels and distinguishes unknown states', () => {
    const html = render(summary, 'ru')
    expect(html).toContain('Доступность серверов')
    expect(html).toContain('Синхронизация подключений')
    expect(html).toContain('Последнее известное состояние')
    expect(html).toMatch(/Нет данных<\/dt><dd>1<\/dd>/)
    expect(html).not.toContain('???')
  })
  it('keeps unknown and other states separate from healthy and stopped states with textual counts', () => {
    const html = render()
    expect(html).toMatch(/Online<\/dt><dd>2<\/dd>/)
    expect(html).toMatch(/Offline<\/dt><dd>1<\/dd>/)
    expect(html.match(/No data<\/dt><dd>1<\/dd>/g)).toHaveLength(2)
    expect(html).toMatch(/Other \/ no data<\/dt><dd>2<\/dd>/)
    expect(html).toMatch(/Stopped<\/dt><dd>2<\/dd>/)
    expect(html).toMatch(/Never run<\/dt><dd>2<\/dd>/)
    expect(html).toContain('stroke-dasharray="50 50"')
    expect(html).toContain('stroke-dashoffset="-75"')
    expect(html).not.toContain('uptime')
  })

  it('does not draw healthy arcs or divide by zero for an empty category', () => {
    const html = render({ ...summary, nodes: { total: 0, online: 0, offline: 0 },
      containers: { total: 0, running: 0, stopped: 0 },
      connections: { total: 0, healthy: 0, failing: 0, neverSynced: 0 } })
    expect(html).not.toContain('distribution-stroke')
    expect(html).not.toContain('NaN')
    expect(html).not.toContain('Infinity')
    expect(html.match(/No objects in the selected scope yet/g)).toHaveLength(3)
  })

  it('preserves the supplied scope on all drill-down links and identifies a snapshot rather than history', () => {
    const html = render()
    expect(html.match(/href="\/resources\?project=p&amp;environment=e"/g)).toHaveLength(2)
    expect(html).toContain('href="/connections?project=p&amp;environment=e"')
    expect(html.match(/Last known state/g)).toHaveLength(3)
  })
})

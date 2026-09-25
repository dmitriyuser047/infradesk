import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import type { MonitorRuleResponse } from '../../types/monitorRule'
import { MonitorRuleRow } from './MonitorRuleRow'

function rule(overrides: Partial<MonitorRuleResponse> = {}): MonitorRuleResponse {
  return {
    id: 'rule', resourceId: 'resource', metricCode: 'CPU_USAGE_PERCENT', operator: 'GREATER_THAN',
    threshold: 90, forSeconds: 300, noDataSeconds: 900, enabled: true, status: null,
    createdAt: '', updatedAt: '', ...overrides,
  }
}

function render(value: MonitorRuleResponse): string {
  return renderToStaticMarkup(<table><tbody>
    <MonitorRuleRow rule={value} onEdit={() => undefined} />
  </tbody></table>)
}

describe('monitor rule row', () => {
  it('shows the condition, the duration and the no-data timeout together', () => {
    const html = render(rule({ operator: 'LESS_THAN_OR_EQUAL', threshold: 10 }))

    expect(html).toContain('&lt;=')
    expect(html).toContain('10%')
    expect(html).toContain('5 мин · без данных 15 мин')
  })

  it('shows a rule without a no-data timeout as off', () => {
    expect(render(rule({ forSeconds: 0, noDataSeconds: 0 }))).toContain('Сразу · без данных выкл.')
  })

  it('shows the evaluated state of the rule, including NO_DATA', () => {
    expect(render(rule({ status: 'NO_DATA' }))).toContain('Нет данных')
    expect(render(rule({ status: 'FIRING' }))).toContain('status-danger')
    expect(render(rule({ status: 'OK' }))).toContain('status-success')
    expect(render(rule({ status: null }))).toContain('Включено')
    expect(render(rule({ status: 'FIRING', enabled: false }))).toContain('Выключено')
  })

  it('omits the edit action for a read-only member', () => {
    const html = renderToStaticMarkup(<table><tbody>
      <MonitorRuleRow rule={rule()} />
    </tbody></table>)

    expect(html).not.toContain('Изменить правило')
    expect(html).not.toContain('>Изменить<')
  })
})

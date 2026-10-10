import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { ResourcePage } from '../../../pages/ResourcePage'
import pageSource from '../../../pages/ResourcePage.tsx?raw'
import type { NodeSpecResponse, ResourceResponse } from '../../../types/resource'
import { I18nProvider, type Locale } from '../../../i18n'

function resource(resourceTypeCode: string, data: ResourceResponse['data']): ResourceResponse {
  return {
    id: 'resource', organizationId: 'org', environmentId: 'environment', resourceTypeId: 'type',
    parentResourceId: null, code: 'resource-code', name: 'Resource name', resourceTypeCode,
    active: true, createdAt: '', updatedAt: '', data,
  }
}

function renderResourcePage(value: ResourceResponse, locale: Locale = 'en'): string {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Example org', role: 'OWNER' }])
  client.setQueryData(['resource', 'org', 'resource'], value)

  return renderToStaticMarkup(<I18nProvider initialLocale={locale}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/environments/environment/resources/resource']}><Routes>
      <Route path="/organizations/:organizationId/environments/:environmentId/resources/:resourceId"
        element={<ResourcePage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

const nodeSpec: NodeSpecResponse = {
  hostname: 'node-1', operatingSystem: 'Linux', distribution: 'Ubuntu 24.04 LTS',
  kernelVersion: '6.8.0', architecture: 'x86_64', cpuModel: 'AMD EPYC', cpuCores: 4, memoryMb: 8192,
}

function nodeWith(online: boolean | null, usage: { cpu: number | null; memory: number | null; uptime: number | null },
  spec: typeof nodeSpec | null = nodeSpec): ResourceResponse {
  return resource('NODE', { kind: 'NODE', spec,
    status: online === null ? null : { online, cpuUsagePercent: usage.cpu, memoryUsagePercent: usage.memory, uptimeSeconds: usage.uptime } })
}

function containerIn(state: string | null, image: string | null = 'backend:2.0'): ResourceResponse {
  return resource('CONTAINER', { kind: 'CONTAINER', spec: { image }, status: state === null ? null : { state } })
}

/** The status badge next to the page title. */
function headerStatus(html: string): string | undefined {
  return html.match(/class="workspace-title-line"><h1>[^<]*<\/h1><span class="status-indicator (status-[a-z]+)"><span class="status-dot" aria-hidden="true"><\/span>([^<]*)</)
    ?.slice(1).join(' ')
}

describe('resource page presentation', () => {
  const node = nodeWith(true, { cpu: 12.5, memory: 37.5, uptime: 3600 })
  const container = containerIn('running')

  it('puts the node status next to its name: online or offline', () => {
    expect(headerStatus(renderResourcePage(node))).toBe('status-success Online')
    expect(headerStatus(renderResourcePage(nodeWith(false, { cpu: null, memory: null, uptime: null })))).toBe('status-danger Unavailable')
    expect(headerStatus(renderResourcePage(node, 'ru'))).toBe('status-success В сети')
  })

  it('summarizes the node state in one strip: status, CPU, memory and uptime', () => {
    const html = renderResourcePage(node)
    const strip = html.slice(html.indexOf('<dl class="metric-strip">'), html.indexOf('</dl>', html.indexOf('<dl class="metric-strip">')))

    expect(strip).toMatch(/<dt>Status<\/dt><dd><span class="status-indicator status-success">.*Online/)
    expect(strip).toContain('<dt>CPU</dt><dd>12.5%</dd>')
    expect(strip).toContain('<dt>Memory</dt><dd>37.5%</dd>')
    expect(strip).toContain('<dt>Uptime</dt><dd>1h 0m</dd>')
    expect(renderResourcePage(node, 'ru')).toContain('<dt>Время работы</dt><dd>1 ч 0 мин</dd>')
  })

  it('shows an unknown value as a dash, and an unreported node as unknown', () => {
    const html = renderResourcePage(nodeWith(null, { cpu: null, memory: null, uptime: null },
      { ...nodeSpec, distribution: null, kernelVersion: null, cpuModel: null }))

    expect(html).toContain('<dt>CPU</dt><dd>—</dd>')
    expect(html).toContain('<dt>Memory</dt><dd>—</dd>')
    expect(html).toContain('<dt>Uptime</dt><dd>—</dd>')
    expect(html).toContain('<dt>Distribution</dt><dd>—</dd>')
    expect(html).toContain('<dt>Kernel</dt><dd>—</dd>')
    expect(html).toContain('<dt>CPU model</dt><dd>—</dd>')
    expect(headerStatus(html)).toBe('status-neutral State unknown')
  })

  it('sets technical node values in monospace and plain ones as text', () => {
    const html = renderResourcePage(node)

    expect(html).toContain('<dt>Hostname</dt><dd class="property-technical">node-1</dd>')
    expect(html).toContain('<dt>Kernel</dt><dd class="property-technical">6.8.0</dd>')
    expect(html).toContain('<dt>Architecture</dt><dd class="property-technical">x86_64</dd>')
    expect(html).toContain('<dt>Code</dt><dd class="property-technical">resource-code</dd>')
    expect(html).toContain('<dt>Operating system</dt><dd>Linux</dd>')
    expect(html).toContain('<dt>CPU cores</dt><dd>4</dd>')
    expect(html).toContain('<dt>Memory</dt><dd>8 GB</dd>')
    expect(html).toContain('class="property-grid property-grid-2"')
  })

  it('keeps the same structure whatever the length of the values', () => {
    const longName = 'x'.repeat(300)
    const long = { ...nodeWith(true, { cpu: 1, memory: 1, uptime: 1 }, { ...nodeSpec, hostname: longName, kernelVersion: longName }),
      name: longName, code: longName }
    const structure = (html: string) => html.replace(/>[^<]*</g, '><')

    expect(structure(renderResourcePage(long))).toBe(structure(renderResourcePage(node)))
    expect(renderResourcePage(long)).toContain(`<dd class="property-technical">${longName}</dd>`)
  })

  it('shows a container state through the shared Docker state presentation', () => {
    const states: [string | null, string][] = [['running', 'status-success Running'], ['exited', 'status-danger Stopped'],
      ['removing', 'status-neutral removing'], [null, 'status-neutral Unknown']]
    for (const [state, expected] of states) {
      const html = renderResourcePage(containerIn(state))
      expect(headerStatus(html)).toBe(expected)
      expect(html).toContain(`<dt>State</dt><dd><span class="status-indicator ${expected.split(' ')[0]}">`)
    }
    expect(headerStatus(renderResourcePage(containerIn('exited'), 'ru'))).toBe('status-danger Остановлен')
  })

  it('shows the container image and code in monospace', () => {
    const html = renderResourcePage(container)

    expect(html).toContain('<dt>Image</dt><dd class="property-technical">backend:2.0</dd>')
    expect(html).toContain('<dt>Code</dt><dd class="property-technical">resource-code</dd>')
    expect(renderResourcePage(containerIn('running', null))).toContain('<dt>Image</dt><dd>—</dd>')
    expect(renderResourcePage(container, 'ru')).toContain('<dt>Образ</dt><dd class="property-technical">backend:2.0</dd>')
  })

  it('offers monitoring only where the monitoring feature applies', () => {
    expect(renderResourcePage(node)).toContain('id="tab-monitoring"')
    expect(renderResourcePage(container)).toContain('id="tab-monitoring"')
    for (const html of [renderResourcePage(node), renderResourcePage(container)]) {
      expect(html).toContain('id="tab-overview"')
      expect(html).toContain('id="tab-activity"')
    }
  })

  it('names the resource type and its state in Russian by default', () => {
    const html = renderResourcePage(container, 'ru')

    expect(html).toContain('Контейнер · resource-code')
    expect(html).toContain('Работает')
    expect(html).toMatch(/role="tablist".*Обзор.*События/s)
  })

  it('renders the common shell and a safe fallback for an unknown resource type', () => {
    const html = renderResourcePage(resource('NEW_SERVER_TYPE', { kind: 'NODE', spec: null, status: null }))

    expect(html).toContain('Resource name')
    expect(html).toContain('NEW_SERVER_TYPE · resource-code')
    expect(html).toContain('Details are not available for this resource type')
    expect(headerStatus(html)).toBeUndefined()
    expect(html).not.toContain('id="tab-monitoring"')
  })

  it('keeps the common shell identical across resource types', () => {
    for (const value of [node, container]) {
      const html = renderResourcePage(value)

      expect(html).toContain('Resource name')
      expect(html).toContain(value.resourceTypeCode === 'NODE' ? 'node-1 · Ubuntu 24.04 LTS' : 'Container · resource-code')
      expect(html).toMatch(/class="workspace-back"[^>]*>.*Servers<\/a>/)
      expect(html).toContain('Overview')
    }
  })

  it('keeps status mapping out of the page: it has no resource type or state of its own', () => {
    const page = pageSource

    for (const literal of ["'NODE'", "'CONTAINER'", 'online', "'running'", "'exited'"]) expect(page).not.toContain(literal)
    expect(page).toContain('presentation.headerStatus')
    expect(page).toContain('supportsResourceMonitoring')
    expect(page).toContain('operationsApplicability')
  })
})

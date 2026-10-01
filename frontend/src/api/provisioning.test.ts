// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { afterEach, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn())
vi.mock('./httpClient', () => ({ requestJson: requestMock }))
import { ProvisioningPollMillis, provisioningPollInterval, useProvisioningRun } from './provisioning'
import type { ProvisioningRun } from '../types/provisioning'

afterEach(() => { cleanup(); vi.useRealTimers(); vi.clearAllMocks() })

describe('provisioning polling', () => {
  it('polls only queued or running work and stops for every terminal state', () => {
    expect(ProvisioningPollMillis).toBe(3000)
    expect(provisioningPollInterval('QUEUED')).toBe(3000)
    expect(provisioningPollInterval('RUNNING')).toBe(3000)
    for (const state of ['PLANNED', 'SUCCEEDED', 'FAILED', 'UNKNOWN'] as const)
      expect(provisioningPollInterval(state)).toBe(false)
    expect(provisioningPollInterval(undefined)).toBe(false)
  })

  it.each(['SUCCEEDED', 'UNKNOWN'] as const)('polls through transitions and stops after terminal %s', async terminal => {
    vi.useFakeTimers()
    const run = (state: ProvisioningRun['state']): ProvisioningRun => ({
      id: 'run-1', organizationId: 'org-1', resourceId: 'resource-1', requestId: 'request-1',
      requestedByUserId: 'actor-1', currentStep: null, state, createdAt: '2026-10-01T10:00:00Z',
      updatedAt: '2026-10-01T10:00:00Z', startedAt: null, finishedAt: null, failureCode: null,
      safeMessage: null, inputSnapshot: { schemaVersion: 1, runKind: 'SERVER_BASELINE_CHECK',
        resourceId: 'resource-1', resourceType: 'NODE', resourceKind: 'VPS', connectionId: 'ssh-1',
        connectionUpdatedAt: '2026-10-01T09:00:00Z', steps: ['PREFLIGHT', 'VERIFY'] },
    })
    const sequence = [run('QUEUED'), run('RUNNING'), run(terminal)]
    requestMock.mockImplementation(async () => ({ run: sequence[Math.min(requestMock.mock.calls.length - 1, 2)], steps: [] }))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: React.ReactNode }) =>
      createElement(QueryClientProvider, { client }, children)
    const hook = renderHook(() => useProvisioningRun('org-1', 'run-1'), { wrapper })
    await act(async () => { await Promise.resolve(); await Promise.resolve() })
    expect(requestMock).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(ProvisioningPollMillis) })
    expect(requestMock).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(ProvisioningPollMillis) })
    expect(requestMock).toHaveBeenCalledTimes(3)
    await act(async () => { await vi.advanceTimersByTimeAsync(ProvisioningPollMillis * 3) })
    expect(requestMock).toHaveBeenCalledTimes(3)
    hook.unmount()
    client.clear()
  })
})

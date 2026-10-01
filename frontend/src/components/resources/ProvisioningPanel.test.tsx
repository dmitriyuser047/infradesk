// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n'
import type { ProvisioningDetail, ProvisioningPlan, ProvisioningRun } from '../../types/provisioning'

const mocks = vi.hoisted(() => ({ history: vi.fn(), plan: vi.fn(), start: vi.fn(), run: vi.fn(), startMutation: vi.fn() }))
vi.mock('../../api/provisioning', () => ({
  useProvisioningHistory: mocks.history,
  usePlanProvisioning: mocks.plan,
  useStartProvisioning: mocks.start,
  useProvisioningRun: mocks.run,
}))

import { ProvisioningPanel } from './ProvisioningPanel'

const baseRun = (state: ProvisioningRun['state'], id = 'run-1'): ProvisioningRun => ({
  id, organizationId: 'org-1', resourceId: 'resource-1', requestId: null, requestedByUserId: null,
  currentStep: state === 'RUNNING' ? 'PREFLIGHT' : null, state,
  createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', startedAt: null, finishedAt: null,
  failureCode: null, safeMessage: null,
  inputSnapshot: { schemaVersion: 1, runKind: 'SERVER_BASELINE_CHECK', resourceId: 'resource-1',
    resourceType: 'NODE', resourceKind: 'new_vps_test', connectionId: 'ssh-1', connectionUpdatedAt: '2026-10-01T09:00:00Z',
    steps: ['PREFLIGHT', 'VERIFY'] },
})
const planData: ProvisioningPlan = {
  run: baseRun('PLANNED'), approvalInput: baseRun('PLANNED').inputSnapshot,
  connectionName: 'SSH production',
  steps: [
    { id: 'step-1', position: 0, kind: 'PREFLIGHT', displayName: 'PREFLIGHT', attempt: 0, state: 'PENDING',
      startedAt: null, finishedAt: null, facts: {}, failureCode: null, safeMessage: null, outputSummary: null,
      verificationResult: null, outputTruncated: false },
    { id: 'step-2', position: 1, kind: 'VERIFY', displayName: 'VERIFY', attempt: 0, state: 'PENDING',
      startedAt: null, finishedAt: null, facts: {}, failureCode: null, safeMessage: null, outputSummary: null,
      verificationResult: null, outputTruncated: false },
  ], warnings: [], blockingProblems: [],
}

function setup(historyItems: ProvisioningRun[] = [], detail?: ProvisioningDetail, locale: 'en' | 'ru' = 'en') {
  mocks.history.mockReturnValue({ data: { items: historyItems }, isPending: false, isError: false })
  mocks.plan.mockReturnValue({ mutateAsync: vi.fn().mockResolvedValue(planData), isPending: false, isError: false })
  mocks.start.mockReturnValue({ mutateAsync: mocks.startMutation, isPending: false, isError: false })
  mocks.run.mockReturnValue({ data: detail, isPending: false, isError: false, refetch: vi.fn() })
  return render(<I18nProvider initialLocale={locale}><ProvisioningPanel organizationId="org-1"
    resourceId="resource-1" resourceName="Finland VPS" canRun={false} /></I18nProvider>)
}

beforeEach(() => {
  mocks.startMutation = vi.fn().mockResolvedValue(baseRun('QUEUED'))
})
afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('ProvisioningPanel', () => {
  it('shows member-visible run history for every lifecycle state without privileged actions', () => {
    setup(['PLANNED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'].map((state, index) =>
      baseRun(state as ProvisioningRun['state'], `run-${index}`)))
    for (const label of ['Planned', 'Queued', 'Running', 'Succeeded', 'Failed', 'Unknown'])
      expect(screen.getByText(label)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Review readiness check' })).toBeNull()
  })

  it('shows the review snapshot and retries an ambiguous start with the same request ID', async () => {
    const mutate = vi.fn().mockRejectedValueOnce(new Error('network unavailable')).mockResolvedValue(baseRun('QUEUED'))
    mocks.startMutation = mutate
    mocks.history.mockReturnValue({ data: { items: [] }, isPending: false, isError: false })
    mocks.plan.mockReturnValue({ mutateAsync: vi.fn().mockResolvedValue(planData), isPending: false, isError: false })
    mocks.start.mockReturnValue({ mutateAsync: mutate, isPending: false, isError: false })
    mocks.run.mockReturnValue({ data: undefined, isPending: false, isError: false, refetch: vi.fn() })
    render(<I18nProvider initialLocale="en"><ProvisioningPanel organizationId="org-1"
      resourceId="resource-1" resourceName="Finland VPS" canRun /></I18nProvider>)

    fireEvent.click(screen.getByRole('button', { name: 'Review readiness check' }))
    await screen.findByRole('dialog')
    expect(screen.getByText('Server: Finland VPS')).toBeTruthy()
    expect(screen.getByText('SSH connection: SSH production')).toBeTruthy()
    expect(screen.getByText('Resource kind: new_vps_test')).toBeTruthy()
    expect(screen.queryByText(/ssh-1/)).toBeNull()
    const approve = screen.getByRole('button', { name: 'Approve and run checks' })
    fireEvent.click(approve)
    await waitFor(() => expect(mutate).toHaveBeenCalledTimes(1))
    fireEvent.click(approve)
    await waitFor(() => expect(mutate).toHaveBeenCalledTimes(2))
    expect(mutate.mock.calls[0][0].requestId).toBe(mutate.mock.calls[1][0].requestId)
    expect(mutate.mock.calls[0][0].planId).toBe('run-1')
  })

  it('shows the trusted connection name and resource kind with Russian labels', async () => {
    mocks.history.mockReturnValue({ data: { items: [] }, isPending: false, isError: false })
    mocks.plan.mockReturnValue({ mutateAsync: vi.fn().mockResolvedValue(planData), isPending: false, isError: false })
    mocks.run.mockReturnValue({ data: undefined, isPending: false, isError: false, refetch: vi.fn() })
    render(<I18nProvider initialLocale="ru"><ProvisioningPanel organizationId="org-1"
      resourceId="resource-1" resourceName="Finland VPS" canRun /></I18nProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Проверить готовность' }))
    await screen.findByRole('dialog')
    expect(screen.getByText('SSH-подключение: SSH production')).toBeTruthy()
    expect(screen.getByText('Вид ресурса: new_vps_test')).toBeTruthy()
    expect(screen.queryByText(/ssh-1/)).toBeNull()
  })

  it('shows observed facts, failed step and truncation without displaying remote output', () => {
    const run = baseRun('FAILED')
    const detail: ProvisioningDetail = { run, steps: [{ ...planData.steps[0], state: 'FAILED',
      failureCode: 'PROVISIONING_DISK_INSUFFICIENT', facts: { os: 'ubuntu', diskAvailable: 'false' },
      outputTruncated: true }] }
    setup([run], detail)
    expect(screen.getByText('Operating system: Ubuntu')).toBeTruthy()
    expect(screen.getByText('Free disk requirement: No')).toBeTruthy()
    expect(screen.getByText('At least 1 GiB of free disk space is required.')).toBeTruthy()
    expect(screen.getByText('Remote output was truncated; results are uncertain.')).toBeTruthy()
  })

  it('shows an uncertainty explanation for UNKNOWN even when no stored message exists', () => {
    const run = { ...baseRun('UNKNOWN'), failureCode: 'PROVISIONING_REMOTE_TIMEOUT',
      safeMessage: 'The worker lease expired; confirm the server state before continuing.' }
    setup([run], { run, steps: [] })
    expect(screen.getByText('The remote result is uncertain. Confirm the server state before continuing.')).toBeTruthy()
    expect(screen.queryByText(run.safeMessage)).toBeNull()
  })

  it('localizes failure and uncertainty details instead of showing the backend message', () => {
    const failed = { ...baseRun('FAILED'), failureCode: 'PROVISIONING_DISK_INSUFFICIENT',
      safeMessage: 'The server needs at least 1 GiB of free disk space.' }
    setup([failed], { run: failed, steps: [] }, 'ru')
    expect(screen.getByText('Нужно не менее 1 ГиБ свободного места.')).toBeTruthy()
    expect(screen.queryByText(failed.safeMessage)).toBeNull()
  })

  it('automatically opens the newest history item and disables approval for blocked plans', async () => {
    const latest = baseRun('RUNNING')
    mocks.history.mockReturnValue({ data: { items: [latest] }, isPending: false, isError: false })
    mocks.plan.mockReturnValue({ mutateAsync: vi.fn().mockResolvedValue({
      ...planData, blockingProblems: ['PROVISIONING_DISK_INSUFFICIENT'],
    }), isPending: false, isError: false })
    mocks.start.mockReturnValue({ mutateAsync: mocks.startMutation, isPending: false, isError: false })
    mocks.run.mockReturnValue({ data: { run: latest, steps: [] }, isPending: false, isError: false })
    render(<I18nProvider initialLocale="en"><ProvisioningPanel organizationId="org-1"
      resourceId="resource-1" resourceName="Finland VPS" canRun /></I18nProvider>)
    await waitFor(() => expect(mocks.run).toHaveBeenLastCalledWith('org-1', 'run-1'))
    fireEvent.click(screen.getByRole('button', { name: 'Review readiness check' }))
    await screen.findByRole('dialog')
    expect((screen.getByRole('button', { name: 'Approve and run checks' }) as HTMLButtonElement).disabled).toBe(true)
  })
})

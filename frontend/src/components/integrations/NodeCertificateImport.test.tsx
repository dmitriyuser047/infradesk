// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n'
import { importNodeCertificate } from '../../api/nodeOnboarding'
import { NodeCertificateImport } from './NodeCertificateImport'

vi.mock('../../api/nodeOnboarding', () => ({ importNodeCertificate: vi.fn() }))
afterEach(() => { cleanup(); vi.resetAllMocks() })

const result = { id: 'certificate-id', domain: 'vpn.example.org', fingerprint: 'a'.repeat(64), expiresAt: '2027-01-01T00:00:00Z' }
function files() {
  fireEvent.change(screen.getByLabelText('Certificate chain (.pem)'), {
    target: { files: [{ size: 100, text: async () => 'certificate-fixture' }] },
  })
  fireEvent.change(screen.getByLabelText('PKCS#8 private key (.key)'), {
    target: { files: [{ size: 100, text: async () => 'private-key-fixture' }] },
  })
}
function view(resourceId: string, onImported: (id: string) => void) {
  return <I18nProvider initialLocale="en"><NodeCertificateImport organizationId="org" integrationId="integration"
    resourceId={resourceId} domain="vpn.example.org" onImported={onImported} /></I18nProvider>
}

describe('NodeCertificateImport', () => {
  it('passes secret material only to import and renders public metadata', async () => {
    vi.mocked(importNodeCertificate).mockResolvedValue(result)
    const onImported = vi.fn()
    render(view('resource-1', onImported)); files()
    fireEvent.click(screen.getByRole('button', { name: 'Validate and import' }))
    await waitFor(() => expect(onImported).toHaveBeenCalledWith(result.id))
    expect(importNodeCertificate).toHaveBeenCalledWith('org', 'integration', {
      resourceId: 'resource-1', domain: result.domain, certificatePem: 'certificate-fixture', privateKeyPem: 'private-key-fixture',
    })
    expect(screen.getByRole('status').textContent).toContain('Certificate validated')
    expect(document.body.textContent).not.toContain('private-key-fixture')
    expect(document.body.textContent).not.toContain('certificate-fixture')
  })
  it('does not attach an in-flight certificate response to a different server', async () => {
    let finish!: (value: typeof result) => void
    vi.mocked(importNodeCertificate).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const onImported = vi.fn()
    const mounted = render(view('resource-1', onImported)); files()
    fireEvent.click(screen.getByRole('button', { name: 'Validate and import' }))
    await waitFor(() => expect(importNodeCertificate).toHaveBeenCalledTimes(1))
    mounted.rerender(view('resource-2', onImported))
    finish(result)
    await waitFor(() => expect((screen.getByRole('button', { name: 'Validate and import' }) as HTMLButtonElement).disabled).toBe(false))
    expect(onImported).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')).toBeNull()
  })
})

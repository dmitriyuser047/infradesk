// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { I18nProvider } from '../../i18n'
import { PackageProbeFindings } from './PackageProbeFindings'

afterEach(cleanup)
it('shows a bounded package diagnosis and actionable repair guidance', () => {
  render(<I18nProvider initialLocale="en"><PackageProbeFindings findings={[{ name: 'docker.io', observedState: 'iF ', classification: 'BROKEN', suggestedAction: 'REPAIR' }]} /></I18nProvider>)
  expect(screen.getByText('docker.io')).toBeTruthy()
  expect(screen.getByText('iF')).toBeTruthy()
  expect(screen.getByText('Broken')).toBeTruthy()
  expect(screen.getByText('Repair the package manually and check again')).toBeTruthy()
})

// @vitest-environment jsdom
import { useState } from 'react'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { I18nProvider } from '../../i18n'
import { IntegrationDialog } from './IntegrationDialog'

function Harness({ busy = false }: { busy?: boolean }) {
  const [open, setOpen] = useState(false)
  return <I18nProvider initialLocale="en"><button onClick={() => setOpen(true)}>Open</button>
    {open ? <IntegrationDialog title="Confirm operation" busy={busy} onClose={() => setOpen(false)}
      actions={<><button disabled={busy} onClick={() => setOpen(false)}>Cancel</button><button disabled={busy}>Confirm</button></>}>
      <label>Server<input /></label>
    </IntegrationDialog> : null}</I18nProvider>
}
afterEach(cleanup)

it('contains keyboard focus, closes on Escape and restores focus to the opener', () => {
  render(<Harness />)
  const opener = screen.getByRole('button', { name: 'Open' }); opener.focus(); fireEvent.click(opener)
  const dialog = screen.getByRole('dialog')
  const close = within(dialog).getByRole('button', { name: 'Close' })
  const confirm = within(dialog).getByRole('button', { name: 'Confirm' })
  expect(document.activeElement).toBe(close)
  fireEvent.keyDown(close, { key: 'Tab', shiftKey: true }); expect(document.activeElement).toBe(confirm)
  fireEvent.keyDown(confirm, { key: 'Tab' }); expect(document.activeElement).toBe(close)
  fireEvent.keyDown(dialog, { key: 'Escape' }); expect(screen.queryByRole('dialog')).toBeNull()
  expect(document.activeElement).toBe(opener)
})

it('does not dismiss a pending request on Escape or backdrop click', () => {
  render(<Harness busy />)
  fireEvent.click(screen.getByRole('button', { name: 'Open' }))
  const dialog = screen.getByRole('dialog')
  fireEvent.keyDown(dialog, { key: 'Escape' }); fireEvent.mouseDown(dialog.parentElement!)
  expect(screen.getByRole('dialog')).toBeTruthy()
  expect((within(dialog).getByRole('button', { name: 'Close' }) as HTMLButtonElement).disabled).toBe(true)
})

// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n'
import { PageActionMenu } from './PageActionMenu'

afterEach(cleanup)
function setup(onSelect = vi.fn()) {
  render(<I18nProvider initialLocale="en"><MemoryRouter><PageActionMenu actions={[
    { label: 'Edit', to: '/edit?project=p&environment=e' },
    { label: 'Unavailable', disabled: true },
    { label: 'Deactivate', danger: true, onSelect },
  ]} /></MemoryRouter></I18nProvider>)
  return screen.getByRole('button', { name: 'Actions' })
}
describe('working page action menu', () => {
  it('opens, skips disabled items, navigates with arrows and restores focus on Escape', () => {
    const trigger = setup()
    expect(screen.queryByRole('menu')).toBeNull()
    fireEvent.click(trigger)
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    const edit = screen.getByRole('menuitem', { name: 'Edit' })
    const deactivate = screen.getByRole('menuitem', { name: 'Deactivate' })
    expect(document.activeElement).toBe(edit)
    fireEvent.keyDown(edit, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(deactivate)
    fireEvent.keyDown(deactivate, { key: 'Home' })
    expect(document.activeElement).toBe(edit)
    fireEvent.keyDown(edit, { key: 'End' })
    expect(document.activeElement).toBe(deactivate)
    fireEvent.keyDown(deactivate, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })
  it('runs a destructive action once and closes before invoking it', () => {
    const select = vi.fn()
    const trigger = setup(select)
    fireEvent.click(trigger)
    const item = screen.getByRole('menuitem', { name: 'Deactivate' })
    expect(item.className).toContain('danger-action')
    fireEvent.click(item)
    expect(select).toHaveBeenCalledOnce()
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })
  it('preserves a supplied link context and closes on trigger, outside pointer and focus departure', () => {
    const trigger = setup()
    fireEvent.click(trigger)
    expect(screen.getByRole('menuitem', { name: 'Edit' }).getAttribute('href')).toBe('/edit?project=p&environment=e')
    fireEvent.click(trigger)
    expect(screen.queryByRole('menu')).toBeNull()
    fireEvent.click(trigger)
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu')).toBeNull()
    fireEvent.click(trigger)
    fireEvent.blur(screen.getByRole('menuitem', { name: 'Edit' }), { relatedTarget: document.body })
    expect(screen.queryByRole('menu')).toBeNull()
  })
  it('renders no trigger when the caller has no permitted actions', () => {
    render(<PageActionMenu actions={[]} />)
    expect(screen.queryByRole('button')).toBeNull()
  })
})

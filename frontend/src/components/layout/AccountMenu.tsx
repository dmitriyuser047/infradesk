import { Check, LogOut, Settings } from 'lucide-react'
import type { KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'

import { useLogout, useMe } from '../../api/auth'
import { Locales, useI18n } from '../../i18n'
import { usePopover } from './usePopover'

/** Who is signed in, the interface language, and signing out. */
export function AccountMenu() {
  const i18n = useI18n()
  const t = i18n.t.shell
  const me = useMe()
  const logout = useLogout()
  const navigate = useNavigate()
  const popover = usePopover()
  const menuRef = popover.panelRef
  const name = me.data?.displayName ?? t.account

  // Arrow keys move between the menu items, as in any menu.
  function onMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp' && event.key !== 'Home' && event.key !== 'End') return
    const items = [...(menuRef.current?.querySelectorAll<HTMLButtonElement>('[role^="menuitem"]') ?? [])]
    if (items.length === 0) return
    event.preventDefault()
    const current = items.indexOf(document.activeElement as HTMLButtonElement)
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
      : event.key === 'ArrowDown' ? (current + 1) % items.length : (current - 1 + items.length) % items.length
    items[next]?.focus()
  }

  return <div className="account-menu">
    <button ref={popover.triggerRef} type="button" className="account-trigger" onClick={popover.toggle}
      aria-haspopup="menu" {...popover.triggerProps} aria-label={t.accountMenu(name)}>
      <span className="avatar" aria-hidden>{initials(name)}</span>
      <span className="account-name">{name}</span>
    </button>
    {popover.open ? <div ref={popover.panelRef}
      id={popover.panelId} className="account-menu-panel" role="menu" aria-label={t.account} onKeyDown={onMenuKeyDown}>
      <div className="account-menu-identity" role="presentation">
        <strong>{name}</strong>
        {me.data?.email ? <small>{me.data.email}</small> : null}
      </div>
      <div className="menu-separator" role="separator" />
      <button type="button" role="menuitem" className="menu-item"
        onClick={() => { popover.close(); navigate('/settings/account') }}>
        <span className="menu-check" aria-hidden><Settings size={16} /></span>{t.accountSettings}
      </button>
      <div className="menu-separator" role="separator" />
      <div className="menu-group-label" role="presentation">{t.language}</div>
      {Locales.map((locale, index) => <button key={locale} type="button" role="menuitemradio"
        aria-checked={i18n.locale === locale} className="menu-item" autoFocus={index === 0}
        lang={locale} onClick={() => i18n.setLocale(locale)}>
        <span className="menu-check" aria-hidden>{i18n.locale === locale ? <Check size={16} /> : null}</span>
        {t.languages[locale]}
      </button>)}
      <div className="menu-separator" role="separator" />
      <button type="button" role="menuitem" className="menu-item" disabled={logout.isPending}
        onClick={() => logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}>
        <span className="menu-check" aria-hidden><LogOut size={16} /></span>{t.signOut}
      </button>
      {logout.isError ? <p className="menu-error" role="alert">{t.signOutFailed}</p> : null}
    </div> : null}
  </div>
}

function initials(name: string): string {
  const letters = name.trim().split(/\s+/).map(word => word[0] ?? '').join('')
  return (letters.slice(0, 2) || '?').toUpperCase()
}

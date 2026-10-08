import { useSyncExternalStore } from 'react'

export type Theme = 'light' | 'dark'
const storageKey = 'infradesk.theme'
const eventName = 'infradesk-theme-change'

export function initializeTheme(): void {
  let theme: Theme = 'light'
  try { if (window.localStorage.getItem(storageKey) === 'dark') theme = 'dark' } catch { /* Storage is optional. */ }
  document.documentElement.dataset.theme = theme
}

export function setTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
  try { window.localStorage.setItem(storageKey, theme) } catch { /* The current page still changes theme. */ }
  window.dispatchEvent(new Event(eventName))
}

function subscribe(notify: () => void): () => void {
  const synchronize = (event: StorageEvent) => {
    if (event.key === storageKey || event.key === null) { initializeTheme(); notify() }
  }
  window.addEventListener(eventName, notify)
  window.addEventListener('storage', synchronize)
  return () => { window.removeEventListener(eventName, notify); window.removeEventListener('storage', synchronize) }
}

export function useTheme(): Theme {
  return useSyncExternalStore(subscribe,
    () => document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light', () => 'light')
}

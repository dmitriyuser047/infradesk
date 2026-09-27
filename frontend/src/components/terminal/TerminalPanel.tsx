import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Maximize, Plug, Unplug } from 'lucide-react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { useI18n } from '../../i18n'
import type { ConnectionResponse } from '../../types/connection'
import { InlineAlert, StatusIndicator } from '../layout/WorkspacePrimitives'
import { TerminalTransport, terminalUrl, type TerminalState } from './terminalTransport'

export function terminalPrerequisite(connection: ConnectionResponse): 'inactive' | 'mismatch' | 'untrusted' | 'missingCredentials' | undefined {
  if (!connection.active) return 'inactive'
  if (connection.lastSync?.errorCode === 'SSH_HOST_KEY_MISMATCH') return 'mismatch'
  if (!connection.ssh?.hostTrusted || !connection.ssh.hostKeyFingerprint) return 'untrusted'
  if (!connection.ssh.credentialConfigured) return 'missingCredentials'
}

export function TerminalPanel({ organizationId, connection, editLink }: {
  organizationId: string; connection: ConnectionResponse; editLink?: string
}) {
  const { t } = useI18n()
  const labels = t.terminal
  const panel = useRef<HTMLDivElement>(null)
  const screen = useRef<HTMLDivElement>(null)
  const dispose = useRef<() => void>(() => undefined)
  const [status, setStatus] = useState<{ state: TerminalState; code?: string }>({ state: 'idle' })
  const [fullscreenError, setFullscreenError] = useState(false)
  const prerequisite = terminalPrerequisite(connection)

  useEffect(() => () => dispose.current(), [organizationId, connection.id, connection.updatedAt])
  useEffect(() => {
    if (prerequisite) dispose.current()
  }, [prerequisite])

  function connect() {
    if (!screen.current || prerequisite) return
    dispose.current()
    const host = screen.current
    let live = true
    let frame = 0
    let transport: TerminalTransport | undefined
    const colors = getComputedStyle(document.documentElement)
    const emulator = new Terminal({
      cursorBlink: true, scrollback: 2000, fontSize: 13,
      fontFamily: colors.getPropertyValue('--font-mono').trim(),
      theme: {
        background: colors.getPropertyValue('--surface').trim(),
        foreground: colors.getPropertyValue('--text-primary').trim(),
        cursor: colors.getPropertyValue('--text-primary').trim(),
      },
    })
    const fit = new FitAddon()
    emulator.loadAddon(fit)
    // Consume OSC clipboard/title requests. Remote output never drives browser side effects.
    const handlers = [0, 1, 2, 8, 52].map(id => emulator.parser.registerOscHandler(id, () => true))
    emulator.open(host)
    function resize() {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        if (!live || !host.clientWidth || !host.clientHeight) return
        fit.fit()
        if (emulator.cols > 500 || emulator.rows > 200) emulator.resize(Math.min(emulator.cols, 500), Math.min(emulator.rows, 200))
        transport?.resize(emulator.cols, emulator.rows)
      })
    }
    const observer = new ResizeObserver(resize)
    observer.observe(host)
    document.addEventListener('fullscreenchange', resize)
    const input = emulator.onData(value => transport?.input(value))
    const cleanup = () => {
      if (!live) return
      live = false
      observer.disconnect()
      cancelAnimationFrame(frame)
      document.removeEventListener('fullscreenchange', resize)
      input.dispose()
      handlers.forEach(handler => handler.dispose())
      transport?.dispose()
      emulator.dispose()
      host.replaceChildren()
    }
    dispose.current = cleanup
    try {
      transport = new TerminalTransport(terminalUrl(window.location, organizationId, connection.id), {
        write: bytes => emulator.write(bytes),
        ready: () => { resize(); emulator.focus() },
        state: (state, code) => {
          if (!live) return
          setStatus({ state, code })
          if (state === 'closed' || state === 'error') cleanup()
        },
      })
      resize()
    } catch {
      setStatus({ state: 'error', code: 'TERMINAL_UNAVAILABLE' })
      cleanup()
    }
  }

  const running = status.state === 'connecting' || status.state === 'connected' || status.state === 'closing'
  const reason = status.code ? labels.reasons[status.code as keyof typeof labels.reasons] ?? labels.unknownError : undefined
  return <div className="terminal-panel" ref={panel}>
    <div className="terminal-toolbar">
      <StatusIndicator label={labels.states[status.state]} tone={status.state === 'connected' ? 'success' : status.state === 'error' ? 'danger' : 'neutral'} />
      <div className="terminal-actions">
        {running ? <button className="secondary-button" type="button" onClick={() => {
          dispose.current()
          setStatus({ state: 'closed', code: 'CLIENT_CLOSE' })
        }}><Unplug size={16} />{labels.disconnect}</button> :
          <button className="primary-button" type="button" disabled={!!prerequisite} onClick={connect}><Plug size={16} />{labels.connect}</button>}
        {document.fullscreenEnabled ? <button className="icon-button" type="button" title={labels.fullscreen} aria-label={labels.fullscreen}
          onClick={() => {
            setFullscreenError(false)
            const request = document.fullscreenElement === panel.current ? document.exitFullscreen() : panel.current?.requestFullscreen()
            request?.catch(() => setFullscreenError(true))
          }}><Maximize size={18} /></button> : null}
      </div>
    </div>
    {prerequisite ? <InlineAlert tone={prerequisite === 'mismatch' ? 'danger' : 'warning'} title={labels[prerequisite]}
      action={editLink ? <Link className="secondary-button" to={editLink}>{t.common.edit}</Link> : undefined} /> : null}
    {reason ? <InlineAlert tone={status.state === 'error' ? 'danger' : 'info'} title={reason} /> : null}
    {fullscreenError ? <InlineAlert tone="warning" title={labels.unknownError} /> : null}
    <div ref={screen} className="terminal-screen" aria-label={labels.title} />
  </div>
}

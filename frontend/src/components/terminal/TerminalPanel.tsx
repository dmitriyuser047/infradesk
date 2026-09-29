import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Clipboard, Copy, Eraser, Maximize, Plug, RotateCcw, Unplug, X } from 'lucide-react'
import { useI18n } from '../../i18n'
import type { ConnectionResponse } from '../../types/connection'
import { InlineAlert, StatusIndicator } from '../layout/WorkspacePrimitives'
import type { TerminalSessionController } from '../workspace/terminalSession'
import { terminalStateTone } from '../workspace/terminalPresentation'
import { useTerminalSession, useTerminalWorkspace } from '../workspace/TerminalWorkspaceProvider'
import type { TerminalState } from './terminalTransport'

export function terminalPrerequisite(connection: ConnectionResponse): 'inactive' | 'mismatch' | 'untrusted' | 'missingCredentials' | undefined {
  if (!connection.active) return 'inactive'
  if (connection.lastSync?.errorCode === 'SSH_HOST_KEY_MISMATCH') return 'mismatch'
  if (!connection.ssh?.hostTrusted || !connection.ssh.hostKeyFingerprint) return 'untrusted'
  if (!connection.ssh.credentialConfigured) return 'missingCredentials'
}

/**
 * The connection's terminal as the page shows it. The terminal itself lives in the application's
 * workspace: leaving this tab or this page only takes it off the screen, and coming back shows the
 * same live session. It ends on Disconnect, on Close, on sign-out, or when the server ends it.
 */
export function TerminalPanel({ organizationId, connection, editLink }: {
  organizationId: string; connection: ConnectionResponse; editLink?: string
}) {
  const { t } = useI18n()
  const labels = t.terminal
  const workspace = useTerminalWorkspace()
  const session = useTerminalSession(organizationId, connection.id)
  const controller = session ? workspace.get(organizationId, connection.id) : undefined
  const panel = useRef<HTMLDivElement>(null)
  const [fullscreenError, setFullscreenError] = useState(false)
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null)
  const prerequisite = terminalPrerequisite(connection)
  const state: TerminalState = session?.state ?? 'idle'
  const running = state === 'connecting' || state === 'reconnecting' || state === 'connected' || state === 'closing'
  const reason = session?.code ? labels.reasons[session.code as keyof typeof labels.reasons] ?? labels.unknownError : undefined

  useEffect(() => { controller?.rename(connection.name) }, [controller, connection.name])

  const connect = () => { if (!prerequisite) workspace.open(organizationId, { id: connection.id, name: connection.name }) }

  return <div className="terminal-panel" ref={panel}>
    <div className="terminal-toolbar">
      <StatusIndicator label={labels.states[state]} tone={terminalStateTone(state)} />
      <div className="terminal-actions">
        {controller ? <>
          <button className="icon-button" type="button" title={labels.copy} aria-label={labels.copy} onClick={() => controller.copy()}><Copy size={16} /></button>
          <button className="icon-button" type="button" title={labels.paste} aria-label={labels.paste} disabled={state !== 'connected'} onClick={() => controller.paste()}><Clipboard size={16} /></button>
          <button className="icon-button" type="button" title={labels.clear} aria-label={labels.clear} onClick={() => controller.clear()}><Eraser size={16} /></button>
          <label className="terminal-theme-label">{labels.theme}<select aria-label={labels.theme} value={session?.theme ?? 'light'}
            onChange={event => controller.setTheme(event.target.value as 'light' | 'dark')}>
            <option value="light">{labels.light}</option><option value="dark">{labels.dark}</option>
          </select></label>
        </> : null}
        {running ? <button className="secondary-button" type="button" onClick={() => controller?.disconnect()}>
          <Unplug size={16} />{labels.disconnect}</button> : null}
        {!session ? <button className="primary-button" type="button" disabled={!!prerequisite} onClick={connect}>
          <Plug size={16} />{labels.connect}</button> : null}
        {session && !running ? <>
          <button className="primary-button" type="button" disabled={!!prerequisite} onClick={connect}>
            <RotateCcw size={16} />{labels.reconnect}</button>
          <button className="secondary-button" type="button" onClick={() => workspace.close(session.key)}>
            <X size={16} />{labels.close}</button>
        </> : null}
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
    {reason && !running ? <InlineAlert tone={state === 'error' ? 'danger' : 'info'} title={reason} /> : null}
    {session?.clipboardNotice ? <InlineAlert tone="warning" title={labels[session.clipboardNotice]} /> : null}
    {fullscreenError ? <InlineAlert tone="warning" title={labels.unknownError} /> : null}
    {controller ? <div className={`terminal-stage terminal-theme-${session?.theme ?? 'light'}`}
      onClick={() => { if (menu) setMenu(null) }}
      onContextMenu={event => { event.preventDefault(); setMenu({ x: event.clientX, y: event.clientY }) }}>
      <TerminalViewport controller={controller} label={labels.title} />
      {state !== 'connected' && controller.hasHistory ? <div className="terminal-history-label">{labels.history} · {labels.states[state]}</div> : null}
      {menu ? <div className="terminal-context-menu" role="menu" style={{ left: menu.x, top: menu.y }} onMouseLeave={() => setMenu(null)}>
        <button type="button" role="menuitem" onClick={() => { controller.copy(); setMenu(null) }}>{labels.copy}</button>
        <button type="button" role="menuitem" disabled={state !== 'connected'} onClick={() => { controller.paste(); setMenu(null) }}>{labels.paste}</button>
        <button type="button" role="menuitem" onClick={() => { controller.selectAll(); setMenu(null) }}>{labels.selectAll}</button>
        <button type="button" role="menuitem" onClick={() => { controller.clear(); setMenu(null) }}>{labels.clear}</button>
      </div> : null}
    </div>
      : <div className="terminal-screen" aria-label={labels.title} />}
  </div>
}

/**
 * Where a terminal is drawn while its page is shown. Mounting attaches the terminal's own element;
 * unmounting detaches it and nothing else: the session, the emulator and its scrollback go on.
 */
function TerminalViewport({ controller, label }: { controller: TerminalSessionController; label: string }) {
  const host = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const container = host.current
    if (!container) return undefined
    const detach = controller.attach(container)
    let frame = 0
    const resize = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => controller.fit())
    }
    const observer = new ResizeObserver(resize)
    observer.observe(container)
    document.addEventListener('fullscreenchange', resize)
    resize()
    return () => {
      observer.disconnect()
      cancelAnimationFrame(frame)
      document.removeEventListener('fullscreenchange', resize)
      detach()
    }
  }, [controller])
  return <div ref={host} className="terminal-screen" aria-label={label} />
}

export type TerminalState = 'idle' | 'connecting' | 'reconnecting' | 'connected' | 'closing' | 'closed' | 'error'
export const terminalProtocol = 'infradesk-terminal-v1'
const chunkBytes = 16 * 1024
const queueLimit = 256 * 1024
const highWater = 64 * 1024

export function terminalUrl(location: Pick<Location, 'protocol' | 'host'>, organizationId: string, connectionId: string): string {
  return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/api/v1/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connectionId)}/terminal`
}

export function inputChunks(value: string): Uint8Array[] {
  return byteChunks(new TextEncoder().encode(value))
}

function byteChunks(bytes: Uint8Array): Uint8Array[] {
  const chunks: Uint8Array[] = []
  for (let offset = 0; offset < bytes.length; offset += chunkBytes) chunks.push(bytes.slice(offset, offset + chunkBytes))
  return chunks
}

export interface TerminalSink {
  write(bytes: Uint8Array): void
  state(state: TerminalState, code?: string): void
  ready(): void
}

/** One transport per explicit connect. No reconnect and no input survives disposal. */
export class TerminalTransport {
  private socket: WebSocket
  private queue: Uint8Array[] = []
  private queuedBytes = 0
  private timer: ReturnType<typeof setTimeout> | undefined
  private active = false
  private disposed = false
  private lastSize = ''
  private pendingSize: { columns: number; rows: number } | undefined
  sessionId: string | undefined

  constructor(url: string, private sink: TerminalSink, createSocket = (url: string, protocol: string) => new WebSocket(url, protocol)) {
    sink.state('connecting')
    this.socket = createSocket(url, terminalProtocol)
    this.socket.binaryType = 'arraybuffer'
    this.socket.onopen = () => {
      if (this.socket.protocol !== terminalProtocol) this.fail('PROTOCOL_ERROR')
    }
    this.socket.onmessage = event => {
      if (this.disposed) return
      if (event.data instanceof ArrayBuffer) {
        if (this.active && event.data.byteLength <= 65536) sink.write(new Uint8Array(event.data))
        else this.fail('PROTOCOL_ERROR')
        return
      }
      try {
        if (typeof event.data !== 'string' || new TextEncoder().encode(event.data).length > 8192) throw new Error()
        const message = JSON.parse(event.data)
        if (message.type === 'ready' && message.protocolVersion === 1 && !this.active) {
          if (typeof message.sessionId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(message.sessionId)) throw new Error()
          this.sessionId = message.sessionId
          this.active = true
          sink.state('connected')
          sink.ready()
        } else if (message.type === 'error' && typeof message.code === 'string') {
          this.fail(message.code)
        } else if (message.type === 'closed' && typeof message.code === 'string') {
          sink.state('closed', message.code)
          this.dispose()
        } else throw new Error()
      } catch { this.fail('PROTOCOL_ERROR') }
    }
    this.socket.onerror = () => this.fail('TERMINAL_UNAVAILABLE')
    this.socket.onclose = event => {
      if (!this.disposed) {
        sink.state('closed', event.reason || 'TRANSPORT_CLOSED')
        this.dispose()
      }
    }
  }

  input(value: string): void {
    if (!this.active || this.disposed) return
    const bytes = new TextEncoder().encode(value)
    const size = bytes.length
    if (size + this.queuedBytes + this.socket.bufferedAmount > queueLimit - 128) {
      this.fail('INPUT_BACKPRESSURE')
      return
    }
    this.queue.push(...byteChunks(bytes))
    this.queuedBytes += size
    this.flush()
  }

  resize(columns: number, rows: number): void {
    if (!this.active || this.disposed || !Number.isInteger(columns) || !Number.isInteger(rows) || columns < 1 || rows < 1) return
    const cols = Math.min(columns, 500)
    rows = Math.min(rows, 200)
    const key = `${cols}:${rows}`
    if (key === this.lastSize) return
    this.pendingSize = { columns: cols, rows }
    this.flush()
  }

  disconnect(): void {
    if (!this.disposed) {
      this.sink.state('closing')
      this.dispose()
      this.sink.state('closed', 'CLIENT_CLOSE')
    }
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.active = false
    this.sessionId = undefined
    clearTimeout(this.timer)
    this.timer = undefined
    this.queue = []
    this.queuedBytes = 0
    this.pendingSize = undefined
    this.socket.onopen = this.socket.onmessage = this.socket.onerror = this.socket.onclose = null
    if (this.socket.readyState < 2) this.socket.close(1000)
  }

  private fail(code: string): void {
    if (this.disposed) return
    this.sink.state('error', code)
    this.dispose()
  }

  private flush(): void {
    clearTimeout(this.timer)
    this.timer = undefined
    if (this.disposed || !this.active) return
    try {
      if (this.pendingSize && this.socket.bufferedAmount < highWater) {
        this.socket.send(JSON.stringify({ type: 'resize', ...this.pendingSize }))
        this.lastSize = `${this.pendingSize.columns}:${this.pendingSize.rows}`
        this.pendingSize = undefined
      }
      while (this.queue.length && this.socket.bufferedAmount < highWater) {
        const chunk = this.queue.shift()!
        this.socket.send(chunk)
        this.queuedBytes -= chunk.length
      }
      if (this.queue.length || this.pendingSize) this.timer = setTimeout(() => this.flush(), 25)
    } catch { this.fail('TERMINAL_UNAVAILABLE') }
  }
}

import { afterEach, describe, expect, it, vi } from 'vitest'
import { inputChunks, TerminalTransport, terminalProtocol, terminalUrl } from './terminalTransport'

class Socket {
  binaryType = ''
  protocol = terminalProtocol
  readyState = 1
  bufferedAmount = 0
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  send = vi.fn()
  close = vi.fn(() => { this.readyState = 3 })
  message(data: unknown) { this.onmessage?.({ data }) }
  ready() { this.message(JSON.stringify({ type: 'ready', protocolVersion: 1, columns: 80, rows: 24, sessionId: '00000000-0000-0000-0000-000000000001' })) }
}
function fixture() {
  const socket = new Socket()
  const sink = { write: vi.fn(), state: vi.fn(), ready: vi.fn() }
  const factory = vi.fn(() => socket as unknown as WebSocket)
  const transport = new TerminalTransport('wss://example/terminal', sink, factory)
  return { socket, sink, factory, transport }
}
afterEach(() => vi.useRealTimers())
describe('terminal transport', () => {
  it('uses same-origin ws/wss with encoded IDs and no auth query', () => {
    expect(terminalUrl({ protocol: 'https:', host: 'example:8443' }, 'a/b', 'x y')).toBe('wss://example:8443/api/v1/organizations/a%2Fb/connections/x%20y/terminal')
    expect(terminalUrl({ protocol: 'http:', host: 'localhost:5173' }, 'a', 'b')).toMatch(/^ws:\/\/localhost:5173/)
  })
  it('negotiates the protocol and accepts no input before ready', () => {
    const { socket, transport, factory, sink } = fixture()
    expect(factory).toHaveBeenCalledWith('wss://example/terminal', terminalProtocol)
    expect(socket.binaryType).toBe('arraybuffer')
    transport.input('secret')
    expect(socket.send).not.toHaveBeenCalled()
    socket.ready()
    expect(sink.state).toHaveBeenLastCalledWith('connected')
    transport.input('hello')
    expect(socket.send).toHaveBeenCalledWith(new TextEncoder().encode('hello'))
    transport.dispose()
  })
  it('writes binary bytes and deduplicates valid bounded resize', () => {
    const { socket, transport, sink } = fixture()
    socket.ready()
    socket.message(new Uint8Array([0, 255, 27]).buffer)
    expect(sink.write).toHaveBeenCalledWith(new Uint8Array([0, 255, 27]))
    transport.resize(0, 10)
    transport.resize(100, 30)
    transport.resize(100, 30)
    transport.resize(999, 999)
    expect(socket.send.mock.calls).toEqual([[JSON.stringify({ type: 'resize', columns: 100, rows: 30 })], [JSON.stringify({ type: 'resize', columns: 500, rows: 200 })]])
    transport.dispose()
  })
  it('chunks UTF8 bytes without corrupting the combined stream', () => {
    const input = '\u{1f642}abc'.repeat(10000)
    const chunks = inputChunks(input)
    expect(chunks.every(chunk => chunk.length <= 16384)).toBe(true)
    const combined = new Uint8Array(chunks.reduce((sum, chunk) => sum + chunk.length, 0))
    let offset = 0
    chunks.forEach(chunk => { combined.set(chunk, offset); offset += chunk.length })
    expect(new TextDecoder().decode(combined)).toBe(input)
  })
  it('queues backpressured input in order and clears pending timers on disposal', () => {
    vi.useFakeTimers()
    const { socket, transport } = fixture()
    socket.ready()
    socket.bufferedAmount = 65536
    transport.input('first')
    transport.input('second')
    expect(socket.send).not.toHaveBeenCalled()
    socket.bufferedAmount = 0
    vi.advanceTimersByTime(25)
    expect(socket.send.mock.calls.map(([bytes]) => new TextDecoder().decode(bytes))).toEqual(['first', 'second'])
    socket.bufferedAmount = 65536
    transport.input('stale')
    transport.dispose()
    socket.bufferedAmount = 0
    vi.runAllTimers()
    expect(socket.send).toHaveBeenCalledTimes(2)
    expect(socket.onmessage).toBeNull()
    expect(socket.close).toHaveBeenCalledTimes(1)
  })
  it('fails closed when bounded input queue overflows without reconnecting', () => {
    const { socket, transport, sink, factory } = fixture()
    socket.ready()
    transport.input('x'.repeat(262145))
    expect(sink.state).toHaveBeenLastCalledWith('error', 'INPUT_BACKPRESSURE')
    expect(socket.close).toHaveBeenCalled()
    expect(factory).toHaveBeenCalledTimes(1)
  })
  it('rejects mismatched protocol and malformed controls safely', () => {
    const first = fixture()
    first.socket.protocol = 'other'
    first.socket.onopen?.()
    expect(first.sink.state).toHaveBeenLastCalledWith('error', 'PROTOCOL_ERROR')
    const second = fixture()
    second.socket.message('{')
    expect(second.sink.state).toHaveBeenLastCalledWith('error', 'PROTOCOL_ERROR')
  })
  it('preserves safe error and close codes and creates no replacement socket', () => {
    const first = fixture()
    first.socket.message(JSON.stringify({ type: 'error', code: 'SSH_UNAVAILABLE' }))
    expect(first.sink.state).toHaveBeenLastCalledWith('error', 'SSH_UNAVAILABLE')
    const second = fixture()
    second.socket.ready()
    second.socket.message(JSON.stringify({ type: 'closed', code: 'REMOTE_EOF' }))
    expect(second.sink.state).toHaveBeenLastCalledWith('closed', 'REMOTE_EOF')
    expect(second.factory).toHaveBeenCalledTimes(1)
  })
})

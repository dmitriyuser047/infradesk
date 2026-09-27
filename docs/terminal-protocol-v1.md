# Browser SSH Terminal Protocol v1

The endpoint is `GET /api/v1/organizations/{organizationId}/connections/{connectionId}/terminal`.
It upgrades an authenticated same-origin HTTP request using the existing HttpOnly session cookie.
The optional negotiated subprotocol is `infradesk-terminal-v1`. Host, port, username, host key,
and credential are always resolved from the active SSH connection on the server.

After opening the SSH PTY, the server sends a text JSON frame:

```json
{"type":"ready","protocolVersion":1,"columns":80,"rows":24,"sessionId":"00000000-0000-0000-0000-000000000001"}
```

Client binary frames are raw stdin bytes; server binary frames are raw PTY output bytes. Client
resize control frames are JSON text:

```json
{"type":"resize","columns":120,"rows":40}
```

Server text control frames report `error` or `closed` with a stable code and safe message. PTY
output is never logged or persisted. Fragmented and unsupported frames are rejected. Binary frames
are bounded to 64 KiB and controls to 8 KiB; dimensions are limited to 500 columns by 200 rows.
Server frames are unfragmented. Oversized binary/control frames close with 1009; unsupported
fragmentation, invalid JSON/control, and invalid dimensions close with 1002. SSH/internal failures
close with 1011; normal disconnect, remote EOF, idle timeout, and maximum lifetime close with 1000.
Live policy revocations close with 1008 and a safe closed control: AUTH_SESSION_ENDED,
TERMINAL_PERMISSION_REVOKED, CONNECTION_CHANGED or TERMINAL_SESSION_REVOKED.
An ordinary authenticated same-origin GET returns 426 `TERMINAL_WEBSOCKET_REQUIRED`.

Default limits are 80x24 initial dimensions, 30 minutes idle time, two hours maximum lifetime,
4 sessions per actor in an organization, 32 per organization and 16 per backend process.
Activity means terminal input, resize, or SSH
output, not WebSocket Ping/Pong traffic. These limits are startup-validated and can be adjusted
within hard bounds using the `INFRADESK_TERMINAL_*` variables in the production environment file.

The server loads the tenant-scoped connection and credential before Upgrade. The PostgreSQL
transaction ends before SSH connect. During a stream, bounded heartbeat transactions renew the
lease and validate the auth session, user, membership and captured connection version; no
database work occurs per input/output/resize frame. Defaults are heartbeat 15s and lease 45s.
An expired-lease reaper closes at most 100 rows per minute. Lifecycle transitions journal
TERMINAL_SESSION_OPENED/CLOSED atomically, never bytes or credentials. SSH
capacity is reserved atomically before Upgrade; a full process returns HTTP 503 `TERMINAL_CAPACITY`.
Invalid handshakes are rejected before capacity is reserved; failed or cancelled WebSocket builds
release their reservations. SSH shares the existing sshj pinned-host-key verifier and authentication
provider. SSH session and
client resources, as well as the in-process capacity permit, are released when the WebSocket
stream closes, fails, times out, or is cancelled during process shutdown.

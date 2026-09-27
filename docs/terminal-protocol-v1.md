# Browser SSH Terminal Protocol v1

The endpoint is `GET /api/v1/organizations/{organizationId}/connections/{connectionId}/terminal`.
It upgrades an authenticated same-origin HTTP request using the existing HttpOnly session cookie.
The optional negotiated subprotocol is `infradesk-terminal-v1`. Host, port, username, host key,
and credential are always resolved from the active SSH connection on the server.

After opening the SSH PTY, the server sends a text JSON frame:

```json
{"type":"ready","protocolVersion":1,"columns":80,"rows":24}
```

Client binary frames are raw stdin bytes; server binary frames are raw PTY output bytes. Client
resize control frames are JSON text:

```json
{"type":"resize","columns":120,"rows":40}
```

Server text control frames report `error` or `closed` with a stable code and safe message. PTY
output is never logged or persisted. Fragmented and unsupported frames are rejected. Binary frames
are bounded to 64 KiB and controls to 8 KiB; dimensions are limited to 500 columns by 200 rows.

Default limits are 80x24 initial dimensions, 30 minutes idle time, two hours maximum lifetime,
and 16 simultaneous sessions per backend process. Activity means terminal input, resize, or SSH
output, not WebSocket Ping/Pong traffic. These limits are startup-validated and can be adjusted
within hard bounds using the `INFRADESK_TERMINAL_*` variables in the production environment file.

The server loads the tenant-scoped connection and credential before Upgrade. The PostgreSQL
transaction ends before SSH connect; no database work occurs during the terminal stream. SSH
shares the existing sshj pinned-host-key verifier and authentication provider. SSH session and
client resources, as well as the in-process capacity permit, are released when the WebSocket
stream closes, fails, times out, or is cancelled during process shutdown.

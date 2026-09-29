# Browser SSH terminal

SSH connections expose a Terminal tab to organization Owners through the independent
`OpenTerminal` capability. Members and non-SSH connections have no terminal tab.
The connection must be active, have configured credentials and a confirmed pinned host key.
A reported host-key mismatch blocks the UI until the connection is explicitly reviewed.

Connect is explicit. Navigation detaches the viewport while the workspace keeps its shell
and scrollback alive. Disconnect ends the socket and SSH shell; closing the dock tab
releases the emulator. An unexpected transport loss retries a fresh SSH session up to
three times with 1, 2 and 4 second delays. Manual Disconnect never retries. A new
session clears the prior shell output after `ready`; until then, a status label marks it
as previous output. Fullscreen uses the browser's Fullscreen API.

The terminal has independent light and near-black themes. Only the theme choice is
persisted in local storage. xterm provides mouse selection and scrollback. Copy and
paste controls, a compact context menu and standard keyboard shortcuts operate on the
emulator; clipboard text and terminal output are not stored by InfraDesk.

The same-origin WebSocket path is
`/api/v1/organizations/{organizationId}/connections/{connectionId}/terminal`.
The browser requests `infradesk-terminal-v1`; the existing authentication cookie and
same-origin check authorize the Upgrade. No token is placed in the URL or subprotocol.

Protocol v1 uses `protocolVersion: 1`. `ready` contains `columns`, `rows` and a public
terminal `sessionId` UUID. Lease owner/token are server-only. Binary frames carry bytes
in both directions. Resize is JSON `{ "type": "resize", "columns": 100, "rows": 35 }`.
Safe error/closed controls carry bounded codes; native pre-Upgrade browser failures cannot
expose the HTTP response body. Unknown codes have a generic localized presentation.

Input is UTF-8 encoded before splitting into 16 KiB frames. The total pending input plus
WebSocket buffered bytes is bounded at 256 KiB; overflow closes the transport explicitly.
Output goes to xterm as bytes. Remote OSC clipboard and title requests are consumed;
there are no web-link or WebGL addons. Scrollback is local and limited to 2,000 lines.

## Durable Lifecycle

`terminal_session` stores lifecycle identifiers, states, timestamps, captured connection
version, leases and bounded close reasons. It stores no SSH configuration, credentials,
auth tokens, stdin, output or transcript. There are no per-byte/per-key/per-resize DB writes.

Organization row locking serializes claims across backend processes. Unexpired OPENING
and ACTIVE sessions count toward distributed limits. Defaults are 4 per actor in an
organization and 32 per organization, with an additional local semaphore of 16.
Lease-token fencing protects every ownership transition. Activation and close write
`TERMINAL_SESSION_OPENED` / `TERMINAL_SESSION_CLOSED` org audit events atomically;
duplicate transitions cannot duplicate audit events. The audit target is TERMINAL_SESSION.

Heartbeat is 15 seconds and lease duration is 45 seconds by default. Heartbeat validates
the originating auth session, active user, active Owner membership and unchanged active
SSH connection. Logout/session revocation and changed access/settings close live terminals
within the heartbeat bound. Heartbeats do not extend idle activity.
Heartbeat database errors are retried twice after 2 and 4 seconds. A revoked or missing
session closes immediately; repeated validation failure closes with 1011 and releases
SSH resources (fail closed).
The periodic reaper runs once a minute, locks at most 100 expired rows with SKIP LOCKED,
closes them as LEASE_EXPIRED and journals their closure in the same transaction.

Policy close code 1008: AUTH_SESSION_ENDED, TERMINAL_PERMISSION_REVOKED,
CONNECTION_CHANGED, TERMINAL_SESSION_REVOKED.
Normal close code 1000: CLIENT_CLOSE, REMOTE_EOF, IDLE_TIMEOUT, MAX_LIFETIME.
Malformed frames use 1002, oversized frames 1009, SSH/unexpected/validation failures 1011.

Configuration is in `.env.example` and production compose. Heartbeat must be positive,
at most 300 seconds; lease must be at least twice heartbeat and at most 900 seconds.
Default idle timeout remains 30 minutes; maximum lifetime remains 2 hours.

Production CI exercises the real nginx-to-backend-to-SSH path, validates the ready UUID,
reads a safe marker in binary output, logs out through the API and requires a safe
AUTH_SESSION_ENDED control followed by close 1008. PostgreSQL tests exercise concurrent
capacity claims, token fencing, revocations, reaping and audit rollback.

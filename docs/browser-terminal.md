# Browser SSH terminal

SSH connections expose a Terminal tab to organization Owners through the independent
`OpenTerminal` capability. Members and non-SSH connections have no terminal tab.
The connection must be active, have configured credentials and a confirmed pinned host key.
A reported host-key mismatch blocks the UI until the connection is explicitly reviewed.

Connect is explicit. Navigation detaches the viewport while the workspace keeps its shell
and scrollback alive. Disconnect, closing the dock tab, signing out and leaving or reloading
the page end the SSH shell; closing the dock tab also releases the emulator.

The SSH shell belongs to its durable terminal session, not to the WebSocket. A socket lost
without the explicit `close` control (network loss, proxy restart, sleeping laptop) leaves
the shell running, detached, for the detach timeout (10 minutes by default): commands such
as a long build keep running and their output is kept, newest bytes first, up to a bound
(256 KiB by default). The client retries up to three times with 1, 2 and 4 second delays,
and Reconnect tries again later; each attempt resumes the same shell and the screen simply
continues, with a dim `…` line where detached output was dropped. If the shell is gone, the
server answers SESSION_NOT_RESUMABLE and the client opens a fresh shell at once. A fresh
shell keeps the prior output in the scrollback: after `ready` it leaves any alternate
screen, soft-resets terminal modes and draws a separator line; until then, a status label
marks it as previous output. Only the explicit Clear action erases it. Manual Disconnect
never retries. Fullscreen uses the browser's Fullscreen API.

The terminal has independent light and near-black themes. Only the theme choice is
persisted in local storage. xterm provides mouse selection and scrollback. Copy and
paste controls, a compact context menu and standard keyboard shortcuts operate on the
emulator. On HTTP origins without the Clipboard API, Copy tries the browser's legacy
selection copy during the click; Paste directs the user to a native keyboard paste if
the browser will not let the page read the clipboard. Clipboard text and terminal
output are not stored by InfraDesk.

The same-origin WebSocket path is
`/api/v1/organizations/{organizationId}/connections/{connectionId}/terminal`.
The browser requests `infradesk-terminal-v1`; the existing authentication cookie and
same-origin check authorize the Upgrade. No token is placed in the URL or subprotocol.

Protocol v1 uses `protocolVersion: 1`. `ready` contains `columns`, `rows`, a public
terminal `sessionId` UUID, `resumed` and `outputTruncated`. Lease owner/token are
server-only. Binary frames carry bytes in both directions. Resize is JSON
`{ "type": "resize", "columns": 100, "rows": 35 }`. `{ "type": "close" }` ends the shell
(close 1000 CLIENT_CLOSE). Ember answers a bare Close frame itself, indistinguishably from
a lost connection, so a Close frame alone only detaches.

`?resume={sessionId}` on the same path attaches a new socket to a live shell. The usual
authentication, OpenTerminal permission, same-origin and active SSH connection checks apply;
the shell must also belong to the same organization, connection, user and login session,
and the connection version must be unchanged. Shells live only in the backend process that
opened them. A missing, foreign, ended or other-process shell is answered identically, with
an error control SESSION_NOT_RESUMABLE and close 1000. A resume supersedes a socket still
attached to the shell, which receives close 1000 SESSION_RESUMED; only the current socket
can end the shell or fail it closed with a protocol error. While a socket is attached, a full
output buffer holds the shell back as before; a socket that takes no output for 30 seconds is
treated as gone and detached.
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
Heartbeat database errors are retried twice after 2 and 4 seconds, with each validation
attempt bounded at 3 seconds. Configuration requires a lease covering one heartbeat,
the 15-second worst-case retry window and five seconds of margin; activation starts a fresh lease after SSH
setup. A revoked or missing session closes immediately; repeated validation failure
closes with 1011 and releases SSH resources (fail closed).
The periodic reaper runs once a minute, locks at most 100 expired rows with SKIP LOCKED,
closes them as LEASE_EXPIRED and journals their closure in the same transaction.

Policy close code 1008: AUTH_SESSION_ENDED, TERMINAL_PERMISSION_REVOKED,
CONNECTION_CHANGED, TERMINAL_SESSION_REVOKED.
Normal close code 1000: CLIENT_CLOSE, REMOTE_EOF, IDLE_TIMEOUT, MAX_LIFETIME,
SESSION_NOT_RESUMABLE, SESSION_RESUMED. A detached shell that is not resumed in time closes
its durable session as DETACH_TIMEOUT. Heartbeat, idle and lifetime limits keep applying while
a shell is detached; output counts as activity. Server shutdown ends every shell with
SERVER_SHUTDOWN.
Malformed frames use 1002, oversized frames 1009, SSH/unexpected/validation failures 1011.

Configuration is in `.env.example` and production compose. Heartbeat must be positive,
at most 300 seconds; lease must be at least twice heartbeat and at most 900 seconds.
Default idle timeout remains 30 minutes; maximum lifetime remains 2 hours. The detach timeout
(`INFRADESK_TERMINAL_DETACH_TIMEOUT_SECONDS`, 1–7200, default 600) and the detached output
bound (`INFRADESK_TERMINAL_DETACHED_OUTPUT_BYTES`, 4096–4194304, default 262144) are
configurable. A detached shell keeps its capacity slot until it ends.

Production CI exercises the real nginx-to-backend-to-SSH path, validates the ready UUID,
reads a safe marker in binary output, logs out through the API and requires a safe
AUTH_SESSION_ENDED control followed by close 1008. PostgreSQL tests exercise concurrent
capacity claims, token fencing, revocations, reaping and audit rollback.

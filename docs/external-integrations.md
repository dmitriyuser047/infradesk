# External integrations: Stages 24A–24B

Organization owners can open **Integrations**, add a Remnawave panel, save its API token and
optional Caddy API key, test the connection, then enable it. New integrations are disabled.
Enabling turns on automatic, read-only observation (Stage 24B below); no action is ever sent.

The base URL must be an absolute HTTP or HTTPS URL with a host. A path prefix is allowed;
user information, query parameters, fragments and invalid ports are rejected. Trailing slashes
are normalized. The read-only test sends `GET <base URL>/api/system/stats` with
`Authorization: Bearer <API token>` and, when configured, `X-Api-Key: <Caddy API key>`.
It accepts a successful JSON response only when the Remnawave stats envelope has numeric
`response.uptime` and `response.users.totalUsers` fields. The response body is not returned
or logged.

The integration credential is encrypted in `integration_secret` with the deployment's existing
master key and a separate integration AAD kind. Updating without `credentials` preserves it;
supplying `credentials` replaces the entire credential. An absent Caddy key in that replacement
removes the old one. API responses contain only configured flags.

The runtime uses a single validated HTTP client for integrations. DNS is resolved and validated
at socket connection time; the validated address is the dialed address. Loopback, link-local and
multicast addresses are always blocked. Private destinations require the operator setting
`INFRADESK_INTEGRATIONS_ALLOW_PRIVATE_DESTINATIONS=true` (default: false). The separate
`INFRADESK_INTEGRATIONS_REQUEST_TIMEOUT_SECONDS` defaults to 10 seconds and accepts 1–120.
There is no per-integration bypass of this policy.

The test intent and the encrypted credential read commit in a short PostgreSQL transaction.
Decryption and the external API call happen afterward, without holding a database connection.
Only the `INTEGRATION_TEST_REQUESTED` audit action is recorded; the journal does not imply that
the external call succeeded. All integration endpoints require `MANAGE_INTEGRATIONS`, held by
organization owners.

## Stage 24B: read-only inventory, synchronization and node binding

**What is read.** One synchronization sends exactly three requests, at most three at a time,
whatever the number of objects: `GET <base URL>/api/nodes`, `GET <base URL>/api/hosts` and
`GET <base URL>/api/config-profiles`, with the same headers as the test. No write request is
ever sent to Remnawave. Any failing endpoint fails the whole synchronization and the stored
inventory stays exactly as it was.

**What is kept.** Each response is decoded into a typed, sanitized projection: node identity,
address, port, connection flags, versions, traffic counters, country, CPU/RAM, active config
profile and tags; host remark, address, port, flags, inbound references, node references and
security layer; config profile name, position, timestamps, node references and each inbound's
tag, type, network, security and port. A node's `proxyUrl`, a profile's `config`, an inbound's
`rawInbound`, a host's `xhttpExtraParams`, `muxParams`, `sockoptParams` and `finalMask`, and any
other raw Xray JSON are never read, so they cannot reach the database, logs, audit or errors.
A response over `INFRADESK_INTEGRATIONS_INVENTORY_MAX_RESPONSE_BYTES` (checked on
`Content-Length` and on raw bytes), invalid UTF-8 or JSON, a missing required field, a
duplicated object or a snapshot over `INFRADESK_INTEGRATIONS_INVENTORY_MAX_OBJECTS` is
`INTEGRATION_INVALID_RESPONSE`.

**How it is stored.** `integration_inventory_object` holds one row per external object. A
snapshot is applied in one transaction with two statements: a batch upsert (unchanged rows keep
their update time) and a deactivation of every stored object of a listed type that the snapshot
no longer contains. Objects are never deleted by synchronization; a reappearing object is
reactivated with the same id. Every table is tenant-scoped and removed with its integration.

**Sessions.** Each synchronization is an `integration_sync_session` (MANUAL or SCHEDULED; RUNNING,
COMPLETED or FAILED with a stable error code). A partial unique index allows one RUNNING session
per integration; a second manual request gets `409 INTEGRATION_SYNC_ALREADY_RUNNING`. A RUNNING
session past its own deadline is failed as `INTEGRATION_SYNC_STALE` by the next attempt. The
first transaction claims the session (and audits `INTEGRATION_SYNC_REQUESTED` for a manual
request); decryption and HTTP happen outside any transaction; the second transaction applies the
snapshot and completes the session, or rolls both back if the session was retired meanwhile.

**Scheduling.** Enabled integrations are observed automatically by a scheduler separate from
connection synchronization. Due rows of `integration_sync_state` are claimed with
`FOR UPDATE SKIP LOCKED` and a lease, each claim fenced by its own token; failures back off
(5, 15, then 30 minutes, never below the interval). Scheduled runs are not audited. Manual
**Sync now** works while an integration is disabled.

| Variable | Default |
| --- | --- |
| `INFRADESK_INTEGRATIONS_SYNC_ENABLED` | `true` |
| `INFRADESK_INTEGRATIONS_SYNC_POLL_INTERVAL_SECONDS` | `5` |
| `INFRADESK_INTEGRATIONS_SYNC_INTERVAL_SECONDS` | `60` |
| `INFRADESK_INTEGRATIONS_SYNC_BATCH_SIZE` | `20` |
| `INFRADESK_INTEGRATIONS_SYNC_MAX_CONCURRENCY` | `3` |
| `INFRADESK_INTEGRATIONS_SYNC_CLAIM_LEASE_SECONDS` | `90` (must exceed the attempt timeout) |
| `INFRADESK_INTEGRATIONS_SYNC_ATTEMPT_TIMEOUT_SECONDS` | `30` |
| `INFRADESK_INTEGRATIONS_INVENTORY_MAX_RESPONSE_BYTES` | `8388608` |
| `INFRADESK_INTEGRATIONS_INVENTORY_MAX_OBJECTS` | `10000` |

**Binding.** A Remnawave node can be bound by hand to one active InfraDesk NODE resource of the
same organization (`PUT .../inventory/objects/{objectId}/binding`, body `{"resourceId": ...}`).
Binding to the same resource again changes nothing; binding to another replaces the link
(`INTEGRATION_RESOURCE_BOUND`); `DELETE` removes it (`INTEGRATION_RESOURCE_UNBOUND`). Nothing is
created or bound automatically, and an inactive node keeps its binding. The resource page shows
the bound node's Remnawave status read-only.

Not part of this stage: node or host actions, traffic reset, profile management, users and
subscriptions, desired state, remediation, webhooks and automatic matching.

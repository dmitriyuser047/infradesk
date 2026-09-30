# External integrations: Stages 24A–24D

Organization owners can open **Integrations**, add a Remnawave panel, save its API token and
optional Caddy API key, test the connection, then enable it. New integrations are disabled.
Enabling turns on automatic, read-only observation (Stage 24B below). Node actions require an
explicit user request.

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
the external call succeeded. Management and read endpoints require `MANAGE_INTEGRATIONS`;
node action requests require `EXECUTE_OPERATIONS`.

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
Decoding follows the upstream contract (`NodesSchema`, `HostsSchema`, `ConfigProfileSchema`)
strictly: a required field that is missing or of the wrong type, a nullable field that is absent
(rather than `null`), an identity or reference that is not a canonical UUID, or any bad item in a
related list is contract drift, and the whole listing is rejected. Nothing is defaulted (a missing
connection flag never becomes `false`) and no related list is shortened. A response over
`INFRADESK_INTEGRATIONS_INVENTORY_MAX_RESPONSE_BYTES` (checked on `Content-Length` and on raw
bytes), invalid UTF-8 or JSON, a duplicated object or a snapshot over
`INFRADESK_INTEGRATIONS_INVENTORY_MAX_OBJECTS` is `INTEGRATION_INVALID_RESPONSE` as well.

**How it is stored.** `integration_inventory_object` holds one row per external object. A
snapshot is applied in one transaction with two statements: a batch upsert (unchanged rows keep
their update time) and a deactivation of every stored object of a listed type that the snapshot
no longer contains. Objects are never deleted by synchronization; a reappearing object is
reactivated with the same id. Every table is tenant-scoped and removed with its integration.
Composite foreign keys keep an object's last-seen session and a binding's object inside the same
integration, so no row can point across integrations.

**Sessions.** Each synchronization is an `integration_sync_session` (MANUAL or SCHEDULED; RUNNING,
COMPLETED or FAILED with a stable error code). A partial unique index allows one RUNNING session
per integration; a second manual request gets `409 INTEGRATION_SYNC_ALREADY_RUNNING`. A RUNNING
session past its own deadline is failed as `INTEGRATION_SYNC_STALE` by the next attempt. The
first transaction claims the session (and audits `INTEGRATION_SYNC_REQUESTED` for a manual
request); decryption and HTTP happen outside any transaction; the second transaction applies the
snapshot and completes the session, or rolls both back if the session was retired meanwhile.
A session that was already finished elsewhere keeps that durable outcome: a later failure of the
same attempt never overwrites it, and the API answers with the stored session.

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

## Stage 24C: durable node actions

Owners with `MANAGE_INTEGRATIONS` and `EXECUTE_OPERATIONS` can confirm **Enable**, **Disable** or
**Restart** for an active observed Remnawave node, whether or not it is bound to a resource or
automatic observation is enabled. The POST endpoint is
`.../integrations/{integrationId}/inventory/objects/{objectId}/actions`, with a UUID `requestId`
and one of `NODE_ENABLE`, `NODE_DISABLE`, `NODE_RESTART`. It returns an execution with HTTP 202.
`GET .../integrations/{integrationId}/actions` lists the latest 50 executions (up to 100), and
`GET .../integrations/{integrationId}/actions/{executionId}` returns one execution. Both read
endpoints require `MANAGE_INTEGRATIONS`.

The short request transaction validates the observed node and credential, inserts a `QUEUED`
execution and records `INTEGRATION_ACTION_REQUESTED` in the same commit. PostgreSQL enforces one
execution per organization and request ID and one active (`QUEUED` or `RUNNING`) action per node.
The worker claims only enough rows to start immediately, using `FOR UPDATE SKIP LOCKED`, and
assigns a token and deadline. Decryption and exactly one Remnawave POST occur after the claim
commits. Completion requires the same claim token. Abandoned `RUNNING` rows become `UNKNOWN`;
they are never retried automatically. A later action on that node requires an observation whose
`lastSeenAt` is after the unknown execution's finish time.

The provider sends `POST /api/nodes/{uuid}/actions/enable` or `disable`, or `POST
/api/nodes/{uuid}/actions/restart` with `{"forceRestart":false}`. It uses the integration's
Bearer token and optional Caddy key. Confirmed success does not change stored inventory; a new
observation does. For an enabled integration, successful and unknown results advance the next
automatic observation. A disabled integration can be refreshed manually with **Sync now**.

| Variable | Default |
| --- | --- |
| `INFRADESK_INTEGRATIONS_ACTIONS_ENABLED` | `true` |
| `INFRADESK_INTEGRATIONS_ACTIONS_POLL_INTERVAL_SECONDS` | `2` |
| `INFRADESK_INTEGRATIONS_ACTIONS_BATCH_SIZE` | `20` |
| `INFRADESK_INTEGRATIONS_ACTIONS_MAX_CONCURRENCY` | `3` |

**The target of an action cannot move under it.** An execution does not copy the base URL or the
credential; the integration is locked instead. A request locks the integration row
(`SELECT ... FOR UPDATE`), and so do edit and delete, so they are serialized. While any action of
the integration is `QUEUED` or `RUNNING`:

- changing the base URL or replacing the credential is refused with `409
  INTEGRATION_ACTION_ALREADY_RUNNING` (renaming stays allowed);
- deleting the integration is refused the same way, so an execution can never be cascaded away
  while its remote write is in flight.

A base URL change or a credential replacement also marks every observed object of the
integration inactive in the same transaction; bindings are kept. Actions need an active node, so
nothing can be sent to the new endpoint with an identifier observed at the old one. The next
**Sync now** reactivates what the new endpoint really reports. An observation that started before
such a change is not applied: its session fails with `INTEGRATION_CONFIGURATION_CHANGED`.

The browser keeps a request's `requestId` until the request is resolved. After an ambiguous
failure (network error or 5xx) the dialog can only check the same request again — it cannot be
cancelled and replaced by a new ID — and it is resolved as soon as the action history shows that
request ID.

Traffic reset, forced restart and bulk actions remain out of scope. Desired state and its
reconciliation are Stage 24D, below.

## Stage 24D: desired state of selected nodes

**Two modes, one of them the default.** Every integration is `OBSERVE`: InfraDesk observes
Remnawave and allows manual actions, and never changes a node on its own. An owner can opt an
integration into `MANAGED_SELECTED`; then only the nodes a person explicitly chooses get a
desired state (`ENABLED` or `DISABLED`), and every other node stays observation-only. There is no
mode that manages everything discovered. An upgrade never changes the mode: existing integrations
stay `OBSERVE`.

- `MANAGED_SELECTED` needs fresh observations, so it requires an enabled integration
  (`INTEGRATION_MANAGEMENT_REQUIRES_SYNC`), and a managed integration cannot be disabled.
- While managed, the base URL and the credential cannot change (`INTEGRATION_MANAGEMENT_ACTIVE`);
  the name can. A desired state never follows an integration to another panel: switch to
  `OBSERVE`, change the endpoint, synchronize, and opt in again.
- Switching back to `OBSERVE` removes every desired state of the integration in one transaction
  and sends nothing to Remnawave: nodes stay as they are.
- The database holds these rules too: a desired state references its integration together with
  the mode `MANAGED_SELECTED`, and a managed integration must be enabled.

**Intent is separate from observation and from execution.** `integration_desired_state` holds
the intent with a `version` that changes only when the intent does. The observed state stays in
the inventory and is written only by synchronization; a successful action never edits it. The
status of a managed node is derived, never stored:

| Status | Meaning |
| --- | --- |
| `UNAVAILABLE` | Remnawave no longer reports the node |
| `APPLYING` | the latest remediation is queued or running |
| `WAITING_REFRESH` | it succeeded or its result is unknown, and no observation began after it finished |
| `REMEDIATION_FAILED` | it failed, and no observation began after it finished |
| `COMPLIANT` | observed state equals desired state |
| `DRIFTED` | observed state differs |

An object's `lastSeenAt` is the moment its observation *began*, so an action that finished while
the provider was being read is not considered observed by that read.

**The reconciler cannot talk to Remnawave.** `IntegrationDesiredStateWorker` is given a
repository and a clock: no provider, HTTP client or credential. Where observed and desired state
differ it creates a `QUEUED` `integration_action_execution` with `source = DESIRED_STATE` and the
intent's id and version; `NODE_ENABLE` or `NODE_DISABLE`, never a restart. The Stage 24C action
worker performs the write, its completion nudges a synchronization, and the snapshot that
synchronization applies makes the integration's desired states due again:

`reconcile → action → action worker → sync nudge → sync → inventory → reconcile`

**At most one action per observation.** Creating an action records the observation it was
decided on (`last_attempt_observation_at`). A further action needs an observation that is newer
than that one and that began after the previous action finished. So an `UNKNOWN` result is never
retried blindly, a `FAILED` one never loops, and however often the reconciler runs between two
observations it creates one action. A person changing the intent resets this: the new version is
decided against the current observation.

**Bounded and fenced.** A wave claims up to a batch of due intents with `FOR UPDATE SKIP LOCKED`
and a lease, reading the intent, the observed node and its actions in the same statement; one
statement creates the actions for the whole batch; one releases the claim. The number of SQL
round trips follows the number of batches, not of nodes. The create statement only acts for an
intent that still has this claim token, this version and this observation, in an integration
that is still enabled and managed, with no active action on the node; anything else is fenced
out. It takes the same integration row lock as intent changes, action requests, edits and
snapshots.

**Manual actions.** For a managed node a one-shot action that works against the intent is
refused (`409 INTEGRATION_ACTION_CONFLICTS_WITH_DESIRED_STATE`): Disable on a node wanted
enabled, Enable on a node wanted disabled. Restart and actions in the direction of the intent
follow the 24C rules. Unmanaged nodes are unchanged. While an action on a node is queued or
running, its desired state cannot be changed or removed and the integration cannot return to
`OBSERVE` (`409 INTEGRATION_ACTION_ALREADY_RUNNING`); there is no cancellation.

**API.** Reading needs `MANAGE_INTEGRATIONS`; every mutation needs `MANAGE_INTEGRATIONS` and
`EXECUTE_OPERATIONS`. No handler creates an action or contacts Remnawave.

- `PUT .../integrations/{integrationId}/management-mode` with `{"mode": "OBSERVE" | "MANAGED_SELECTED"}`
- `PUT .../integrations/{integrationId}/inventory/objects/{objectId}/desired-state` with
  `{"state": "ENABLED" | "DISABLED"}`; setting the same state again changes nothing
- `DELETE .../integrations/{integrationId}/inventory/objects/{objectId}/desired-state`

Integration responses carry `managementMode`, the overview carries counters of managed nodes,
node listings carry each node's `desiredState` (or `null`), and action history carries `source`,
`desiredStateId` and `desiredStateVersion`. User changes are audited as
`INTEGRATION_MANAGEMENT_MODE_CHANGED`, `INTEGRATION_DESIRED_STATE_SET` and
`INTEGRATION_DESIRED_STATE_REMOVED`; automatic actions are not audited as user actions — the
action history is their journal, and it names who set the intent.

| Variable | Default |
| --- | --- |
| `INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED` | `true` |
| `INFRADESK_INTEGRATIONS_DESIRED_STATE_POLL_INTERVAL_SECONDS` | `5` |
| `INFRADESK_INTEGRATIONS_DESIRED_STATE_BATCH_SIZE` | `50` |
| `INFRADESK_INTEGRATIONS_DESIRED_STATE_MAX_CONCURRENCY` | `4` |
| `INFRADESK_INTEGRATIONS_DESIRED_STATE_CLAIM_LEASE_SECONDS` | `30` |

Desired-state reconciliation requires both integration synchronization
(`INFRADESK_INTEGRATIONS_SYNC_ENABLED=true`) and integration actions
(`INFRADESK_INTEGRATIONS_ACTIONS_ENABLED=true`). Invalid combinations fail startup.

With the subsystem disabled no integration can enter `MANAGED_SELECTED`
(`INTEGRATION_DESIRED_STATE_DISABLED`); observing integrations are unaffected.

Out of scope: managing every node automatically, desired state for hosts or config profiles,
creating or deleting nodes, automatic restart, traffic reset, config profile editing or rollout,
and remediation triggered by monitoring or incidents.

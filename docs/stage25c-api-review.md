# Stage 25C — Remnawave Node API compatibility

Review date: 2026-10-02. This document records the API preparation for Stage 25C.
The durable onboarding workflow, migration, SSH installer and wizard are still pending.
The API foundation is implemented and tested against source-derived contracts; this does
not certify a production deployment or a real VPS installation.

## Evidence and API generations

Reviewed official backend source at 79 tagged revisions: 1.6.18 and the published
2.0.0–3.4.4 releases. Compared Node creation, Node response schemas, keygen response
schemas, keygen service and metadata commands. Missing command files at historical
releases were recorded as absent, not treated as network failures or invented endpoints.

The compatibility catalog contains the exact official commit and SHA-256 of the reviewed
source files for each enabled release:
`src/main/resources/integration/remnawave/node-api-releases.json`.
This is release evidence shared by generation adapters, not a separate implementation
or conditional branch for each patch. A source file hash change alone does not establish
a breaking wire change: unused optional fields and TypeScript/Zod changes were assessed
against the subset of fields InfraDesk sends and reads.

| Source generation | Relevant differences | Stage 25C API support |
| --- | --- | --- |
| 1.6.18 | `excludedInbounds`; no required config profile in create | Investigated; no provisioning adapter enabled |
| 2.0–2.2 | Required `configProfile`; older telemetry; name/port validation evolves | Investigated; no provisioning adapter enabled |
| 2.3–2.7 | Create supports tags; older telemetry; metadata available starting 2.5; keygen returns `pubKey` | Investigated; older inventory adapter would be needed for complete onboarding |
| 2.8.0–2.8.1 | Modern `system`/`versions` telemetry, numeric uptime; keygen returns `pubKey` | `PROFILE_PUBKEY` |
| 3.0.0–3.4.4, published releases only | Keygen returns `secretKey`; additional optional Node fields | `PROFILE_SECRET_KEY` |

The last two adapters share create, find/get, status, request validation and reconciliation
logic. They differ in decoding the keygen response. Both return a backend-only installation
value containing the Node `SECRET_KEY`. Backend and Node image versions are separate;
an installer must pin and verify a compatible image policy, never infer a Node image tag
from the Panel patch version.

Official source references:

- [1.6.18 create](https://github.com/remnawave/backend/blob/4042d70b3132127208d35c9b7cd8116eefb19e80/libs/contract/commands/nodes/create.command.ts)
- [2.0.0 create](https://github.com/remnawave/backend/blob/b0475054009954229fe2af27a22c4e1ec37c4496/libs/contract/commands/nodes/create.command.ts)
- [2.8.1 create](https://github.com/remnawave/backend/blob/ba51868149362d0b9ac0e23133d0532176ccb5a2/libs/contract/commands/nodes/create.command.ts)
- [2.8.1 keygen](https://github.com/remnawave/backend/blob/ba51868149362d0b9ac0e23133d0532176ccb5a2/libs/contract/commands/keygen/get-pubkey.command.ts)
- [3.4.4 create](https://github.com/remnawave/backend/blob/b22970cc88481a7e278b5767721672a18f8b2ada/libs/contract/commands/nodes/create.command.ts)
- [3.4.4 Node schema](https://github.com/remnawave/backend/blob/b22970cc88481a7e278b5767721672a18f8b2ada/libs/contract/models/nodes.schema.ts)
- [3.4.4 keygen service](https://github.com/remnawave/backend/blob/b22970cc88481a7e278b5767721672a18f8b2ada/src/modules/keygen/keygen.service.ts)
- [3.4.4 create implementation](https://github.com/remnawave/backend/blob/b22970cc88481a7e278b5767721672a18f8b2ada/src/modules/nodes/nodes.service.ts)
- [3.4.4 metadata](https://github.com/remnawave/backend/blob/b22970cc88481a7e278b5767721672a18f8b2ada/libs/contract/commands/system/get-metadata.command.ts)
- [3.4.4 error codes](https://github.com/remnawave/backend/blob/b22970cc88481a7e278b5767721672a18f8b2ada/libs/contract/constants/errors/errors.ts)
- [Node 2.8.0 Compose](https://github.com/remnawave/node/blob/596f015a5c8f876dc9a9d61b6cb78d35bd8e379b/docker-compose-prod.yml)
- [Node 3.4.1 Compose](https://github.com/remnawave/node/blob/44912631321664dbd5822e9bf8d96766ccff7c93/docker-compose-prod.yml)
- [Official installation documentation](https://docs.rw/install/remnawave-node/)

## Confirmed source contracts

| Operation | Wire contract in the enabled generations |
| --- | --- |
| Metadata | `GET /api/system/metadata`; `response.version`, `response.git.backend.commitSha` |
| Find/list | `GET /api/nodes`; `response` array |
| Get/status | `GET /api/nodes/{uuid}`; `response` object; `isConnected`, `isConnecting`, `isDisabled` |
| Create | `POST /api/nodes`; `name`, `address`, `port`, `configProfile.activeConfigProfileUuid`, `configProfile.activeInbounds`, optional `tags`; response Node object |
| Installation credential | `GET /api/keygen`; `response.pubKey` in 2.8, `response.secretKey` in 3.x |
| Delete | `DELETE /api/nodes/{uuid}` exists upstream; not used for automatic cleanup |
| Actions | Existing `POST /api/nodes/{uuid}/actions/{enable,disable,restart}` |

Create requires a config profile and an explicit list of inbound UUIDs. This differs from
the initial optional-profile sketch in the Stage 25C request. Existing InfraDesk config
profile inventory supplies profile and inbound identities. The future wizard must select
these actual objects, and preview must validate that the chosen inbounds belong to the
chosen profile. No raw Xray config is accepted from the browser.

Node name is 3–30 characters in enabled releases; InfraDesk also rejects control characters
and untrimmed input. Port is 1–65535. Address is a hostname/IP rather than a URL. The upstream
database makes Node name and address unique. The minimal shared create payload does not use
new optional fields such as proxy configuration, plugins or Node integrations.

The keygen service generates a certificate payload including the Node private key. The old
wire name `pubKey` must not be interpreted as permission to expose its value publicly.
Node installation consumes `SECRET_KEY` and `NODE_PORT`; official Compose uses host networking.
The official examples use a floating image, which must be replaced by a pinned installer
policy in InfraDesk. No official Compose-download endpoint was confirmed in the reviewed
Node/keygen contracts; compose must be generated from controlled backend values.

## Version-neutral interface and capabilities

`application.port.NodeProvisioningTransport` exposes inspect, create, find/get, installation
data and reconciliation. `ProvisionedNode` is a sanitized projection; provider DTOs and
registration credentials do not enter onboarding snapshots. `NodeInstallationData` has no
JSON encoder and redacts its string representation. Durable encrypted secret storage remains
part of the pending onboarding implementation.

| Capability | PROFILE_PUBKEY / PROFILE_SECRET_KEY, confirmed at runtime | Unknown or unreviewed release |
| --- | --- | --- |
| NODE_INVENTORY | Yes after successful listing validation | Only if listing validates |
| NODE_STATUS | Yes after successful listing validation | Only the validated read contract |
| NODE_CREATE | Yes | No |
| NODE_INSTALLATION_DATA | Yes | No; keygen is not requested |
| NODE_CONFIG_PROFILE | Yes; required by create | No provisioning permission |
| NODE_CREATE_IDEMPOTENCY | No | No |
| NODE_CREATE_RECONCILIATION | Yes, with exact intent and correlation evidence | No automatic mutation/resume |

Integration connection testing now returns `nodeApi` runtime evidence separately from the
connectivity result. A successful stats read is not permission to provision. Metadata must
identify a catalogued release and match its official commit (a 7–40 digit SHA prefix is
accepted), and a bounded Node listing must validate. Unknown future patches, minors, majors,
prereleases, custom builds and missing/malformed metadata do not receive write capabilities.
Read-only synchronization retains its existing strict decoder and remains usable whenever
that contract validates.

Existing Remnawave actions and configuration updates through the provider are also gated.
Consequently a deployment that cannot supply compatible metadata can still synchronize but
will no longer perform provider mutations. This is intentional fail-closed behavior.
Low-level HTTP methods are transport primitives; application services must use the provider
interfaces, including the guarded Node provisioning transport.

Before create or keygen, the adapter repeats inspection and compares the actual version,
generation and source commit with reviewed evidence. Changes require a new preview. There
is no automatic mutation fallback to a nearby version and no caller-selected adapter.

## Ambiguous create and partial failure

No idempotency-key contract was found in the enabled create endpoints. A single approved
create sends at most one POST. A timeout, malformed success response, mismatched returned
intent, 5xx or uncertain transport result is UNKNOWN.

Important upstream behavior: create writes the Node before validating profile inbounds.
`A124` can therefore return HTTP 404 after the Node exists. HTTP 404 is not treated as proof
that nothing happened. Unclassified 400/404/422 responses also remain UNKNOWN; local typed
validation can reject invalid input before sending anything. Confirmed duplicate codes
`A033/A034` are conflicts. Raw error text never becomes the returned failure message.

Create uses a bounded correlation tag `ID:<32 hex digits>` alongside the upstream unique name
and address. Reconciliation requires exactly one candidate matching the correlation marker,
name, address, port, profile and inbound set. Name alone is insufficient. A missing result
does not prove non-creation and never triggers another POST. Duplicate/mismatching results
remain unproven. These markers are not upstream idempotency keys, and tags can be edited;
the coordinator must preserve UNKNOWN whenever proof is unavailable.

The future durable coordinator must journal the in-flight create boundary before calling
this adapter. This adapter's no-retry property alone does not implement crash-safe workflow
or claim/lease/fencing. There is no automatic deletion on install failure.

## Actual deployment verification

First target: `https://ax7k2.velesoracle.com`.
No installed version or production adapter has been confirmed. Unauthenticated API requests
from this environment failed during TLS before an HTTP response, using Windows .NET,
Schannel curl and Python/OpenSSL clients. Certificate checking was not disabled. No credentials
were supplied to this session. Remnawave Web UI was not scraped.

`RemnawaveDeploymentContractSpec` is an opt-in authenticated read test using production
adapters and outbound-destination policy. It checks metadata, existing full inventory import,
Node listing and get/status if a Node exists. It performs no create, keygen, action, SSH or
VPS mutation. Its disabled/skipped result must never be reported as a production verification.

Set these environment variables in a controlled local test environment without committing them:

- `INFRADESK_RUN_REMNAWAVE_CONTRACT_TESTS=true`
- `INFRADESK_REMNAWAVE_CONTRACT_BASE_URL=https://ax7k2.velesoracle.com`
- `INFRADESK_REMNAWAVE_CONTRACT_API_TOKEN` with the existing API token
- `INFRADESK_REMNAWAVE_CONTRACT_CADDY_KEY` only if the deployment requires `X-Api-Key`
- `INFRADESK_REMNAWAVE_CONTRACT_EXPECTED_VERSION` optionally pins the expected response

Run `sbt "testOnly *RemnawaveDeploymentContractSpec"` with JDK 21 or newer. Record the sanitized
version/generation/commit result here when the test has actually passed. A local official
OpenAPI export and deployment build evidence can also be reviewed while network access is
being resolved; neither source-only tests nor an exported schema prove real Node installation.

## Validation and next boundary

Source-derived tests cover all 16 enabled releases, exact authenticated create payloads,
generation-specific credential decoding, invalid intent, unknown/future/custom versions,
version changes after preview, duplicate and mismatching reconciliation, timeout/restart
without a second POST, upstream A124 partial creation, and safe error/redaction behavior.
The full Stage 25C acceptance suite will be added with the durable workflow and installer.

Validation on Windows with JDK 21: the full backend suite passed with an isolated temporary
PostgreSQL container (984 passed, 19 skipped, 0 failed). The container was removed afterwards;
the working database was not used. Skips comprise the opt-in live deployment contract test
and 18 existing Linux/SSH tests. The targeted frontend localization and integration-page
suite passed 85 tests, and the frontend production build passed.

Stage 25C is not complete. Remaining work includes deployment contract confirmation, image
compatibility policy, durable run/phases/migration, shared resource conflict locking, child
25B apply, safe managed files/firewall/SSH canary, Panel wait, reuse of sync/binding/desired
state, final verification, wizard/progress/history, and their tests. The disposable VPS canary
comes after code and test completion. Stage 25D/25E fleet reconciliation/rollout and Stage 25F
Node upgrades remain outside this stage.

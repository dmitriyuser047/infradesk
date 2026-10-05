# Stage 25C — One-click Remnawave Node Onboarding

Implemented on top of accepted foundation commits `fb62f36` and `7d74477`.
The final commit SHA is provided in the delivery message; this document is part of that commit.
Stage 25D/25E/25F are not started. Live deployment acceptance remains a separate review boundary.

## Durable execution and admission

| Requested report items | Implementation |
| --- | --- |
| 1. Commit | The delivery commit contains the complete implementation, tests and this report. No skip-CI marker. |
| 2. Migration | `V48__remnawave_node_onboarding.sql`; V47 is unchanged. Existing-data and empty-database migration tests reach V48. |
| 3. Schema | Separate `remnawave_node_onboarding`, ordered phase journal, encrypted installation envelope; optional baseline run and sync session references with tenant/resource constraints. |
| 4. States/phases | PLANNED, QUEUED, RUNNING, SUCCEEDED, FAILED, UNKNOWN. Thirteen phases from VALIDATE through FINAL_VERIFY. Terminal records are immutable and never automatically resumed. |
| 5. Worker/lease/fencing | SKIP LOCKED claims, heartbeat, token/deadline checks under row locks; external I/O outside transactions. Phase boundaries commit before mutations. Expired claims retain the in-flight phase. |
| 6. Resource conflicts | Shared resource advisory lock, one active onboarding per server, cross-engine admission triggers for provisioning, configuration deployment and resource operations. Exact live Stage25B child exception; concurrent manual binding edits guarded. |
| 7. Immutable snapshot | Approved connection version, assignment/revision/hash, baseline plan, integration version/credential reference, API release/generation/commit/capabilities, image, intent, inbounds and panel CIDRs. Closed codec; no installation secrets. Plans expire after 24 hours and do not appear in history. |
| 8. API | Bounded options, preview, start, detail and history endpoints under `/remnawave-node-onboarding`. Closed bodies, canonical UUIDs, list/body limits and tenant scope. Organization/requestId is unique and retries return the original run. |
| 9. Stage25B child | Assigned profile is required. Compliant baseline skips apply. Drift uses the existing approved profile plan, existing start service and worker; the parent waits and then observes pinned compliance again. Child FAILED/UNKNOWN propagates. |

The phase order is VALIDATE → PREPARE_SERVER → CREATE_NODE → GET_INSTALLATION_DATA →
CONFIGURE_NODE_FIREWALL → INSTALL_NODE → START_NODE → VERIFY_LOCAL_NODE → WAIT_FOR_PANEL →
SYNC_INVENTORY → BIND_RESOURCE → SET_DESIRED_STATE → FINAL_VERIFY.

## Remote contract and host safety

| Requested report items | Implementation |
| --- | --- |
| 10. Create flow | Existing version-neutral Node provisioning transport; fresh Docker/Compose/active-firewall prerequisites after baseline, live profile/inbound validation before create, exact intent and correlation tag. |
| 11. Ambiguity/crash | A recovered CREATE_NODE reconciles only. An uncertain response reconciles exact identity; unproven or mismatching results stay UNKNOWN. No second create POST. |
| 12. Credential lifecycle | Keygen data is held only in an encrypted per-run envelope until local verification, then deleted; terminal failure also removes the database envelope. Host `.env` remains part of the managed install. |
| 13. Encryption | Existing AES-GCM envelope with purpose-specific derived key and tenant/run/kind authenticated context. No plaintext API, snapshot, journal, audit or command arguments. SFTP upload uses private staging. |
| 14. Image policy | Backend selects `remnawave/node:2.8.0` for PROFILE_PUBKEY and `remnawave/node:3.4.1` for PROFILE_SECRET_KEY. Independent of Panel patch; unknown generations fail closed. Reviewed version tags, not floating latest. Registry digest pinning is a possible later hardening. |
| 15. Compose | Backend-generated host networking, NET_ADMIN, nofile 1048576, SECRET_KEY/NODE_PORT via private env file. Validate candidate, pull selected image, revalidate exact managed files/config before one up request. |
| 16. Filesystem | `/opt/infradesk/remnawave/<external UUID>`; canonical ownership metadata including credential hash, guarded root-owned parents/files, no symlinks/hardlinks/writable parents, exclusive staging, hashes and atomic replacements. `.env` is 0600. |
| 17. Firewall | Explicit nonempty IPv4/IPv6 CIDRs; no hostnames, ANY, /0 or duplicates. Namespace `infradesk:remnawave:<resource>:<node>:node`; Stage25B treats these as foreign and preserves them. Broad or conflicting foreign management-port rules block. |
| 18. SSH | Existing pinned host-key transport and noninteractive sudo; actual SSH source/port checks and fresh SSH canaries. No UFW reset, bulk firewall replacement or SSH policy mutation. |
| 19. Local verification | Exact managed files/hash/modes, pulled image and actual container image ID, running host-network container, listening management port, no restarts and at least ten seconds uptime. Bounded polling allows startup. |
| 20. Panel wait | Full pinned Node identity/profile/inbound evidence and connected/enabled status. Bounded timeout fails; unavailable API yields UNKNOWN. Running Docker alone cannot succeed. |

Host settings follow the reviewed official [Node 2.8 Compose](https://github.com/remnawave/node/blob/596f015a5c8f876dc9a9d61b6cb78d35bd8e379b/docker-compose-prod.yml)
and [Node 3.4.1 Compose](https://github.com/remnawave/node/blob/44912631321664dbd5822e9bf8d96766ccff7c93/docker-compose-prod.yml).
InfraDesk generates its own controlled configuration with selected version tags.
These source checks do not establish compatibility of the user's installed Panel or VPS.

## Completion, UI and observability

| Requested report items | Implementation |
| --- | --- |
| 21. Sync reuse | Existing manual integration sync application service and transaction/import path. Successful/failed session IDs are retained when returned. |
| 22. Matching | The created external UUID identifies the inventory Node; no address/name guessing. |
| 23. Binding | Existing binding service with strict create-only method; never overwrites another resource link. Active onboarding guards concurrent manual changes. |
| 24. Desired state | Existing desired-state service sets ENABLED, requiring enabled integration and ManagedSelected mode. Existing reconciler retains ownership of subsequent actions. |
| 25. Final verification | Fresh pinned baseline and runtime API release/generation/commit, exact local evidence, live Panel identity/connection/profile/inbounds, active enabled inventory, correct binding and existing ENABLED desired state. All required for success. |
| 26. Partial failure | External UUID, baseline run and sync session survive failure. Known failure is FAILED; uncertain mutation/observation is UNKNOWN. No automatic Node deletion, host cleanup or terminal retry. |
| 27. Wizard | Nodes section: server, Remnawave profile/inbounds, preparation CIDRs, preview, execute. Missing assignment links to the server. Preview shows image, destination, port, CIDRs and API evidence. |
| 28. Progress/history | Thirteen phase statuses, partial results, terminal explanation, recent approved runs, URL-preserved run ID and polling while active. |
| 29. Permissions | Preview/start require manageIntegrations + manageConfigurations + executeOperations. Options require manageIntegrations; safe history/detail require organization read. Mutation entry disabled without permission or confirmed provisioning API. |
| 30. Audit | Minimal REQUESTED and COMPLETED events; existing child/sync/binding/desired services keep their established events. Structured worker log uses IDs, phase and state only. |
| 31. RU/EN | Wizard controls, guidance, progress labels and terminal explanations in Russian and English; technical state/error codes remain visible for diagnosis. |

## Verification and stage boundary

| Requested report items | Result |
| --- | --- |
| 32. Backend counts | `sbt testFull`: 1040 total, 1021 passed, 19 opt-in checks skipped, zero failures/errors. Real isolated PostgreSQL 18 database, migrations through V48. |
| 33. Frontend counts | Full Vitest suite: 655 passed in 77 files. Nine onboarding cases cover flow, blockers, permissions, CIDRs/inbounds, uncertain-submit requestId reuse, RU and terminal URL return. |
| 34. Production build | `sbt "Universal / stage"` and frontend `npm run build` pass. Existing documentation-link and frontend chunk-size warnings remain. |
| 35. Stage24 regression | Included in full backend/frontend suites; old action/config mutations remain independent of provisioningReady. |
| 36. Stage25A regression | Included in full backend suite; existing provisioning engine remains the child executor. |
| 37. Stage25B regression | Included in full suite and disposable Ubuntu22.04 Linux safety harness. Atomic staging, 0600 credential install, guard/recovery/rollback/package simulation, Node proof/start safety all pass. |
| 38. Live Remnawave contract | Not performed for this implementation. Authenticated installed-version/commit and create/keygen/status contract acceptance still required after review. Prior source inspection is not a live acceptance result. |
| 39. Disposable VPS canary | Not performed. Linux harness uses a disposable local container and mocks Docker commands; it is not a VPS installation canary. |
| 40. Later stages | Offboarding/cleanup/reinstall, terminal UNKNOWN recovery UX, fleet rollout and lifecycle automation remain outside Stage25C. No Stage25D/25E/25F implementation started. |

## Crash-recovery addendum (durability fix, same stage)

Two crash windows were closed after the review of the implementation above. No migration was added;
`V48` is unchanged and the existing nullable `sync_session_id` column carries the correlation.

**PREPARE_SERVER.** `attachBaseline` and the child start are separate durable steps, so a crash
between them used to leave the approved child PLANNED while the parent only polled it to
`baselineTimeout`. The worker now calls `ensureBaselineStarted`, which reads the durable child and
starts it only while it is PLANNED, through the existing `ProvisioningRuns.start` with the request ID
`RemnawaveNodeOnboardingRun.baselineRequestId(onboardingId)`. Repeating that start is therefore the
same Stage25A request, not a second one, and no new plan or run is ever created. QUEUED and RUNNING
are waited on; SUCCEEDED is verified for compliance and continues; FAILED and UNKNOWN propagate. None
of those states is restarted. Before any start the child must match the snapshot exactly - plan ID,
tenant, resource, `SERVER_PROFILE_APPLY` kind, `onboarding_parent_id` and the assignment/revision pin
- otherwise the phase stops with `REMNAWAVE_ONBOARDING_BASELINE_CHANGED`.

**SYNC_INVENTORY.** The session is claimed and recorded in one transaction
(`IntegrationSyncTransactions.prepare` plus the fenced `attachSync`), so a crash can no longer leave a
RUNNING session the onboarding cannot name. The observation then runs outside that transaction through
`IntegrationSync.execute`. A recovered worker reads the stored session by ID: RUNNING waits, FAILED
fails the onboarding with the stored code, a session the storage no longer holds is UNKNOWN, and a
RUNNING session past its own `recover_after_at` is re-claimed so the existing stale recovery retires
it. When another synchronization of the same integration holds the single slot, its ID is recorded and
its outcome awaited - `INTEGRATION_SYNC_ALREADY_RUNNING` is never surfaced as a failure. A COMPLETED
session counts only once the stored inventory holds this onboarding's external node; otherwise one
fresh observation is started per pass. All waiting is bounded by `syncTimeout`
(`REMNAWAVE_ONBOARDING_SYNC_TIMEOUT`), and no path retries the claim in a loop. The external node is
never deleted, and `externalNodeId` and `syncSessionId` survive every failure.

CREATE_NODE, firewall, install, start, binding and desired-state recovery are unchanged, as is the
wizard: new codes reach the existing generic terminal explanation.

Reproduce backend checks with JDK21 and the existing PostgreSQL integration-test environment flags.
Reproduce frontend checks with `npm test -- --run` and `npm run build` in `frontend`.
Linux extraction/verification is the existing CI `server-profile-safety` step, extended for Node files and start/recovery guards.
No production Panel or VPS was mutated during these checks.

## Live UFW 0.36.2 blocker and reviewed firewall recovery

Stage25C maintained a separate `ufw show added` parser that accepted the header only without a
trailing colon. Ubuntu/Debian UFW 0.36.2 prints that colon, so supported Server Profile rules were
rejected before any node rule could be added. The empty `(None)` fix in Stage25B did not reach this
second parser. Stage25C now uses the same canonical parser and empty-output handling as Stage25B.

Ownership remains scoped: Server Profile owns `infradesk:<resource>:<rule>`; onboarding owns only
`infradesk:remnawave:<resource>:<external-node>:<rule>`. Supported rules from the other engine and
supported foreign rules are preserved as foreign by the current engine. Unknown syntax remains
unsupported. Bare IPv4 and its /32 representation use the existing canonical source conversion.

The onboarding-specific call on the existing typed remote engine adds missing reviewed sources,
accepts exact existing node rules without duplicates, and rejects unexpected own action, port,
protocol, rule suffix or source with `PROVISIONING_FIREWALL_OWNERSHIP_CONFLICT` before mutation.
It does not reset UFW or delete rules. Each addition rechecks current rules and SSH safety; final
re-observation proves the complete exact rule set and active UFW. Loss of that proof after mutation
remains UNKNOWN. Fleet rollout/rollback retain the engine's original approved source replacement
path; a regression test exercises that path with Server Profile rules present.

A terminal FAILED run is never resumed. A new preview may recover only a single previously created
node whose latest run FAILED at CONFIGURE_NODE_FIREWALL, before install, with identical onboarding
input, integration, connection and selected image. A bounded tenant/resource query returns the
latest record per external UUID (at most two, sufficient to reject ambiguity), independently of
paginated history. The new plan carries the same external UUID and original correlation ID and
shows them for operator review. Admission checks that identity again under the resource lock.
CREATE_NODE reconciles the existing node using the original correlation tag and full pinned Node
identity; it never invokes create for this recovery. Missing/mismatched evidence yields UNKNOWN,
without firewall/install mutations. Later-phase failures, UNKNOWN, changed input or multiple nodes
produce `REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW`. Original terminal history and its
phase journal remain immutable. Installation data is obtained afresh for the new run; the previous
run's encrypted envelope stays deleted.

The localized UI shows the stable failure code and safe explanation directly beside the failed
phase, including FIREWALL_RULE_UNSUPPORTED. It does not render backend exception prose or append
an English safeMessage to a Russian dialog. An approved recovery preview explicitly states that
an existing node will be reused rather than created.

Regression coverage includes the three live Server Profile UFW rules and reviewed TCP/2222 from
2.27.26.18/32, exact repeat/no duplicate, canonical /32, unsupported output, ownership conflicts,
post-mutation proof, fleet source replacement, failed-create/firewall recovery, mismatched identity,
real PostgreSQL admission/tenant scoping/terminal immutability, and localized phase errors/recovery
preview. No migration or external HTTP API change is needed. No production Panel/VPS was mutated.

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
| 40. Later stages | The subsequent recovery lifecycle addendum below supersedes the original terminal recovery limitation; fleet rollout/image lifecycle have their own Stage25D/25E/25F documents. |

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

## Partial local installation ownership

Installation publishes an empty root:root 0700 ownership-marker directory at
`/opt/infradesk/remnawave/.owner-<node UUID>-<installation owner UUID>-<resource UUID>-<image reference SHA256>`.
Its backend-selected name contains only immutable non-secret identity; a single `mkdir` atomically
establishes the complete marker, and `sync -f` makes it durable before the final node directory or
any credential/file mutation. There is no intermediate partially written ownership document.
Initial installation then prepares the three files in a separate root:root 0755 directory
`.staging-<node>-<installation owner>-<resource>-<image SHA256>`. The final directory stays absent
through file writes, hash/metadata/Compose validation and file synchronization. A same-filesystem
no-clobber directory rename publishes the complete installation; the source inode, destination inode
and disappearance of staging prove publication. Docker image pull happens after publication.
File replacement never creates or recreates a missing final node directory itself.

Fresh local reconciliation uses the shared domain LocalInstallationState: ABSENT, OWNED_COMPLETE,
OWNED_PARTIAL, OWNED_DAMAGED, FOREIGN, PORT_CONFLICT and UNKNOWN. An exact durable claim with
an incomplete staging directory is OWNED_PARTIAL. A proven legacy final directory missing files is
also partial; a complete owned footprint with damaged expected contents is OWNED_DAMAGED.
Only exact root-owned paths are repairable. Repairs retain file hash CAS and fresh ownership checks,
validate Compose, skip matching bytes, and leave container startup to the existing durable START_NODE
phase. Private staging leftovers must match the installation owner's UUID, root:root 0700 directory
mode, and the closed candidate/previous file set with root:root 0600/0644 single-link regular files.
Cleanup checks ownership and hashes again; unexpected paths are preserved and block recovery.
Private SFTP upload leftovers are likewise bound to the original owner's run UUID and authenticated
SSH UID: only 0700 upload directories with a single 0600 single-link regular `input` file may be
cleaned. Their contents are never returned, and complete managed files with leftover staging still
require repair rather than bypassing INSTALL_NODE.

Legacy compose or managed metadata can still prove the original immutable owner and receive the
new marker before repair or retirement. A legacy `.env`-only directory has no ownership evidence:
it remains FOREIGN, including the orphan produced by versions before this fix, and needs manual
review. Recovery never infers ownership from credential contents, an arbitrary directory name,
stored inventory health, or the mere existence of `.env`.

Retirement removes the owner marker last. A crash while removing managed files, or after removing
the final directory, therefore leaves a recoverable OWNED_PARTIAL marker. Symlinks, wrong owners or
modes, hardlinks, conflicting identities, unexpected files and foreign containers/listeners remain
fail-closed. Probes emit only classifications and facts; credentials travel only through private SFTP
file writes and never appear in command arguments, API facts or failure messages. Schema V52 and
terminal history remain unchanged; the lifecycle changes below use additive migrations V53–V55.

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

Terminal runs are never resumed or edited. Recovery creates a separately approved intent and
follows the external node/correlation chain, including a latest UNKNOWN CREATE_NODE with a known
external UUID. A fresh exact provider lookup and candidate check classify PRESENT_EXACT,
PRESENT_UNHEALTHY, CONFIRMED_NOT_FOUND, PRESENT_CONFLICT or UNKNOWN; stored inventory activity
is not evidence of remote presence. Immutable identity includes integration, name, address,
node port, config profile, active inbound set and correlation tag.

Exact and unhealthy nodes reuse the external UUID and original installation owner. CREATE_NODE
is a read-only identity check for these plans. Existing phases obtain fresh installation data,
reconcile firewall access, conditionally repair safe owned local files, start the container,
enable a disabled exact node, verify locally and in the Panel, synchronize inventory, bind the
resource, set desired state and perform final verification. Foreign, unsafe or ambiguous local
ownership fails closed.

Only the typed provider node-not-found response at the exact UUID proves absence. Timeout, 5xx,
wrong UUID, malformed response and unrelated 404 remain UNKNOWN. ?Check again? performs only
read-only reconciliation. Conflicting remote identities retain the existing review blocker.

Confirmed absence offers recreation, which requires explicit approval at Start. The new
immutable snapshot records the source run, previous external UUID and correlation, installation
owner, reviewed recovery state/action and a new durable correlation. The optional delete/recreate
preview requires the same approval and adds DELETE_NODE and CONFIRM_NODE_DELETED. DELETE uses
the typed provider transport once; an uncertain result ends UNKNOWN without CREATE. Fresh
confirmed absence is required before guarded retirement of the exact old local installation
and again before CREATE. Local retirement has its own RETIRE_LOCAL_NODE durable phase, avoiding
an ambiguous POST outcome when only local cleanup ran. New inventory binding replaces only the
approved inactive predecessor binding. No remote deletion is performed through shell commands.

V52 adds the conditional phase sequences and exact predecessor-binding exception without
rewriting old snapshots or terminal histories. Resource-lock admission permits only one winner
among concurrent recovery/recreate plans, and request retries return the same durable run.
Fleet observation continues using the original installation owner after successful repair.

The localized UI shows the stable failure code and safe explanation directly beside the failed
phase, including FIREWALL_RULE_UNSUPPORTED. It does not render backend exception prose or append
an English safeMessage to a Russian dialog. An approved recovery preview explicitly states that
an existing node will be reused rather than created.

Regression coverage includes the three live Server Profile UFW rules and reviewed TCP/2222 from
2.27.26.18/32, exact repeat/no duplicate, canonical /32, unsupported output, ownership conflicts,
post-mutation proof, fleet source replacement, failed-create/firewall recovery, mismatched identity,
real PostgreSQL admission/tenant scoping/terminal immutability, and localized phase errors/recovery
preview. Lifecycle regression coverage also includes fresh classification, typed absence,
unknown-create chains, damaged installation repair, uncertain deletion, extra durable phases,
concurrent recovery approval, tenant isolation and immutable terminal phase journals.
Reconciliation is POST /runs/{runId}/reconcile with RECOVER or DELETE_RECREATE; this returns a
preview. Start accepts confirmRecreate=true for reviewed recreation actions. No production
Panel/VPS was mutated during implementation checks.

## Lifecycle hardening (V53–V56)

New snapshots pin lifecycleVersion=2. Their recreation sequence retires the old firewall in the
explicit RETIRE_NODE_FIREWALL phase before RETIRE_LOCAL_NODE and CREATE_NODE; delete/recreate
first performs DELETE_NODE and fresh CONFIRM_NODE_DELETED. Version 1 snapshots retain their
original valid phase sequences. Retirement accepts only exact old namespace rules and the old
reviewed CIDRs, requires a surviving allow rule covering the current SSH session and fresh SSH
canaries, and preserves foreign rules. Ambiguous mutation ends UNKNOWN without progressing to POST.

At BIND_RESOURCE, a reviewed recreation transfers an existing active fleet membership from the
exact inactive predecessor to the exact new bound inventory node in the same organization,
integration, resource and fleet. The atomic transaction increments membership version, removes
stale assessment/image evidence, schedules observation immediately and appends immutable replacement
history. Repeating that transaction is a no-op. Integration row, fleet and resource locks serialize
onboarding admission with node actions, fleet rollout and image upgrade; reciprocal DB triggers
reject competing workflows before destructive work, including paused fleet workflows.

Start resolves the tenant-scoped request identity before asking for destructive confirmation.
The same request and plan return the existing run even without a repeated confirmation; a reused
request for another plan is rejected. Only a new PLANNED → QUEUED transition needs confirmation.
The browser stores only plan/request IDs and reloads authoritative run state.

Integration deletion is a tombstone: deleted_at is set, the integration is disabled, its credential
reference is cleared and the encrypted credential is removed in the same transaction. Active
workflows and sync/desired-state claims block deletion. New work and active queries exclude
tombstones; immutable execution, inventory and fleet histories retain their foreign keys.

Options assemble candidates, batch eligibility and stored server status without per-server SQL.
The regression test counts those reads for 1, 100 and 500 servers. Shared automation-state logic
keeps the single-server and batch projection equivalent. History/detail require organization read;
preview/start/reconcile retain all three mutation permissions in both backend and UI.

Database transactions only persist plans, claims, fenced progress and binding/membership outcomes.
Provider and SSH I/O run between transactions; cleanup and publication uncertainty remain observable
and recoverable. Stage acceptance still requires exact-SHA CI and disposable VPS failure/recovery
acceptance; the Linux harness alone does not satisfy live acceptance.

V56 keeps observer lease/progress updates from reversing integration → membership lock order.
Desired-state workers share the integration lock while claiming disjoint batches and take the
exclusive integration lock before creating actions. Lease release remains possible after a
tombstone; new claims and mutation admission still require an active integration. The regression
retains forty nodes and six concurrent workers and requires every intent to be claimed once.

Start returns a typed `OnboardingStartResult` rather than inferring a new transition from timestamp
equality. PostgreSQL returns canonical stored timestamps, so HTTP retries return identical durable
state even when application timestamps have nanoseconds. New transitions still validate source,
baseline and integration pins and record audit in the same transaction.

Recovery, installation proof and Start share the controlled-image ownership proof used by Stage25F.
A reviewed catalog image change normalizes only the single image line against the original template;
the immutable installation marker and metadata keep their original identity. Duplicate image lines,
unreviewed references, other compose edits and mismatched container identity remain fail-closed.

Live acceptance is reserved for the operator. No VPS or Panel mutation is authorized for the
implementation agent. Manual scenarios and the acceptance boundary are recorded in
`remnawave-hardening-review.md`; automated success does not mark Stage25 accepted.

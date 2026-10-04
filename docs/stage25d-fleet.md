# Stage 25D — Remnawave Fleet Desired State & Drift Detection

Implemented on top of accepted Stage 25A/25B/25C, whose architecture is unchanged. The last Stage 25C
commit is `ed2150a`. Stage 25E and 25F are not started.

**Stage 25D performs no remote mutation.** Nothing in it applies a server profile, deploys a
configuration, executes a node action, touches a firewall, restarts a container or changes a desired
node state. It declares, observes and reports.

## Data model

| Requested report items | Implementation |
| --- | --- |
| 2. Migration | `V49__remnawave_fleet_desired_state.sql`, additive. V48 and every earlier migration are unchanged. The empty-database and existing-data migration tests reach V49. |
| 3. Fleet tables | `remnawave_fleet`, `remnawave_fleet_revision`, `remnawave_fleet_membership`, `remnawave_fleet_node_assessment`. Due and claim state lives on the membership, not on the derived assessment, so discarding every assessment loses no schedule. |
| 4. Revision model | `RemnawaveFleetRevision` holds a closed `FleetDesiredContent` as canonical jsonb plus its hash. A DB trigger rejects every UPDATE and DELETE; the desired pointer lives in `remnawave_fleet.desired_revision_id` with a foreign key that admits only a revision of that same fleet. |
| 5. Membership constraints | A partial unique index gives one active fleet per inventory node per integration. A trigger requires a real `integration_resource_binding` for exactly the named node and resource, and requires the object to be a NODE. Tenant-scoped foreign keys cover fleet, node and resource. A removed membership stays readable as history. |
| 6. Promotion | `POST .../revisions/:id/promote` moves `desired_revision_id` under an optimistic `expectedVersion`, bumps the version, and marks the fleet's members due. Promoting the revision that is already desired changes nothing and records nothing. No remote call. |
| 7. Revision content | Server profile id/revision id/number/hash; inventory config profile object id, its external UUID, the managed `ConfigurationProfile` id, config revision id/number/`contentSha256`; `activeInboundIds`; `nodePort`; canonical `panelCidrs`; `desiredNodeState`. All identities and hashes are resolved by the backend from a person's choice, so a client cannot pin a hash the database does not hold. |
| 8. Why no image | The node image is deliberately absent: its lifecycle is Stage 25F. The image Stage 25C installed is used only as observation provenance, and a mismatch is reported as `LOCAL_INSTALLATION_DRIFT` without ever proposing an upgrade. |

The pinning is literal. A newer `ServerProfileRevision` or a newer configuration revision never moves
a fleet: a new `RemnawaveFleetRevision` must be created and then explicitly promoted.

## Drift model

| Requested report items | Implementation |
| --- | --- |
| 9. Compliance | `COMPLIANT`, `DRIFTED`, `UNKNOWN`, `BLOCKED`. Each dimension answers separately and the member takes the worst answer, ordered blocked > drifted > unknown > compliant: a proven difference is more actionable than missing evidence, while a structural problem makes the comparison meaningless. There is no optimistic default. |
| 10. Health | `HEALTHY`, `DEGRADED`, `UNKNOWN`, judged against the operational state the fleet intends. A fleet that wants a node disabled does not call that node unhealthy for being offline, and a local observation that failed is unknown rather than bad. |
| 11. Drift reasons | `FleetDriftReason`: server profile assignment/content/unobserved, config profile mismatch, config revision drift/unobserved, active inbound drift/unobserved, desired and actual node state drift, node port drift, panel CIDR drift, local installation drift, local observation unavailable, local management unavailable, binding missing, inventory missing/stale, resource unavailable, server/config profile unavailable, API contract unconfirmed. Health reasons are a separate `FleetHealthReason` enum and are never mixed in. |
| 12. Evidence freshness | Every assessment stores `inventoryObservedAt`, `serverObservedAt`, `localObservedAt` and `computedAt`. Evidence older than the configurable `staleAfter` window never produces a difference - it produces UNKNOWN - so nothing is ever reported as compliant or as drifted on the strength of a stale observation. |
| 13. Server profile comparison | Two levels. The assignment is compared to the pinned revision id; separately the latest Stage 25B observation is compared to the desired revision's own content with the existing `ServerProfileDiff.assess`. A right assignment on a changed host is `SERVER_PROFILE_CONTENT_DRIFT`. No new SSH parser was written. |
| 14. Config profile comparison | The target revision's `contentSha256` is compared to the `configSha256` of the shared CONFIG_PROFILE inventory object, and the node's `activeConfigProfileUuid` to the pinned external profile. No raw configuration is fetched on a dashboard read. |
| 15. Active inbounds | Set semantics: order is equal, a missing and an extra inbound are both drift. `RemnawaveNodeSummary` gained an optional `activeInboundIds`, extracted by the existing inventory decoder; a row stored before that field existed decodes to `None`, which is unobserved rather than empty, so no false drift is ever reported. |
| 16. Desired state | The fleet's intent is compared to the stored `IntegrationDesiredState`, and separately the panel's own `isDisabled` flag to the intended state. Connectivity is a health matter, not a compliance one. Nothing is enabled or disabled. |
| 17. Local observation | For a Stage 25C-managed node the existing read-only `RemnawaveNodeRemote.observe` is used with a spec built from the provenance (onboarding id, external node id, managed image) and the fleet revision (`nodePort`, `panelCidrs`), giving port, firewall and managed-installation drift without any mutation. |
| 18. Provenance | A SUCCEEDED Stage 25C onboarding matched on organization, integration, resource and external node id, joined to the inventory object by identity - never by directory or name. Its `imageReference` and reviewed API generation are the only things carried over; the onboarding snapshot is not copied into the fleet. |
| 19. Legacy nodes | A node without provenance keeps its full panel and server-profile verdict. Only its local dimensions become `LOCAL_MANAGEMENT_UNAVAILABLE`, the UI states "not managed by InfraDesk", and `NO_MANAGED_LOCAL_INSTALLATION` is recorded as a rollout blocker. Local state is never reported as compliant. |

## Observer

| Requested report items | Implementation |
| --- | --- |
| 20. Scheduler | `next_check_at` plus `claimed_by`/`claim_token`/`claim_deadline` on the membership. `claimDue` uses `FOR UPDATE SKIP LOCKED` with a lease, so one member is observed by one worker across instances. Promotion, member addition and manual refresh all simply mark members due. |
| 21. Race handling | TX1 snapshots the desired revision, the member and every stored source. Remote reads happen with no transaction open. TX2 writes the verdict only while the same claim holds, the membership version is unchanged, the fleet still desires that exact revision and the binding still binds that node to that resource; otherwise the result is discarded and the member rescheduled. An assessment is always bound to its exact `fleetRevisionId` and membership version. |
| 22. Sync reuse | No new inventory reader. A refresh calls the existing `IntegrationSyncStateRepository.scheduleAt`, so the existing scheduler performs exactly one integration-level synchronization however many members are due. |
| 23. Bounded SSH | `parTraverseN(maxConcurrency)` with a per-observation timeout, both configurable; a fleet of a hundred servers never opens a hundred connections. |
| 24. Impact preview | A read model computed from stored evidence only, with the local dimensions deliberately excluded, which is why the numbers are an estimate. It moves no pointer, starts no deployment and opens no connection. |

Configuration: `INFRADESK_INTEGRATIONS_FLEETS_*` for enabled, poll interval, batch size, concurrency,
claim lease, observation timeout, recheck interval and the staleness window. Drift detection requires
synchronization to be enabled (`fleetsOperational`).

## UX, permissions and audit

| Requested report items | Implementation |
| --- | --- |
| 25. Fleet UI | A `Fleet` section in the Remnawave integration detail, not a new sidebar entry. List cards show the desired revision and the compliance and health counts; the detail shows the summary, the desired configuration, the members table and the revision history with a per-revision impact preview and a promote action. |
| 26. Member detail | Desired versus actual per dimension, both reason lists with readable text and the technical code beside it, the rollout blockers, the local-management statement and the last check. Names and revision numbers lead; UUIDs are not the primary UX. |
| 27. Permissions | Reading needs `ReadOrganization`. Creating, revising, promoting, membership and archive need `ManageIntegrations` + `ManageConfigurations`. Only the refresh action, which causes remote reads, additionally needs `ExecuteOperations`. Promotion is metadata and therefore does not. |
| 28. Audit | `REMNAWAVE_FLEET_CREATED/UPDATED/ARCHIVED`, `REMNAWAVE_FLEET_REVISION_CREATED/PROMOTED`, `REMNAWAVE_FLEET_MEMBER_ADDED/REMOVED`, targeting the integration like the Stage 25C events. Background assessment writes no audit entry; it logs one structured line with identifiers, compliance and health only - never a token, credential or configuration body. |
| 29. No remote mutation | Confirmed by construction and by test. The observer's constructor receives no provisioning service, no configuration deployment service and no action service; its only transport is the read-only node observer. `RemnawaveFleetObserverSpec` counts every mutating transport method and asserts zero, including on a member drifting on every dimension. |

Stale evidence is shown as "needs a refresh" rather than a green verdict, and a verdict computed for a
revision that is no longer desired is not presented as the member's current state.

## Verification

| Requested report items | Result |
| --- | --- |
| 30. Backend | `sbt "testOnly *"`: 1083 total, 1064 passed, 19 opt-in checks skipped, zero failures. Real PostgreSQL 17.5, migrations through V49. 29 new cases: 14 in `FleetAssessorSpec`, 8 in `RemnawaveFleetObserverSpec`, 7 in `RemnawaveFleetIntegrationSpec`. |
| 31. Frontend | Full Vitest suite: 666 passed in 78 files. 11 new `FleetSection` cases cover the list, member compliance and health, member detail, impact preview, promotion, refresh, candidate eligibility, stale evidence, permissions and Russian. |
| 32. Production build | `sbt "Universal / stage"` and frontend `npm run build` pass; only the pre-existing chunk-size warning. |
| 33. Stage24 regression | Green in the full suite: synchronization, actions, desired state, config profile management, deployments and rollouts. |
| 34. Stage25A regression | Green: the provisioning engine is untouched and remains the only applier. |
| 35. Stage25B regression | Green: server profiles, their revisions, assignments and observations are reused, not re-implemented. |
| 36. Stage25C regression | Green: onboarding, create reconciliation without a second POST, the baseline and sync crash recovery of `ed2150a`, the installer, the firewall and auto-binding. |
| Live verification | Not performed. The observer is read-only and needs no destructive canary, but production readiness still requires a read-only check against a real test fleet: inventory, server-profile observation and local observation with no remote write. |

## Durability addendum (same stage)

Three state-consistency problems were closed after the review of the implementation above. No
migration was needed: V49 is unchanged and no new persistent field was added.

**Archiving owned nodes forever.** Archiving set `fleet.archived` and left every membership with
`removed_at IS NULL`, so the archived fleet still held the node under the one-active-fleet index and
the node could never join another fleet. `archive` now runs in one transaction under the fleet
advisory lock: it archives the fleet, ends every active membership (`removed_at`, version bumped,
claim cleared) and drops those memberships' current verdicts, which are a derived cache. The rows
themselves stay, so the fleet's history still names who was a member, who added them and when the
fleet released them. Archiving twice returns the same archived fleet without a second release or a
second journal entry. Nothing remote is touched: no node is disabled, unbound, re-firewalled,
redeployed or unassigned, and the binding survives.

**An expired lease could still write.** `saveAssessment` and `reschedule` checked only
`claim_token = $token`, so a worker whose observation outran its lease could still write for as long
as no other worker had reclaimed the row. Both now additionally require
`claim_deadline IS NOT NULL AND claim_deadline > greatest($now, clock_timestamp())`, so expiry
invalidates a worker immediately rather than at the next reclaim. `reschedule` is fenced for the same
reason: a stale worker must not push the next check out from under the owner. Timing margins in the
settings are still there, but nothing relies on them.

**A connection could change under an observation.** TX2 proved the desired revision, the membership
version and the binding, but not that the SSH source the read actually used was still the current
one. The observation is now pinned to a `FleetSourcePin` - the connection id and its `updatedAt`,
taken from the same `ProvisioningTargetQuery.eligible` that Stage25A pins a plan with - and TX2
re-reads the eligible target and compares. An edited, replaced or withdrawn connection, and equally
one that became eligible mid-read, discards the result: it never becomes the member's current state
and never receives a current `localObservedAt`. TX2 reads the database only; it performs no second
remote call. A read that simply failed while the source was unchanged still behaves as before -
UNKNOWN with `LOCAL_OBSERVATION_UNAVAILABLE`.

The observer therefore has two independent guards, both required: the lease proves this worker may
still write, and the source pin proves the evidence still describes the current desired state,
member, binding and connection. A lost lease is never a fleet failure - the observation is simply
discarded and another worker recomputes.

Added for this fix: 4 observer cases (source edited/replaced/withdrawn, unchanged source, expired
lease writes and reschedules nothing) and 2 PostgreSQL cases (archive releases its nodes while
keeping history and changing nothing remote; an expired lease loses the right to write before any
reclaim, then the reclaiming worker is the only one that may write). Totals after the fix: backend
1088 total / 1069 passed / 19 skipped, frontend 666 passed, both production builds green.

## Left for later stages

**Stage 25E (rollout).** Applying a desired revision, full-fleet rollout preview, canary nodes,
batch/staged rollout, reuse of the existing `ServerProfile` apply and `IntegrationConfigRollout` /
`IntegrationConfigDeployment` paths, desired-state changes, network reconciliation, health gates,
pause/continue and rollback. Stage 25D computes `rolloutEligibility` and starts none of it. A later
rollout must also understand that one shared Config Profile deployment can affect several members.

**Stage 25F (image lifecycle).** A desired node image, node updates, pulling a new version, rolling
restarts and Panel/Node upgrade compatibility. Stage 25D reports an image difference as installation
drift and proposes nothing.

Also deliberately absent from Stage 25D: arbitrary per-node overrides (use another fleet), automatic
reconciliation of any kind, and a generic multi-provider fleet abstraction.

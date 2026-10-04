# Stage 25E — Fleet Rollout

Implementation and automated verification are ready for review. Operational acceptance requires a
disposable Fleet canary. No production rollout or Stage 25F work was performed.

This report follows the 47 items in the Stage 25E request. CI results and the exact commit SHA are
reported with the delivered GitHub pull request; they are not inferred from local tests.

| Item | Implementation / verification |
| --- | --- |
| 1. Commit SHA | Resolve the implementation commit with `git log -1 --format=%H -- docs/stage25e-fleet-rollout.md`. The delivery message includes its SHA and CI run. |
| 2. Migration | Additive `V50__remnawave_fleet_rollout.sql`. V49 and earlier migrations are unchanged. Empty-database and existing-data upgrade tests reach V50. Both production backup/restore upgrade boundaries in CI now reach V50. |
| 3. Tables | `remnawave_fleet_rollout`, `remnawave_fleet_rollout_member`, `remnawave_fleet_rollout_action`. Existing child records gain optional parent IDs; their engines remain responsible for execution. |
| 4. Parent states / phases | States: PLANNED, QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, UNKNOWN, ROLLING_BACK, ROLLED_BACK. Execution: VALIDATE → APPLY_SHARED_CONFIG / VERIFY_SHARED_CONFIG when required → APPLY_CANARY / VERIFY_CANARY → APPLY_WAVES / VERIFY_WAVE → FINAL_VERIFY → COMPLETE. ROLLBACK is a separate compensation phase. PREPARE_SHARED_CONFIG and PREPARE_CANARY are reserved enum values, not separately scheduled steps. Failure retains its last phase. |
| 5. Member / action states | Members: PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN, SKIPPED, ROLLING_BACK, ROLLED_BACK. Actions: PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN, SKIPPED. Direction distinguishes forward actions from compensation. |
| 6. Immutable snapshot | Closed schema-versioned JSON and SHA-256 pin exact Fleet / ServerProfile / Config revisions, membership versions, external node identities, SSH source ID and timestamp, policy, baseline assignment / intent / health, and every shared Config consumer. Database trigger rejects snapshot changes. No secrets, scripts or raw configuration bodies. |
| 7. Idempotency | Organization-scoped unique request ID; a repeated start returns the same rollout. Deterministic child request IDs and action IDs prevent duplicate journal entries; existing child plans/runs are recovered before starting work. Preview has a 24-hour TTL. |
| 8. Lease / fencing | SKIP LOCKED claims with token/deadline and heartbeat. Parent, member and journal writes reject expired/foreign claims. Scoped child transactions lock the live parent and set transaction-local ownership; heartbeat and other parents execute concurrently. Remote calls run outside database transactions. |
| 9. Cross-engine conflicts | DB guards and advisory locks serialize admission against provisioning, configuration deployments, onboarding, resource operations, node actions, assignments, bindings, desired intents and SSH source edits. An exact journaled child may enter under its live owner. One active rollout per Fleet, node and resource; shared Config admission excludes competing deployments. PAUSED still owns these locks logically. |
| 10. Preview | Read-only remote behavior. Validates selected desired revision, membership and binding, active managed references, fresh evidence, API contract, trusted SSH and supported capabilities. REFRESH_REQUIRED and BLOCKED are explicit; start revalidates the snapshot. |
| 11. Canary | Default first eligible mutable member; explicit eligible IDs are validated against wave size. Already compliant members are skipped. A one-member rollout needs no separate canary selection. |
| 12. Waves | Deterministic canary wave zero and remaining members grouped by requested size, bounded to 25. Stored position/wave survive restart. |
| 13. Concurrency | At most two member operations per parent by default. Members advance in bounded waves; later waves cannot begin before the current verification gate passes. Claimed parents have independent heartbeats. |
| 14. Shared Config blast radius | Applied once before individual member changes. Preview exposes all consumers inside/outside the Fleet, baseline revision, external counts and rollback capability. Stale consumers require refresh; unhealthy enabled external consumers block the plan. Shared Config cannot provide a per-node canary. |
| 15. Config engine reuse | Uses existing Stage 24 IntegrationConfigRollout preview/start/outcome and its verification/automatic rollback. Exact child request/run correlation is durable. |
| 16. ServerProfile reuse | Existing Stage 25B assignment, preview, provisioning start, child outcome and observation paths. No new arbitrary SSH executor or provisioning DSL. |
| 17. ServerProfile rollback | Restores the captured assignment and applies its previous revision through the same engine. Fresh assessment verifies the result. Missing previous profile or unavailable capability is explicit incomplete compensation. |
| 18. Desired state | Existing desired-state service/reconciler; exact reconciler child is linked into the action journal. Rollback restores prior intent, or baseline observed enabled/disabled state when intent was absent, then removes the temporary intent after verification. |
| 19. Firewall | Existing typed managed-node firewall reconciliation and trusted SSH transport. Captures managed CIDRs before mutation; compensation restores that set and verifies it. Recovery of an interrupted firewall mutation is UNKNOWN, without blind replay. |
| 20. Node port | Port changes are explicitly BLOCKED with NODE_PORT_CHANGE_UNSUPPORTED. No unsafe restart/recreate implementation was introduced. |
| 21. Health gates | Compliant + healthy passes. Fresh compliant + degraded pauses at verification; drift/blocked fails; unknown/missing evidence waits. Shared gate checks shared dimensions before permitting individual ServerProfile changes. |
| 22. Freshness | Revision and membership version must match; computed and inventory evidence must follow the refresh/mutation boundary. Managed local evidence must also be fresh. Applied ServerProfiles require a post-start observation. Missing evidence eventually pauses with a timeout reason. |
| 23. Pause | Durable request; the active child can finish, but another action/member cannot start after the marker is observed. Parent pauses at the current phase. No cancellation of a running child is pretended. |
| 24. Resume | Only PAUSED is resumable; validates current membership, sources, references and shared context. It continues the stored phase/plan. Terminal and expired plans cannot be resumed. |
| 25. Rollback policy | CURRENT_WAVE or ALL_COMPLETED, in reverse wave order. Compensation uses only known-successful forward actions and its own journal. Shared rollback requires the previous revision and scope covering every affected completed wave. Fresh restoration verification is required before ROLLED_BACK. |
| 26. UNKNOWN | Distinct terminal outcome, no automatic retry/rollback and no resume. Operator must inspect actual remote state. Failed/unknown journal outcomes remain terminal during crash recovery. |
| 27. Crash recovery | Reuses successful actions, existing profile plans/runs and Config children by request; observes active children; fences lost workers. Unknown firewall results are not replayed. Rollback waiting for evidence does not repeat completed compensation. |
| 28. Final verification | Fresh evidence for every member, including skipped members and completed earlier waves. SUCCEEDED requires the pinned desired revision, compliance and health across the Fleet. |
| 29. Preview UI | Target revision, canary choice, wave size, exact node changes/skips, shared consumers, warnings/blockers, expiry, estimates and rollback capabilities. Changing options or rebuilding a failed preview invalidates the old plan. |
| 30. Progress UI | Durable parent phase/state, completed nodes and waves, child IDs, per-member/actions outcomes, pause/rollback markers and failure reasons. |
| 31. History | Fleet rollout history and persisted detail; no removal of outcome evidence. |
| 32. Permissions | Organization read for detail/history. Preview/start/pause/resume/rollback require ManageIntegrations + ManageConfigurations + ExecuteOperations. UI hides mutation controls from readers. |
| 33. Audit | Requested, paused, resumed, rollback-requested and finished outcomes use typed audit actions. Payload/logging contains IDs and stable codes, not secrets or configuration content. |
| 34. RU / EN | Rollout controls, phases, states, preview issues, baselines, rollback capability and shared consumers in both languages. Child-engine codes remain available for diagnosis. |
| 35. Backend tests | Full `testFull` with PostgreSQL enabled: 1120 total, 1101 passed, 19 skipped, zero failures/errors. Includes planner, gates, crash recovery and pause/rollback tests. |
| 36. PostgreSQL tests | Separate disposable PostgreSQL 17 cluster, no production DB. Nine Fleet rollout integration cases cover admission/idempotency, active locks, controls, fencing, snapshot immutability, owned desired-state correlation, terminal guards and source pinning; migration/upgrade and existing integration suites run too. |
| 37. Frontend tests | Full Vitest run: 682 passed in 79 files. Rollout panel: 16 tests, including invalidated preview, permissions, consumers, capabilities, history and UNKNOWN. |
| 38. Production build | `npm run build` and `Universal / stage`; production Docker topology and backup/restore are verified by the full GitHub CI workflow. Exact completion status is in delivery. |
| 39. CI | Push triggers the full workflow for the implementation SHA. Acceptance requires all job groups, including images and both self-hosted jobs. The CI run is linked in delivery; local results do not substitute for it. |
| 40. Stage 24 regression | Existing configuration deployment/rollout and desired-state suites included in full backend/frontend verification. |
| 41. Stage 25A regression | Existing provisioning plans, execution, recovery and SSH safety suites included. Linux safety helper tests additionally execute in CI. |
| 42. Stage 25B regression | Existing ServerProfile assignment, revision, diff, application and observation suites included. |
| 43. Stage 25C regression | Existing onboarding/import/remote setup/recovery suites included. |
| 44. Stage 25D regression | Existing Fleet desired-state, membership, observation, drift and frontend suites included. V49 unchanged. |
| 45. Disposable Fleet canary | NOT RUN: no disposable Fleet / trusted SSH / test Panel access supplied. Unit and PostgreSQL tests are not a real VPS canary. Operational acceptance remains pending. |
| 46. Limitations | Node port changes, config-profile rebinding, active inbound changes, unmanaged local installation changes and image lifecycle are blocked. No guaranteed complete rollback without prior baselines. Uncertain remote outcome requires manual inspection. Shared Config changes affect every consumer and cannot be node-canary isolated. Real Panel/SSH behavior awaits disposable canary. |
| 47. Stage 25F | Not started. Desired image/version, compatibility matrix, digest policy, update detection, controlled pull, rolling upgrade/restart/recreate, version verification and upgrade rollback remain separate work after 25E acceptance. |

## Disposable canary procedure

Use a dedicated test Panel and two or three disposable nodes with verified host keys and managed
Stage 25C installations. Preserve a separate shared Config consumer to check the blast-radius rules.

1. Establish fresh healthy baseline observations and pinned Fleet desired revision.
2. Preview a supported ServerProfile/desired-state/CIDR change; verify exact nodes, waves and shared consumers.
3. Start with one canary and pause-after-canary; verify no next-wave mutations before resume.
4. Restart the InfraDesk worker during a profile child; verify child ID is reused and progression waits for fresh evidence.
5. Test operator pause mid-child, resume, degraded-health pause and known failure with automatic compensation.
6. Test explicit CURRENT_WAVE and ALL_COMPLETED compensation, prior intent absent, and shared rollback scope protection.
7. Exercise a controlled uncertain firewall response; confirm UNKNOWN, no automatic replay, and manual inspection.
8. Confirm final Fleet compliance/health, durable history/audit and absence of secret material in API/logs.

Record parent/member/child IDs, before/after observations, failures and verified compensation results.
Only then close operational acceptance; Stage 25F must still be separately requested.

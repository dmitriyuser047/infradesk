# Remnawave hardening delivery review

Follow-up to user commit `3a605b85c8d19c83dafc84bc650851e46efaa406` and failed
[CI run 37449298899](https://github.com/dmitriyuser047/infradesk/actions/runs/37449298899).
The delivery message identifies the final commit and exact push CI; this report belongs to that
commit. Automated validation does not declare live acceptance.

## Blockers and state machines

- Initial installation establishes a durable exact ownership claim before secret-bearing staging.
  Complete files are published together using same-filesystem no-overwrite rename, inode proof and
  fsync. Every earlier crash leaves the final namespace absent and recoverable owned staging.
  Cleanup failures remain observable; unknown paths are preserved.
- Shared local states are ABSENT, OWNED_COMPLETE, OWNED_PARTIAL, OWNED_DAMAGED, FOREIGN,
  PORT_CONFLICT and UNKNOWN. Only complete evidence verifies installation. Exact partial/damaged
  ownership uses guarded file CAS repair; unknown ownership never authorizes repair.
- Recovery uses fresh typed Panel lookup for exact/unhealthy/absent/conflict/unknown. Exact existing
  nodes reuse their UUID without create POST. Historical UNKNOWN CREATE_NODE retains its durable
  correlation/installation owner chain. Recreation needs explicit approval.
- Version 2 recreation adds RETIRE_NODE_FIREWALL before local retirement and CREATE. Only exact old
  namespace/CIDRs may be removed, with surviving SSH access and fresh canaries. Uncertainty ends
  UNKNOWN. Version 1 terminal snapshots and journal sequences retain their old meaning.
- BIND_RESOURCE atomically transfers exact old fleet membership to the new bound inventory node,
  increments version, invalidates assessment/image cache, schedules observation and appends immutable
  replacement history. Repeating the transaction makes no additional change.
- Reciprocal onboarding/action/rollout/upgrade admission uses integration → fleet → resource locks
  and DB triggers. Paused workflows remain active. Lease-only progress avoids reverse lock order.
- Scoped Start request identity is resolved before destructive confirmation. Typed start results
  distinguish new transitions from retries; DB-returned timestamps make retry responses identical.
  New activation still checks source/baseline/integration pins and records audit atomically.
- Integration deletion tombstones identity and atomically removes its credential while preserving
  history/FKs. Active workflows and sync/desired claims block deletion. New work and active queries
  exclude tombstones; tenant-scoped history/detail remain readable.
- ReadOrganization can view onboarding history/detail. The three existing mutation permissions
  remain required by backend and frontend; RU/EN texts expose safe error codes.
- Reviewed image changes preserve original installation ownership. Recovery, installation proof,
  Start and image observation share exact single-image-line normalization. Duplicate lines,
  unreviewed references, other template edits and conflicting container identity fail closed.
  Lazy script initialization avoids dependence on which SSH capability loads first.

## Transactions, external I/O and DB invariants

Plans commit before execution. Claims and fenced progress use short DB transactions. Provider HTTP,
SSH, Docker, filesystem and UFW execute between transactions. Binding/membership replacement,
desired intent and audit use existing typed engines. Remote DELETE uses the typed provider capability.
Token/deadline CAS prevents stale writes; uncertain mutations are observed rather than replayed.

V53 adds firewall retirement/versioned phase validation. V54 adds reciprocal admission, controlled
membership replacement, scoped FKs and immutable replacement history. V55 adds tombstones and
active-work/metadata admission. V56 corrects observer lease/progress lock order while retaining active
integration checks and exclusive intent/action admission. Published V48–V55 are unchanged by this
follow-up; V56 is additive.

## Cost model and regressions

| Path | Before | After |
| --- | --- | --- |
| Onboarding options core reads | Per-server SQL | 4 statements for 1/100/500 resources |
| Rollout Preview member facts/source | Approximately 5 statements per member | 1 fact projection + 2 eligibility statements for 1/10/100/500 |
| Rollout snapshot drift | Per-member membership/inventory/binding/evidence | 1 fresh fact projection plus existing member-list read |
| Upgrade projections | Adjacent paths reviewed | Evidence 2, eligibility 2, rows 2, image cache 1, independent of count |
| Worker snapshot/control lookup | Repeated nested scans | Maps/sets per batch |

Context/config/profile/sync add a fixed cost to these core counts. Tests measure real PostgreSQL
statements and tenant/selection boundaries. Provider/SSH freshness remains per-node with bounded
concurrency. Evidence provenance is batched; one-node durable polling retains fencing and fresh reads.

New regressions cover claim/staging/env/compose/metadata/publication crashes; repeat repair no-op;
uncertain cleanup/retirement; symlink, UID/GID, mode, hardlink and extra-file rejection; secret-free
arguments/facts/output; INSTALL_NODE recovery for all seven states; exact firewall retirement and
SSH survival; identical-timestamp/HTTP request retries; terminal history; replacement and rollback;
stale/foreign claims; paused admission; concurrent onboarding versus rollout/upgrade; observer
progress while integration is locked; forty intents with six desired workers; deletion versus claims;
query counts; fresh schema and V43/V52 upgrades preserving onboarding/rollout/upgrade history.

Local final validation: backend `testFull` **1279 total, 1260 passed, 19 platform skips, zero
failures/errors** with real PostgreSQL 17 enabled. PostgreSQL/database suites account for **371
total, 362 passed, 9 platform skips, zero failures/errors**. Frontend **85 files / 760 tests passed**;
production frontend build and `sbt Universal/stage` passed. Linux safety ends
`LINUX SAFETY CHECKS PASSED`, including reviewed-image recovery and every install crash boundary.
Exact-SHA push CI is recorded in the delivery message. Windows validation uses
temporary LF normalization of three cryptographic fixture files and a JVM-only hosts file for SMTP
hostname resolution. Neither fixture bytes nor SMTP deadlines are changed in Git. The Linux harness
runs extracted production shell in an isolated container with mocked Docker/listener responses.

## Operator-only acceptance and remaining risks

Live actions were explicitly refused by the operator and were not performed. Stage25 remains
unaccepted until manual failure/recovery acceptance after exact-SHA green CI. On disposable objects:

1. Clean onboarding succeeds; identical repeat Apply adds no UFW rule or duplicate container.
2. Stop at claim, staging, env, compose, metadata and publication boundaries; restart and reconcile.
   Owned partial states repair to complete, with final files appearing together.
3. Fail firewall then recover the existing external UUID without create POST.
4. Lose CREATE response; retry only read-only reconciliation until presence is proven.
5. Confirm remote absence, Preview recreate and explicitly approve Start. No POST precedes approval;
   old UUID, correlation, run and journal remain immutable.
6. Preview DELETE_RECREATE. Uncertain DELETE stops UNKNOWN without CREATE; proven absence permits
   exact old firewall/file retirement before creating the replacement.
7. Check old namespace removal, surviving foreign/Server Profile rules, new binding/desired state,
   one membership version increment, invalidated caches and retained old/new history.
8. Restart during durable phases and repeat requests after reload; child and request IDs are reused.
9. Run fleet configuration rollout and reviewed image upgrade, then recovery; the approved image
   remains with its original installation ownership.
10. Exercise automatic rollback and uncertain mutation; UNKNOWN must not replay destructive work.

After every destructive case inspect Panel, DB, filesystem, Docker, UFW, binding, desired state,
membership, history and audit. The v0.1.9 env-only orphan without ownership evidence remains FOREIGN
and needs operator review. It is never automatically accepted or deleted. New installation publishes
whole directories; legacy proven partial states use exact guarded repair. Drain old workers for
deployment: mixed old/new worker operation is not claimed supported. Mocked safety is not live proof.

## Production file inventory

For an exact list relative to v0.1.9, run:

```text
git diff --name-only 35438b6 HEAD -- src/main frontend/src
```

This includes migrations, domain/local state, onboarding and fleet application/ports/workers,
PostgreSQL repositories/projections, SSH managed files/image/node transport and onboarding UI/types.
The test and documentation file list is available in the full commit diff.

The first follow-up push CI passed 1276 backend cases and failed only the 500-member fixture's
30-second preparation deadline. Fixture creation now uses set-based inserts with all ownership
triggers enabled and retains 500 distinct resources, inventory nodes, memberships and SSH sources.
The final targeted PostgreSQL suite passes all 10 cases; the 500-member case takes 2.868 seconds
locally. Assertions, dataset size and timeout are unchanged. A subsequent exact-SHA push CI is
required and is identified in the delivery message.

Production backup/restore CI fixtures in `.github/workflows/ci.yml` now reverse V53–V56 additive
objects before constructing their disposable V29/V41 backups and expect restoration through V56.
Previously the V54 replacement foreign key blocked fixture preparation. A real PostgreSQL regression
executes both exact workflow SQL blocks and then Flyway upgrades, checks target versions and retained
markers. This fixture conversion is confined to CI; production migrations and restore behavior are
unchanged. Existing V52 terminal onboarding/rollout/upgrade migration checks also remain green.

- frontend/src/components/integrations/NodeOnboarding.tsx
- frontend/src/types/nodeOnboarding.ts
- src/main/resources/db/migration/V53__remnawave_onboarding_firewall_retirement.sql
- src/main/resources/db/migration/V54__remnawave_replacement_admission.sql
- src/main/resources/db/migration/V55__integration_tombstones.sql
- src/main/scala/application/integration/FleetAssessor.scala
- src/main/scala/application/integration/FleetRolloutChildren.scala
- src/main/scala/application/integration/IntegrationManagement.scala
- src/main/scala/application/integration/OnboardingRecoveryObservation.scala
- src/main/scala/application/integration/RemnawaveFleetRolloutWorker.scala
- src/main/scala/application/integration/RemnawaveFleetRollouts.scala
- src/main/scala/application/integration/RemnawaveFleetUpgrades.scala
- src/main/scala/application/integration/RemnawaveFleets.scala
- src/main/scala/application/integration/RemnawaveOnboarding.scala
- src/main/scala/application/integration/RemnawaveOnboardingOperations.scala
- src/main/scala/application/integration/RemnawaveOnboardingWorker.scala
- src/main/scala/application/port/IntegrationActionRepository.scala
- src/main/scala/application/port/RemnawaveFleetPorts.scala
- src/main/scala/application/port/RemnawaveNodeRemote.scala
- src/main/scala/application/port/RemnawaveOnboardingPorts.scala
- src/main/scala/application/provisioning/ServerProfiles.scala
- src/main/scala/domain/integration/LocalInstallationState.scala
- src/main/scala/domain/integration/RemnawaveOnboarding.scala
- src/main/scala/domain/provisioning/ServerProfileAutomationState.scala
- src/main/scala/integration/ssh/ProfileManagedFiles.scala
- src/main/scala/integration/ssh/SshManagedNodeImages.scala
- src/main/scala/integration/ssh/SshRemnawaveNodeRemote.scala
- src/main/scala/persistence/postgres/PostgresIntegrationActionRepository.scala
- src/main/scala/persistence/postgres/PostgresIntegrationDesiredStateRepository.scala
- src/main/scala/persistence/postgres/PostgresIntegrationInventoryRepository.scala
- src/main/scala/persistence/postgres/PostgresIntegrationRepository.scala
- src/main/scala/persistence/postgres/PostgresRemnawaveFleetQuery.scala
- src/main/scala/persistence/postgres/PostgresRemnawaveFleetRepository.scala
- src/main/scala/persistence/postgres/PostgresRemnawaveOnboardingQuery.scala
- src/main/scala/persistence/postgres/PostgresRemnawaveOnboardingRepository.scala
- src/main/resources/db/migration/V56__remnawave_observer_lease_lock_order.sql

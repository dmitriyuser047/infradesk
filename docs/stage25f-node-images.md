# Stage 25F — Remnawave Node image lifecycle

Implementation and automated verification are separate from production acceptance. Disposable
25E/25F Fleet canaries still require a dedicated Panel and Stage25C-owned test nodes. No production
node was used to substitute for those canaries.

## Final report (48 requested items)

1. **Final 25E fix:** `2390e27a483d709478d0598121ac2f8da77910b1`.
   [All five CI jobs passed](https://github.com/dmitriyuser047/infradesk/actions/runs/37239633468).
   Every new wave/action rechecks admission; stale evidence pauses with zero new mutations.
   Existing running children are reconciled before admission without blind replay.
2. **25F commit:** the implementation commit introducing this document, separate from item 1.
   Resolve its SHA with `git log --diff-filter=A --format=%H -- docs/stage25f-node-images.md`.
3. **V51:** `V51__remnawave_node_image_lifecycle.sql` is additive. Six tenant-scoped tables:
   immutable release revisions, separate desired release pointer, image observation cache, upgrade
   runs, members, actions. Triggers enforce immutable history, success proof and shared workflow locks.
4. **Official sources reviewed:** [Node 3.4.1](https://github.com/remnawave/node/releases/tag/3.4.1),
   [Node 3.4.0](https://github.com/remnawave/node/releases/tag/3.4.0),
   [Node 2.8.0](https://github.com/remnawave/node/releases/tag/2.8.0),
   [Node installation](https://docs.rw/install/remnawave-node/),
   [upgrade order](https://docs.rw/install/upgrading/). Official GHCR OCI indices, architecture
   manifests and BuildKit provenance were fetched and hashed during review.
5. **Catalog structure:** backend resource `integration/remnawave/node-image-releases.json` contains
   release ID, semantic Node version, repository, index digest, architecture-specific manifest and
   config digests, compressed size, provenance manifest/blob pins, Panel interval, API generation,
   source commit, publication date, catalog version and AVAILABLE/DEPRECATED/BLOCKED status.
6. **Reviewed releases/ranges:** 2.8.0 → Panel `[2.8.0,3.0.0)` / PROFILE_PUBKEY;
   3.4.0 and 3.4.1 → Panel `[3.4.0,3.5.0)` / PROFILE_SECRET_KEY. These are conservative reviewed
   intervals, not a claim that every Node version matching Panel major is compatible.
7. **Compatibility:** interval + API generation + exact reviewed Panel version/commit from the
   existing Panel catalog + provisioning capabilities + platform + release status. Results:
   COMPATIBLE / INCOMPATIBLE / UNCONFIRMED. Custom and unconfirmed builds cannot mutate.
8. **Repository/digest:** only `ghcr.io/remnawave/node`; target selection accepts release ID.
   Pull/switch use the reviewed platform manifest digest, never `latest` or a user supplied tag.
   The index digest, platform manifest digest and running Docker config Image ID remain distinct.
9. **Multi-arch:** linux/amd64 and linux/arm64 have separate manifest, config and provenance pins.
   An unsupported platform blocks admission. Catalog versions never stand in for runtime identity.
10. **Desired Fleet release:** immutable `FleetNodeReleaseRevision` plus a separate release-policy
    pointer. Selection changes metadata only. Stage25D configuration desired revision stays separate.
11. **Installed image:** existing Fleet observer reads narrow Docker fields: configured image,
    actual config Image ID, RepoDigests, OS/architecture, container identity/creation/start, restarts,
    running/listener/stability, owned-file hashes, image size and Docker-filesystem free bytes.
    Cache is pinned to tenant, membership version, resource, onboarding and SSH source identity.
12. **Availability:** CURRENT, UPDATE_AVAILABLE, AHEAD_OF_TARGET, UNSUPPORTED or UNKNOWN.
    An unreviewed runtime digest is UNKNOWN, including one that reports a newer semantic version;
    it is not promoted to a trusted “ahead of catalog” result from a version string alone.
13. **Ownership:** only a Stage25C provenance/binding/trusted-source installation whose marker,
    private environment hash, compose ownership and every non-image compose byte still match is
    eligible. Unmanaged/adopted/foreign installations are not converted by an upgrade.
14. **States/phases:** PLANNED → QUEUED → RUNNING, with PAUSED / ROLLING_BACK and terminal
    SUCCEEDED / FAILED / UNKNOWN / ROLLED_BACK. VALIDATE, PREPARE_CANARY, PREFETCH_CANARY,
    UPGRADE_CANARY, VERIFY_CANARY, PREFETCH_WAVE, UPGRADE_WAVE, VERIFY_WAVE, FINAL_VERIFY,
    ROLLBACK, COMPLETE are persisted. Each member has durable progress and typed action history.
15. **Snapshot:** organization/integration/Fleet, config revision/hash/content, release revision/hash,
    full reviewed target and Panel pin, membership versions, exact baseline images/container/file
    hashes/source timestamps, deterministic canary/waves and rollback policy. Closed codecs reject
    extra fields; DB triggers forbid identity/snapshot replacement.
16. **Idempotency:** organization-scoped request ID is pinned to the plan. UI keeps the same request
    ID on a network retry. A repeated start returns that run; reuse for another plan is rejected.
17. **Cross-engine guards:** shared advisory resource/config/source locks and active-member locks
    exclude 25A provisioning, 25B deployment, 25C onboarding, 25E rollout, manual container
    operations, manual Panel Node actions and shared-config deployments/rollouts. Pin/source,
    assignment, binding and desired-pointer mutations cannot pass an active upgrade.
18. **Preflight:** current integration/Panel/catalog pins, current healthy compliant Stage25D
    assessment and each required timestamp, enabled/connected Node, current config facts,
    stable owned Docker baseline, exact SSH source, no conflicts, supported architecture,
    working Compose, verified artifact and enough disk before mutation.
19. **Disk:** prefetch minimum `max(8 GiB, running image bytes + 5 × compressed target bytes +
    1 GiB)` on Docker's actual root filesystem. Before switch, require running + stored target
    image bytes + 1 GiB. This is a conservative minimum, not exact prediction of layer expansion.
20. **Prefetch:** pinned OCI index/platform/config/provenance chain is revalidated by bounded TLS
    reads. Store inspection can reconcile an interrupted pull; only a missing image triggers one
    exact platform pull. The old running container must remain unchanged afterward.
21. **Compose:** only the controlled `image:` line is replaced using the Stage25C backend renderer.
    `.env`, registration data, managed marker, paths, container name, port and network remain owned.
    Staging is private and replacement is atomic on the destination filesystem.
22. **Validation:** `docker compose ... config -q` runs on the candidate before commit/up. Failure
    becomes a constant safe code; no compose diagnostics/environment are persisted or returned.
23. **Switch:** one owned-service `docker compose ... up -d --no-deps --pull never node`.
    No `down`, mass restart, generic command input or prune. Admission/fence callbacks run before
    atomic file commit and again immediately before up.
24. **Local verification:** configured reviewed reference + actual platform config digest + unchanged
    ownership marker + running container + listener + stable start + zero restarts. Bounded wait
    handles settling; a known stopped target can fail and trigger known-state rollback.
25. **Panel verification:** existing IntegrationSync and Stage25D observer supply fresh evidence
    strictly after the switch boundary. Enabled/connected, healthy/compliant, correct configuration
    and runtime identity are required before member/wave/Fleet success.
26. **Version:** official inventory `versions.node` is reused when available. A reported version
    mismatch rejects proof even if Docker is locally healthy. Missing optional version does not
    override independently proven actual config Image ID.
27. **Canary:** selected immutable canary runs first and must pass local, Panel and Fleet gates.
    Multi-node pending sets require a canary and prevent selecting every node as canary.
28. **Waves:** pending nodes sort by name/membership ID; pinned canary is wave zero, remaining
    nodes split into wave-size 1–25 groups. Already-target members are explicitly skipped but
    still participate in final fresh verification. Node mutation concurrency is one per Fleet;
    at most two Fleet workers run concurrently.
29. **Pre-wave admission:** START, RESUME, VALIDATE, prefetch, new switch, next wave and final
    success recheck current assessment/evidence, pins and baseline. Completed prior waves must
    remain fresh and healthy on target. Stale evidence yields REFRESH_REQUIRED and zero new
    mutations; Refresh is explicit and never silently resumes a paused rollout.
30. **Pause/resume:** operator pause finishes the current atomic node boundary then stops.
    pauseAfterCanary persists PAUSED. Resume requires current admission. Pause does not depend
    on a reachable Panel. Rollback request persists scope CURRENT_WAVE / ALL_COMPLETED.
31. **Crash recovery:** deterministic durable action IDs, exact baseline/target observations and
    lease fencing precede mutation. Running SWITCH with proved target completes without another
    up. A committed target compose with the exact old container proves the limited pending-up
    boundary. Completed verification journals are reused. Persisted member failure cannot advance.
32. **UNKNOWN:** uncertain SSH/result or unprovable runtime identity stops the Fleet. Running
    children become UNKNOWN; no blind retry, automatic rollback, next wave or success claim.
    UI offers inspection, not a Retry button for UNKNOWN.
33. **Rollback baseline:** exact reviewed previous platform reference and config Image ID are
    captured from runtime evidence, separately from target and original Stage25C marker. Unknown,
    blocked or incompatible previous software is not advertised as an available automatic rollback.
34. **Rollback:** only members with durable successful forward SWITCH are candidates; chosen
    scope runs in reverse position order. Recheck Panel/source/ownership, use retained exact image,
    validate compose, switch and require fresh local + Panel + Fleet proof. Recovery reconciles
    already-previous runtime; uncertainty remains UNKNOWN. Incomplete rollback is explicit.
35. **Retention:** the previous image is never pruned by this feature. Rollback never substitutes
    the current version tag or pulls an arbitrary previous tag. Missing retained proof blocks it.
36. **UI:** Fleet “Node versions” in RU/EN: installed version/availability/architecture, catalog and
    compatibility, desired selection, canary/wave policy, reviewable preview, explicit Start,
    active phase/member/action progress, Pause/Refresh/Resume/scoped Rollback and immutable history.
37. **Permissions:** ReadOrganization for catalog/history/detail; ManageIntegrations +
    ManageConfigurations for release selection; also ExecuteOperations for preview/start/control.
    Existing organization authorization boundary provides tenant context before service access.
38. **Audit:** target changed, requested, paused, resumed, rollback requested and completed codes
    are added to the existing audit mechanism and schema allowlist. Audit identifies the integration;
    the execution history separately retains durable run/member/action IDs.
39. **Redaction:** closed narrow snapshots/action facts, no full Docker inspect, env, Node logs,
    installation credentials, private keys, arbitrary compose, tokens or raw remote diagnostics.
    GHCR anonymous pull token stays in memory; redirects strip authorization and stay on official
    HTTPS hosts. Errors/logs expose constant codes and IDs. Fake-secret regression tests cover DTOs.
40. **Backend count:** local `testFull`: 1196 total, 1177 passed, 19 Windows platform skips,
    zero failures. This includes 50 new 25F regression cases across admission, lifecycle worker,
    OCI provenance, typed SSH, HTTP permissions/DTOs and PostgreSQL.
41. **PostgreSQL:** real PG17 integration suite covers V51 migration, separate target/hash round
    trip, request uniqueness, terminal immutability, success proof, live fencing/claim replacement,
    cross-engine conflicts in both directions and simultaneous 25E/25F start transactions.
42. **Frontend count:** 694 passed in 80 files, zero failures;
    eight dedicated NodeVersionsPanel cases cover readonly, selection, stable request ID, refresh,
    UNKNOWN, RU controls and disabled automatic rollback without a previous baseline.
43. **Build:** TypeScript/Vite production and Scala Universal staging are part of final validation.
44. **GitHub CI:** all five jobs are required on the pushed implementation SHA. The delivery report
    links the exact run; [master runs](https://github.com/dmitriyuser047/infradesk/actions?query=branch%3Amaster)
    also retain backend/frontend/image and Ubuntu 22.04/24.04 smoke/restore evidence.
45. **Disposable Fleet upgrade canary:** PENDING — no dedicated Panel/Stage25C-owned test-node
    access was supplied. Automated three-node canary/wave tests are not a real upgrade canary.
46. **Failure/rollback canary:** PENDING — automated known-local/Panel failure rollback and exact
    digest proof pass; the requested disposable real-node controlled-failure run remains required.
47. **Crash-recovery canary:** PENDING — automated after-up and after-compose recovery tests pass;
    a real worker crash/restart on disposable nodes remains required.
48. **Limitations:** conservative reviewed catalog and Panel intervals; unsigned BuildKit provenance
    supplements reviewed SHA pins and is not a Cosign/signature claim. GHCR reachability is required
    for new prefetch. Unreviewed runtime baseline blocks an upgrade; no arbitrary adoption occurs.
    Fleet observers/sync must produce fresh post-switch evidence within five minutes. No production
    acceptance claim until the 25E and 25F disposable canaries and any discovered fixes pass.

## Disposable acceptance procedure

Use a dedicated compatible reviewed Panel, trusted SSH and 2–3 disposable nodes provisioned by
Stage25C. Keep production resources out of the Fleet. Confirm original install evidence, fresh
Stage25D healthy/compliant assessment and version A runtime identity. Select reviewed compatible B,
preview a single-node canary, set pauseAfterCanary, approve the immutable plan and Start. Verify A
containers remain unchanged during prefetch. Confirm canary B local/config Image ID, Panel version
and fresh assessments, then intentionally age evidence while paused: Resume must reject with zero
mutations. Refresh explicitly, Resume and prove wave completion and final success.

On a new disposable run, induce a known target local/Panel verification failure. Verify only known
successful switches roll back to exact retained A, with fresh local and Panel proof. Separately stop
the worker after compose commit and after up, restart after lease expiration and prove no duplicate
up when B is already running. Simulate uncertain SSH and confirm UNKNOWN without automatic rollback
or advancing any further node. Record run/action IDs, redacted actual image proofs and timestamps.

Before final Stage25 acceptance also execute the separate 25E disposable desired-configuration
canary, including stale start/resume/next-wave admission and shared external-consumer health gates.
Do not begin a new major Stage automatically.


## Admission read-path review fix

Admission, preview and version status use batch evidence and source projections. Each admission
boundary loads a fixed number of SQL statements and builds membership/source/image/action indexes;
validation performs no per-member database reads. Checks remain fresh at START, RESUME, VALIDATE,
prefetch, wave transitions and the immediate mutation-authority callbacks. No evidence cache crosses
those safety boundaries. Consequently total statement count grows with mutation boundaries, rather
than boundaries multiplied by member count. Reading all current members at each boundary still
processes Fleet-sized data; this is intentionally retained for the safety gates.

Wave/final verification submits one batch, inspects Panel once, loads only the requested membership,
assessment, image and evidence rows, then validates every member with its own mutation timestamp.
Single-node forward/rollback verification uses the same batch path with one member.

Real PostgreSQL regression exercises 1, 10, 100 and 500 actual members: evidence/provenance uses two
statements, source/connections two, member/assessment two and image observations one. Tenant,
missing-source, ambiguous-source and selection boundaries are checked. Worker regressions verify
one final-verification batch at each size with no remote mutations.

Observer startup now requires claimLease > 2 * observationTimeout + 5 seconds, accounting for both
sequential SSH observations and the fenced-save margin. Defaults remain 120/45 seconds. Environment
parsing and direct settings construction reject unsafe shorter leases.

The global Stage25D claim/fencing regression runs in a disposable isolated database, which is migrated
and dropped by a managed resource. Suite parallelism and the production global claim query remain
unchanged. The previous documentation HEAD 9e11f82 failed CI due to that fixture isolation defect;
only a complete green CI run of the replacement exact HEAD satisfies delivery acceptance.

Real disposable upgrade, controlled rollback and crash-recovery canaries above remain PENDING until
the dedicated compatible Panel and Stage25C test-node access are supplied. These automated database
and worker regressions do not constitute operational acceptance.

Local review-fix validation: `testFull` reports 1203 total, 1184 passed, 19 Windows platform skips,
zero failures; targeted review regressions report 60 passed. Windows local validation uses a temporary
JVM-only hosts file because canonical hostname resolution on this workstation takes 12.4 seconds
and exceeds the unrelated SMTP fixture's five-second deadline. SMTP's 17 cases then pass without
changing test deadlines or production SMTP code. The replacement CI uses its normal Linux environment.
Scala Universal staging also passes on the review-fix sources.

## Onboarding replacement and local provenance hardening

V53–V55 extend the existing lifecycle without changing V48–V52 or terminal snapshots. New
recreation plans retire the previous node's exact firewall namespace in a separate durable phase.
At binding, the same transaction transfers the fleet membership to the exact new node, increments
its version, invalidates assessment/image cache and makes it due for fresh observation. An immutable
replacement trail retains both inventory identities. Image upgrades and configuration rollouts
serialize admission with onboarding on integration, fleet and resource locks, in both directions;
paused workflows remain active for this purpose. No replacement is allowed beneath active work.

Fleet local evidence uses the shared LocalInstallationState classifier. FOREIGN/PORT_CONFLICT
block repair, UNKNOWN supplies no usable ownership proof, and owned partial/damaged installations
report drift. Only OWNED_COMPLETE supplies verified local evidence. Initial installation uses owned
staging and atomic whole-directory publication; repair retains exact ownership and file CAS checks.
Docker image pull occurs after publication, so a crash cannot leave an unprovable secret-only final
directory. See Stage25C for staging recovery and secret cleanup details.

Integration deletion retains a tombstone and historical foreign keys, removes the credential in
the same transaction and prevents new observer, sync, action, rollout or upgrade work. Dashboard
history remains stored; active candidate/evidence projections exclude tombstones. These changes
do not replace fresh provider and SSH checks before external mutations or rollback.

The earlier validation counts above describe their earlier delivery only. Hardening acceptance
requires a new full regression run, exact-SHA CI and the disposable VPS failure/recovery scenarios.

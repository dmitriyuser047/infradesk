# Stage 25B — versioned server profiles

Stage 25B adds explicit, reviewed server baseline changes through the existing Stage 25A
provisioning engine. A profile assignment records intent; creating, assigning or editing a
profile does not connect to a server. There is no automatic reconciliation or bulk rollout.

## 1. Profile model

`ServerProfile` stores tenant, stable code, name, description, latest revision and archive state.
The code is unique within the organization. Archive is idempotent and requires zero assignments;
profiles and revisions remain available to historical runs. Migration V47 introduces the tables
without modifying the released V45/V46 migrations.

## 2. Revision model

`ServerProfileRevision` stores numbered typed content, schema version 1, canonical SHA-256,
creator and creation time. A database trigger rejects updates and deletes. Appending content
identical to the latest revision returns that revision without another audit event. A real
change appends a revision under a profile row lock; concurrent identical submissions create one
new revision. Reusing older content creates a new revision when it differs from the latest.

## 3. Assignment model

`ServerProfileAssignment` pins profile ID, revision ID/number and assignment identity/version
to a tenant Node. New profile revisions do not move existing pins. Changing a pin increments its
version; unassign/reassign creates a new identity, invalidating old previews. Assignment requires
an active Node, but no SSH connection. Removal is permitted after a Node becomes inactive.
Assignment changes and approvals share the same resource advisory lock and refuse active runs.

## 4. Observation model

`ServerProfileObservation` retains the latest sanitized facts with SSH source ID/version,
assignment ID/version, revision, timestamp and canonical hash. VERIFY observations also link to
their exact provisioning run. Old observations survive pin removal/change, but are not treated
as observations of the new desired state. Organization readers use DB-only endpoints; remote
observation is explicit. Reads that race with assignment/source changes or even a completed run
are discarded before publication. Raw stdout, stderr, credentials and process arguments are
never stored in observations.

## 5. Module schema

Schema version 1 has eight closed objects. Unknown fields and malformed values are rejected.
Numeric values must be JSON numbers. Disabled modules are outside management and do not uninstall
packages, revert settings or disable existing services.

| Module | Typed configuration |
| --- | --- |
| Packages | Enabled flag and up to 64 literal Debian package names |
| Network | Enabled flag, BBR flag and six allowlisted sysctl keys with bounded values |
| Limits | Soft/hard NOFILE and systemd default, 1024–16777216, soft no greater than hard |
| Firewall | Up to 64 named TCP/UDP allow rules, bounded ports, literal IPv4/IPv6 CIDRs or ANY |
| Fail2ban | Enabled flag for the fixed SSH jail baseline |
| Docker | Enabled flag for engine/service/Compose availability |
| Caddy | Domain, actual HTTPS listener port, managed root, gzip/zstd, predefined redirect mode |
| Site | Controlled root/domain and DEFAULT_PLACEHOLDER template version 1 |

There are no shell commands, scripts, package version options, arbitrary file paths or secrets
in profile content. Site roots are restricted to `/var/www/infradesk/<slug>`.

## 6. Packages

The installer adds only missing requested packages and dependencies for enabled modules.
It refreshes apt metadata with a timeout, simulates the transaction and rejects removals,
upgrades of installed packages and non-installed package states that cannot be interpreted safely.
The simulation repeats immediately before `apt-get install --no-upgrade --no-remove`.
Simulation is a best-effort guard, not an apt database transaction; transport uncertainty is
recorded as UNKNOWN. Existing usable Docker/Caddy binaries are retained.

When Caddy has no distro candidate, InfraDesk uses its fixed official signed stable repository.
It first bootstraps the distro CA package when necessary, then writes only the fixed InfraDesk
key/source paths and refuses conflicting contents. The bundled public key comes from
`https://dl.cloudsmith.io/public/caddy/stable/gpg.key`, primary fingerprint
`65760C51EDEA2017CEA2CA15155B6D79CA56EA34`, SHA-256
`783dfee04b19e851a928cd87b34710213ebbe7628f98d9f34595ab83be578c00`.
No installer is fetched and piped into a shell; global apt sources are not replaced.

## 7. Network

InfraDesk renders `/etc/sysctl.d/99-infradesk.conf` with an ownership marker and deterministic
content. BBR adds the congestion-control setting and a default fq qdisc unless explicitly configured.
Only this file is loaded with `sysctl -p`; unrelated drop-ins are not rewritten. Compliance checks
both persistent managed content and actual kernel values.

## 8. Limits

Fixed marked files under `limits.d` and `system.conf.d` set soft/hard NOFILE and the systemd
manager default. Their hashes and ownership are checked before replacement. Systemd is reloaded;
observation reads its real soft/hard defaults. The UI explains that limits affect future processes;
existing application processes are not restarted automatically.

## 9. Firewall safety

InfraDesk uses supported UFW allow-rule forms and resource/rule ownership comments.
Unsupported rules block planning. Foreign equivalent allows are reused without retagging;
foreign rules are never removed. Owned tuple collisions block rather than transfer ownership.
Deletion includes the full typed rule and exact nonempty ownership comment, never a mutable rule
number. No reset or global default-policy change is performed.

The actual SSH client address and server port are read from SSH_CONNECTION. Literal CIDR
coverage must prove a desired management allow; contradictory denies block. Management allows
are added first. A fresh authenticated, pinned SSH canary must pass before old owned rules are
removed; inactive UFW is enabled only after the first canary and checked again. Rules are refreshed
immediately before additions and deletions. This reduces races but does not claim transactional
control over other administrators' concurrent firewall commands.

## 10. Docker

Missing engine/Compose capabilities add bounded distro dependencies. Existing usable engines
are preserved; unsafe apt dependency conflicts fail closed. The Docker step enables the service
and checks Compose availability. Final observation independently checks the binary, service and
Compose capability. No remote Docker installer script is used.

## 11. Caddy safety

An existing unmarked Caddyfile blocks planning. The only fresh-install exception requires the
same run's successful package-install step to prove that the actual Caddyfile matches dpkg's
conffile metadata and record its exact SHA-256. It is not generic adoption of an unmanaged file.
Candidates are validated by Caddy before atomic replacement. Known reload failure restores only
a known predecessor while the active file still matches our candidate, then reactivates it.
Timeout, truncation or disconnect never triggers a blind rollback.

All managed files use exclusive SSH-user staging at mode 0700, SFTP creation at 0600, root-owned
same-filesystem candidate staging, safe parent/owner/link/mode/hash checks and guarded atomic
replacement. Exact owned artifacts are cleaned; recursive deletion is not used.

The Caddyfile explicitly listens at `https://<domain>:<localHttpsPort>`; redirects include that
port. Read-only checks observe service state, managed config and real Caddy-owned listeners.
Foreign listeners block planning. DNS checks only report unresolved/mismatched targets; they do
not change DNS. Verification does not claim that a public certificate has been issued.

## 12. Placeholder site

DEFAULT_PLACEHOLDER version 1 renders a deterministic escaped HTML page into the controlled
managed root. Ownership and exact bytes are verified. Existing unmarked content is not adopted
or removed. Site and Caddy domain/root must agree when the site module is enabled.

## 13. Drift calculation

`ServerProfileDiff` is a pure deterministic comparison of pinned desired content with actual
sanitized facts. It reports enabled module compliance and typed changes, including persistent
and runtime network/limits drift, full firewall tuples, packages, services, site content and Caddy
config/listeners. Disabled modules contribute no changes. Apply results do not overwrite desired
state; later observation derives compliance again.

## 14. Preview

Explicit preview collects fresh facts, saves their identity/hash and creates an immutable reviewed
plan. It presents the human connection/profile names, resource kind, pinned revision, module
changes, missing dependency packages, actual endpoint, warnings, blockers and ten ordered steps.
Plans use the same 24-hour lifecycle as Stage 25A; PLANNED rows are hidden from execution history
and abandoned plans are cleaned in bounded batches. Blocking plans cannot be approved.

## 15. Integration with Stage 25A

SERVER_PROFILE_APPLY uses the existing durable plan/start/request-id/worker/lease engine:
PREFLIGHT, INSTALL_PACKAGES, CONFIGURE_NETWORK, CONFIGURE_LIMITS, CONFIGURE_FIREWALL,
CONFIGURE_FAIL2BAN, CONFIGURE_DOCKER, DEPLOY_SITE, CONFIGURE_CADDY, VERIFY.
Stage 25A keeps its original two-step order. Positions are persisted, not inferred from enum order.
Only module steps may skip with NOT_MANAGED or ALREADY_COMPLIANT; PREFLIGHT/VERIFY cannot skip.
Known negative outcomes are FAILED; transport or mutation uncertainty is UNKNOWN. Neither is
automatically replayed. Every boundary checks the pinned assignment/source; the first mutation
also refreshes the reviewed facts. VERIFY independently repeats readiness and profile observation.
Publication locks the resource and fences on the actual parent run's token/deadline before
writing the linked observation. Success requires all ten steps and successful independent VERIFY.
Defaults are a bounded 300-second step timeout and 900-second claim lease, with heartbeat renewal.

## 16. UI

Server profiles live inside Configurations alongside file configurations, with structured module
forms, immutable revision history/comparison and assigned-server names. Resource Automation has
explicit assignment/pin update, removal, Observe, Preview and Apply actions. Profile apply and
readiness use one run journal with localized run kinds/steps. Active work is polled until terminal;
request identity is reused after an uncertain approval response. RU/EN labels explain ownership,
disabled management, future-process limits and real listener ports. Read-only users can inspect
profile data, while mutation and execution controls follow organization permissions.

## 17. API

All routes are under `/api/v1/organizations/{organizationId}`:

| Method | Path | Purpose |
| --- | --- | --- |
| GET/POST | `/server-profiles` | List/create |
| GET/DELETE | `/server-profiles/{id}` | Detail with revisions/assignments; archive |
| POST | `/server-profiles/{id}/revisions` | Append immutable revision |
| PUT/DELETE | `/resources/{id}/server-profile` | Assign/update pin; remove |
| GET | `/resources/{id}/server-profile/automation` | Stored automation state |
| POST | `/resources/{id}/server-profile/observe` | Explicit read-only observation |
| POST | `/resources/{id}/server-profile/preview` | Reviewed apply plan |
| POST | `/provisioning/runs` | Approve `{planId, requestId}` through the shared engine |

Reads require organization read. Management requires manageConfigurations; observation, preview
and approval additionally require executeOperations. Bodies are bounded and closed; tenant and
revision/source checks are repeated server-side. There is no browser shell endpoint.

## 18. Audit

Atomic events are SERVER_PROFILE_CREATED, SERVER_PROFILE_REVISION_CREATED,
SERVER_PROFILE_ASSIGNED, SERVER_PROFILE_UNASSIGNED, SERVER_PROFILE_ARCHIVED and
SERVER_PROFILE_APPLY_REQUESTED. No-op operations emit no duplicate event; idempotent approval
emits one event. Worker steps do not add audit noise. Events contain identities, not content or
credentials. V47 extends the database constraints together with the domain enum.

## 19. Tests and evidence

Backend `testFull` with real isolated PostgreSQL passed: **992 total, 974 passed, 18 skipped,
zero failures** on local JDK 25. CI uses JDK 21. Coverage includes immutable/concurrent revisions,
intent-only pinning, tenant/inactive/archive policies, stale approval and observation races,
ten-step execution, independent verification, idempotency, UNKNOWN without replay, actual lease
fencing and profile status beyond a page of readiness history. HTTP tests cover permissions,
DB-only reads, strict input and human preview fields. Pure tests cover all eight modules and CIDRs.
Real in-process SSH tests verify literal argv and bounded stdout/stderr, including zero-byte capture.

Executable Linux helpers were tested in a disposable Ubuntu 22.04 container with no network:
exclusive staging, atomic commit, guarded rollback, unmanaged preservation, symlink/writable/
hardlink refusal, concurrent edits, read-only probes and apt simulation failure modes. The
reproducible harness lives in `src/test/resources/server-profile-safety` and runs in backend CI.
Recording-transport tests cover validation before commit, known reload failure, disconnect without
rollback, nonroot sudo argv, foreign-rule preservation and canary failure before deletion.
Separate disposable tests validated the rendered Caddyfile with real Caddy and the fixed signed
repository bootstrap on Ubuntu 22.04. These are not a claim of deployment to a real VPS or public
certificate issuance. Windows skips 18 existing tests requiring POSIX; Linux CI runs those tests.

## 20. Frontend build

The frontend's full test suite passed: **626 tests across 75 files**. Its production build passed.
The primary reviewed the worker's changes and verified the complete suite after final presentation
corrections. Revision comparison uses localized values and readable firewall tuples, not JSON.

## 21. Backend packaging and CI

`sbt Universal/stage` packages the backend. Full CI also builds production images and tests real
backup/restore upgrade boundaries through schema 47 plus self-hosted install/update/restore on
Ubuntu 22.04 and 24.04. An annotated release tag is created only after every exact-commit CI job
passes. Release automation publishes pinned images, the installer bundle, manifest and checksums.

## 22. Left for Stage 25C

Remnawave Node installation/creation, registration secrets, automatic binding/assignment,
fleet/bulk rollout, automatic reconciliation, custom scripts, package dist-upgrade, DNS provider
integration and certificate-provider automation beyond normal Caddy behavior are intentionally
outside Stage 25B.

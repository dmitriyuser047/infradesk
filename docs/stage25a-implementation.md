# Stage 25A — Provisioning Engine

Stage 25A adds an explicit, read-only readiness workflow for an existing active `NODE`:
review a persisted plan, approve it with a request UUID, then inspect its durable run journal.
It installs no software and changes no server configuration.

## Model and persistence

`ProvisioningInputSnapshot` pins the organization, resource type/kind, SSH connection ID and
connection version, schema version, run kind and ordered steps. It contains no credentials.
The run records request/actor, state, current step, timestamps, safe failure code/message and
worker claim token/deadline. Each step records its stable ID, position, attempt, state,
timestamps, sanitized facts, verification result and output truncation flag.

Migration V45 creates `provisioning_run` and `provisioning_run_step`, tenant foreign keys,
an organization/request uniqueness constraint and a partial unique index preventing two
queued/running runs for one resource. Previously released migrations remain unchanged.

## Execution and recovery

The worker claims queued rows with `FOR UPDATE SKIP LOCKED` and processes a bounded batch
in parallel. SSH work runs outside database transactions. Every write locks the parent run
and then checks its token and actual lease deadline, including time spent waiting for a lock.
Failure updates are one transaction; losing the lease rolls the transaction back.

`PREFLIGHT` checks supported OS/architecture, root or non-interactive sudo, memory, free disk,
required commands, Docker presence and systemd availability. Docker absence is allowed.
`VERIFY` independently repeats the observation and requires an explicit successful result.
Known failed requirements produce `FAILED`; timeout, disconnect, truncation or an expired
lease produce `UNKNOWN`. Pending steps are skipped. Unknown work is never claimed for replay.

Both steps use the application's existing SSH client, encrypted credential provider and
pinned host identity. Commands are fixed in the backend. Output is bounded and parsed into
allowlisted facts; raw stdout/stderr and credentials are not persisted in the journal.

## API, authorization and audit

All endpoints are under `/api/v1/organizations/{organizationId}`:

- `POST /provisioning/plan`: exactly `{ resourceId }`.
- `POST /provisioning/runs`: exactly `{ planId, requestId }`.
- `GET /provisioning/runs/{runId}`: run and ordered step journal.
- `GET /resources/{resourceId}/provisioning/runs`: recent resource runs.
- `GET /provisioning/runs`: recent organization runs.

Planning and approval require both `ManageConfigurations` and `ExecuteOperations`.
History requires `ReadOrganization`. Requests reject unknown fields, including commands
and scripts. Repeating one approval returns the same run; reusing its request ID for a
different plan is rejected. Enqueue and `PROVISIONING_RUN_REQUESTED` audit are atomic;
concurrent duplicate approvals record one audit event.

## Interface and configuration

Server Detail contains an Automation section with plan review, explicit approval, recent
runs, step states and sanitized facts. Errors and the unknown-result explanation are
localized in Russian and English. Polling occurs every three seconds only while a run is
queued/running. Navigating to another target resets the reviewed plan; an uncertain HTTP
approval retry preserves its request UUID.

Worker settings and deployment variables are documented in [provisioning.md](provisioning.md).
Disabling provisioning rejects plans/approvals and leaves queued and expired work untouched.

## Verification boundary and subsequent stages

Automated coverage includes real PostgreSQL concurrency, tenant isolation, idempotent audit,
target eligibility, lease expiry after lock waiting, worker success/failure/timeout/verification,
disabled runtime and cancellation, supported OS parsing, malformed/truncated output, API
authorization and frontend polling/localization. These tests do not claim validation on a
production VPS.

The completed local checks were `sbt testFull` with PostgreSQL integration enabled
(929 passed, 18 skipped, no failures), `npm test` (608 passed in 73 files), and
`npm run build`. Backend packaging and the release CI also validate the distributable.

Server profiles, package installation, system configuration, firewall, Docker installation,
Caddy and placeholder sites belong to Stage 25B. Remnawave installation/registration and
automatic node binding remain outside Stage 25A.

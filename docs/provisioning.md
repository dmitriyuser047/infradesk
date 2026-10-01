# Read-only server readiness checks

InfraDesk can run a bounded baseline readiness check over the SSH connection already associated
with a `NODE` resource. Planning stores the selected server and SSH connection version so the
operator can review the target before approval. Approval enqueues a durable run and is idempotent
for the same plan and request ID.

Stage 25A performs observations only. It does not install packages, create users, change files,
enable services, or alter server configuration. The fixed checks require Debian 12 or Ubuntu
22.04/24.04 on amd64/x86_64, root or non-interactive sudo, at least 512 MiB memory, at least 1 GiB
free disk, shell/basic commands, apt-get, systemctl, and Docker status observation. Docker being
absent is reported as a fact but is not a Stage 25A blocker. The verify step repeats
the readiness observation independently. SSH commands and output are bounded; only allowlisted,
sanitized facts and stable failure codes are stored.

Runs can be planned and approved at `POST /api/v1/organizations/{organizationId}/provisioning/plan`
and `POST /api/v1/organizations/{organizationId}/provisioning/runs`. Run detail is available at
`GET /api/v1/organizations/{organizationId}/provisioning/runs/{runId}` and resource history at
`GET /api/v1/organizations/{organizationId}/resources/{resourceId}/provisioning/runs`.
Planning and approval require both configuration-management and operation-execution permissions;
history is visible to organization readers.

Preview plans are retained for 24 hours. `PLANNED` previews are excluded from both organization
and resource run history; an expired approval returns `PROVISIONING_PLAN_EXPIRED` and requires
a new preview. After cleanup, the deleted draft returns not found. A bounded hourly cleanup
removes only expired `PLANNED` rows and their step rows. Approved, queued, running, and terminal
runs remain in history. Cleanup runs even when SSH provisioning is disabled and performs no
remote work.

An SSH timeout, disconnect, output truncation, or lease uncertainty records `UNKNOWN`. InfraDesk
does not automatically replay an unknown run. Confirm the remote server's state before initiating
another check. Known unsupported platforms and failed readiness conditions are recorded as
`FAILED`. Disabling the subsystem rejects new plans and approvals and stops claiming or recovering
queued work.

The worker defaults are configurable with `INFRADESK_PROVISIONING_ENABLED` (true),
`INFRADESK_PROVISIONING_POLL_INTERVAL_SECONDS` (2), `INFRADESK_PROVISIONING_BATCH_SIZE` (10),
`INFRADESK_PROVISIONING_MAX_CONCURRENCY` (2),
`INFRADESK_PROVISIONING_CLAIM_LEASE_SECONDS` (120), and
`INFRADESK_PROVISIONING_STEP_TIMEOUT_SECONDS` (30). Lease duration must exceed the bounded step
timeout and heartbeat interval. Production Compose forwards these variables to the backend.

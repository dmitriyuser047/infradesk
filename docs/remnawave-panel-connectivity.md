# Remnawave Panel connectivity recovery

Lifecycle version 4 adds a reviewed Panel network source to the existing durable onboarding engine.
V59 is additive after V58. Versions 1, 2 and 3 keep their original snapshots and phase sequences;
a terminal legacy run is recovered through a new immutable plan, never rewritten.

The wizard defaults to AUTO. A tenant-scoped HOST binding matching the integration endpoint's
hostname takes priority; its active SSH configuration supplies the managed server address.
Without that binding, the endpoint hostname is resolved through bounded A and AAAA queries.
Results are exact canonical /32 and /128 candidates, not confirmed outbound identity. API ingress
and Panel egress can differ behind NAT, a proxy or CDN. Advanced MANUAL sources support that case.
Operator/browser/X-Forwarded-For/SSH_CONNECTION addresses are never source evidence.

AUTO refuses unresolved DNS, reserved addresses, more than 32 candidates and public /0 rules.
Private sources require the existing explicit private-destination deployment policy. DNS cache is
disabled for this purpose: preview and start must observe fresh answers. Mode, method, candidates,
confidence and endpoint fingerprint are pinned in the reviewed snapshot. A changed source blocks
start and the worker's validation/mutation boundary with `REMNAWAVE_ONBOARDING_SOURCE_CHANGED`.
Unresolved preview has `REMNAWAVE_PANEL_SOURCE_UNRESOLVED` and cannot create a node.

## Controlled recovery

An exact Panel UUID with a healthy OWNED_COMPLETE installation produces
REPAIR_PANEL_CONNECTIVITY. Its phases are VALIDATE, PREPARE_SERVER, RESOLVE_PANEL_SOURCE,
ADD_PANEL_SOURCES, WAIT_FOR_PANEL, FINALIZE_PANEL_SOURCES, SYNC_INVENTORY, BIND_RESOURCE,
SET_DESIRED_STATE and FINAL_VERIFY. It has no CREATE_NODE, DELETE_NODE, INSTALL_NODE or START_NODE.
The UUID, installation owner, image and correlation identity remain the previous identities.

The SSH adapter checks the exact managed resource/UUID namespace and the reviewed union of old
and new sources. Foreign rules on the node port, unreviewed owned sources or unsupported shapes
produce `REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY`; automatic broadening is prohibited.
It adds the reviewed union, preserves SSH access and waits for an enabled, connected exact Panel
node. Confirmation is journalled before removing old sources. A crash after either mutation is
reconciled against the reviewed union/final state, using the same fenced run and UUID.

On a proven disconnected timeout, FINALIZE_PANEL_SOURCES restores the previous reviewed rules
and records `REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT` with a closed sanitized connectivity finding.
New onboarding removes only its own unconfirmed candidate rules, keeping the created UUID and
installation available for later recovery. An unavailable/mismatched Panel observation is UNKNOWN,
with candidate compensation and no fabricated connectivity proof. Lost confirmation after a
confirmed finalization requires fresh review rather than a blind rollback.

## Verification and operation

Tests exercise production DNS through local UDP fixtures (A, AAAA, NXDOMAIN, TTL changes, bounds
and private policy), real SSH command adapters for exact add/commit/rollback and foreign-rule guards,
worker confirmation/timeout/observation-loss/crash recovery, routes and the AUTO/advanced UI.
PostgreSQL tests cover tenant-scoped HOST selection, V3-to-V4 recovery, phase admission, idempotency,
fencing, closed findings and historical migration retention. Existing production RecoveryProbe
real-shell tests continue to run separately from adapter mocks.

Source discovery performs one bounded set-based binding query and at most two concurrent DNS
queries, outside database transactions. Queries have a 3-second resolver timeout and a 5-second
overall deadline. Existing bounded SSH sessions, durable claims, heartbeats, resource locking,
permissions and tenant-scoped repository guards remain the execution route.

Commit deployments requested without CI do not constitute exact-SHA CI acceptance. Deployment
requires a quiescent backup, verified image revision labels, schema V59 and application readiness.
Live acceptance additionally requires the original node UUID to be connected and final verification
to succeed. DNS candidates alone do not establish acceptance; unreachable node DNS or distinct
Panel egress may still require correcting the external network configuration or explicit MANUAL sources.

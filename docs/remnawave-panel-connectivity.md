# Remnawave Panel connectivity recovery

Lifecycle version 5 extends the existing durable onboarding engine with passive source observation
and an explicit Node address decision. V60 is additive after V59. Versions 1 through 4 keep their original snapshots and phase sequences;
a terminal legacy run is recovered through a new immutable plan, never rewritten.

The wizard defaults to AUTO. A tenant-scoped HOST binding matching the integration endpoint's
hostname takes priority; its active SSH configuration supplies the managed server address.
Without that binding, the endpoint hostname is resolved through bounded A and AAAA queries.
Results are exact canonical /32 and /128 candidates, not confirmed outbound identity. API ingress
and Panel egress can differ behind NAT, a proxy or CDN. A disconnected DNS attempt now proceeds
to bounded passive inbound SYN discovery before offering a technical-unavailability manual fallback.
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
ADD_PANEL_SOURCES, WAIT_FOR_PANEL, OBSERVE_PANEL_SOURCE, ADD_OBSERVED_PANEL_SOURCE,
VERIFY_OBSERVED_PANEL, FINALIZE_PANEL_SOURCES, SYNC_INVENTORY, BIND_RESOURCE,
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

## Passive source observation

The production SSH adapter uses Ubuntu nftables and a separate InfraDesk-owned `inet` table,
scoped by run/resource/UUID/port and an exact ownership marker. It installs no packet ACCEPT/DROP
rule and never enables global UFW logging. Its prerouting chain records SYN sources only for the
managed TCP port; existing firewall decisions remain effective. tcpdump is not required.

Observation lasts at most 60 seconds from the durable phase start, including recovery. Two bounded
dynamic sets hold IPv4/IPv6 source evidence; more than one source or more than 64 matching packets
returns AMBIGUOUS/MANUAL_ONLY. One source yields only an exact /32 or /128 AUTO_OBSERVED candidate.
The adapter enforces the existing private-source policy and returns closed sanitized findings.
No traffic yields NO_TRAFFIC, without fabricating an outbound IP or guessing a subnet.

A transient systemd cleanup timer is armed before the atomic nft batch. The timer executes the
same ownership-checked cleanup after the observation lease plus 15 seconds, surviving SSH loss or
backend termination. Cleanup validates the entire owned table shape and leaves foreign artifacts
untouched. Evidence is returned only after table and timer cleanup succeeds; uncertain cleanup
blocks the workflow. Reboot clears this transient instrumentation.

The fenced run persists candidate evidence before any temporary allow. The existing firewall
engine adds only the reviewed old/DNS/observed union, then confirms the exact enabled Panel UUID.
Successful confirmation promotes the observed candidate and retires the old sources; otherwise
the next durable finalization phase restores the prior rules. Observation and completion become
immutable database fields. A successful compensation can coexist with a FAILED connectivity run.
WAIT_FOR_PANEL displays Connected, Not connected or Not confirmed, rather than SUCCEEDED for a
disconnected timeout. The cleanup result is displayed separately from the connectivity failure.

## Node address

Standard onboarding defaults to PUBLIC_IP. The authenticated, host-key-pinned selected server
supplies its public interface addresses through a bounded read-only probe; resource hostname,
browser headers and SSH_CONNECTION are not evidence. A single public IPv4 is preferred, otherwise
a single public IPv6 is used. Multiple eligible addresses fail closed. Missing resource DNS does
not prevent IP onboarding. DOMAIN is an explicit user choice: fresh A/AAAA results must all match
the confirmed server addresses. Preview, start and worker validation recheck the evidence outside
database transactions.

Healthy existing nodes whose reviewed address changes keep the same UUID, correlation and local
installation. Their immutable recovery plan inserts UPDATE_NODE_ADDRESS before connectivity phases.
The typed provider performs one PATCH containing UUID and address only. Its capability is enabled
only for reviewed Remnawave 2.8.0 and 3.4.4 update contracts with pinned source hashes. Unknown
mutation results are reconciled by reading the exact UUID; a mutation is never blindly repeated.
DELETE_RECREATE still verifies and deletes the previous address identity before creating a new one.

## Verification and operation

Tests exercise production DNS through local UDP fixtures (A, AAAA, NXDOMAIN, TTL changes, bounds
and private policy), explicit Node-domain DNS, real SSH command adapters for exact add/commit/rollback and foreign-rule guards,
worker confirmation/timeout/observation-loss/crash recovery, routes and the AUTO/advanced UI.
The opt-in PanelSynProbeShellSpec executes the compiled production Python on Ubuntu 24.04 against
real kernel nftables and injected inbound IPv4/IPv6 SYN. Only systemd's DBus boundary is replaced
by a detached lease fixture; cleanup itself is the production program. It proves existing DROP
behavior, wrong-port exclusion, ambiguity/packet bounds and cleanup after killing the probe.
PostgreSQL tests cover tenant-scoped HOST selection, legacy-to-V5 recovery, phase admission, idempotency,
fencing, closed findings and historical migration retention. Existing production RecoveryProbe
real-shell tests continue to run separately from adapter mocks.

Source discovery performs one bounded set-based binding query and at most two concurrent DNS
queries, outside database transactions. Queries have a 3-second resolver timeout and a 5-second
overall deadline. Existing bounded SSH sessions, durable claims, heartbeats, resource locking,
permissions and tenant-scoped repository guards remain the execution route.

Commit deployments requested without CI do not constitute exact-SHA CI acceptance. Deployment
requires a quiescent backup, verified image revision labels, schema V60 and application readiness.
Live acceptance additionally requires the original node UUID to be connected and final verification
to succeed. DNS candidates alone do not establish acceptance. No observed inbound traffic can
still indicate external routing/filtering or an inactive Panel connection attempt; it is diagnosed
without broadening the firewall or inventing a source address.

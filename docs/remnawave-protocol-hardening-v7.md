# Remnawave protocol onboarding: V7 hardening

This change prepares code for live acceptance. Automated tests do not prove public ACME
issuance, a live Panel connection, an authenticated Shadowsocks session or a QUIC handshake.
Production deployment and live acceptance are reported separately from automated validation. No release or tag is created.

## Immutable workflow

Fresh generated-protocol plans use lifecycle 7. Existing-profile plans remain lifecycle 5, except an explicitly reviewed new-config replacement, which uses lifecycle 7.
Persisted lifecycle 1–6 snapshots and journal sequences are unchanged. V63 is additive;
V61 and V62 are not rewritten. Terminal V6 recovery creates a new reviewed V7 plan
with the previous Node/profile receipt rather than creating another Panel Node.

The generated workflow contains these durable boundaries:

```
VALIDATE
PREPARE_SERVER
PROTOCOL_PREFLIGHT
[controlled previous-node retirement when required]
RESOLVE_PANEL_SOURCE
[ISSUE_TLS for HTTP-01]
CREATE_PROTOCOL_PROFILE
CREATE_NODE
GET_INSTALLATION_DATA
CONFIGURE_NODE_FIREWALL
[INSTALL_TLS]
CONFIGURE_CLIENT_FIREWALL
INSTALL_NODE
START_NODE
VERIFY_LOCAL_NODE
WAIT_FOR_PANEL
[bounded passive Panel-source observation and verification]
FINALIZE_PANEL_SOURCES
VERIFY_PROTOCOL
SYNC_INVENTORY
BIND_RESOURCE
SET_DESIRED_STATE
FINAL_VERIFY
```

Existing-node connectivity repair omits creation and installation. Successful connectivity
finalization precedes protocol runtime verification; failed verification cannot synchronize
inventory, bind a resource or set desired state.

## Read-only preflight and races

Preview and PROTOCOL_PREFLIGHT observe UDP for Hysteria2 and TCP plus UDP for
Shadowsocks. Public evidence contains only port, transport and one typed state:
FREE, OWNED_EXPECTED, FOREIGN_LISTENER, FIREWALL_CONFLICT, OBSERVATION_UNKNOWN.
Unknown output, truncation, command failure or unavailable ownership proof blocks writes.
An occupied fresh client port blocks TLS issuance, profile/Node creation and client rules.

OWNED_EXPECTED requires the production installation ownership probe, the exact managed
container's Compose label, pinned image and host network, and every observed socket PID
in that container's Xray process list. A name, running container or listening socket alone
does not establish ownership. The probe observes only; it never stops a listener.

Port-scoped UFW parsing ignores foreign rules only if explicit destination ports or transport
prove them unrelated. Unsupported policy which may affect the port fails closed. Relevant
deny/reject and conflicting managed rules block. Foreign equivalent allows are retained.
Each missing client allow is preceded by a fresh socket/ownership/policy observation.
This narrows the observation/mutation race; there is no atomic Linux socket-plus-UFW lock.
A subsequently arriving foreign listener is still rejected by runtime verification.

Client ANY rules remain confined to `infradesk:remnawave-client:<resource>:<node>:tcp|udp`.
Management-port rules and bounded passive SYN discovery retain exact /32 or /128 sources.
There is no reset, foreign rule deletion, subnet guessing or global logging.

HTTP-01 preview and the durable preflight also check TCP/80 and relevant policy. The
issuance-time port check, pinned Certbot image and independent cleanup lease remain.

## TLS identities and filesystem asset lifetime

Expired PLANNED cleanup locks the selected plans with FOR UPDATE SKIP LOCKED. In the
same transaction it removes those plans and deletes their HTTP-01 issuance identities only
when no onboarding row anywhere references the UUID and no certificate row exists.
Terminal history, other preview references and issued/imported certificate versions pin it.
Identity advisory locks serialize reservations, issuance, imported references and cleanup;
plan row locks fence concurrent start. Identity UUIDs cannot be reassigned across resources.

`/var/lib/infradesk/remnawave/tls/<certificateUUID>` is a reusable immutable certificate
version asset, not a Node installation directory. Its resource/certificate ownership marker,
root-only metadata and content hashes prevent overwrite/adoption. Node retirement deletes
only the proven Node installation and its exact rules; it deliberately retains referenced
TLS versions so recovery and image rollback can mount the same version. Historical
onboarding references and the encrypted certificate row pin these assets indefinitely.
There is no unreferenced-version garbage collector or automatic certificate renewal here.

Owned ACME state is similarly keyed by the reserved certificate UUID and retained for
issuance reconciliation. Ephemeral issuer containers, input firewall rules and cleanup timers
have bounded lease cleanup. Unknown issuance retains its durable identity for diagnosis;
read-only preview cleanup never removes remote owned state. Installation `.owner`/`.staging`
artifacts are classified and retired through the existing controlled ownership engine.

Private keys stay in the dedicated redacted material type, encrypted persistence and owned
files. They never enter snapshots, journals, audit, errors or shell arguments. Unexpected
internal errors log exception class and stack frames with run/integration/resource/phase;
exception messages, response bodies and nested causes are omitted.

## Reviewed Panel contract

Panel 3.4.5 is reviewed only at commit
`010b365ab1fabea01192b5e6ade4e98e66ee1dbd`. The seven files in the compatibility
catalog were independently downloaded and their SHA-256 values matched reviewed 3.4.4
commit `b22970cc88481a7e278b5767721672a18f8b2ada` byte for byte.

Source: [upstream 3.4.5 commit](https://github.com/remnawave/backend/commit/010b365ab1fabea01192b5e6ade4e98e66ee1dbd).
Create, InstallationData, Status, ConfigProfile, CreateReconciliation, AddressUpdate and
ProtocolProfileCreate are reviewed capabilities. CreateIdempotency is intentionally absent:
the reviewed create contract supplies no idempotency guarantee. Lost create responses use
exact read-only reconciliation and never a second POST. 3.4.6 and mismatching 3.4.5 commits
remain unconfirmed.

PUBLIC_IP remains the default. Management resource hostname DNS is not required. Explicit
DOMAIN mode needs successful DNS verification; HTTP-01's TLS domain has a separate DNS check.

## Controlled recreation with new settings (V64)

Fresh wizard uses a separate `RECREATE_WITH_NEW_CONFIG` action when strict same-intent recovery fails and exactly one persisted tenant/integration/resource/connection ownership chain is available. Exact previous UUID must be `CONFIRMED_NOT_FOUND`; conflicting provider candidates, foreign/unknown local ownership and active/unrelated bindings block the plan. Ordinary `RECOVER` preserves its strict input contract and cannot switch protocols.

Recovery pins `PreviousNodeInstallation` (old name/address/management port/protocol/certificate and optional inactive inventory binding), previous UUID, correlation, owner, image and CIDRs. New desired input, TLS identity, generated profile receipt and correlation remain separate. The previous receipt is never copied. Blocked read-only observations retain the old correlation; a new mutation identity is minted only with an executable immutable plan.

Before new TLS/profile/Node creation the durable sequence adds `CONFIRM_PREVIOUS_NODE_ABSENT`, optional `UNBIND_PREVIOUS_NODE`, optional `RETIRE_PREVIOUS_CLIENT_FIREWALL`, existing `RETIRE_NODE_FIREWALL`/`RETIRE_LOCAL_NODE`, and `VERIFY_PREVIOUS_RETIRED`. There is no provider DELETE for an absent UUID. Pre-retirement protocol checks allow an exactly proved old Xray listener and old client policy; post-retirement checks require free management/client ports, absent old installation/firewall and absent old client rules. Each retirement repeats provider absence and remote ownership proof.

Start requires the existing `confirmRecreate` contract and rechecks provider/local evidence, binding and HTTP-01 DNS outside the short DB transaction. Stale exact inactive binding/desired state removal uses existing audited services under claim fencing; history is preserved, and later sync/bind targets only the new UUID. Migration V64 protects pinned source metadata, phase order, immutable receipt/history, inactive binding removal and fleet replacement admission. Existing V1-V7 plans retain their original phase sequences unless they explicitly carry the new action.

The UI names the new action, explains the old/new identity, stale binding and irreversible retirement boundary, and rechecks the fresh desired input without invoking strict RECOVER. After retirement, a new creation failure does not restore the old configuration. Live acceptance requires an actual confirmed product run ending in SUCCEEDED; automated tests alone do not establish that result.

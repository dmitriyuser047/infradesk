# Remnawave protocol onboarding

Implemented through the existing reviewed preview/start workflow and durable onboarding worker. This document describes the delivered behavior, not a proposal.

## User flow

1. Open the Remnawave integration and choose **Add node**.
2. Select a managed server. Its confirmed public IP is the default Node address; an explicit domain address still requires DNS verification.
3. Choose **Shadowsocks**, **Hysteria2**, or an existing Panel profile. Generated profiles are available only for the reviewed Panel 3.4.4 contract.
4. For Shadowsocks, choose a client port and supported AEAD method. For Hysteria2, enter the TLS domain and choose HTTP-01 or certificate import.
5. HTTP-01 requires the domain to resolve to the managed Node, free TCP/80, an email and explicit acceptance of the ACME terms and temporary port access. Import requires a trusted PEM chain and matching unencrypted PKCS#8 key, valid for at least seven more days.
6. Review the server preparation, protocol, TLS and firewall changes, then start once.
7. Observe the five progress groups and expandable durable journal. Connection observation shows disconnected outcomes separately from successful cleanup.
8. On success, the Node is running, connected and enabled in Panel, its generated profile matches the stored receipt, its Xray socket is present, inventory is synchronized and desired state is verified.

## Protocol presets

Hysteria2 uses UDP, TLS with h3 ALPN and version 2. QUIC uses BBR/standard, idle timeout 30 seconds, keepalive 10 seconds, stream windows 2/4 MiB and connection windows 8/16 MiB. Path MTU discovery remains enabled. Certificate paths use the reviewed read-only Xray SSL mount. No customer domain is hardcoded.

Shadowsocks supports chacha20-ietf-poly1305, aes-128-gcm and aes-256-gcm, TCP and UDP, and HTTP/TLS/QUIC sniffing. Both presets retain DIRECT/BLOCK outbounds and block private networks/domains and BitTorrent. Client credentials are managed by Remnawave rather than stored in the preset.

## Execution and ownership

Lifecycle V7 adds PROTOCOL_PREFLIGHT before provider writes and moves VERIFY_PROTOCOL before inventory synchronization; persisted V6 order remains unchanged. See [V7 hardening](remnawave-protocol-hardening-v7.md). The existing onboarding workflow adds ISSUE_TLS, CREATE_PROTOCOL_PROFILE, INSTALL_TLS, CONFIGURE_CLIENT_FIREWALL and VERIFY_PROTOCOL where applicable. V61/V62 are additive migrations; legacy snapshots and phase sequences retain their meaning.

A generated profile is created once through the reviewed provider contract. Its UUID, inbound UUID and canonical configuration hash are stored in an immutable fenced receipt before Node creation. A lost provider response is reconciled by exact name, tag and hash; an unconfirmed result does not authorize another POST. Fresh plans do not adopt conflicting profiles. Resource admission and tenant scope use existing locking and permissions.

Certificates are encrypted with authenticated identity and tenant binding. Plan/API/audit records contain references and public metadata, never private keys. Import and audit are atomic. Issued material can be saved only under the live ISSUE_TLS lease. TLS files live in an owned root-only directory outside the Node installation and are mounted read-only without implicit directory creation. Image upgrades carry the immutable certificate reference.

HTTP-01 uses a pinned official Certbot image, an owned ACME directory and container, and an independently registered host cleanup lease. Temporary rules allow only TCP/80 in the UFW input path, are not persisted by UFW, and are removed on completion or crash. Management-port source restrictions remain exact. Existing owned issuance evidence is observed before recovery; uncertain attempts never blindly submit another ACME order.

Client access uses a separate ownership namespace: UDP for Hysteria2; TCP and UDP for Shadowsocks. Retirement touches only matching previous Node rules after the existing recovery proofs. Existing foreign rules are preserved. Runtime verification requires socket ownership by Xray in the exact managed container, not an unrelated listener on the host.

## Verified boundaries

Automated coverage includes generated profile HTTP contracts and lost-response reconciliation, worker sequencing and failure prevention, legacy snapshot compatibility, real PostgreSQL migration/fencing/immutability/tenant constraints, encrypted TLS identity, and production HTTP-01 shell execution with actual Ubuntu netfilter. The latter replaces the external CA/container boundary with a fixture; it does not prove live public CA issuance.

Frontend tests cover the existing workflow and new Shadowsocks/Hysteria2 HTTP-01 choices. Production packaging and deployment are checked separately; skipped CI is not reported as green CI.

## Current limits

- DNS-01 and certificate renewal are not implemented. Expiry and the absence of automatic renewal are displayed.
- Success does not claim authenticated client VPN traffic or an external QUIC handshake. The API explicitly reports clientTrafficVerification=NOT_RUN.
- Host/Squad publication and subscriber assignment are still managed in Remnawave. The success view tells users to assign the new inbound to the intended group.
- This workflow configures new Nodes; changing the protocol of an existing Node is not included.
- Old manual certificate copies are not automatically converted into owned TLS mounts.
- A terminal UNKNOWN before Node creation blocks a competing create. It requires diagnosis; there is no new automatic terminal resume operation for that case.
- Generated profiles are capability gated to reviewed Panel 3.4.4 and exact reviewed 3.4.5. Earlier supported versions retain the existing-profile workflow.

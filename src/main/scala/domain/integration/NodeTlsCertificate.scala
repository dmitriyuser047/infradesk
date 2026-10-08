package ru.bitec.app.ops
package domain.integration

import java.time.Instant
import java.util.UUID

final case class NodeTlsHttp01(certificateId: UUID, email: String) {
  def valid: Boolean = certificateId != new UUID(0,0) && email.length <= 254 &&
    email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}")
}

final case class NodeTlsCertificate(id: UUID, organizationId: UUID, resourceId: UUID,
  domain: String, fingerprint: String, expiresAt: Instant) {
  require(RemnawaveProtocol.domain(domain).contains(domain))
  require(fingerprint.matches("[0-9a-f]{64}"))
}

/** Secret-bearing value deliberately has neither a case-class printer nor a JSON encoder. */
final class NodeTlsMaterial(val certificatePem: String, val privateKeyPem: String) {
  override def toString: String = "NodeTlsMaterial(<redacted>)"
}

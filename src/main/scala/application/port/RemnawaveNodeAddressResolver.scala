package ru.bitec.app.ops
package application.port

import domain.integration.NodeAddressEvidence

/** DNS is checked only for an explicitly selected domain, against authenticated host evidence. */
trait RemnawaveNodeAddressResolver[F[_]] {
  def verifyDomain(domain: String,publicAddresses: List[String]): F[NodeAddressEvidence]
}

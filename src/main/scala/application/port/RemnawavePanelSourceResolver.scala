package ru.bitec.app.ops
package application.port

import domain.integration._

/** Only Panel identities enter this port. Operator, browser and SSH client addresses cannot. */
trait RemnawavePanelSourceResolver[F[_]] {
  def resolve(endpoint: IntegrationBaseUrl, mode: PanelSourceMode, manualSources: List[String],
    managedPanelAddress: Option[String] = None): F[PanelSourceEvidence]
}

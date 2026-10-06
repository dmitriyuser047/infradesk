package ru.bitec.app.ops
package domain.provisioning

/** Shared read model for single-resource and batched onboarding projections. */
object ServerProfileAutomationState {
  def assess(completeAssignment: Boolean, observed: Boolean, compliant: Boolean,
    latest: Option[ProvisioningRunState], linkedToLatest: Boolean, laterManualObservation: Boolean): String = {
    if (!completeAssignment) "UNOBSERVED"
    else if (latest.exists(!_.terminal)) "APPLYING"
    else if (!observed) "UNOBSERVED"
    else latest.filter(_.terminal).filter(s => !laterManualObservation &&
      (s != ProvisioningRunState.Succeeded || !linkedToLatest)) match {
      case Some(ProvisioningRunState.Failed) => "APPLY_FAILED"
      case Some(ProvisioningRunState.Unknown) => "UNKNOWN"
      case Some(ProvisioningRunState.Succeeded) => "WAITING_REFRESH"
      case _ => if (compliant) "COMPLIANT" else "DRIFTED"
    }
  }
}

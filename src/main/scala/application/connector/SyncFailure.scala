package ru.bitec.app.ops
package application.connector

import application.port.ResourceConnectorFailure

final case class SyncFailure(code: String, message: String)

object SyncFailure {
  val Generic: SyncFailure = SyncFailure("SYNC_FAILED", "Synchronization failed")
  val Stale: SyncFailure = SyncFailure("SYNC_STALE", "Synchronization did not finish")

  def from(error: Throwable): SyncFailure = error match {
    case failure: ResourceConnectorFailure => SyncFailure(failure.code, failure.safeMessage)
    case _ => Generic
  }
}

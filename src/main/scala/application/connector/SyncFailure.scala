package ru.bitec.app.ops
package application.connector

import application.port.ResourceConnectorFailure

final case class SyncFailure(code: String, message: String)

object SyncFailure {
  val Generic: SyncFailure = SyncFailure("SYNC_FAILED", "Synchronization failed")

  def from(error: Throwable): SyncFailure = error match {
    case failure: ResourceConnectorFailure => SyncFailure(failure.code, failure.safeMessage)
    case _ => Generic
  }
}

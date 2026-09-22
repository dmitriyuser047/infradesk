package ru.bitec.app.ops
package application.connector

import java.util.UUID

final case class ConnectionSyncNotFound() extends RuntimeException("Connection was not found")
final case class ConnectionSyncInactive() extends RuntimeException("Connection is inactive")
final case class SyncAlreadyRunning() extends RuntimeException("Synchronization is already running")
final case class ConnectionSyncExecutionFailed(sessionId: UUID, underlying: Throwable)
  extends RuntimeException("Synchronization failed", underlying)

package ru.bitec.app.ops
package application.integration

/** The adapter wraps only errors outside the dispatched write and its reconciliation boundary.
  * The original exception is for sanitized internal diagnostics, never a public message/cause. */
private[ops] final class ProtocolProfilePreparationFailed(private[ops] val original: Throwable)
  extends RuntimeException("Protocol profile preparation failed")

package ru.bitec.app.ops
package application.port

import java.time.Instant

trait TimeProvider[F[_]] {
  def now: F[Instant]
}
package ru.bitec.app.ops
package application.port

import java.util.UUID

trait IdGenerator[F[_]] {
  def nextId: F[UUID]
}
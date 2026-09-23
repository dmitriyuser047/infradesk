package ru.bitec.app.ops
package application.port

trait ReadinessCheck[F[_]] {
  def check: F[Unit]
}

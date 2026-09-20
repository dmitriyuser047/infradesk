package ru.bitec.app.ops
package application.port

trait TransactionRunner[F[_], Tx[_]] {
  def run[A](program: Tx[A]): F[A]
}

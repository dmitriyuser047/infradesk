package ru.bitec.app.ops
package application.auth

import cats.effect.IO
import org.mindrot.jbcrypt.BCrypt

trait PasswordHasher {
  def hash(password: String): IO[String]
  def verify(password: String, hash: String): IO[Boolean]
  def verifyDummy(password: String): IO[Unit]
}

final class BCryptPasswordHasher extends PasswordHasher {
  override def hash(password: String): IO[String] =
    IO.blocking(BCrypt.hashpw(password, BCrypt.gensalt(12)))

  override def verify(password: String, hash: String): IO[Boolean] =
    IO.blocking(BCrypt.checkpw(password, hash))

  override def verifyDummy(password: String): IO[Unit] =
    verify(password, BCryptPasswordHasher.dummyHash).void
}

object BCryptPasswordHasher {
  // Precomputed BCrypt cost 12 hash, used for unknown or inactive accounts.
  private val dummyHash: String = "$2a$12$EEAIRJz9x8xzteJ9mufT5eDYrplGe.PamAFOY6tsuf2/CH1MWWNFu"
}

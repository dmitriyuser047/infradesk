package ru.bitec.app.ops
package domain.connection

import java.util.UUID
import scala.util.Try

sealed trait SecretRef
object SecretRef {
  final case class Environment(name: String) extends SecretRef
  final case class Database(id: UUID) extends SecretRef

  def parse(value: String): Either[IllegalArgumentException, SecretRef] =
    if (value.startsWith("env:") && value.drop(4).trim.nonEmpty)
      Right(Environment(value.drop(4).trim))
    else if (value.startsWith("db:"))
      Try(UUID.fromString(value.drop(3))).toEither
        .left.map(_ => new IllegalArgumentException("Invalid database secret reference"))
        .map(Database.apply)
    else Left(new IllegalArgumentException("Unsupported secret reference"))
}

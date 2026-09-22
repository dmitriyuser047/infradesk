package ru.bitec.app.ops
package domain.enviroment

import java.time.Instant
import java.util.UUID

sealed trait EnvironmentKind {
  def code: String
}

object EnvironmentKind {
  val DevCode = "DEV"
  val TestCode = "TEST"
  val StageCode = "STAGE"
  val ProdCode = "PROD"
  val CustomCode = "CUSTOM"

  case object Dev extends EnvironmentKind {
    override val code: String = DevCode
  }

  case object Test extends EnvironmentKind {
    override val code: String = TestCode
  }

  case object Stage extends EnvironmentKind {
    override val code: String = StageCode
  }

  case object Prod extends EnvironmentKind {
    override val code: String = ProdCode
  }

  case object Custom extends EnvironmentKind {
    override val code: String = CustomCode
  }

  def fromCode(code: String): Either[IllegalArgumentException, EnvironmentKind] =
    code match {
      case DevCode => Right(Dev)
      case TestCode => Right(Test)
      case StageCode => Right(Stage)
      case ProdCode => Right(Prod)
      case CustomCode => Right(Custom)
      case value => Left(new IllegalArgumentException(s"Unsupported environment kind: $value"))
    }
}

final case class Environment(
                              id: UUID,
                              organizationId: UUID,
                              projectId: UUID,
                              code: String,
                              name: String,
                              kind: EnvironmentKind,
                              isActive: Boolean,
                              createdAt: Instant,
                              updatedAt: Instant
                            )


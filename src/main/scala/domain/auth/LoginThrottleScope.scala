package ru.bitec.app.ops
package domain.auth

/** What a login-throttle row counts failures for.
  *
  * SOURCE is a client origin (a proxied client IP), which limits one origin spraying many
  * accounts. IDENTIFIER is the normalized login email, which limits a distributed attack on one
  * account and — because it exists whether or not the account does — never reveals account
  * existence through the throttle.
  */
sealed trait LoginThrottleScope {
  def code: String
}

object LoginThrottleScope {
  case object Source extends LoginThrottleScope { override val code: String = "SOURCE" }
  case object Identifier extends LoginThrottleScope { override val code: String = "IDENTIFIER" }

  val All: List[LoginThrottleScope] = List(Source, Identifier)

  def fromCode(code: String): Either[IllegalArgumentException, LoginThrottleScope] =
    All.find(_.code == code).toRight(new IllegalArgumentException(s"Unsupported login throttle scope '$code'"))
}

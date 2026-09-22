package ru.bitec.app.ops
package infrastructure.http

final case class AuthSettings(ttlSeconds: Long, secureCookie: Boolean)

object AuthSettings {
  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, AuthSettings] = {
    val ttl = values.get("INFRADESK_AUTH_SESSION_TTL_SECONDS") match {
      case None => Right(604800L)
      case Some(value) => scala.util.Try(value.toLong).toEither
        .left.map(_ => new IllegalArgumentException("Invalid auth session TTL"))
        .flatMap(seconds =>
          if (seconds > 0) Right(seconds)
          else Left(new IllegalArgumentException("Auth session TTL must be positive"))
        )
    }
    val secure = values.get("INFRADESK_AUTH_COOKIE_SECURE") match {
      case None => Right(false)
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(_) => Left(new IllegalArgumentException("Invalid auth cookie secure setting"))
    }
    for { seconds <- ttl; isSecure <- secure } yield AuthSettings(seconds, isSecure)
  }
}

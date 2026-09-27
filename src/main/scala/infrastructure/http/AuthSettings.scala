package ru.bitec.app.ops
package infrastructure.http

/** Session cookie lifetime and security, plus whether a forwarded client address may be trusted.
  *
  * `trustForwardedFor` is off by default: the backend uses a client address for login throttling
  * only where it is reachable exclusively through the reverse proxy that sets the header. The
  * production topology satisfies that — the backend publishes no host port and nginx overwrites the
  * header with the resolved client address — so the deployment turns it on. Left off, the login
  * source layer is simply absent and identifier throttling still applies.
  */
final case class AuthSettings(ttlSeconds: Long, secureCookie: Boolean, trustForwardedFor: Boolean)

object AuthSettings {
  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, AuthSettings] = {
    val ttl = values.get("INFRADESK_AUTH_SESSION_TTL_SECONDS") match {
      case None => Right(604800L)
      case Some(value) => scala.util.Try(value.toLong).toEither
        .left.map(_ => new IllegalArgumentException("Invalid INFRADESK_AUTH_SESSION_TTL_SECONDS: expected positive integer"))
        .flatMap(seconds =>
          if (seconds > 0) Right(seconds)
          else Left(new IllegalArgumentException("Invalid INFRADESK_AUTH_SESSION_TTL_SECONDS: expected positive integer"))
        )
    }
    val secure = boolean(values, "INFRADESK_AUTH_COOKIE_SECURE")
    val trustForwarded = boolean(values, "INFRADESK_TRUST_FORWARDED_FOR")
    for { seconds <- ttl; isSecure <- secure; trust <- trustForwarded } yield
      AuthSettings(seconds, isSecure, trust)
  }

  private def boolean(values: Map[String, String], key: String): Either[IllegalArgumentException, Boolean] =
    values.get(key) match {
      case None => Right(false)
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(_) => Left(new IllegalArgumentException(s"Invalid $key: expected true or false"))
    }
}

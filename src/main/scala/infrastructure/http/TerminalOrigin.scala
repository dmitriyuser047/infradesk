package ru.bitec.app.ops
package infrastructure.http

import org.http4s.Request
import org.typelevel.ci.CIString

import scala.util.Try

private[http] object TerminalOrigin {
  def sameOrigin[F[_]](request: Request[F]): Boolean = {
    val origin = request.headers.headers.find(_.name == CIString("Origin")).map(_.value)
    val host = request.headers.headers.find(_.name == CIString("Host")).map(_.value)
    val forwardedScheme = request.headers.headers.find(_.name == CIString("X-Forwarded-Proto"))
      .map(_.value.toLowerCase(java.util.Locale.ROOT)).filter(value => value == "http" || value == "https")
    (origin, host) match {
      case (Some(value), Some(hostValue)) =>
        Try(new java.net.URI(value)).toOption.exists { originUri =>
            val scheme = Option(originUri.getScheme).map(_.toLowerCase(java.util.Locale.ROOT))
            val expectedScheme = forwardedScheme.orElse(Some("http"))
            val requestUri = expectedScheme.flatMap(scheme =>
              Try(new java.net.URI(s"$scheme://$hostValue")).toOption)
            requestUri.exists { hostUri =>
              val sameHost = Option(originUri.getHost).map(_.toLowerCase(java.util.Locale.ROOT)) ==
                Option(hostUri.getHost).map(_.toLowerCase(java.util.Locale.ROOT))
              val samePort = effectivePort(originUri) == effectivePort(hostUri)
              Option(originUri.getRawPath).forall(path => path.isEmpty || path == "/") &&
                originUri.getRawQuery == null && originUri.getRawFragment == null && originUri.getRawUserInfo == null &&
                scheme == expectedScheme && sameHost && samePort
            }
        }
      case _ => false
    }
  }

  private def effectivePort(uri: java.net.URI): Int =
    if (uri.getPort >= 0) uri.getPort
    else Option(uri.getScheme).map(_.toLowerCase(java.util.Locale.ROOT)).getOrElse("") match {
      case "https" => 443
      case _ => 80
    }
}

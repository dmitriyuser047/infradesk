package ru.bitec.app.ops
package integration.remnawave

import application.integration.IntegrationError
import integration.http.OutboundDestinationRejected
import java.net.{ConnectException, NoRouteToHostException, SocketTimeoutException, UnknownHostException}
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

object RemnawaveErrors {
  def status(code: Int): IntegrationError = code match {
    case 401 => error("INTEGRATION_AUTH_FAILED")
    case 403 => error("INTEGRATION_FORBIDDEN")
    case 404 => error("INTEGRATION_ENDPOINT_NOT_FOUND")
    case 429 => error("INTEGRATION_RATE_LIMITED")
    case n if n >= 500 => error("INTEGRATION_REMOTE_UNAVAILABLE")
    case _ => error("INTEGRATION_INVALID_RESPONSE")
  }

  def throwable(value: Throwable): IntegrationError = value match {
    case _: OutboundDestinationRejected => error("INTEGRATION_DESTINATION_NOT_ALLOWED")
    case _: TimeoutException | _: SocketTimeoutException => error("INTEGRATION_TIMEOUT")
    case _: SSLException => error("INTEGRATION_TLS_ERROR")
    case _: UnknownHostException | _: ConnectException | _: NoRouteToHostException =>
      error("INTEGRATION_UNREACHABLE")
    case other if other.getCause != null && (other.getCause ne other) => throwable(other.getCause)
    case _ => error("INTEGRATION_UNREACHABLE")
  }

  def error(code: String): IntegrationError = IntegrationError(code, code)
}

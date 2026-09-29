package ru.bitec.app.ops
package domain.integration

import java.net.URI
import java.time.Instant
import java.util.UUID
import scala.util.Try

sealed trait IntegrationProviderType { def code: String }
object IntegrationProviderType {
  case object Remnawave extends IntegrationProviderType { val code = "REMNAWAVE" }
  val All: List[IntegrationProviderType] = List(Remnawave)
  def fromCode(code: String): Either[IllegalArgumentException, IntegrationProviderType] =
    All.find(_.code == code).toRight(new IllegalArgumentException("Unsupported integration provider"))
}

sealed trait IntegrationCapability { def code: String }
object IntegrationCapability {
  case object ConnectivityTest extends IntegrationCapability { val code = "CONNECTIVITY_TEST" }
  case object NodeDiscovery extends IntegrationCapability { val code = "NODE_DISCOVERY" }
  case object HostDiscovery extends IntegrationCapability { val code = "HOST_DISCOVERY" }
  case object ConfigProfileDiscovery extends IntegrationCapability { val code = "CONFIG_PROFILE_DISCOVERY" }
  case object MetricsRead extends IntegrationCapability { val code = "METRICS_READ" }
  case object SafeActions extends IntegrationCapability { val code = "SAFE_ACTIONS" }
  case object ConfigProfileManagement extends IntegrationCapability { val code = "CONFIG_PROFILE_MANAGEMENT" }
}

/** Non-secret URL. Parsed and normalized when written, then parsed again before any request. */
final case class IntegrationBaseUrl private (value: String) {
  def statsEndpoint: URI = URI.create(value + "/").resolve("api/system/stats")
}
object IntegrationBaseUrl {
  def parse(input: String): Either[IllegalArgumentException, IntegrationBaseUrl] = {
    val invalid = new IllegalArgumentException("Invalid integration base URL")
    val raw = input.trim
    Try(new URI(raw)).toOption.filter { uri =>
      Set("http", "https").contains(Option(uri.getScheme).map(_.toLowerCase(java.util.Locale.ROOT)).getOrElse("")) &&
      uri.getHost != null && uri.getHost.nonEmpty && uri.getRawUserInfo == null &&
      uri.getRawQuery == null && uri.getRawFragment == null && uri.getPort <= 65535 &&
      raw.length <= 2048 && !raw.exists(_.isControl)
    }.toRight(invalid).map { uri =>
      val path = Option(uri.getRawPath).getOrElse("").reverse.dropWhile(_ == '/').reverse
      IntegrationBaseUrl(s"${uri.getScheme.toLowerCase(java.util.Locale.ROOT)}://${uri.getRawAuthority}$path")
    }
  }
}

sealed trait IntegrationCredential { def providerType: IntegrationProviderType }
final case class RemnawaveCredential(apiToken: String, caddyApiKey: Option[String]) extends IntegrationCredential {
  override val providerType: IntegrationProviderType = IntegrationProviderType.Remnawave
  def valid: Boolean = apiToken.nonEmpty && apiToken.length <= 8192 && safe(apiToken) &&
    caddyApiKey.forall(value => value.nonEmpty && value.length <= 8192 && safe(value))
  private def safe(value: String): Boolean = !value.exists(_.isControl)
}

final case class Integration(
  id: UUID,
  organizationId: UUID,
  name: String,
  providerType: IntegrationProviderType,
  baseUrl: IntegrationBaseUrl,
  enabled: Boolean,
  secretId: UUID,
  caddyApiKeyConfigured: Boolean,
  createdAt: Instant,
  updatedAt: Instant
)

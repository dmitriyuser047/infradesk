package ru.bitec.app.ops
package infrastructure.config

import application.auth.{AuthRateLimitSettings, BootstrapConfig, SecurityEventSettings}
import application.configuration.{ConfigurationDeploymentSettings, ConfigurationRuleSettings}
import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import infrastructure.database.DatabaseConfig
import infrastructure.http.AuthSettings
import integration.ssh.{EnvironmentSecrets, SecretEncryptionConfig}
import org.http4s.Uri

import scala.concurrent.duration._
import scala.util.Try

final case class HttpConfig(host: Host, port: Port)
/** Automatic observation of enabled integrations. A claim must outlive the attempt it covers. */
final case class IntegrationSyncConfig(
  enabled: Boolean = true,
  pollInterval: FiniteDuration = 5.seconds,
  interval: FiniteDuration = 60.seconds,
  batchSize: Int = 20,
  maxConcurrency: Int = 3,
  claimLease: FiniteDuration = 90.seconds,
  attemptTimeout: FiniteDuration = 30.seconds
) {
  require(maxConcurrency > 0 && batchSize > 0, "Integration sync concurrency and batch size must be positive")
  require(interval > Duration.Zero && pollInterval > Duration.Zero, "Integration sync intervals must be positive")
  require(claimLease > attemptTimeout, "Integration sync claim lease must exceed the attempt timeout")
}
final case class IntegrationActionsConfig(enabled: Boolean = true,
  pollInterval: FiniteDuration = 2.seconds, batchSize: Int = 20, maxConcurrency: Int = 3) {
  require(pollInterval > Duration.Zero && batchSize > 0 && maxConcurrency > 0)
}
/** Reconciliation of desired node state. When disabled, no integration can enter MANAGED_SELECTED. */
final case class IntegrationDesiredStateConfig(enabled: Boolean = true, pollInterval: FiniteDuration = 5.seconds,
  batchSize: Int = 50, maxConcurrency: Int = 4, claimLease: FiniteDuration = 30.seconds) {
  require(pollInterval > Duration.Zero && batchSize > 0 && maxConcurrency > 0 && claimLease > Duration.Zero)
}
final case class IntegrationsConfig(
  requestTimeout: FiniteDuration,
  allowPrivateDestinations: Boolean,
  sync: IntegrationSyncConfig = IntegrationSyncConfig(),
  actions: IntegrationActionsConfig = IntegrationActionsConfig(),
  desiredState: IntegrationDesiredStateConfig = IntegrationDesiredStateConfig(),
  inventoryMaxResponseBytes: Int = 8 * 1024 * 1024,
  inventoryMaxObjects: Int = 10000
) {
  require(inventoryMaxResponseBytes > 0 && inventoryMaxObjects > 0, "Inventory limits must be positive")
}
final case class SchedulerConfig(
  enabled: Boolean,
  pollInterval: FiniteDuration,
  batchSize: Int,
  maxConcurrency: Int,
  claimLease: FiniteDuration
)

/** Outbound notifications. Without a webhook URL the subsystem is off: no deliveries are
  * recorded and no dispatcher runs.
  */
final case class NotificationConfig(
  webhookUrl: Option[Uri],
  pollInterval: FiniteDuration,
  batchSize: Int,
  maxConcurrency: Int,
  claimLease: FiniteDuration,
  requestTimeout: FiniteDuration,
  maxAttempts: Long,
  /** Whether a configured channel may point at a private network.
    *
    * An internal SMTP relay or a webhook receiver inside the perimeter is an ordinary
    * arrangement, and letting any user of the product reach one is an ordinary mistake, so the
    * deployment decides rather than the channel. The loopback interface, link-local addresses
    * and the cloud metadata endpoint stay out of reach either way.
    */
  allowPrivateDestinations: Boolean
) {
  /** Whether the webhook the deployment configures itself exists. Channels are configured in
    * the database and are not what this answers.
    */
  def enabled: Boolean = webhookUrl.isDefined

  // The URL may carry a token, so it never reaches a log line.
  override def toString: String =
    s"NotificationConfig(webhook=${if (enabled) "configured" else "disabled"}, " +
      s"pollInterval=$pollInterval, batchSize=$batchSize, maxConcurrency=$maxConcurrency, " +
      s"claimLease=$claimLease, requestTimeout=$requestTimeout, maxAttempts=$maxAttempts, " +
      s"allowPrivateDestinations=$allowPrivateDestinations)"
}

final case class AppConfig(
  database: DatabaseConfig,
  http: HttpConfig,
  auth: AuthSettings,
  loginRateLimit: AuthRateLimitSettings,
  securityEvents: SecurityEventSettings,
  bootstrap: Option[BootstrapConfig],
  secretEncryption: SecretEncryptionConfig,
  scheduler: SchedulerConfig,
  notification: NotificationConfig,
  sshEnvironmentSecrets: EnvironmentSecrets,
  terminal: TerminalConfig,
  configurationDeployment: ConfigurationDeploymentSettings = ConfigurationDeploymentSettings.Default,
  configurationRules: ConfigurationRuleSettings = ConfigurationRuleSettings(),
  integrations: IntegrationsConfig = IntegrationsConfig(10.seconds, allowPrivateDestinations = false)
)

object AppConfig {
  def load: IO[AppConfig] = IO(sys.env).flatMap(values => IO.fromEither(fromEnvironment(values)))

  def fromEnvironment(values: Map[String, String]): Either[IllegalArgumentException, AppConfig] =
    for {
      database <- DatabaseConfig.fromEnvironment(values)
      http <- parseHttp(values)
      auth <- AuthSettings.fromEnvironment(values)
      loginRateLimit <- parseLoginRateLimit(values)
      securityEvents <- positiveInt(values, "INFRADESK_SECURITY_EVENT_RETENTION_SECONDS", SecurityEventSettings.default.retention.toSeconds.toInt)
      bootstrap <- BootstrapConfig.fromEnvironment(values)
      secretEncryption <- SecretEncryptionConfig.fromEnvironment(values)
      scheduler <- parseScheduler(values)
      notification <- parseNotification(values)
      integrationTimeout <- bounded(values, "INFRADESK_INTEGRATIONS_REQUEST_TIMEOUT_SECONDS", 10, 1, 120)
      integrationAllowPrivate <- parseBoolean(values, "INFRADESK_INTEGRATIONS_ALLOW_PRIVATE_DESTINATIONS")
      integrationSync <- parseIntegrationSync(values)
      integrationActions <- parseIntegrationActions(values)
      integrationDesiredState <- parseIntegrationDesiredState(values)
      inventoryMaxBytes <- bounded(values, "INFRADESK_INTEGRATIONS_INVENTORY_MAX_RESPONSE_BYTES", 8 * 1024 * 1024,
        64 * 1024, 64 * 1024 * 1024)
      inventoryMaxObjects <- bounded(values, "INFRADESK_INTEGRATIONS_INVENTORY_MAX_OBJECTS", 10000, 1, 100000)
      terminal <- TerminalConfig.fromEnvironment(values)
      configurationDeployment <- parseConfigurationDeployment(values)
      ruleEnabled <- parseBoolean(values, "INFRADESK_CONFIGURATION_RULES_ENABLED", default = true)
      ruleInterval <- bounded(values, "INFRADESK_CONFIGURATION_RULE_RECONCILE_SECONDS", 45, 5, 3600)
    } yield AppConfig(database, http, auth, loginRateLimit, SecurityEventSettings(securityEvents.seconds), bootstrap, secretEncryption, scheduler,
      notification, EnvironmentSecrets.fromEnvironment(values), terminal, configurationDeployment,
      ConfigurationRuleSettings(enabled = ruleEnabled, reconcileInterval = ruleInterval.seconds),
      IntegrationsConfig(integrationTimeout.seconds, integrationAllowPrivate, integrationSync,
        integrationActions, integrationDesiredState, inventoryMaxBytes, inventoryMaxObjects))

  private def parseIntegrationDesiredState(
    values: Map[String, String]): Either[IllegalArgumentException, IntegrationDesiredStateConfig] =
    for {
      enabled <- parseBoolean(values, "INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED", default = true)
      poll <- bounded(values, "INFRADESK_INTEGRATIONS_DESIRED_STATE_POLL_INTERVAL_SECONDS", 5, 1, 3600)
      batch <- bounded(values, "INFRADESK_INTEGRATIONS_DESIRED_STATE_BATCH_SIZE", 50, 1, 500)
      concurrency <- bounded(values, "INFRADESK_INTEGRATIONS_DESIRED_STATE_MAX_CONCURRENCY", 4, 1, 32)
      lease <- bounded(values, "INFRADESK_INTEGRATIONS_DESIRED_STATE_CLAIM_LEASE_SECONDS", 30, 5, 3600)
    } yield IntegrationDesiredStateConfig(enabled, poll.seconds, batch, concurrency, lease.seconds)

  private def parseIntegrationActions(values: Map[String, String]): Either[IllegalArgumentException, IntegrationActionsConfig] =
    for {
      enabled <- parseBoolean(values, "INFRADESK_INTEGRATIONS_ACTIONS_ENABLED", default = true)
      poll <- bounded(values, "INFRADESK_INTEGRATIONS_ACTIONS_POLL_INTERVAL_SECONDS", 2, 1, 3600)
      batch <- bounded(values, "INFRADESK_INTEGRATIONS_ACTIONS_BATCH_SIZE", 20, 1, 500)
      concurrency <- bounded(values, "INFRADESK_INTEGRATIONS_ACTIONS_MAX_CONCURRENCY", 3, 1, 32)
    } yield IntegrationActionsConfig(enabled, poll.seconds, batch, concurrency)

  private def parseIntegrationSync(values: Map[String, String]): Either[IllegalArgumentException, IntegrationSyncConfig] =
    for {
      enabled <- parseBoolean(values, "INFRADESK_INTEGRATIONS_SYNC_ENABLED", default = true)
      poll <- bounded(values, "INFRADESK_INTEGRATIONS_SYNC_POLL_INTERVAL_SECONDS", 5, 1, 3600)
      interval <- bounded(values, "INFRADESK_INTEGRATIONS_SYNC_INTERVAL_SECONDS", 60, 10, 86400)
      batch <- bounded(values, "INFRADESK_INTEGRATIONS_SYNC_BATCH_SIZE", 20, 1, 500)
      concurrency <- bounded(values, "INFRADESK_INTEGRATIONS_SYNC_MAX_CONCURRENCY", 3, 1, 32)
      lease <- bounded(values, "INFRADESK_INTEGRATIONS_SYNC_CLAIM_LEASE_SECONDS", 90, 10, 3600)
      attempt <- bounded(values, "INFRADESK_INTEGRATIONS_SYNC_ATTEMPT_TIMEOUT_SECONDS", 30, 5, 600)
      _ <- Either.cond(lease > attempt, (), new IllegalArgumentException(
        "Invalid INFRADESK_INTEGRATIONS_SYNC_CLAIM_LEASE_SECONDS: must exceed INFRADESK_INTEGRATIONS_SYNC_ATTEMPT_TIMEOUT_SECONDS"))
    } yield IntegrationSyncConfig(enabled, poll.seconds, interval.seconds, batch, concurrency, lease.seconds, attempt.seconds)

  private def parseLoginRateLimit(
    values: Map[String, String]
  ): Either[IllegalArgumentException, AuthRateLimitSettings] = {
    val d = AuthRateLimitSettings.default
    for {
      idWindow <- positiveInt(values, "INFRADESK_AUTH_IDENTIFIER_WINDOW_SECONDS", d.identifierWindow.toSeconds.toInt)
      idMax <- positiveInt(values, "INFRADESK_AUTH_IDENTIFIER_MAX_FAILURES", d.identifierMaxFailures)
      idBlock <- positiveInt(values, "INFRADESK_AUTH_IDENTIFIER_BLOCK_SECONDS", d.identifierBlock.toSeconds.toInt)
      srcWindow <- positiveInt(values, "INFRADESK_AUTH_SOURCE_WINDOW_SECONDS", d.sourceWindow.toSeconds.toInt)
      srcMax <- positiveInt(values, "INFRADESK_AUTH_SOURCE_MAX_FAILURES", d.sourceMaxFailures)
      srcBlock <- positiveInt(values, "INFRADESK_AUTH_SOURCE_BLOCK_SECONDS", d.sourceBlock.toSeconds.toInt)
      retention <- positiveInt(values, "INFRADESK_AUTH_THROTTLE_RETENTION_SECONDS", d.retention.toSeconds.toInt)
    } yield AuthRateLimitSettings(idWindow.seconds, idMax, idBlock.seconds, srcWindow.seconds, srcMax,
      srcBlock.seconds, retention.seconds)
  }

  private def parseHttp(values: Map[String, String]): Either[IllegalArgumentException, HttpConfig] = {
    val host = values.get("INFRADESK_HTTP_HOST") match {
      case None => Right(Host.fromString("0.0.0.0").get)
      case Some(value) => Host.fromString(value)
        .filter(_ => validHost(value))
        .toRight(
        new IllegalArgumentException("Invalid INFRADESK_HTTP_HOST: expected a valid host")
      )
    }
    val port = values.get("INFRADESK_HTTP_PORT") match {
      case None => Right(Port.fromInt(8080).get)
      case Some(value) => Try(value.toInt).toOption.filter(_ > 0).flatMap(Port.fromInt).toRight(
        new IllegalArgumentException("Invalid INFRADESK_HTTP_PORT: expected integer 1..65535")
      )
    }
    for { h <- host; p <- port } yield HttpConfig(h, p)
  }

  private def validHost(value: String): Boolean = {
    val dnsOrIpv4 = value.split("\\.", -1).forall(
      _.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    )
    val ipv6 = value.contains(":") && value.matches("\\[?[0-9A-Fa-f:.]+\\]?")
    dnsOrIpv4 || ipv6
  }

  private def parseNotification(values: Map[String, String]): Either[IllegalArgumentException, NotificationConfig] =
    for {
      webhookUrl <- parseWebhookUrl(values)
      pollIntervalSeconds <- positiveInt(values, "INFRADESK_NOTIFICATION_POLL_INTERVAL_SECONDS", 5)
      batchSize <- positiveInt(values, "INFRADESK_NOTIFICATION_BATCH_SIZE", 50)
      maxConcurrency <- positiveInt(values, "INFRADESK_NOTIFICATION_MAX_CONCURRENCY", 5)
      claimLeaseSeconds <- positiveInt(values, "INFRADESK_NOTIFICATION_CLAIM_LEASE_SECONDS", 60)
      requestTimeoutSeconds <- positiveInt(values, "INFRADESK_NOTIFICATION_REQUEST_TIMEOUT_SECONDS", 10)
      maxAttempts <- positiveInt(values, "INFRADESK_NOTIFICATION_MAX_ATTEMPTS", 10)
      allowPrivate <- parseBoolean(values, "INFRADESK_NOTIFICATION_ALLOW_PRIVATE_DESTINATIONS")
      // A lease shorter than a request would let a second dispatcher start the same delivery
      // while the first one is still waiting for the receiver. The dispatcher claims in waves of
      // at most maxConcurrency, so the lease only has to cover one request plus database overhead,
      // not the whole batch.
      _ <- Either.cond(
        claimLeaseSeconds > requestTimeoutSeconds,
        (),
        new IllegalArgumentException(
          "INFRADESK_NOTIFICATION_CLAIM_LEASE_SECONDS must be greater than INFRADESK_NOTIFICATION_REQUEST_TIMEOUT_SECONDS"
        )
      )
    } yield NotificationConfig(webhookUrl, pollIntervalSeconds.seconds, batchSize, maxConcurrency,
      claimLeaseSeconds.seconds, requestTimeoutSeconds.seconds, maxAttempts.toLong, allowPrivate)

  /** Absent means no, and anything that is not plainly yes or no is a configuration error
    * rather than a default: this one decides what the product is allowed to connect to.
    */
  /** Remote configuration work: every limit typed, bounded and overridable per deployment. */
  private def parseConfigurationDeployment(
    values: Map[String, String]
  ): Either[IllegalArgumentException, ConfigurationDeploymentSettings] = {
    val d = ConfigurationDeploymentSettings.Default
    def seconds(key: String, default: FiniteDuration, max: Int): Either[IllegalArgumentException, FiniteDuration] =
      bounded(values, key, default.toSeconds.toInt, 1, max).map(_.seconds)
    for {
      enabled <- parseBoolean(values, "INFRADESK_CONFIGURATION_DEPLOYMENT_ENABLED", d.enabled)
      concurrency <- bounded(values, "INFRADESK_CONFIGURATION_DEPLOYMENT_MAX_CONCURRENCY", d.maxConcurrency, 1, 64)
      perOrganization <- bounded(values, "INFRADESK_CONFIGURATION_DEPLOYMENT_PER_ORGANIZATION_LIMIT", d.perOrganizationLimit, 1, 64)
      maxFileBytes <- bounded(values, "INFRADESK_CONFIGURATION_MAX_REMOTE_FILE_BYTES", d.maxRemoteFileBytes, 1024, 8 * 1024 * 1024)
      sftp <- seconds("INFRADESK_CONFIGURATION_SFTP_TIMEOUT_SECONDS", d.sftpTimeout, 600)
      validator <- seconds("INFRADESK_CONFIGURATION_VALIDATOR_TIMEOUT_SECONDS", d.validatorTimeout, 600)
      activation <- seconds("INFRADESK_CONFIGURATION_ACTIVATION_TIMEOUT_SECONDS", d.activationTimeout, 600)
      health <- seconds("INFRADESK_CONFIGURATION_HEALTH_TIMEOUT_SECONDS", d.healthTimeout, 600)
      overall <- seconds("INFRADESK_CONFIGURATION_DEPLOYMENT_TIMEOUT_SECONDS", d.overallTimeout, 24 * 3600)
      attempts <- bounded(values, "INFRADESK_CONFIGURATION_TRANSIENT_ATTEMPTS", d.maxTransientAttempts, 0, 20)
    } yield d.copy(enabled = enabled, maxConcurrency = concurrency, perOrganizationLimit = perOrganization,
      maxRemoteFileBytes = maxFileBytes, sftpTimeout = sftp, validatorTimeout = validator,
      activationTimeout = activation, healthTimeout = health, overallTimeout = overall,
      maxTransientAttempts = attempts)
  }

  private def bounded(values: Map[String, String], key: String, default: Int, min: Int,
                      max: Int): Either[IllegalArgumentException, Int] =
    values.get(key) match {
      case None => Right(default)
      case Some(value) => Try(value.trim.toInt).toOption.filter(n => n >= min && n <= max).toRight(
        new IllegalArgumentException(s"Invalid $key: expected an integer from $min to $max"))
    }

  private def parseBoolean(
    values: Map[String, String],
    key: String
  ): Either[IllegalArgumentException, Boolean] =
    values.get(key).map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty) match {
      case None => Right(false)
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(_) => Left(new IllegalArgumentException(s"Invalid $key: expected true or false"))
    }

  private def parseWebhookUrl(values: Map[String, String]): Either[IllegalArgumentException, Option[Uri]] =
    values.get("INFRADESK_NOTIFICATION_WEBHOOK_URL").map(_.trim).filter(_.nonEmpty) match {
      case None => Right(None)
      case Some(value) =>
        // The value itself is never echoed back: it may contain a token.
        val invalid = new IllegalArgumentException(
          "Invalid INFRADESK_NOTIFICATION_WEBHOOK_URL: expected an absolute http or https URL"
        )
        Uri.fromString(value).toOption
          .filter(uri => uri.scheme.exists(scheme => scheme.value == "http" || scheme.value == "https"))
          .filter(_.host.exists(_.value.trim.nonEmpty))
          .toRight(invalid)
          .map(Some(_))
    }

  private def positiveInt(
    values: Map[String, String],
    key: String,
    default: Int
  ): Either[IllegalArgumentException, Int] =
    values.get(key) match {
      case None => Right(default)
      case Some(value) => Try(value.toInt).toOption.filter(_ > 0).toRight(
        new IllegalArgumentException(s"Invalid $key: expected positive integer")
      )
    }

  private def parseScheduler(values: Map[String, String]): Either[IllegalArgumentException, SchedulerConfig] = {
    def positiveInt(key: String, default: Int): Either[IllegalArgumentException, Int] =
      values.get(key) match {
        case None => Right(default)
        case Some(value) => Try(value.toInt).toOption.filter(_ > 0).toRight(
          new IllegalArgumentException(s"Invalid $key: expected positive integer")
        )
      }

    for {
      enabled <- parseBoolean(values, "INFRADESK_SCHEDULER_ENABLED", default = true)
      seconds <- positiveInt("INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS", 1)
      batchSize <- positiveInt("INFRADESK_SCHEDULER_BATCH_SIZE", 100)
      maxConcurrency <- positiveInt("INFRADESK_SCHEDULER_MAX_CONCURRENCY", 5)
      claimLeaseSeconds <- positiveInt("INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS", 900)
    } yield SchedulerConfig(enabled, seconds.seconds, batchSize, maxConcurrency,
      claimLeaseSeconds.seconds)
  }

  private def parseBoolean(
    values: Map[String, String],
    key: String,
    default: Boolean
  ): Either[IllegalArgumentException, Boolean] =
    values.get(key).map(_.trim.toLowerCase) match {
      case None => Right(default)
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(_) => Left(new IllegalArgumentException(s"Invalid $key: expected true or false"))
    }
}

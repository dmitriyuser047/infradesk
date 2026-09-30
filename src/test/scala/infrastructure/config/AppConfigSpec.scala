package ru.bitec.app.ops
package infrastructure.config

import munit.FunSuite

import java.util.Base64
import scala.concurrent.duration._

final class AppConfigSpec extends FunSuite {
  private val key = Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7))
  private val minimal = Map(
    "INFRADESK_DB_URL" -> "jdbc:postgresql://localhost:5432/infradesk",
    "INFRADESK_DB_USER" -> "infradesk",
    "INFRADESK_DB_PASSWORD" -> "test-password",
    "INFRADESK_SECRET_MASTER_KEY_BASE64" -> key
  )

  test("the database pool is explicit, bounded and redacted") {
    val defaults = AppConfig.fromEnvironment(minimal).toOption.get.database
    val configured = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_DB_MAX_POOL_SIZE" -> "24",
      "INFRADESK_DB_CONNECTION_TIMEOUT_SECONDS" -> "5"
    )).toOption.get.database

    assertEquals(defaults.maxPoolSize, 10)
    assertEquals(defaults.connectionTimeout, 10.seconds)
    assertEquals(configured.maxPoolSize, 24)
    assertEquals(configured.connectionTimeout, 5.seconds)
    // The pool is part of the startup log line; the credentials are not.
    assert(configured.toString.contains("maxPoolSize=24"))
    assert(!configured.toString.contains("test-password"))
    assert(!configured.toString.contains("localhost"))

    List("0", "-1", "1000", "many", "").foreach { value =>
      assert(
        AppConfig.fromEnvironment(minimal + ("INFRADESK_DB_MAX_POOL_SIZE" -> value)).isLeft ||
          value.isEmpty,
        s"accepted pool size '$value'"
      )
    }
    assert(AppConfig.fromEnvironment(minimal + ("INFRADESK_DB_CONNECTION_TIMEOUT_SECONDS" -> "0")).isLeft)
  }

  test("minimal configuration parses with HTTP, auth and scheduler defaults") {
    val config = AppConfig.fromEnvironment(minimal).toOption.get
    assertEquals(config.database.user, "infradesk")
    assertEquals(config.http.host.toString, "0.0.0.0")
    assertEquals(config.http.port.value, 8080)
    assertEquals(config.auth.ttlSeconds, 604800L)
    assertEquals(config.auth.secureCookie, false)
    assertEquals(config.auth.trustForwardedFor, false)
    assertEquals(config.loginRateLimit.identifierWindow, 15.minutes)
    assertEquals(config.loginRateLimit.identifierMaxFailures, 10)
    assertEquals(config.loginRateLimit.identifierBlock, 15.minutes)
    assertEquals(config.loginRateLimit.sourceWindow, 15.minutes)
    assertEquals(config.loginRateLimit.sourceMaxFailures, 50)
    assertEquals(config.loginRateLimit.sourceBlock, 15.minutes)
    assertEquals(config.bootstrap, None)
    assertEquals(config.scheduler.enabled, true)
    assertEquals(config.scheduler.pollInterval, 1.second)
    assertEquals(config.scheduler.batchSize, 100)
    assertEquals(config.scheduler.maxConcurrency, 5)
    assertEquals(config.scheduler.claimLease, 900.seconds)
    assert(!config.toString.contains(key))
    assert(!config.toString.contains("test-password")) // DatabaseConfig must not be rendered in logs.
  }

  test("notifications are disabled without a webhook URL and configured with one") {
    val disabled = AppConfig.fromEnvironment(minimal).toOption.get.notification
    val enabled = AppConfig.fromEnvironment(
      minimal + ("INFRADESK_NOTIFICATION_WEBHOOK_URL" -> "https://hooks.example.test/infradesk?token=secret")
    ).toOption.get.notification

    assertEquals(disabled.enabled, false)
    assertEquals(disabled.webhookUrl, None)
    assertEquals(disabled.pollInterval, 5.seconds)
    assertEquals(disabled.batchSize, 50)
    assertEquals(disabled.maxConcurrency, 5)
    assertEquals(disabled.claimLease, 60.seconds)
    assertEquals(disabled.requestTimeout, 10.seconds)
    assertEquals(disabled.maxAttempts, 10L)

    assertEquals(enabled.enabled, true)
    assertEquals(enabled.webhookUrl.map(_.host.map(_.value)), Some(Some("hooks.example.test")))
    // The URL can carry a token, so it must not be rendered anywhere.
    assert(!enabled.toString.contains("secret"))
    assert(!enabled.toString.contains("hooks.example.test"))
  }

  test("integration HTTP policy is separate from notifications and private destinations default to blocked") {
    val defaults = AppConfig.fromEnvironment(minimal).toOption.get.integrations
    assertEquals(defaults.requestTimeout, 10.seconds)
    assertEquals(defaults.allowPrivateDestinations, false)
    val configured = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_INTEGRATIONS_REQUEST_TIMEOUT_SECONDS" -> "4",
      "INFRADESK_INTEGRATIONS_ALLOW_PRIVATE_DESTINATIONS" -> "true"
    )).toOption.get.integrations
    assertEquals(configured.requestTimeout, 4.seconds)
    assertEquals(configured.allowPrivateDestinations, true)
    assert(AppConfig.fromEnvironment(minimal + ("INFRADESK_INTEGRATIONS_REQUEST_TIMEOUT_SECONDS" -> "0")).isLeft)
  }

  test("desired state reconciliation has bounded defaults and rejects unsafe values with their keys") {
    val defaults = AppConfig.fromEnvironment(minimal).toOption.get.integrations.desiredState
    assertEquals(defaults, IntegrationDesiredStateConfig(enabled = true, 5.seconds, 50, 4, 30.seconds))
    val configured = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED" -> "false",
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_POLL_INTERVAL_SECONDS" -> "9",
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_BATCH_SIZE" -> "10",
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_MAX_CONCURRENCY" -> "2",
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_CLAIM_LEASE_SECONDS" -> "45")).toOption.get.integrations.desiredState
    assertEquals(configured, IntegrationDesiredStateConfig(enabled = false, 9.seconds, 10, 2, 45.seconds))
    List("INFRADESK_INTEGRATIONS_DESIRED_STATE_BATCH_SIZE" -> "0",
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_MAX_CONCURRENCY" -> "0",
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_POLL_INTERVAL_SECONDS" -> "0",
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_CLAIM_LEASE_SECONDS" -> "1",
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED" -> "sometimes").foreach { case (key, value) =>
      assert(AppConfig.fromEnvironment(minimal + (key -> value)).left.exists(_.getMessage.contains(key)), s"$key=$value")
    }
  }

  test("desired state requires both deployment synchronization and action workers") {
    for {
      desired <- List(false, true)
      sync <- List(false, true)
      actions <- List(false, true)
    } {
      val result = AppConfig.fromEnvironment(minimal ++ Map(
        "INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED" -> desired.toString,
        "INFRADESK_INTEGRATIONS_SYNC_ENABLED" -> sync.toString,
        "INFRADESK_INTEGRATIONS_ACTIONS_ENABLED" -> actions.toString,
        "INFRADESK_INTEGRATIONS_CONFIG_ROLLOUTS_ENABLED" -> "false"))
      val valid = !desired || (sync && actions)
      assertEquals(result.isRight, valid, s"desired=$desired sync=$sync actions=$actions")
      result match {
        case Right(config) =>
          assertEquals(config.integrations.desiredStateOperational, desired && sync && actions)
        case Left(error) =>
          assert(error.getMessage.contains("INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED"))
          assert(error.getMessage.contains("INFRADESK_INTEGRATIONS_SYNC_ENABLED"))
          assert(error.getMessage.contains("INFRADESK_INTEGRATIONS_ACTIONS_ENABLED"))
      }
    }
  }

  test("integration synchronization has bounded defaults and rejects unsafe values with their keys") {
    val defaults = AppConfig.fromEnvironment(minimal).toOption.get.integrations
    assertEquals(defaults.sync, IntegrationSyncConfig(enabled = true, 5.seconds, 60.seconds, 20, 3, 90.seconds, 30.seconds))
    assertEquals(defaults.inventoryMaxResponseBytes, 8388608)
    assertEquals(defaults.inventoryMaxObjects, 10000)
    val configured = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED" -> "false",
      "INFRADESK_INTEGRATIONS_CONFIG_ROLLOUTS_ENABLED" -> "false",
      "INFRADESK_INTEGRATIONS_SYNC_ENABLED" -> "false",
      "INFRADESK_INTEGRATIONS_SYNC_POLL_INTERVAL_SECONDS" -> "2",
      "INFRADESK_INTEGRATIONS_SYNC_INTERVAL_SECONDS" -> "120",
      "INFRADESK_INTEGRATIONS_SYNC_BATCH_SIZE" -> "5",
      "INFRADESK_INTEGRATIONS_SYNC_MAX_CONCURRENCY" -> "2",
      "INFRADESK_INTEGRATIONS_SYNC_CLAIM_LEASE_SECONDS" -> "60",
      "INFRADESK_INTEGRATIONS_SYNC_ATTEMPT_TIMEOUT_SECONDS" -> "20",
      "INFRADESK_INTEGRATIONS_INVENTORY_MAX_RESPONSE_BYTES" -> "1048576",
      "INFRADESK_INTEGRATIONS_INVENTORY_MAX_OBJECTS" -> "500"
    )).toOption.get.integrations
    assertEquals(configured.sync, IntegrationSyncConfig(enabled = false, 2.seconds, 120.seconds, 5, 2, 60.seconds, 20.seconds))
    assertEquals((configured.inventoryMaxResponseBytes, configured.inventoryMaxObjects), (1048576, 500))
    List("INFRADESK_INTEGRATIONS_SYNC_MAX_CONCURRENCY" -> "0", "INFRADESK_INTEGRATIONS_SYNC_INTERVAL_SECONDS" -> "0",
      "INFRADESK_INTEGRATIONS_SYNC_BATCH_SIZE" -> "0", "INFRADESK_INTEGRATIONS_INVENTORY_MAX_RESPONSE_BYTES" -> "999999999",
      "INFRADESK_INTEGRATIONS_INVENTORY_MAX_OBJECTS" -> "0", "INFRADESK_INTEGRATIONS_SYNC_ENABLED" -> "maybe",
      "INFRADESK_INTEGRATIONS_SYNC_CLAIM_LEASE_SECONDS" -> "30").foreach { case (key, value) =>
      val result = AppConfig.fromEnvironment(minimal + (key -> value))
      assert(result.left.exists(_.getMessage.contains(key)), s"$key=$value")
    }
  }

  test("guarded config rollouts require automatic integration synchronization") {
    val defaults = AppConfig.fromEnvironment(minimal).toOption.get.integrations.configRollouts
    assertEquals(defaults, IntegrationConfigRolloutsConfig(enabled = true, 2.seconds, 20, 2,
      30.seconds, 180.seconds))
    val invalid = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED" -> "false",
      "INFRADESK_INTEGRATIONS_SYNC_ENABLED" -> "false",
      "INFRADESK_INTEGRATIONS_CONFIG_ROLLOUTS_ENABLED" -> "true"))
    assert(invalid.left.exists(_.getMessage.contains("INFRADESK_INTEGRATIONS_CONFIG_ROLLOUTS_ENABLED")))
    val disabled = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_INTEGRATIONS_DESIRED_STATE_ENABLED" -> "false",
      "INFRADESK_INTEGRATIONS_SYNC_ENABLED" -> "false",
      "INFRADESK_INTEGRATIONS_CONFIG_ROLLOUTS_ENABLED" -> "false"))
    assert(disabled.isRight)
    assertEquals(disabled.toOption.get.integrations.configRolloutsOperational, false)
  }

  test("a webhook URL that is not an absolute http or https endpoint fails startup") {
    List(
      "ftp://hooks.example.test/infradesk",
      "hooks.example.test/infradesk",
      "/infradesk",
      "https://",
      "not a url"
    ).foreach { value =>
      val result = AppConfig.fromEnvironment(minimal + ("INFRADESK_NOTIFICATION_WEBHOOK_URL" -> value))
      assertEquals(
        result.swap.toOption.map(_.getMessage),
        Some("Invalid INFRADESK_NOTIFICATION_WEBHOOK_URL: expected an absolute http or https URL"),
        s"accepted $value"
      )
    }
  }

  test("a claim lease shorter than the request timeout fails startup") {
    val result = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_NOTIFICATION_CLAIM_LEASE_SECONDS" -> "10",
      "INFRADESK_NOTIFICATION_REQUEST_TIMEOUT_SECONDS" -> "10"
    ))

    assert(result.swap.toOption.exists(_.getMessage.contains("CLAIM_LEASE_SECONDS")))
  }

  test("missing database URL, user and password fail with their keys") {
    List("INFRADESK_DB_URL", "INFRADESK_DB_USER", "INFRADESK_DB_PASSWORD").foreach { key =>
      assertEquals(AppConfig.fromEnvironment(minimal - key).swap.toOption.get.getMessage, s"$key is required")
    }
  }

  test("invalid explicitly supplied HTTP host and port fail instead of defaulting") {
    assertInvalid("INFRADESK_HTTP_HOST", "bad host")
    assertInvalid("INFRADESK_HTTP_PORT", "abc")
    assertInvalid("INFRADESK_HTTP_PORT", "0")
    assertInvalid("INFRADESK_HTTP_PORT", "65536")
  }

  test("invalid auth TTL and cookie security fail with their keys") {
    assertInvalid("INFRADESK_AUTH_SESSION_TTL_SECONDS", "0")
    assertInvalid("INFRADESK_AUTH_SESSION_TTL_SECONDS", "nope")
    assertInvalid("INFRADESK_AUTH_COOKIE_SECURE", "yes")
    assertInvalid("INFRADESK_TRUST_FORWARDED_FOR", "yes")
  }

  test("login throttle settings and trusted forwarding are explicit and typed") {
    val config = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_TRUST_FORWARDED_FOR" -> "true",
      "INFRADESK_AUTH_IDENTIFIER_WINDOW_SECONDS" -> "600",
      "INFRADESK_AUTH_IDENTIFIER_MAX_FAILURES" -> "7",
      "INFRADESK_AUTH_IDENTIFIER_BLOCK_SECONDS" -> "120",
      "INFRADESK_AUTH_SOURCE_WINDOW_SECONDS" -> "900",
      "INFRADESK_AUTH_SOURCE_MAX_FAILURES" -> "42",
      "INFRADESK_AUTH_SOURCE_BLOCK_SECONDS" -> "180",
      "INFRADESK_AUTH_THROTTLE_RETENTION_SECONDS" -> "86400"
    )).toOption.get

    assert(config.auth.trustForwardedFor)
    assertEquals(config.loginRateLimit.identifierWindow, 600.seconds)
    assertEquals(config.loginRateLimit.identifierMaxFailures, 7)
    assertEquals(config.loginRateLimit.identifierBlock, 120.seconds)
    assertEquals(config.loginRateLimit.sourceWindow, 900.seconds)
    assertEquals(config.loginRateLimit.sourceMaxFailures, 42)
    assertEquals(config.loginRateLimit.sourceBlock, 180.seconds)
    assertEquals(config.loginRateLimit.retention, 1.day)
    assertInvalid("INFRADESK_AUTH_IDENTIFIER_MAX_FAILURES", "0")
  }

  test("partial bootstrap settings fail without exposing their values") {
    val error = AppConfig.fromEnvironment(minimal.updated("INFRADESK_BOOTSTRAP_EMAIL", "admin@example.test"))
      .swap.toOption.get
    assert(error.getMessage.contains("INFRADESK_BOOTSTRAP_PASSWORD"))
    assert(!error.getMessage.contains("admin@example.test"))
  }

  test("missing, malformed and short master keys fail without exposing values") {
    val missing = AppConfig.fromEnvironment(minimal - "INFRADESK_SECRET_MASTER_KEY_BASE64").swap.toOption.get
    assert(missing.getMessage.contains("INFRADESK_SECRET_MASTER_KEY_BASE64"))
    assertInvalid("INFRADESK_SECRET_MASTER_KEY_BASE64", "not-base64!")
    assertInvalid("INFRADESK_SECRET_MASTER_KEY_BASE64", Base64.getEncoder.encodeToString(new Array[Byte](16)))
  }

  test("invalid scheduler interval, batch size, concurrency and claim lease fail with their keys") {
    assertInvalid("INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS", "0")
    assertInvalid("INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS", "abc")
    assertInvalid("INFRADESK_SCHEDULER_BATCH_SIZE", "-1")
    assertInvalid("INFRADESK_SCHEDULER_BATCH_SIZE", "abc")
    assertInvalid("INFRADESK_SCHEDULER_MAX_CONCURRENCY", "0")
    assertInvalid("INFRADESK_SCHEDULER_MAX_CONCURRENCY", "-1")
    assertInvalid("INFRADESK_SCHEDULER_MAX_CONCURRENCY", "abc")
    assertInvalid("INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS", "0")
    assertInvalid("INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS", "-1")
    assertInvalid("INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS", "abc")
    assertInvalid("INFRADESK_SCHEDULER_ENABLED", "sometimes")
  }

  test("explicit valid HTTP and scheduler values are retained") {
    val config = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_HTTP_HOST" -> "127.0.0.1",
      "INFRADESK_HTTP_PORT" -> "5174",
      "INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS" -> "5",
      "INFRADESK_SCHEDULER_BATCH_SIZE" -> "17",
      "INFRADESK_SCHEDULER_MAX_CONCURRENCY" -> "8",
      "INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS" -> "1200",
      "INFRADESK_SCHEDULER_ENABLED" -> "false"
    )).toOption.get
    assertEquals(config.http.port.value, 5174)
    assertEquals(config.scheduler.enabled, false)
    assertEquals(config.scheduler.pollInterval, 5.seconds)
    assertEquals(config.scheduler.batchSize, 17)
    assertEquals(config.scheduler.maxConcurrency, 8)
    assertEquals(config.scheduler.claimLease, 1200.seconds)
  }

  private def assertInvalid(key: String, value: String): Unit = {
    val error = AppConfig.fromEnvironment(minimal.updated(key, value)).swap.toOption.get
    assert(error.getMessage.contains(key))
    assert(!error.getMessage.contains(value))
  }

  test("terminal limits have bounded defaults and reject unsafe configuration") {
    val terminal = AppConfig.fromEnvironment(minimal).toOption.get.terminal
    assertEquals(terminal.initialColumns, 80)
    assertEquals(terminal.initialRows, 24)
    assertEquals(terminal.maxColumns, 500)
    assertEquals(terminal.maxRows, 200)
    assertEquals(terminal.maxFrameBytes, 65536)
    assertEquals(terminal.maxControlBytes, 8192)
    assertEquals(terminal.maxConcurrentSessions, 16)

    List(
      "INFRADESK_TERMINAL_INITIAL_COLUMNS" -> "0",
      "INFRADESK_TERMINAL_MAX_ROWS" -> "201",
      "INFRADESK_TERMINAL_MAX_FRAME_BYTES" -> "1000000",
      "INFRADESK_TERMINAL_IDLE_TIMEOUT_SECONDS" -> "nope",
      "INFRADESK_TERMINAL_MAX_LIFETIME_SECONDS" -> "100"
    ).foreach { case (key, value) =>
      assert(AppConfig.fromEnvironment(minimal + (key -> value)).isLeft, s"accepted $key=$value")
    }
  }
}

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

  test("minimal configuration parses with HTTP, auth and scheduler defaults") {
    val config = AppConfig.fromEnvironment(minimal).toOption.get
    assertEquals(config.database.user, "infradesk")
    assertEquals(config.http.host.toString, "0.0.0.0")
    assertEquals(config.http.port.value, 8080)
    assertEquals(config.auth.ttlSeconds, 604800L)
    assertEquals(config.auth.secureCookie, false)
    assertEquals(config.bootstrap, None)
    assertEquals(config.scheduler.pollInterval, 1.second)
    assertEquals(config.scheduler.batchSize, 100)
    assertEquals(config.scheduler.maxConcurrency, 5)
    assertEquals(config.scheduler.claimLease, 900.seconds)
    assert(!config.toString.contains(key))
    assert(!config.toString.contains("test-password")) // DatabaseConfig must not be rendered in logs.
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
  }

  test("explicit valid HTTP and scheduler values are retained") {
    val config = AppConfig.fromEnvironment(minimal ++ Map(
      "INFRADESK_HTTP_HOST" -> "127.0.0.1",
      "INFRADESK_HTTP_PORT" -> "5174",
      "INFRADESK_SCHEDULER_POLL_INTERVAL_SECONDS" -> "5",
      "INFRADESK_SCHEDULER_BATCH_SIZE" -> "17",
      "INFRADESK_SCHEDULER_MAX_CONCURRENCY" -> "8",
      "INFRADESK_SCHEDULER_CLAIM_LEASE_SECONDS" -> "1200"
    )).toOption.get
    assertEquals(config.http.port.value, 5174)
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
}

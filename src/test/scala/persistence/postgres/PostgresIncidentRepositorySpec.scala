package ru.bitec.app.ops
package persistence.postgres

import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class PostgresIncidentRepositorySpec extends FunSuite {

  private val now = Instant.parse("2026-09-22T10:00:00Z")

  private def row(status: String, reason: String) = PostgresIncidentRepository.IncidentRow(
    UUID.randomUUID(),
    UUID.randomUUID(),
    UUID.randomUUID(),
    UUID.randomUUID(),
    status,
    reason,
    now,
    now,
    None,
    now,
    now
  )

  test("fails fast when an incident row has an unknown status") {
    assert(row("UNKNOWN", "THRESHOLD").toDomain.isLeft)
  }

  test("fails fast when an incident row has an unknown reason") {
    assert(row("OPEN", "UNKNOWN").toDomain.isLeft)
  }

  test("reads a stored incident with its reason") {
    assertEquals(row("OPEN", "NO_DATA").toDomain.map(_.reason),
      Right(ru.bitec.app.ops.domain.incident.IncidentReason.NoData))
  }
}

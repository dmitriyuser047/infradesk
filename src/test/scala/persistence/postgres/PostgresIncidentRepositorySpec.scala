package ru.bitec.app.ops
package persistence.postgres

import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class PostgresIncidentRepositorySpec extends FunSuite {

  test("fails fast when an incident row has an unknown status") {
    val now = Instant.parse("2026-09-22T10:00:00Z")
    val row = PostgresIncidentRepository.IncidentRow(
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID(),
      UUID.randomUUID(),
      "UNKNOWN",
      now,
      now,
      None,
      now,
      now
    )

    assert(row.toDomain.isLeft)
  }
}

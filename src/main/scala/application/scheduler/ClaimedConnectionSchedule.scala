package ru.bitec.app.ops
package application.scheduler

import domain.connection.ConnectionSchedule

import java.time.Instant
import java.util.UUID

final case class ClaimedConnectionSchedule(
                                             schedule: ConnectionSchedule,
                                             claimedBy: UUID,
                                             claimedUntil: Instant
                                           )

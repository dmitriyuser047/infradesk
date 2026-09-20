package ru.bitec.app.ops
package domain.externalref

import java.time.Instant
import java.util.UUID

final case class ExternalRef(
                        id: UUID,
                        organizationId: UUID,
                        connectionId: UUID,
                        externalType: String,
                        externalId: String,
                        resourceId: UUID,
                        firstSeenAt: Instant,
                        lastSeenAt: Instant,
                        createdAt: Instant,
                        updatedAt: Instant
                      )

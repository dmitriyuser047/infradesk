package ru.bitec.app.ops
package infrastructure.database

import application.port.IdGenerator
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.free.connection

import java.util.UUID

final class ConnectionIOIdGenerator extends IdGenerator[ConnectionIO] {
  override def nextId: ConnectionIO[UUID] =
    connection.delay(UUID.randomUUID())
}

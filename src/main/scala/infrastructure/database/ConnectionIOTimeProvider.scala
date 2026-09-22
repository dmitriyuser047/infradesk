package ru.bitec.app.ops
package infrastructure.database
import application.port.TimeProvider
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.free.connection
import java.time.Instant
final class ConnectionIOTimeProvider extends TimeProvider[ConnectionIO] { def now:ConnectionIO[Instant]=connection.delay(Instant.now()) }

package ru.bitec.app.ops
package application.notification

import application.port.NotificationChannelRepository
import domain.notification.NotificationChannel

import java.util.UUID

/** The settings page: every channel of one organization, in one query.
  *
  * Nothing here decrypts a credential, so the list costs no cryptography and carries no secret
  * into the layers above it.
  */
final class ListNotificationChannels[Tx[_]](channels: NotificationChannelRepository[Tx]) {
  def execute(organizationId: UUID): Tx[List[NotificationChannel]] =
    channels.listByOrganization(organizationId)
}

final class GetNotificationChannel[Tx[_]](channels: NotificationChannelRepository[Tx]) {
  def execute(organizationId: UUID, id: UUID): Tx[Option[NotificationChannel]] =
    channels.findById(organizationId, id)
}

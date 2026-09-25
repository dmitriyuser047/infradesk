-- A delivery now says which channel it is for, not merely which transport it needs.
--
-- Without this, two Telegram channels of one organization are indistinguishable in the outbox
-- and the unique index below would collapse them into a single delivery. The column is nullable
-- because the environment-configured webhook of Stage 9 has no channel row and never will: it
-- is a second kind of target, not a channel that happens to be missing.

ALTER TABLE notification_delivery ADD COLUMN notification_channel_id uuid;

-- The channel a delivery names belongs to the same organization as the delivery. A channel is
-- switched off rather than deleted, so a delivery never loses the subject it was addressed to.
ALTER TABLE notification_delivery ADD CONSTRAINT fk_notification_delivery_channel_tenant
  FOREIGN KEY (notification_channel_id, organization_id)
  REFERENCES notification_channel (id, organization_id);

-- Only the legacy webhook has no channel: there is no legacy Telegram or legacy mail.
ALTER TABLE notification_delivery ADD CONSTRAINT ck_notification_delivery_legacy_target
  CHECK (notification_channel_id IS NOT NULL OR channel = 'WEBHOOK');

-- One delivery per incident transition per destination. The old index counted a transport as a
-- destination, which is true of the legacy webhook and false of configured channels, so the rule
-- is now stated once for each kind of target.
DROP INDEX ux_notification_delivery_event;

CREATE UNIQUE INDEX ux_notification_delivery_legacy_event
  ON notification_delivery (organization_id, incident_id, event_type, channel)
  WHERE notification_channel_id IS NULL;

CREATE UNIQUE INDEX ux_notification_delivery_managed_event
  ON notification_delivery (organization_id, incident_id, event_type, notification_channel_id)
  WHERE notification_channel_id IS NOT NULL;

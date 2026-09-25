-- Mail as a third kind of notification channel.
--
-- The non-secret part of an SMTP configuration gets typed columns, like the Telegram chat before
-- it: what a channel needs in order to reach its external system is part of the schema, not an
-- opaque document. The SMTP password stays where every channel credential already lives, in
-- notification_channel_secret, encrypted.
--
-- Rows of the existing kinds are untouched: their new columns are null, which is exactly what
-- their type requires, so nothing is backfilled and nothing is re-encrypted.

ALTER TABLE notification_channel ADD COLUMN smtp_host varchar(255);
ALTER TABLE notification_channel ADD COLUMN smtp_port integer;
ALTER TABLE notification_channel ADD COLUMN smtp_security varchar(16);
ALTER TABLE notification_channel ADD COLUMN smtp_username varchar(255);
ALTER TABLE notification_channel ADD COLUMN smtp_from_address varchar(320);
ALTER TABLE notification_channel ADD COLUMN smtp_recipients text[];

ALTER TABLE notification_channel DROP CONSTRAINT ck_notification_channel_type;
ALTER TABLE notification_channel ADD CONSTRAINT ck_notification_channel_type
  CHECK (channel_type IN ('WEBHOOK', 'TELEGRAM', 'EMAIL'));

ALTER TABLE notification_channel ADD CONSTRAINT ck_notification_channel_smtp_security
  CHECK (smtp_security IS NULL OR smtp_security IN ('NONE', 'STARTTLS', 'TLS'));

ALTER TABLE notification_channel ADD CONSTRAINT ck_notification_channel_smtp_port
  CHECK (smtp_port IS NULL OR (smtp_port BETWEEN 1 AND 65535));

-- A configuration belongs to exactly one channel type. The rule of V23 is restated here rather
-- than extended in place, because the columns it has to speak about are new.
ALTER TABLE notification_channel DROP CONSTRAINT ck_notification_channel_settings;
ALTER TABLE notification_channel ADD CONSTRAINT ck_notification_channel_settings
  CHECK (
    (channel_type = 'WEBHOOK'
      AND telegram_chat_id IS NULL
      AND smtp_host IS NULL AND smtp_port IS NULL AND smtp_security IS NULL
      AND smtp_username IS NULL AND smtp_from_address IS NULL AND smtp_recipients IS NULL)
    OR (channel_type = 'TELEGRAM'
      AND telegram_chat_id IS NOT NULL
      AND length(btrim(telegram_chat_id)) >= 1
      AND smtp_host IS NULL AND smtp_port IS NULL AND smtp_security IS NULL
      AND smtp_username IS NULL AND smtp_from_address IS NULL AND smtp_recipients IS NULL)
    -- Mail in this version always authenticates, so the user name is as required as the host.
    OR (channel_type = 'EMAIL'
      AND telegram_chat_id IS NULL
      AND smtp_host IS NOT NULL AND length(btrim(smtp_host)) >= 1
      AND smtp_port IS NOT NULL
      AND smtp_security IS NOT NULL
      AND smtp_username IS NOT NULL AND length(btrim(smtp_username)) >= 1
      AND smtp_from_address IS NOT NULL AND length(btrim(smtp_from_address)) >= 1
      AND smtp_recipients IS NOT NULL AND cardinality(smtp_recipients) >= 1)
  );

-- A delivery may now name a mail channel as well. No delivery is addressed to one yet: routing
-- is a later stage, and this only keeps the column in step with the channel types that exist.
ALTER TABLE notification_delivery DROP CONSTRAINT ck_notification_delivery_channel;
ALTER TABLE notification_delivery ADD CONSTRAINT ck_notification_delivery_channel
  CHECK (channel IN ('WEBHOOK', 'TELEGRAM', 'EMAIL'));

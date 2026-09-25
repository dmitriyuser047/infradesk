-- Organization-scoped notification channels: a destination plus the events it asked for.
--
-- The credential of a channel lives in its own table, encrypted with the same primitive as an
-- SSH credential. Listing, routing and auditing channels therefore never decrypt anything: only
-- the sender, at delivery time, needs the plaintext.

CREATE TABLE notification_channel_secret (
  id              uuid        PRIMARY KEY,
  organization_id uuid        NOT NULL REFERENCES organization (id),
  kind            varchar(64) NOT NULL,
  nonce           bytea       NOT NULL,
  ciphertext      bytea       NOT NULL,
  created_at      timestamptz NOT NULL,

  CONSTRAINT uq_notification_channel_secret_id_organization
    UNIQUE (id, organization_id),

  CONSTRAINT ck_notification_channel_secret_kind
    CHECK (kind = 'NOTIFICATION_CHANNEL_CREDENTIAL'),

  CONSTRAINT ck_notification_channel_secret_nonce
    CHECK (octet_length(nonce) = 12)
);

CREATE INDEX ix_notification_channel_secret_organization
  ON notification_channel_secret (organization_id);

CREATE TABLE notification_channel (
  id              uuid        PRIMARY KEY,
  organization_id uuid        NOT NULL REFERENCES organization (id),

  name            varchar(255) NOT NULL,
  channel_type    varchar(32)  NOT NULL,
  enabled         boolean      NOT NULL,

  -- Routing conditions. Two dimensions, both validated against the codes the domain knows:
  -- what happened to an incident, and why the incident exists.
  subscribed_event_types text[] NOT NULL,
  subscribed_reasons     text[] NOT NULL,

  -- The non-secret part of the configuration. A webhook has none: its URL is a credential.
  telegram_chat_id varchar(64),

  secret_id       uuid        NOT NULL,

  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,

  CONSTRAINT uq_notification_channel_id_organization
    UNIQUE (id, organization_id),

  -- The credential belongs to the channel and to the same tenant as the channel.
  CONSTRAINT fk_notification_channel_secret
    FOREIGN KEY (secret_id, organization_id)
    REFERENCES notification_channel_secret (id, organization_id),

  CONSTRAINT ck_notification_channel_type
    CHECK (channel_type IN ('WEBHOOK', 'TELEGRAM')),

  CONSTRAINT ck_notification_channel_name
    CHECK (length(btrim(name)) BETWEEN 1 AND 255),

  CONSTRAINT ck_notification_channel_event_types
    CHECK (
      cardinality(subscribed_event_types) >= 1
      AND subscribed_event_types <@ ARRAY['INCIDENT_OPENED', 'INCIDENT_RESOLVED']::text[]
    ),

  CONSTRAINT ck_notification_channel_reasons
    CHECK (
      cardinality(subscribed_reasons) >= 1
      AND subscribed_reasons <@ ARRAY['THRESHOLD', 'NO_DATA']::text[]
    ),

  -- A configuration belongs to exactly one channel type: a Telegram chat cannot be stored on a
  -- webhook, and a Telegram channel cannot exist without its chat.
  CONSTRAINT ck_notification_channel_settings
    CHECK (
      (channel_type = 'TELEGRAM'
        AND telegram_chat_id IS NOT NULL
        AND length(btrim(telegram_chat_id)) >= 1)
      OR (channel_type = 'WEBHOOK' AND telegram_chat_id IS NULL)
    )
);

-- The settings page: every channel of one organization, in the order it displays them.
CREATE INDEX ix_notification_channel_organization
  ON notification_channel (organization_id, name, id);

-- Routing at incident time reads only the enabled channels of one organization, so the index
-- that serves it excludes the disabled ones.
CREATE INDEX ix_notification_channel_routing
  ON notification_channel (organization_id)
  WHERE enabled;

-- A delivery may now be addressed to a Telegram channel as well. Existing rows keep their value;
-- nothing is rewritten.
ALTER TABLE notification_delivery DROP CONSTRAINT ck_notification_delivery_channel;
ALTER TABLE notification_delivery ADD CONSTRAINT ck_notification_delivery_channel
  CHECK (channel IN ('WEBHOOK', 'TELEGRAM'));

-- Changing a channel is a configuration change of the organization, so it is journalled like
-- every other one. The journal records identifiers and flags, never a credential.
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
  'PROJECT_CREATED','ENVIRONMENT_CREATED','CONNECTION_CREATED','CONNECTION_UPDATED',
  'CONNECTION_DELETED','MONITOR_RULE_CREATED','MONITOR_RULE_UPDATED','MANUAL_SYNC_REQUESTED',
  'CONTAINER_START_REQUESTED','CONTAINER_STOP_REQUESTED','CONTAINER_RESTART_REQUESTED',
  'NOTIFICATION_CHANNEL_CREATED','NOTIFICATION_CHANNEL_UPDATED',
  'NOTIFICATION_CHANNEL_ENABLED','NOTIFICATION_CHANNEL_DISABLED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','MONITOR_RULE','RESOURCE','NOTIFICATION_CHANNEL'
));

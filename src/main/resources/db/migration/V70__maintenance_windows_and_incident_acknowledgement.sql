-- A maintenance window covers one resource and the resources directly beneath it. Monitoring
-- keeps evaluating during it; incidents it opens are recorded but never notified.
CREATE TABLE maintenance_window (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  resource_id uuid NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  reason varchar(500) NOT NULL,
  created_by uuid NOT NULL REFERENCES user_account(id),
  created_at timestamptz NOT NULL,
  cancelled_at timestamptz,
  cancelled_by uuid REFERENCES user_account(id),
  FOREIGN KEY (resource_id, organization_id) REFERENCES resource(id, organization_id),
  CHECK (ends_at > starts_at),
  CHECK (ends_at - starts_at <= interval '30 days'),
  CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
  CHECK ((cancelled_at IS NULL) = (cancelled_by IS NULL)),
  CHECK (cancelled_at IS NULL OR cancelled_at >= created_at)
);
CREATE INDEX ix_maintenance_window_resource ON maintenance_window (organization_id, resource_id, ends_at)
  WHERE cancelled_at IS NULL;
CREATE INDEX ix_maintenance_window_listing ON maintenance_window (organization_id, starts_at DESC, id DESC);

-- Whether an incident's notifications are silenced is decided once, when it opens, and kept, so
-- its resolution is silenced too. Acknowledgement records who took an open incident in hand.
ALTER TABLE incident
  ADD COLUMN notifications_silenced boolean NOT NULL DEFAULT false,
  ADD COLUMN acknowledged_at timestamptz,
  ADD COLUMN acknowledged_by uuid REFERENCES user_account(id),
  ADD CONSTRAINT ck_incident_acknowledgement CHECK ((acknowledged_at IS NULL) = (acknowledged_by IS NULL)),
  ADD CONSTRAINT ck_incident_acknowledged_after_opened CHECK (acknowledged_at IS NULL OR acknowledged_at >= opened_at);

DO $$
DECLARE previous_check text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous_check FROM pg_constraint
 WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_action';
 IF previous_check IS NULL THEN RAISE EXCEPTION 'AUDIT_ACTION_CONSTRAINT_MISSING'; END IF;
 ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
 EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (' || substring(previous_check FROM 7) ||
  ' OR action IN (''MAINTENANCE_WINDOW_CREATED'',''MAINTENANCE_WINDOW_CANCELLED'',''INCIDENT_ACKNOWLEDGED''))';
END $$;
DO $$
DECLARE previous_check text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous_check FROM pg_constraint
 WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_target_type';
 IF previous_check IS NULL THEN RAISE EXCEPTION 'AUDIT_TARGET_CONSTRAINT_MISSING'; END IF;
 ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
 EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (' || substring(previous_check FROM 7) ||
  ' OR target_type IN (''MAINTENANCE_WINDOW'',''INCIDENT''))';
END $$;

-- Installation administration is distinct from organization membership. Existing owners do
-- not become global administrators; only the explicitly configured bootstrap account can.
CREATE TABLE administration_access_guard (id boolean PRIMARY KEY CHECK(id));
INSERT INTO administration_access_guard VALUES (true);
CREATE TABLE installation_administrator (
 user_id uuid PRIMARY KEY REFERENCES user_account(id),
 is_active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
ALTER TABLE organization_membership DROP CONSTRAINT organization_membership_role_check;
ALTER TABLE organization_membership ADD CONSTRAINT organization_membership_role_check
 CHECK(role IN ('OWNER','ADMINISTRATOR','OPERATOR','MEMBER'));
CREATE TABLE administration_request (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES user_account(id),
 kind varchar(32) NOT NULL CHECK(kind IN ('CREATE_ORGANIZATION','CREATE_USER')),
 fingerprint varchar(64) NOT NULL, target_id uuid NOT NULL, created_at timestamptz NOT NULL
);
CREATE TABLE administration_audit (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES user_account(id),
 action varchar(32) NOT NULL CHECK(action IN ('USER_CREATED','USER_STATUS_CHANGED','MEMBERSHIP_CHANGED','ORGANIZATION_CREATED','ADMINISTRATOR_BOOTSTRAPPED')),
 target_id uuid NOT NULL, organization_id uuid REFERENCES organization(id),
 occurred_at timestamptz NOT NULL
);
CREATE INDEX ix_administration_audit_recent ON administration_audit (occurred_at DESC,id DESC);
DO $$
DECLARE previous_check text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous_check FROM pg_constraint
 WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_action';
 IF previous_check IS NULL THEN RAISE EXCEPTION 'AUDIT_ACTION_CONSTRAINT_MISSING'; END IF;
 ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
 EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (' || substring(previous_check FROM 7) ||
  ' OR action IN (''ORGANIZATION_CREATED'',''USER_CREATED'',''MEMBERSHIP_CHANGED''))';
END $$;
DO $$
DECLARE previous_check text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous_check FROM pg_constraint
 WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_target_type';
 IF previous_check IS NULL THEN RAISE EXCEPTION 'AUDIT_TARGET_CONSTRAINT_MISSING'; END IF;
 ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
 EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (' || substring(previous_check FROM 7) ||
  ' OR target_type=''ORGANIZATION'')';
END $$;

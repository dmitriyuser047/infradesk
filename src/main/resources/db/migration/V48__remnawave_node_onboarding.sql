CREATE TABLE remnawave_node_onboarding (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  integration_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  request_id uuid,
  created_by uuid NOT NULL REFERENCES user_account(id),
  state varchar(16) NOT NULL CHECK (state IN ('PLANNED','QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
  phase varchar(40) NOT NULL CHECK (phase IN ('VALIDATE','PREPARE_SERVER','CREATE_NODE','GET_INSTALLATION_DATA',
    'CONFIGURE_NODE_FIREWALL','INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY',
    'BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY')),
  input_snapshot jsonb NOT NULL CHECK (jsonb_typeof(input_snapshot)='object'),
  external_node_id uuid,
  baseline_run_id uuid,
  sync_session_id uuid,
  failure_code varchar(96),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  started_at timestamptz,
  finished_at timestamptz,
  claim_owner uuid,
  claim_token uuid,
  claim_deadline timestamptz,
  UNIQUE (id,organization_id),
  FOREIGN KEY (resource_id,organization_id) REFERENCES resource(id,organization_id),
  FOREIGN KEY (integration_id,organization_id) REFERENCES integration(id,organization_id),
  FOREIGN KEY (baseline_run_id,organization_id,resource_id) REFERENCES provisioning_run(id,organization_id,resource_id),
  FOREIGN KEY (sync_session_id,integration_id,organization_id) REFERENCES integration_sync_session(id,integration_id,organization_id),
  CHECK ((state='RUNNING') = (claim_owner IS NOT NULL AND claim_token IS NOT NULL AND claim_deadline IS NOT NULL)),
  CHECK ((state='PLANNED') = (request_id IS NULL)),
  CHECK ((state IN ('SUCCEEDED','FAILED','UNKNOWN')) = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_remnawave_onboarding_request ON remnawave_node_onboarding(organization_id,request_id) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_remnawave_onboarding_active_resource ON remnawave_node_onboarding(organization_id,resource_id) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX ix_remnawave_onboarding_claim ON remnawave_node_onboarding(state,claim_deadline,created_at);
CREATE INDEX ix_remnawave_onboarding_history ON remnawave_node_onboarding(organization_id,integration_id,created_at DESC);

CREATE TABLE remnawave_node_onboarding_phase (
  run_id uuid NOT NULL REFERENCES remnawave_node_onboarding(id) ON DELETE CASCADE,
  phase varchar(40) NOT NULL,
  position smallint NOT NULL CHECK (position BETWEEN 0 AND 12),
  state varchar(16) NOT NULL CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
  started_at timestamptz,
  finished_at timestamptz,
  failure_code varchar(96),
  PRIMARY KEY(run_id,phase), UNIQUE(run_id,position),
  CHECK (phase = (ARRAY['VALIDATE','PREPARE_SERVER','CREATE_NODE','GET_INSTALLATION_DATA',
    'CONFIGURE_NODE_FIREWALL','INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY',
    'BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY'])[position+1])
);
CREATE TABLE remnawave_node_installation_secret (
  run_id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  kind varchar(40) NOT NULL CHECK(kind='REMNAWAVE_NODE_INSTALLATION'),
  nonce bytea NOT NULL CHECK(octet_length(nonce)=12),
  ciphertext bytea NOT NULL,
  FOREIGN KEY(run_id,organization_id) REFERENCES remnawave_node_onboarding(id,organization_id) ON DELETE CASCADE
);
ALTER TABLE provisioning_run ADD COLUMN onboarding_parent_id uuid;
ALTER TABLE provisioning_run ADD CONSTRAINT fk_provisioning_onboarding_parent FOREIGN KEY(onboarding_parent_id,organization_id)
  REFERENCES remnawave_node_onboarding(id,organization_id);

-- Cross-engine admission uses the same resource advisory lock as Stage25A/B approval.
-- A child may pass only with the exact same tenant/resource and approved baseline plan ID.
CREATE FUNCTION infradesk_onboarding_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent uuid; active boolean; row_state text;
BEGIN
  IF TG_TABLE_NAME IN ('remnawave_node_onboarding','configuration_deployment') THEN row_state := NEW.state; ELSE row_state := NEW.status; END IF;
  IF row_state NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
  IF TG_OP='UPDATE' THEN
    IF TG_TABLE_NAME IN ('remnawave_node_onboarding','configuration_deployment') THEN
      IF OLD.state IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
    ELSE IF OLD.status IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF; END IF;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text || ':' || NEW.resource_id::text,1));
  IF TG_TABLE_NAME='provisioning_run' THEN parent := NEW.onboarding_parent_id; END IF;
  IF TG_TABLE_NAME='remnawave_node_onboarding' THEN
    SELECT EXISTS(SELECT 1 FROM provisioning_run p WHERE p.organization_id=NEW.organization_id AND p.resource_id=NEW.resource_id
      AND p.status IN ('QUEUED','RUNNING') AND p.onboarding_parent_id IS DISTINCT FROM NEW.id) OR
      EXISTS(SELECT 1 FROM configuration_deployment d WHERE d.organization_id=NEW.organization_id AND d.resource_id=NEW.resource_id AND d.state IN ('QUEUED','RUNNING')) OR
      EXISTS(SELECT 1 FROM operation_execution e WHERE e.organization_id=NEW.organization_id AND e.resource_id=NEW.resource_id AND e.status='RUNNING') INTO active;
  ELSE
    SELECT EXISTS(SELECT 1 FROM remnawave_node_onboarding o WHERE o.organization_id=NEW.organization_id AND o.resource_id=NEW.resource_id
      AND o.state IN ('QUEUED','RUNNING') AND NOT (parent IS NOT NULL AND o.id=parent AND o.input_snapshot->>'baselinePlanId'=NEW.id::text)) INTO active;
    IF parent IS NOT NULL THEN
      IF NEW.run_kind<>'SERVER_PROFILE_APPLY' OR NOT EXISTS(SELECT 1 FROM remnawave_node_onboarding o WHERE o.id=parent AND o.organization_id=NEW.organization_id
      AND o.resource_id=NEW.resource_id AND o.state='RUNNING' AND o.input_snapshot->>'baselinePlanId'=NEW.id::text
      AND o.claim_deadline>clock_timestamp()) THEN active := true; END IF;
    END IF;
  END IF;
  IF active THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_onboarding_operation_guard BEFORE INSERT OR UPDATE OF state ON remnawave_node_onboarding
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_operation_guard();
CREATE TRIGGER provisioning_onboarding_operation_guard BEFORE INSERT OR UPDATE OF status ON provisioning_run
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_operation_guard();
CREATE TRIGGER configuration_onboarding_operation_guard BEFORE INSERT OR UPDATE OF state ON configuration_deployment
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_operation_guard();
CREATE TRIGGER resource_onboarding_operation_guard BEFORE INSERT OR UPDATE OF status ON operation_execution
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_operation_guard();

CREATE FUNCTION infradesk_onboarding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.input_snapshot IS DISTINCT FROM OLD.input_snapshot OR NEW.organization_id<>OLD.organization_id
    OR NEW.integration_id<>OLD.integration_id OR NEW.resource_id<>OLD.resource_id OR NEW.created_by<>OLD.created_by
    OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_SNAPSHOT_IMMUTABLE'; END IF;
  IF OLD.state IN ('SUCCEEDED','FAILED','UNKNOWN') AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_TERMINAL'; END IF;
  IF OLD.external_node_id IS NOT NULL AND NEW.external_node_id IS DISTINCT FROM OLD.external_node_id THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_IDENTITY_IMMUTABLE'; END IF;
  IF NEW.state='SUCCEEDED' AND (NEW.phase<>'FINAL_VERIFY' OR
    (SELECT count(*) FROM remnawave_node_onboarding_phase WHERE run_id=NEW.id AND state='SUCCEEDED')<>13) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_VERIFICATION_REQUIRED'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER remnawave_onboarding_immutable BEFORE UPDATE ON remnawave_node_onboarding
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_immutable();

-- Manual binding edits must not race a reviewed onboarding on the same server.
CREATE FUNCTION infradesk_onboarding_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE binding_row integration_resource_binding; onboarding remnawave_node_onboarding; external_id text; source_integration uuid;
BEGIN
  IF TG_OP='DELETE' THEN binding_row := OLD; ELSE binding_row := NEW; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(binding_row.organization_id::text || ':' || binding_row.resource_id::text,1));
  SELECT * INTO onboarding FROM remnawave_node_onboarding WHERE organization_id=binding_row.organization_id
    AND resource_id=binding_row.resource_id AND state IN ('QUEUED','RUNNING');
  IF FOUND THEN
    SELECT o.external_id,o.integration_id INTO external_id,source_integration FROM integration_inventory_object o WHERE o.id=binding_row.inventory_object_id;
    IF TG_OP='DELETE' OR onboarding.external_node_id IS NULL OR external_id IS DISTINCT FROM onboarding.external_node_id::text
      OR source_integration IS DISTINCT FROM onboarding.integration_id THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514';
    END IF;
  END IF;
  IF TG_OP='UPDATE' AND OLD.resource_id IS DISTINCT FROM NEW.resource_id THEN
    PERFORM pg_advisory_xact_lock(hashtextextended(OLD.organization_id::text || ':' || OLD.resource_id::text,1));
    IF EXISTS(SELECT 1 FROM remnawave_node_onboarding WHERE organization_id=OLD.organization_id AND resource_id=OLD.resource_id
      AND state IN ('QUEUED','RUNNING')) THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER remnawave_onboarding_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON integration_resource_binding
  FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_binding_guard();

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
  'PROJECT_CREATED','ENVIRONMENT_CREATED','CONNECTION_CREATED','CONNECTION_UPDATED','CONNECTION_DELETED',
  'MONITOR_RULE_CREATED','MONITOR_RULE_UPDATED','MANUAL_SYNC_REQUESTED','CONTAINER_START_REQUESTED',
  'CONTAINER_STOP_REQUESTED','CONTAINER_RESTART_REQUESTED','NOTIFICATION_CHANNEL_CREATED',
  'NOTIFICATION_CHANNEL_UPDATED','NOTIFICATION_CHANNEL_ENABLED','NOTIFICATION_CHANNEL_DISABLED',
  'INTEGRATION_CREATED','INTEGRATION_UPDATED','INTEGRATION_ENABLED','INTEGRATION_DISABLED','INTEGRATION_DELETED',
  'INTEGRATION_TEST_REQUESTED','INTEGRATION_SYNC_REQUESTED','INTEGRATION_RESOURCE_BOUND',
  'INTEGRATION_RESOURCE_UNBOUND','INTEGRATION_ACTION_REQUESTED','INTEGRATION_MANAGEMENT_MODE_CHANGED',
  'INTEGRATION_DESIRED_STATE_SET','INTEGRATION_DESIRED_STATE_REMOVED','INTEGRATION_CONFIG_PROFILE_ADOPTED',
  'INTEGRATION_CONFIG_REVISION_CREATED','INTEGRATION_CONFIG_DEPLOYMENT_REQUESTED',
  'INTEGRATION_CONFIG_ROLLOUT_REQUESTED','INTEGRATION_CONFIG_ROLLOUT_CANCELLED','ACCOUNT_PASSWORD_CHANGED',
  'ACCOUNT_PROFILE_UPDATED','ACCOUNT_SESSION_REVOKED','ACCOUNT_OTHER_SESSIONS_REVOKED','ACCOUNT_ALL_SESSIONS_REVOKED',
  'TERMINAL_SESSION_OPENED','TERMINAL_SESSION_CLOSED','CONFIGURATION_PROFILE_CREATED','CONFIGURATION_PROFILE_UPDATED',
  'CONFIGURATION_PROFILE_ARCHIVED','CONFIGURATION_REVISION_CREATED','CONFIGURATION_ASSIGNMENT_CREATED',
  'CONFIGURATION_ASSIGNMENT_UPDATED','CONFIGURATION_ASSIGNMENT_REMOVED','CONFIGURATION_DEPLOYMENT_REQUESTED',
  'CONFIGURATION_DEPLOYMENT_CANCELLED','CONFIGURATION_ASSIGNMENTS_PROMOTED','CONFIGURATION_ROLLOUT_REQUESTED',
  'CONFIGURATION_ROLLOUT_CANCELLED','CONFIGURATION_RULE_CREATED','CONFIGURATION_RULE_UPDATED',
  'CONFIGURATION_RULE_ENABLED','CONFIGURATION_RULE_DISABLED','CONFIGURATION_RULE_ARCHIVED',
  'CONFIGURATION_RULE_REVISION_PROMOTED','CONFIGURATION_RULE_RESOURCE_EXCLUDED','CONFIGURATION_RULE_RESOURCE_INCLUDED',
  'CONFIGURATION_RULE_RECONCILE_REQUESTED','CONFIGURATION_ASSIGNMENT_ADOPTED','CONFIGURATION_ASSIGNMENT_DETACHED',
  'RESOURCE_LABELS_UPDATED','PROVISIONING_RUN_REQUESTED','SERVER_PROFILE_CREATED','SERVER_PROFILE_REVISION_CREATED',
  'SERVER_PROFILE_ASSIGNED','SERVER_PROFILE_UNASSIGNED','SERVER_PROFILE_ARCHIVED','SERVER_PROFILE_APPLY_REQUESTED','REMNAWAVE_NODE_ONBOARDING_REQUESTED','REMNAWAVE_NODE_ONBOARDING_COMPLETED'
));

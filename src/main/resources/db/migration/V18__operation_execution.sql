ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
  'PROJECT_CREATED','ENVIRONMENT_CREATED','CONNECTION_CREATED','CONNECTION_UPDATED',
  'CONNECTION_DELETED','MONITOR_RULE_CREATED','MONITOR_RULE_UPDATED','MANUAL_SYNC_REQUESTED',
  'CONTAINER_START_REQUESTED','CONTAINER_STOP_REQUESTED','CONTAINER_RESTART_REQUESTED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_target_type;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_target_type CHECK (target_type IN (
  'PROJECT','ENVIRONMENT','CONNECTION','MONITOR_RULE','RESOURCE'
));

CREATE TABLE operation_execution (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  resource_id uuid NOT NULL,
  actor_user_id uuid NOT NULL REFERENCES user_account(id),
  operation varchar(32) NOT NULL,
  target_connection_id uuid NOT NULL,
  target_external_type varchar(64) NOT NULL,
  target_external_id varchar(512) NOT NULL,
  status varchar(16) NOT NULL,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  error_code varchar(64),
  error_message varchar(512),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT fk_operation_execution_resource_tenant FOREIGN KEY (resource_id, organization_id)
    REFERENCES resource(id, organization_id),
  CONSTRAINT ck_operation_execution_operation CHECK (operation IN ('CONTAINER_START','CONTAINER_STOP','CONTAINER_RESTART')),
  CONSTRAINT ck_operation_execution_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
  CONSTRAINT ck_operation_execution_lifecycle CHECK (
    (status = 'RUNNING' AND finished_at IS NULL AND error_code IS NULL AND error_message IS NULL) OR
    (status = 'SUCCEEDED' AND finished_at IS NOT NULL AND error_code IS NULL AND error_message IS NULL) OR
    (status IN ('FAILED','UNKNOWN') AND finished_at IS NOT NULL AND error_code IS NOT NULL AND error_message IS NOT NULL)
  )
);

CREATE UNIQUE INDEX ux_operation_execution_resource_running
  ON operation_execution(organization_id, resource_id) WHERE status = 'RUNNING';
CREATE INDEX ix_operation_execution_resource_history
  ON operation_execution(organization_id, resource_id, started_at DESC, id DESC);

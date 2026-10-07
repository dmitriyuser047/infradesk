-- Observation can resolve node actions/config writes; onboarding requires a successful descendant.
-- UNKNOWN history stays immutable. All checks run under the existing integration admission lock.
CREATE FUNCTION infradesk_integration_unresolved_unknown(org uuid, integration_id uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
  WITH RECURSIVE recovered(root,id,state) AS (
    SELECT id,id,state FROM remnawave_node_onboarding
      WHERE organization_id=org AND integration_id=$2 AND state='UNKNOWN'
    UNION
    SELECT r.root,c.id,c.state FROM recovered r JOIN remnawave_node_onboarding c
      ON c.input_snapshot->'recovery'->>'sourceRunId'=r.id::text
      WHERE c.organization_id=org AND c.integration_id=$2 AND c.state<>'PLANNED'
  )
  SELECT EXISTS (SELECT 1 FROM recovered r WHERE r.id=r.root
      AND NOT EXISTS (SELECT 1 FROM recovered done WHERE done.root=r.root AND done.state='SUCCEEDED'))
    OR EXISTS (SELECT 1 FROM integration_action_execution a
      WHERE a.organization_id=org AND a.integration_id=$2 AND a.status='UNKNOWN'
        AND NOT EXISTS (SELECT 1 FROM integration_inventory_object o WHERE o.organization_id=org
          AND o.integration_id=$2 AND o.id=a.inventory_object_id AND o.last_seen_at>a.finished_at))
    OR EXISTS (SELECT 1 FROM integration_config_deployment d
      WHERE d.organization_id=org AND d.integration_id=$2 AND d.status='UNKNOWN'
        AND NOT EXISTS (SELECT 1 FROM integration_inventory_object o WHERE o.organization_id=org
          AND o.integration_id=$2 AND o.id=d.inventory_object_id AND o.last_seen_at>d.finished_at))
    OR EXISTS (SELECT 1 FROM integration_config_rollout WHERE organization_id=org AND integration_id=$2 AND status='UNKNOWN')
    OR EXISTS (SELECT 1 FROM remnawave_fleet_rollout WHERE organization_id=org AND integration_id=$2 AND state='UNKNOWN')
    OR EXISTS (SELECT 1 FROM remnawave_fleet_upgrade_run WHERE organization_id=org AND integration_id=$2 AND state='UNKNOWN');
$$;

DO $$
DECLARE prior text;
BEGIN
  SELECT pg_get_constraintdef(oid) INTO prior FROM pg_constraint
    WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_action';
  ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
  EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action = ' ||
    quote_literal('INTEGRATION_RECOVERY_ABANDONED') || ' OR ' || substring(prior FROM 8);
END $$;

-- A second guard retains the V55 active-work checks and makes direct tombstone writes fail closed.
CREATE FUNCTION infradesk_integration_unknown_deletion_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL
    AND infradesk_integration_unresolved_unknown(OLD.organization_id,OLD.id)
    AND NOT EXISTS (SELECT 1 FROM audit_event WHERE organization_id=OLD.organization_id
      AND target_id=OLD.id AND target_type='INTEGRATION' AND action='INTEGRATION_RECOVERY_ABANDONED') THEN
    RAISE EXCEPTION 'INTEGRATION_RECOVERY_REQUIRED' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER integration_unknown_deletion_guard BEFORE UPDATE ON integration
  FOR EACH ROW EXECUTE FUNCTION infradesk_integration_unknown_deletion_guard();

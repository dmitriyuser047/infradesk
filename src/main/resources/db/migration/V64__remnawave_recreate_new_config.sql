-- Controlled replacement keeps old ownership separate from the new desired intent.
ALTER TABLE remnawave_node_onboarding DROP CONSTRAINT remnawave_node_onboarding_phase_check;
ALTER TABLE remnawave_node_onboarding ADD CONSTRAINT remnawave_node_onboarding_phase_check CHECK(phase IN
 ('VALIDATE','PREPARE_SERVER','PROTOCOL_PREFLIGHT','CONFIRM_PREVIOUS_NODE_ABSENT','UNBIND_PREVIOUS_NODE','RETIRE_PREVIOUS_CLIENT_FIREWALL','VERIFY_PREVIOUS_RETIRED','DELETE_NODE','CONFIRM_NODE_DELETED','RETIRE_NODE_FIREWALL','RETIRE_LOCAL_NODE','CREATE_NODE',
 'GET_INSTALLATION_DATA','RESOLVE_PANEL_SOURCE','CONFIGURE_NODE_FIREWALL','ADD_PANEL_SOURCES','FINALIZE_PANEL_SOURCES',
 'OBSERVE_PANEL_SOURCE','ADD_OBSERVED_PANEL_SOURCE','VERIFY_OBSERVED_PANEL','UPDATE_NODE_ADDRESS',
 'INSTALL_NODE','START_NODE','VERIFY_LOCAL_NODE','WAIT_FOR_PANEL','SYNC_INVENTORY','BIND_RESOURCE','SET_DESIRED_STATE','FINAL_VERIFY',
 'CREATE_PROTOCOL_PROFILE','ISSUE_TLS','INSTALL_TLS','CONFIGURE_CLIENT_FIREWALL','VERIFY_PROTOCOL'));
ALTER TABLE remnawave_node_onboarding_phase DROP CONSTRAINT remnawave_node_onboarding_phase_position_check;
ALTER TABLE remnawave_node_onboarding_phase ADD CONSTRAINT remnawave_node_onboarding_phase_position_check CHECK(position BETWEEN 0 AND 33);
ALTER FUNCTION infradesk_onboarding_expected_phases(jsonb) RENAME TO infradesk_onboarding_v7_expected_phases;
CREATE FUNCTION infradesk_onboarding_expected_phases(snapshot jsonb) RETURNS text[] LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE expected text[]; previous jsonb;
BEGIN
 IF snapshot->'recovery'->>'action' IS DISTINCT FROM 'RECREATE_WITH_NEW_CONFIG' THEN
  RETURN infradesk_onboarding_v7_expected_phases(snapshot);
 END IF;
 previous:=snapshot->'recovery'->'previousInstallation';
 expected:=infradesk_onboarding_v7_expected_phases(jsonb_set(snapshot,'{recovery,action}','"RECREATE"'::jsonb));
 SELECT array_agg(p ORDER BY position) INTO expected FROM (
  SELECT p,ordinality::numeric position FROM unnest(expected) WITH ORDINALITY AS phases(p,ordinality)
  UNION ALL SELECT 'CONFIRM_PREVIOUS_NODE_ABSENT',array_position(expected,'RETIRE_NODE_FIREWALL')::numeric-0.3
  UNION ALL SELECT 'UNBIND_PREVIOUS_NODE',array_position(expected,'RETIRE_NODE_FIREWALL')::numeric-0.2
    WHERE previous->>'inventoryObjectId' IS NOT NULL
  UNION ALL SELECT 'RETIRE_PREVIOUS_CLIENT_FIREWALL',array_position(expected,'RETIRE_NODE_FIREWALL')::numeric-0.1
    WHERE previous->>'protocol' IS NOT NULL
  UNION ALL SELECT 'VERIFY_PREVIOUS_RETIRED',array_position(expected,'RETIRE_LOCAL_NODE')::numeric+0.1
 ) phases;
 RETURN expected;
END $$;

-- A replacement source is persisted same-tenant history, never a client-supplied identity.
CREATE FUNCTION infradesk_onboarding_new_config_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source remnawave_node_onboarding; proof jsonb; old jsonb; expected jsonb; previous uuid;
BEGIN
 proof:=NEW.input_snapshot->'recovery';
 IF proof->>'action' IS DISTINCT FROM 'RECREATE_WITH_NEW_CONFIG' THEN RETURN NEW; END IF;
 SELECT * INTO source FROM remnawave_node_onboarding WHERE id=(proof->>'sourceRunId')::uuid
  AND organization_id=NEW.organization_id AND integration_id=NEW.integration_id AND resource_id=NEW.resource_id
  AND state IN ('SUCCEEDED','FAILED','UNKNOWN');
 IF NOT FOUND THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_REPLACEMENT_SOURCE_INVALID'; END IF;
 old:=proof->'previousInstallation';
 previous:=coalesce(source.external_node_id,(source.input_snapshot->'recovery'->>'previousExternalNodeId')::uuid);
 IF source.external_node_id IS NULL AND source.input_snapshot->'recovery' ? 'previousInstallation' THEN
  expected:=source.input_snapshot->'recovery'->'previousInstallation';
 ELSE
  expected:=jsonb_build_object('nodeName',source.input_snapshot->>'nodeName','address',source.input_snapshot->>'address',
   'nodePort',source.input_snapshot->'nodePort','protocol',source.input_snapshot->'protocol',
   'certificateId',coalesce(source.input_snapshot->'tlsHttp01'->'certificateId',source.input_snapshot->'tlsCertificateId'));
 END IF;
 IF (NEW.input_snapshot->>'lifecycleVersion')::int IS DISTINCT FROM 7 OR proof->>'state' IS DISTINCT FROM 'CONFIRMED_NOT_FOUND'
  OR source.state='UNKNOWN' AND source.external_node_id IS NULL AND source.phase IN ('CREATE_NODE','CREATE_PROTOCOL_PROFILE','ISSUE_TLS')
  OR previous IS NULL OR proof->>'previousExternalNodeId' IS DISTINCT FROM previous::text
  OR proof->'localInstallation'->>'state' IS NULL OR proof->'localInstallation'->>'state' NOT IN ('OWNED_COMPLETE','OWNED_PARTIAL','OWNED_DAMAGED')
  OR jsonb_typeof(old) IS DISTINCT FROM 'object' OR (old-'inventoryObjectId') IS DISTINCT FROM (expected-'inventoryObjectId')
  OR proof->>'previousNodeAddress' IS DISTINCT FROM old->>'address'
  OR source.input_snapshot->>'connectionId' IS DISTINCT FROM NEW.input_snapshot->>'connectionId'
  OR proof->>'previousCorrelationId' IS DISTINCT FROM (CASE WHEN source.external_node_id IS NOT NULL THEN source.input_snapshot->>'correlationId'
    ELSE coalesce(source.input_snapshot->'recovery'->>'previousCorrelationId',source.input_snapshot->>'correlationId') END)
  OR proof->>'installationOwnerId' IS DISTINCT FROM (CASE WHEN source.external_node_id IS NULL OR
    source.input_snapshot->'recovery'->>'action' IN ('RECOVER','REPAIR_PANEL_CONNECTIVITY')
    THEN coalesce(source.input_snapshot->'recovery'->>'installationOwnerId',source.id::text) ELSE source.id::text END)
  OR proof->>'previousImageReference' IS DISTINCT FROM (CASE WHEN source.external_node_id IS NULL
    THEN coalesce(source.input_snapshot->'recovery'->>'previousImageReference',source.input_snapshot->>'imageReference')
    ELSE CASE WHEN source.input_snapshot->'recovery'->>'action' IN ('RECOVER','REPAIR_PANEL_CONNECTIVITY') THEN coalesce(source.input_snapshot->'recovery'->>'previousImageReference',source.input_snapshot->>'imageReference') ELSE source.input_snapshot->>'imageReference' END END)
  OR proof->'previousPanelCidrs' IS DISTINCT FROM (CASE WHEN source.external_node_id IS NULL
    THEN coalesce(source.input_snapshot->'recovery'->'previousPanelCidrs',source.input_snapshot->'panelCidrs')
    ELSE CASE WHEN source.observed_panel_source->>'status'='AUTO_OBSERVED' THEN source.observed_panel_source->'sources' ELSE source.input_snapshot->'panelCidrs' END END)
  OR NEW.input_snapshot->>'correlationId' IS NULL OR NEW.input_snapshot->>'correlationId'=proof->>'previousCorrelationId'
  OR TG_OP='INSERT' AND (NEW.external_node_id IS NOT NULL OR NEW.protocol_binding IS NOT NULL) THEN
   RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_REPLACEMENT_SOURCE_INVALID';
 END IF;
 IF TG_OP='INSERT' OR NEW.state='QUEUED' THEN
  IF EXISTS(SELECT 1 FROM integration_inventory_object WHERE organization_id=NEW.organization_id AND integration_id=NEW.integration_id
    AND object_type='NODE' AND external_id=previous::text AND is_active) OR old->>'inventoryObjectId' IS NOT NULL AND NOT EXISTS(SELECT 1 FROM integration_inventory_object o
   JOIN integration_resource_binding b ON b.inventory_object_id=o.id AND b.organization_id=o.organization_id
   WHERE o.id=(old->>'inventoryObjectId')::uuid AND o.organization_id=NEW.organization_id AND o.integration_id=NEW.integration_id
     AND o.object_type='NODE' AND NOT o.is_active AND o.external_id=previous::text AND b.resource_id=NEW.resource_id) OR
   EXISTS(SELECT 1 FROM integration_resource_binding b JOIN integration_inventory_object o ON o.id=b.inventory_object_id
    WHERE b.organization_id=NEW.organization_id AND b.resource_id=NEW.resource_id AND o.object_type='NODE' AND
     (o.id::text IS DISTINCT FROM old->>'inventoryObjectId' OR o.is_active OR o.integration_id<>NEW.integration_id OR o.external_id<>previous::text)) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER remnawave_new_config_guard BEFORE INSERT OR UPDATE ON remnawave_node_onboarding
 FOR EACH ROW EXECUTE FUNCTION infradesk_onboarding_new_config_guard();

CREATE OR REPLACE FUNCTION infradesk_remnawave_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE resource uuid; node_external text; member record;
BEGIN
  IF TG_TABLE_NAME='integration_action_execution' THEN
    IF NEW.status NOT IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND OLD.status IN ('QUEUED','RUNNING') THEN RETURN NEW; END IF;
  ELSE
    IF NEW.state NOT IN ('PLANNED','QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND OLD.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN RETURN NEW; END IF;
  END IF;
  PERFORM id FROM integration WHERE organization_id=NEW.organization_id AND id=NEW.integration_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'INTEGRATION_NOT_FOUND' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='integration_action_execution' THEN
    SELECT external_id INTO node_external FROM integration_inventory_object WHERE id=NEW.inventory_object_id
      AND organization_id=NEW.organization_id AND integration_id=NEW.integration_id AND object_type='NODE';
    FOR resource IN
      SELECT b.resource_id FROM integration_resource_binding b WHERE b.organization_id=NEW.organization_id
        AND b.integration_id=NEW.integration_id AND b.inventory_object_id=NEW.inventory_object_id
      UNION SELECT r.resource_id FROM remnawave_node_onboarding r WHERE r.organization_id=NEW.organization_id
        AND r.integration_id=NEW.integration_id AND r.state<>'PLANNED' AND
        (r.external_node_id::text=node_external OR r.input_snapshot->'recovery'->>'previousExternalNodeId'=node_external)
      ORDER BY 1
    LOOP
      PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':'||resource::text,1));
      IF EXISTS(SELECT 1 FROM remnawave_node_onboarding r WHERE r.organization_id=NEW.organization_id
        AND r.resource_id=resource AND r.state IN ('QUEUED','RUNNING')) THEN
        RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
      END IF;
    END LOOP;
  ELSIF TG_TABLE_NAME='remnawave_node_onboarding' AND NEW.state IN ('QUEUED','RUNNING') THEN
    FOR member IN SELECT m.fleet_id FROM remnawave_fleet_membership m WHERE m.organization_id=NEW.organization_id
      AND m.integration_id=NEW.integration_id AND m.resource_id=NEW.resource_id AND m.removed_at IS NULL ORDER BY m.fleet_id
    LOOP
      PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':fleet:'||member.fleet_id::text,2));
      IF EXISTS(SELECT 1 FROM remnawave_fleet_rollout WHERE fleet_id=member.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))
        OR EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE fleet_id=member.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
        RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
      END IF;
    END LOOP;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':'||NEW.resource_id::text,1));
    IF EXISTS(SELECT 1 FROM integration_action_execution a WHERE a.organization_id=NEW.organization_id
      AND a.status IN ('QUEUED','RUNNING') AND (
        EXISTS(SELECT 1 FROM integration_resource_binding b WHERE b.organization_id=NEW.organization_id
          AND b.inventory_object_id=a.inventory_object_id AND b.resource_id=NEW.resource_id)
        OR a.integration_id=NEW.integration_id AND (a.external_id_snapshot=NEW.external_node_id::text
          OR a.external_id_snapshot=NEW.input_snapshot->'recovery'->>'previousExternalNodeId'))) THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
    IF NEW.input_snapshot->'recovery'->>'action' IN ('RECREATE','DELETE_RECREATE','RECREATE_WITH_NEW_CONFIG') AND
      (SELECT count(*) FROM remnawave_fleet_membership WHERE organization_id=NEW.organization_id
        AND integration_id=NEW.integration_id AND resource_id=NEW.resource_id AND removed_at IS NULL)>1 THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW' USING ERRCODE='23514';
    END IF;
  ELSIF TG_TABLE_NAME IN ('remnawave_fleet_rollout','remnawave_fleet_upgrade_run') AND
    NEW.state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK') THEN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':fleet:'||NEW.fleet_id::text,2));
    IF EXISTS(SELECT 1 FROM remnawave_fleet_membership m JOIN remnawave_node_onboarding r
      ON r.organization_id=m.organization_id AND r.integration_id=m.integration_id AND r.resource_id=m.resource_id
      WHERE m.organization_id=NEW.organization_id AND m.integration_id=NEW.integration_id AND m.fleet_id=NEW.fleet_id
        AND m.removed_at IS NULL AND r.state IN ('QUEUED','RUNNING')) THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION infradesk_membership_lifecycle_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r remnawave_node_onboarding; approved uuid; token uuid;
BEGIN
  IF TG_OP='UPDATE' AND (NEW.inventory_node_id,NEW.resource_id,NEW.fleet_id,NEW.integration_id,NEW.removed_at)
    IS NOT DISTINCT FROM (OLD.inventory_node_id,OLD.resource_id,OLD.fleet_id,OLD.integration_id,OLD.removed_at) THEN RETURN NEW; END IF;
  PERFORM id FROM integration WHERE id=NEW.integration_id AND organization_id=NEW.organization_id FOR UPDATE;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':fleet:'||NEW.fleet_id::text,2));
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text||':'||NEW.resource_id::text,1));
  IF EXISTS(SELECT 1 FROM remnawave_fleet_rollout WHERE fleet_id=NEW.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))
    OR EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE fleet_id=NEW.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  SELECT * INTO r FROM remnawave_node_onboarding WHERE organization_id=NEW.organization_id AND resource_id=NEW.resource_id
    AND state IN ('QUEUED','RUNNING');
  IF FOUND THEN
    approved:=nullif(current_setting('infradesk.onboarding_replacement_id',true),'')::uuid;
    token:=nullif(current_setting('infradesk.onboarding_replacement_token',true),'')::uuid;
    IF TG_OP<>'UPDATE' OR approved IS DISTINCT FROM r.id OR token IS DISTINCT FROM r.claim_token
      OR r.state<>'RUNNING' OR r.phase<>'BIND_RESOURCE' OR r.claim_deadline<=clock_timestamp()
      OR r.integration_id<>NEW.integration_id OR NEW.organization_id<>OLD.organization_id
      OR NEW.integration_id<>OLD.integration_id OR NEW.fleet_id<>OLD.fleet_id OR NEW.resource_id<>OLD.resource_id
      OR NEW.removed_at IS DISTINCT FROM OLD.removed_at OR r.input_snapshot->'recovery'->>'action' NOT IN ('RECREATE','DELETE_RECREATE','RECREATE_WITH_NEW_CONFIG')
      OR NOT EXISTS(SELECT 1 FROM integration_inventory_object WHERE id=OLD.inventory_node_id
        AND NOT is_active AND external_id=r.input_snapshot->'recovery'->>'previousExternalNodeId')
      OR NOT EXISTS(SELECT 1 FROM integration_inventory_object WHERE id=NEW.inventory_node_id
        AND is_active AND external_id=r.external_node_id::text) THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
    END IF;
  ELSIF TG_OP='UPDATE' AND NEW.inventory_node_id<>OLD.inventory_node_id THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_REPLACEMENT_REQUIRES_ONBOARDING' USING ERRCODE='23514';
  END IF;
  IF NEW.removed_at IS NULL AND EXISTS(SELECT 1 FROM remnawave_fleet_membership WHERE organization_id=NEW.organization_id
    AND integration_id=NEW.integration_id AND resource_id=NEW.resource_id AND removed_at IS NULL AND id<>NEW.id) THEN
    RAISE EXCEPTION 'REMNAWAVE_FLEET_RESOURCE_ALREADY_MEMBER' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION infradesk_onboarding_replace_membership(org uuid, run uuid, token uuid, node uuid, at timestamptz)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r remnawave_node_onboarding; m remnawave_fleet_membership; old_node uuid; integration_key uuid;
BEGIN
  SELECT integration_id INTO integration_key FROM remnawave_node_onboarding WHERE id=run AND organization_id=org;
  PERFORM id FROM integration WHERE id=integration_key AND organization_id=org FOR UPDATE;
  SELECT * INTO r FROM remnawave_node_onboarding WHERE id=run AND organization_id=org FOR UPDATE;
  IF NOT FOUND OR r.state<>'RUNNING' OR r.phase<>'BIND_RESOURCE' OR r.claim_token IS DISTINCT FROM token
    OR r.claim_deadline<=greatest(at,clock_timestamp()) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_LEASE_LOST' USING ERRCODE='23514';
  END IF;
  IF r.input_snapshot->'recovery'->>'action' NOT IN ('RECREATE','DELETE_RECREATE','RECREATE_WITH_NEW_CONFIG') OR r.input_snapshot->'recovery' IS NULL
    OR r.input_snapshot->'recovery'='null'::jsonb THEN RETURN; END IF;
  IF NOT EXISTS(SELECT 1 FROM integration_inventory_object o JOIN integration_resource_binding b ON b.inventory_object_id=o.id
    WHERE o.id=node AND o.organization_id=org AND o.integration_id=r.integration_id AND o.object_type='NODE'
      AND o.is_active AND o.external_id=r.external_node_id::text AND b.resource_id=r.resource_id AND b.organization_id=org) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514';
  END IF;
  SELECT id INTO old_node FROM integration_inventory_object WHERE organization_id=org AND integration_id=r.integration_id
    AND object_type='NODE' AND external_id=r.input_snapshot->'recovery'->>'previousExternalNodeId';
  IF old_node IS NULL THEN RETURN; END IF;
  IF EXISTS(SELECT 1 FROM integration_inventory_object WHERE id=old_node AND is_active) OR
    EXISTS(SELECT 1 FROM integration_resource_binding WHERE organization_id=org AND integration_id=r.integration_id
      AND inventory_object_id=old_node AND resource_id<>r.resource_id) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514';
  END IF;
  SELECT * INTO m FROM remnawave_fleet_membership WHERE organization_id=org AND integration_id=r.integration_id
    AND resource_id=r.resource_id AND inventory_node_id=old_node AND removed_at IS NULL;
  IF NOT FOUND THEN
    IF EXISTS(SELECT 1 FROM remnawave_fleet_membership WHERE organization_id=org AND integration_id=r.integration_id
      AND resource_id=r.resource_id AND removed_at IS NULL AND inventory_node_id<>node) THEN
      RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514';
    END IF;
    RETURN;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(org::text||':fleet:'||m.fleet_id::text,2));
  PERFORM pg_advisory_xact_lock(hashtextextended(org::text||':'||r.resource_id::text,1));
  PERFORM id FROM remnawave_fleet WHERE id=m.fleet_id AND organization_id=org AND NOT archived FOR UPDATE;
  IF NOT FOUND OR EXISTS(SELECT 1 FROM remnawave_fleet_rollout WHERE fleet_id=m.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))
    OR EXISTS(SELECT 1 FROM remnawave_fleet_upgrade_run WHERE fleet_id=m.fleet_id AND state IN ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) THEN
    RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_RESOURCE_BUSY' USING ERRCODE='23514';
  END IF;
  SELECT * INTO m FROM remnawave_fleet_membership WHERE id=m.id AND removed_at IS NULL FOR UPDATE;
  IF NOT FOUND OR m.inventory_node_id<>old_node THEN RAISE EXCEPTION 'REMNAWAVE_ONBOARDING_BINDING_CONFLICT' USING ERRCODE='23514'; END IF;
  DELETE FROM remnawave_fleet_node_assessment WHERE membership_id=m.id;
  DELETE FROM remnawave_node_image_observation WHERE membership_id=m.id;
  PERFORM set_config('infradesk.onboarding_replacement_id',run::text,true);
  PERFORM set_config('infradesk.onboarding_replacement_token',token::text,true);
  UPDATE remnawave_fleet_membership SET inventory_node_id=node,version=version+1,next_check_at=at,
    claimed_by=NULL,claim_token=NULL,claim_deadline=NULL WHERE id=m.id;
  INSERT INTO remnawave_fleet_membership_replacement VALUES(run,org,r.integration_id,m.id,m.fleet_id,r.resource_id,
    old_node,node,m.version,m.version+1,at);
END $$;

CREATE OR REPLACE FUNCTION infradesk_onboarding_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE binding_row integration_resource_binding; onboarding remnawave_node_onboarding; external_id text; source_integration uuid;
BEGIN
  IF TG_OP='DELETE' THEN binding_row := OLD; ELSE binding_row := NEW; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(binding_row.organization_id::text || ':' || binding_row.resource_id::text,1));
  SELECT * INTO onboarding FROM remnawave_node_onboarding WHERE organization_id=binding_row.organization_id
    AND resource_id=binding_row.resource_id AND state IN ('QUEUED','RUNNING');
  IF FOUND THEN
    SELECT o.external_id,o.integration_id INTO external_id,source_integration FROM integration_inventory_object o WHERE o.id=binding_row.inventory_object_id;
    IF TG_OP='DELETE' AND onboarding.state='RUNNING' AND ((onboarding.phase='BIND_RESOURCE' AND onboarding.input_snapshot->'recovery'->>'action' IN ('RECREATE','DELETE_RECREATE')) OR
      (onboarding.phase='UNBIND_PREVIOUS_NODE' AND onboarding.input_snapshot->'recovery'->>'action'='RECREATE_WITH_NEW_CONFIG'
       AND binding_row.inventory_object_id::text=onboarding.input_snapshot->'recovery'->'previousInstallation'->>'inventoryObjectId'))
      AND onboarding.claim_deadline>clock_timestamp()
      AND external_id=onboarding.input_snapshot->'recovery'->>'previousExternalNodeId'
      AND source_integration=onboarding.integration_id
      AND EXISTS(SELECT 1 FROM integration_inventory_object o WHERE o.id=binding_row.inventory_object_id
        AND o.organization_id=onboarding.organization_id AND NOT o.is_active) THEN RETURN OLD; END IF;
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

DO $$
DECLARE previous_check text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous_check FROM pg_constraint
 WHERE conrelid='audit_event'::regclass AND conname='ck_audit_event_action';
 IF previous_check IS NULL THEN RAISE EXCEPTION 'AUDIT_ACTION_CONSTRAINT_MISSING'; END IF;
 ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_action;
 EXECUTE 'ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_action CHECK (' || substring(previous_check FROM 7) ||
  ' OR action IN (''REMNAWAVE_NODE_REPLACEMENT_REQUESTED'',''REMNAWAVE_PREVIOUS_INSTALLATION_RETIRED''))';
END $$;

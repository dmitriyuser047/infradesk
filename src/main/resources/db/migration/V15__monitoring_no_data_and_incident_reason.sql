-- Monitoring v2: numeric comparison operators, a no-data timeout per rule, the NO_DATA rule
-- state and an explicit incident reason.

-- Existing rules keep monitoring their threshold and inherit a no-data timeout of 900 seconds:
-- the smallest SSH sync interval is 300 seconds and the stale sync / scheduler claim horizon is
-- 900 seconds, so three missed synchronizations are needed before a rule reports NO_DATA.
ALTER TABLE monitor_rule
  ADD COLUMN no_data_seconds bigint NOT NULL DEFAULT 900;

ALTER TABLE monitor_rule
  ALTER COLUMN no_data_seconds DROP DEFAULT;

ALTER TABLE monitor_rule
  ADD CONSTRAINT ck_monitor_rule_no_data_seconds_non_negative
    CHECK (no_data_seconds >= 0);

ALTER TABLE monitor_rule
  DROP CONSTRAINT ck_monitor_rule_operator;

ALTER TABLE monitor_rule
  ADD CONSTRAINT ck_monitor_rule_operator
    CHECK (operator IN (
      'GREATER_THAN',
      'GREATER_THAN_OR_EQUAL',
      'LESS_THAN',
      'LESS_THAN_OR_EQUAL'
    ));

ALTER TABLE monitor_rule_state
  DROP CONSTRAINT ck_monitor_rule_state_status;

ALTER TABLE monitor_rule_state
  ADD CONSTRAINT ck_monitor_rule_state_status
    CHECK (status IN ('OK', 'PENDING', 'FIRING', 'NO_DATA'));

-- NO_DATA has no observed violation, so it carries no pendingSince.
ALTER TABLE monitor_rule_state
  DROP CONSTRAINT ck_monitor_rule_state_pending_since;

ALTER TABLE monitor_rule_state
  ADD CONSTRAINT ck_monitor_rule_state_pending_since
    CHECK (
      (status IN ('OK', 'NO_DATA') AND pending_since IS NULL)
      OR
      (status IN ('PENDING', 'FIRING') AND pending_since IS NOT NULL)
    );

-- Incidents created before monitoring v2 can only be threshold violations.
ALTER TABLE incident
  ADD COLUMN reason varchar(32) NOT NULL DEFAULT 'THRESHOLD';

ALTER TABLE incident
  ALTER COLUMN reason DROP DEFAULT;

ALTER TABLE incident
  ADD CONSTRAINT ck_incident_reason
    CHECK (reason IN ('THRESHOLD', 'NO_DATA'));

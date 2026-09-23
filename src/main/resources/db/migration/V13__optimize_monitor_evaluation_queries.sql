CREATE INDEX ix_metric_observation_resource_metric_observed_at
  ON metric_observation (
    organization_id,
    resource_id,
    metric_code,
    observed_at DESC
  );

CREATE INDEX ix_monitor_rule_enabled_resource
  ON monitor_rule (
    organization_id,
    resource_id
  )
  WHERE enabled = true;

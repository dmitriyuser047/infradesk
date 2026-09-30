-- Keep deployment history attached to its original binding while requiring explicit adoption
-- after an integration endpoint or credential changes.
ALTER TABLE integration_config_profile_binding ADD COLUMN detached_at timestamptz;
ALTER TABLE integration_config_profile_binding DROP CONSTRAINT uq_integration_config_binding_object;
ALTER TABLE integration_config_profile_binding DROP CONSTRAINT uq_integration_config_binding_profile;
CREATE UNIQUE INDEX ux_integration_config_binding_active_object
  ON integration_config_profile_binding (organization_id, integration_id, inventory_object_id)
  WHERE detached_at IS NULL;
CREATE UNIQUE INDEX ux_integration_config_binding_active_profile
  ON integration_config_profile_binding (organization_id, configuration_profile_id)
  WHERE detached_at IS NULL;

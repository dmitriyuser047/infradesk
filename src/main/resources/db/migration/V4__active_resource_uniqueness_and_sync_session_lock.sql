DROP INDEX ux_resource_root_code_ci;

CREATE UNIQUE INDEX ux_resource_root_code_ci
    ON resource (
                 organization_id,
                 environment_id,
                 resource_type_id,
                 lower(code)
        )
    WHERE parent_resource_id IS NULL
      AND is_active;

DROP INDEX ux_resource_child_code_ci;

CREATE UNIQUE INDEX ux_resource_child_code_ci
    ON resource (
                 organization_id,
                 parent_resource_id,
                 resource_type_id,
                 lower(code)
        )
    WHERE parent_resource_id IS NOT NULL
      AND is_active;

CREATE UNIQUE INDEX ux_sync_session_running_connection
    ON sync_session (
                     organization_id,
                     connection_id
        )
    WHERE status = 'RUNNING';

CREATE TABLE sync_session (
                              id               uuid        PRIMARY KEY,
                              organization_id  uuid        NOT NULL,
                              connection_id    uuid        NOT NULL,
                              started_at       timestamptz NOT NULL,
                              finished_at      timestamptz,
                              status           varchar(32) NOT NULL,

                              CONSTRAINT fk_sync_session_connection_tenant
                                  FOREIGN KEY (connection_id, organization_id)
                                  REFERENCES connection (id, organization_id),

                              CONSTRAINT uq_sync_session_id_organization
                                  UNIQUE (id, organization_id),

                              CONSTRAINT ck_sync_session_status
                                  CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),

                              CONSTRAINT ck_sync_session_finished_at
                                  CHECK (
                                    (status = 'RUNNING' AND finished_at IS NULL)
                                    OR (status IN ('COMPLETED', 'FAILED') AND finished_at IS NOT NULL)
                                  )
);

CREATE INDEX ix_sync_session_connection_started
    ON sync_session (organization_id, connection_id, started_at DESC);

ALTER TABLE external_ref
    ADD COLUMN last_seen_sync_session_id uuid;

ALTER TABLE external_ref
    ADD CONSTRAINT fk_external_ref_last_seen_sync_session_tenant
    FOREIGN KEY (last_seen_sync_session_id, organization_id)
    REFERENCES sync_session (id, organization_id);

CREATE INDEX ix_external_ref_connection_sync_session
    ON external_ref (organization_id, connection_id, last_seen_sync_session_id);

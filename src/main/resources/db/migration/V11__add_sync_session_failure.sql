ALTER TABLE sync_session
  ADD COLUMN error_code varchar(64),
  ADD COLUMN error_message varchar(512);

UPDATE sync_session
SET error_code = 'SYNC_FAILED',
    error_message = 'Synchronization failed'
WHERE status = 'FAILED';

ALTER TABLE sync_session
  ADD CONSTRAINT ck_sync_session_failure_metadata CHECK (
    (status = 'FAILED' AND error_code IS NOT NULL AND error_message IS NOT NULL)
    OR (status IN ('RUNNING', 'COMPLETED') AND error_code IS NULL AND error_message IS NULL)
  );

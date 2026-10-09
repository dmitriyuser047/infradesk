-- A shell whose socket was lost may now stay detached until a socket resumes it. When none does,
-- the session closes with its own reason instead of one that would misstate who ended it.
DO $$
DECLARE prior text;
BEGIN
  SELECT conname INTO prior FROM pg_constraint
    WHERE conrelid = 'terminal_session'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%SESSION_VALIDATION_FAILED%';
  IF prior IS NULL THEN
    RAISE EXCEPTION 'terminal_session close_reason constraint not found';
  END IF;
  EXECUTE format('ALTER TABLE terminal_session DROP CONSTRAINT %I', prior);
END $$;

ALTER TABLE terminal_session ADD CONSTRAINT ck_terminal_session_close_reason CHECK (close_reason IN (
  'CLIENT_CLOSE','REMOTE_EOF','IDLE_TIMEOUT','MAX_LIFETIME',
  'SERVER_SHUTDOWN','LEASE_EXPIRED','SSH_OPEN_FAILED','SSH_FAILURE','PROTOCOL_ERROR',
  'AUTH_SESSION_ENDED','TERMINAL_PERMISSION_REVOKED','CONNECTION_CHANGED',
  'TERMINAL_SESSION_REVOKED','SESSION_VALIDATION_FAILED','DETACH_TIMEOUT'
));

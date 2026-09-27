-- Self-service account security history. This is intentionally separate from audit_event: it is
-- account/auth telemetry, never organization business audit, and does not record failed logins.
CREATE TABLE account_security_event (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES user_account(id),
  event_type varchar(32) NOT NULL CHECK (event_type IN (
    'LOGIN_SUCCEEDED', 'PASSWORD_CHANGED', 'SESSION_REVOKED',
    'OTHER_SESSIONS_REVOKED', 'ALL_SESSIONS_REVOKED'
  )),
  occurred_at timestamptz NOT NULL,
  session_id uuid,
  -- A client IP only after trusted proxy resolution, never a forwarded value supplied directly
  -- by a browser. No password, token, hash, cookie or arbitrary request metadata is stored.
  source varchar(45),
  affected_session_count integer CHECK (affected_session_count >= 0)
);

CREATE INDEX ix_account_security_event_user_occurred
  ON account_security_event (user_id, occurred_at DESC, id DESC);
CREATE INDEX ix_account_security_event_occurred_at ON account_security_event (occurred_at);

-- Distributed brute-force protection for the login endpoint.
--
-- One aggregated row per throttle key, not a row per attempt: the failure count, the window it
-- belongs to, and the moment the key is blocked until. Enforcement is shared by every backend
-- instance because the count lives here and is incremented by one atomic statement. The key is an
-- HMAC digest of the login email or the client source, never the raw value, so the table is not a
-- plaintext journal of who tried to sign in and from where. Login history is a later stage; this
-- table holds only throttle state.

CREATE TABLE auth_login_throttle (
  scope             varchar(16) NOT NULL,
  key_hash          varchar(64) NOT NULL,

  failure_count     integer     NOT NULL DEFAULT 0,
  window_started_at timestamptz NOT NULL,
  last_failure_at   timestamptz NOT NULL,
  -- Set only once the count reaches the maximum; always a bounded, self-expiring cooldown, so a
  -- throttle can never become a permanent account lockout.
  blocked_until     timestamptz,

  updated_at        timestamptz NOT NULL,

  CONSTRAINT pk_auth_login_throttle PRIMARY KEY (scope, key_hash),

  CONSTRAINT ck_auth_login_throttle_scope
    CHECK (scope IN ('SOURCE', 'IDENTIFIER')),

  CONSTRAINT ck_auth_login_throttle_failure_count
    CHECK (failure_count >= 0)
);

-- The lookup path is by (scope, key_hash), which the primary key already covers. This index serves
-- the opportunistic cleanup that removes rows untouched for the retention period.
CREATE INDEX ix_auth_login_throttle_updated_at
  ON auth_login_throttle (updated_at);

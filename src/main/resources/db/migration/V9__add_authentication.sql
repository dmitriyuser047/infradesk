CREATE TABLE user_account (
  id uuid PRIMARY KEY,
  email varchar(320) NOT NULL,
  password_hash varchar(255) NOT NULL,
  display_name varchar(255) NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX ux_user_account_email_ci ON user_account (lower(email));

CREATE TABLE organization_membership (
  user_id uuid NOT NULL REFERENCES user_account(id),
  organization_id uuid NOT NULL REFERENCES organization(id),
  role varchar(16) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (user_id, organization_id)
);

CREATE INDEX ix_organization_membership_organization ON organization_membership (organization_id);

CREATE TABLE auth_session (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES user_account(id),
  token_hash varchar(64) NOT NULL UNIQUE,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);

CREATE INDEX ix_auth_session_user ON auth_session (user_id);

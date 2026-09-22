# InfraDesk frontend

Requirements: Node.js and npm. The InfraDesk backend should be running on `http://localhost:8080`.

```bash
cd frontend
npm install
npm run dev
```

Vite starts on its default development port and proxies `/api` requests to the backend.

Open `/login` to sign in. The backend needs an applied `V9__add_authentication.sql` migration and an initial account. To create one owner account on startup, configure all four variables:

SSH password onboarding also requires applying `V10__add_connection_secret.sql` and setting `INFRADESK_SECRET_MASTER_KEY_BASE64` to a persistent Base64-encoded 32-byte random key before starting the backend. The backend refuses to start without a valid key. Keep it outside the repository and back it up securely: losing or rotating it without re-encrypting stored secrets makes saved SSH passwords unreadable. Existing `env:` SSH references remain supported.

```text
INFRADESK_BOOTSTRAP_EMAIL
INFRADESK_BOOTSTRAP_PASSWORD
INFRADESK_BOOTSTRAP_ORGANIZATION_ID
INFRADESK_BOOTSTRAP_DISPLAY_NAME
```

The organization must already exist. Repeated startups preserve the existing password and membership. Session lifetime defaults to seven days (`INFRADESK_AUTH_SESSION_TTL_SECONDS`); set `INFRADESK_AUTH_COOKIE_SECURE=true` for HTTPS deployments.

After signing in, `/organizations` lists available workspaces. Existing Environment deep links remain available:

```text
/organizations/{organizationId}/environments/{environmentId}
```

Create a production bundle with `npm run build`.

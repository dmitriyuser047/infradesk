# InfraDesk frontend

Requirements: Node.js and npm. The InfraDesk backend should be running on `http://localhost:8080`.

```bash
cd frontend
npm install
npm run dev
```

Vite starts on its default development port and proxies `/api` requests to the backend.

Open `/login` to sign in. The backend needs an applied `V9__add_authentication.sql` migration and an initial account. To create one owner account on startup, configure all four variables:

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

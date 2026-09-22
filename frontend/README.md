# InfraDesk frontend

Requirements: Node.js and npm. The InfraDesk backend should be running on `http://localhost:8080`.

```bash
cd frontend
npm install
npm run dev
```

Vite starts on its default development port and proxies `/api` requests to the backend.

Open an Environment page with:

```text
/organizations/{organizationId}/environments/{environmentId}
```

Create a production bundle with `npm run build`.

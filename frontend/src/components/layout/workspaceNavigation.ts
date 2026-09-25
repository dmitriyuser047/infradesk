export type WorkspaceModule = 'overview' | 'workspace' | 'infrastructure' | 'incidents' | 'connections'

export function activeWorkspaceModule(pathname: string): WorkspaceModule {
  if (/^\/organizations\/[^/]+\/overview\/?$/.test(pathname)) return 'overview'
  if (pathname.includes('/incidents')) return 'incidents'
  if (pathname.includes('/connections')) return 'connections'
  if (pathname.includes('/environments/') && !pathname.includes('/environments/new')) return 'infrastructure'
  return 'workspace'
}

export function workspaceModulePaths(organizationId: string | undefined, environmentId: string | undefined) {
  const base = organizationId ? `/organizations/${encodeURIComponent(organizationId)}` : undefined
  return {
    overview: base ? `${base}/overview` : undefined,
    workspace: base ?? '/organizations',
    infrastructure: base && environmentId ? `${base}/environments/${encodeURIComponent(environmentId)}` : undefined,
    incidents: base ? `${base}/incidents` : undefined,
    connections: base ? `${base}/connections` : undefined,
  }
}

export function contextSearch(params: URLSearchParams): string {
  const query = new URLSearchParams()
  for (const key of ['project', 'environment']) {
    const value = params.get(key)
    if (value) query.set(key, value)
  }
  const encoded = query.toString()
  return encoded ? `?${encoded}` : ''
}

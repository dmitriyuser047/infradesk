/**
 * The sections of the application and where each one lives for a given workspace context.
 *
 * The URL stays the only source of truth for the context: organization in the path, project and
 * environment in `?project=&environment=`. Nothing here keeps state.
 */
export type WorkspaceModule = 'overview' | 'resources' | 'connections' | 'incidents' | 'notifications' | 'configurations' | 'workspace'

export interface WorkspaceScope {
  organizationId?: string
  projectId?: string | null
  environmentId?: string | null
}

/** Which section a path belongs to; null outside any organization (the organization list). */
export function activeWorkspaceModule(pathname: string): WorkspaceModule | null {
  if (!/^\/organizations\/[^/]+/.test(pathname)) return null
  if (/^\/organizations\/[^/]+\/overview\/?$/.test(pathname)) return 'overview'
  if (pathname.includes('/incidents')) return 'incidents'
  if (pathname.includes('/connections')) return 'connections'
  if (pathname.includes('/notifications')) return 'notifications'
  if (/^\/organizations\/[^/]+\/configurations(\/|$)/.test(pathname)) return 'configurations'
  if (/^\/organizations\/[^/]+\/resources\/?$/.test(pathname)) return 'resources'
  if (pathname.includes('/environments/') && !pathname.endsWith('/environments/new')) return 'resources'
  return 'workspace'
}

/** The page of a section inside a context, carrying the context along. */
export function modulePath(module: WorkspaceModule, scope: WorkspaceScope): string | undefined {
  if (!scope.organizationId) return undefined
  const base = `/organizations/${encodeURIComponent(scope.organizationId)}`
  const query = contextQuery(scope)

  switch (module) {
    case 'overview': return `${base}/overview${query}`
    case 'workspace': return `${base}${query}`
    case 'incidents': return `${base}/incidents${query}`
    case 'connections': return `${base}/connections${query}`
    case 'notifications': return `${base}/notifications${query}`
    case 'configurations': return `${base}/configurations${query}`
    case 'resources':
      // Resources are listed per environment; without one the page asks which to open.
      return scope.environmentId && scope.projectId
        ? `${base}/environments/${encodeURIComponent(scope.environmentId)}${contextQuery({ projectId: scope.projectId })}`
        : `${base}/resources${query}`
  }
}

/** `?project=…&environment=…`; an environment is only meaningful together with its project. */
export function contextQuery(scope: Pick<WorkspaceScope, 'projectId' | 'environmentId'>): string {
  const query = new URLSearchParams()
  if (scope.projectId) {
    query.set('project', scope.projectId)
    if (scope.environmentId) query.set('environment', scope.environmentId)
  }
  const encoded = query.toString()
  return encoded ? `?${encoded}` : ''
}

export function contextSearch(params: URLSearchParams): string {
  return contextQuery({ projectId: params.get('project'), environmentId: params.get('environment') })
}

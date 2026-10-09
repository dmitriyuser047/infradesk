/**
 * The sections of the application and where each one lives for a given workspace context.
 *
 * The URL stays the only source of truth for the context: organization in the path, project and
 * environment in `?project=&environment=`. Nothing here keeps state.
 */
export type WorkspaceModule = 'overview' | 'resources' | 'connections' | 'incidents' | 'notifications' | 'integrations' | 'configurations' | 'workspace' | 'members'

export interface WorkspaceScope {
  organizationId?: string
  projectId?: string | null
  environmentId?: string | null
}

/** Which section a path belongs to; null outside any organization (the organization list). */
export function activeWorkspaceModule(pathname: string): WorkspaceModule | null {
  pathname = pathname.split(/[?#]/, 1)[0]
  if (!/^\/organizations\/[^/]+(\/|$)/.test(pathname)) return null
  if (/^\/organizations\/[^/]+\/overview\/?$/.test(pathname)) return 'overview'
  if (/^\/organizations\/[^/]+\/members(\/|$)/.test(pathname)) return 'members'
  if (/^\/organizations\/[^/]+\/incidents(\/|$)/.test(pathname)) return 'incidents'
  if (/^\/organizations\/[^/]+\/connections(\/|$)/.test(pathname)) return 'connections'
  if (/^\/organizations\/[^/]+\/notifications(\/|$)/.test(pathname)) return 'notifications'
  if (/^\/organizations\/[^/]+\/integrations(\/|$)/.test(pathname)) return 'integrations'
  if (/^\/organizations\/[^/]+\/(configurations|configuration-rules|configuration-assignments)(\/|$)/.test(pathname)) return 'configurations'
  if (/^\/organizations\/[^/]+\/resources(\/|$)/.test(pathname)) return 'resources'
  if (/^\/organizations\/[^/]+\/environments\/[^/]+(\/|$)/.test(pathname)) return 'resources'
  return 'workspace'
}

/** The page of a section inside a context, carrying the context along. */
export function modulePath(module: WorkspaceModule, scope: WorkspaceScope): string | undefined {
  if (!scope.organizationId) return undefined
  const base = `/organizations/${encodeURIComponent(scope.organizationId)}`
  const query = contextQuery(scope)

  switch (module) {
    case 'overview': return `${base}/overview${query}`
    case 'members': return `${base}/members`
    case 'workspace': return `${base}${query}`
    case 'incidents': return `${base}/incidents${query}`
    case 'connections': return `${base}/connections${query}`
    case 'notifications': return `${base}/notifications${query}`
    case 'integrations': return `${base}/integrations${query}`
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

/** Carry only the workspace scope; keep the destination's own tab and filter parameters. */
export function withWorkspaceContext(path: string, params: URLSearchParams): string {
  const [pathname, search = ''] = path.split('?')
  const destination = new URLSearchParams(search)
  const scope = new URLSearchParams(contextSearch(params))
  scope.forEach((value, key) => destination.set(key, value))
  const query = destination.toString()
  return `${pathname}${query ? `?${query}` : ''}`
}

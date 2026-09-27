/**
 * The canonical route of every object the infrastructure graph links to. Links are built from
 * identifiers only — never from display names — so they work as direct URLs, and each carries the
 * workspace context (`?project=&environment=`) that its page reads.
 */

const organizationBase = (organizationId: string) => `/organizations/${encodeURIComponent(organizationId)}`

/** A project's place in the resources section: its environments. */
export function projectPath(organizationId: string, projectId: string): string {
  return `${organizationBase(organizationId)}/resources${search({ project: projectId })}`
}

export function environmentPath(organizationId: string, projectId: string, environmentId: string): string {
  return `${organizationBase(organizationId)}/environments/${encodeURIComponent(environmentId)}` +
    search({ project: projectId, environment: environmentId })
}

export function connectionPath(organizationId: string, connectionId: string, query = ''): string {
  return `${organizationBase(organizationId)}/connections/${encodeURIComponent(connectionId)}${query}`
}

export function resourcePath(organizationId: string, environmentId: string, resourceId: string, query = ''): string {
  return `${organizationBase(organizationId)}/environments/${encodeURIComponent(environmentId)}` +
    `/resources/${encodeURIComponent(resourceId)}${query}`
}

export function incidentPath(organizationId: string, incidentId: string, query = ''): string {
  return `${organizationBase(organizationId)}/incidents/${encodeURIComponent(incidentId)}${query}`
}

/**
 * Where a detail page was opened from, when that was another detail page: only the immediate
 * logical parent, never a history. It decides where "back" leads; without it every page falls back
 * to its own deterministic parent. Identifiers only — names are never put in a URL.
 */
export type Origin =
  | { kind: 'connection'; id: string }
  | { kind: 'resource'; id: string; environmentId: string }
  | { kind: 'incident'; id: string }

export const OriginParams = {
  connection: 'fromConnection', resource: 'fromResource', environment: 'fromEnvironment', incident: 'fromIncident',
} as const

export function readOrigin(params: URLSearchParams): Origin | null {
  const connection = params.get(OriginParams.connection)
  if (connection) return { kind: 'connection', id: connection }
  const incident = params.get(OriginParams.incident)
  if (incident) return { kind: 'incident', id: incident }
  const resource = params.get(OriginParams.resource)
  const environment = params.get(OriginParams.environment)
  return resource && environment ? { kind: 'resource', id: resource, environmentId: environment } : null
}

/** Where "back" to an origin leads: the page it names, on the tab the link was followed from. */
export function originPath(organizationId: string, origin: Origin, workspace: string, tab?: string): string {
  const query = tab ? withTab(workspace, tab) : workspace
  switch (origin.kind) {
    case 'connection': return connectionPath(organizationId, origin.id, query)
    case 'resource': return resourcePath(organizationId, origin.environmentId, origin.id, query)
    case 'incident': return incidentPath(organizationId, origin.id, workspace)
  }
}

/**
 * The query a link from a detail page carries: the workspace context of the current page plus
 * where the link was followed from. Tab and filter state of the current page stay behind.
 */
export function originQuery(current: URLSearchParams, origin: Origin): string {
  const values: Record<string, string | null> = { project: current.get('project'), environment: current.get('environment') }
  if (!values.project) values.environment = null
  if (origin.kind === 'connection') values[OriginParams.connection] = origin.id
  else if (origin.kind === 'incident') values[OriginParams.incident] = origin.id
  else {
    values[OriginParams.resource] = origin.id
    values[OriginParams.environment] = origin.environmentId
  }
  return search(values)
}

/** The workspace context of the current page alone, without origin, tab or filter state. */
export function workspaceQuery(current: URLSearchParams): string {
  const project = current.get('project')
  return search({ project, environment: project ? current.get('environment') : null })
}

export function withTab(query: string, tab: string): string {
  const params = new URLSearchParams(query)
  params.set('tab', tab)
  return `?${params.toString()}`
}

function search(values: Record<string, string | null | undefined>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value) params.set(key, value)
  }
  const encoded = params.toString()
  return encoded ? `?${encoded}` : ''
}

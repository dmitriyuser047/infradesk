import type { I18n } from '../../i18n'
import type { ResourceResponse } from '../../types/resource'
import type { SourceConnection } from '../../types/infrastructure'

export function resourceDisplayName(resource: ResourceResponse): string {
  return resource.name.trim() || (resource.data?.kind === 'NODE' ? resource.data.spec?.hostname : undefined) || resource.code
}

/** Only an unambiguous active SSH source can open a server's existing terminal route. */
export function resourceTerminalConnection(resource: ResourceResponse, sources: readonly SourceConnection[]): SourceConnection | undefined {
  const ssh = sources.filter(source => source.active && source.connectorType === 'SSH')
  return resource.active && resource.resourceTypeCode === 'NODE' && ssh.length === 1 ? ssh[0] : undefined
}

/** An incomplete preview cannot establish that all children are containers. */
export function resourceChildrenTitle(children: readonly ResourceResponse[], total: number, i18n: I18n): string {
  return children.length > 0 && total === children.length && children.every(child => child.resourceTypeCode === 'CONTAINER')
    ? i18n.t.workScreens.containers : i18n.t.infrastructure.children
}

import { ConnectionScopeType, type ConnectionScopeResponse } from '../../types/connection'
import type { EnvironmentResponse, ProjectResponse } from '../../types/navigation'
import { shortConnectionIdentifier } from './connectionPresentation'

export function projectName(projectId: string, projects: readonly ProjectResponse[] | undefined): string {
  return projects?.find(project => project.id === projectId)?.name ?? shortConnectionIdentifier(projectId)
}

export function environmentName(environmentId: string,
  environments: readonly EnvironmentResponse[] | undefined): string {
  return environments?.find(environment => environment.id === environmentId)?.name ??
    shortConnectionIdentifier(environmentId)
}

export function connectionScopeDisplayLabel(scope: ConnectionScopeResponse,
  projects: readonly ProjectResponse[] | undefined,
  environments: readonly EnvironmentResponse[] | undefined): string {
  switch (scope.type) {
    case ConnectionScopeType.organization: return 'Organization'
    case ConnectionScopeType.project: return `Project · ${projectName(scope.projectId, projects)}`
    case ConnectionScopeType.environment: return `Environment · ${environmentName(scope.environmentId, environments)}`
  }
}

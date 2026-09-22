import { EnvironmentKind } from '../../types/navigation'

export function getEnvironmentKindLabel(kind: string): string {
  switch (kind) {
    case EnvironmentKind.dev: return 'Development'
    case EnvironmentKind.test: return 'Test'
    case EnvironmentKind.stage: return 'Staging'
    case EnvironmentKind.prod: return 'Production'
    case EnvironmentKind.custom: return 'Custom'
    default: return kind
  }
}

export function validSelection<T extends { id: string }>(
  selectedId: string | null,
  items: readonly T[],
): string | null {
  return items.some((item) => item.id === selectedId) ? selectedId : items[0]?.id ?? null
}

export interface ContextSelection {
  projectId: string | null
  environmentId: string | null
}

export function selectProject(projectId: string): ContextSelection {
  return { projectId, environmentId: null }
}

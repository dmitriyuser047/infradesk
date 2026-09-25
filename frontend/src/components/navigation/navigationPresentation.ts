import type { I18n } from '../../i18n'

export function getEnvironmentKindLabel(kind: string, i18n: I18n): string {
  return (i18n.t.environmentKinds as Record<string, string>)[kind] ?? kind
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

import type { I18n } from '../../i18n'

export interface DisplayNameSource {
  displayName?: string | null
  name?: string | null
  code?: string | null
}

/** Prefer a human name; fall back only to the available name/code, never an identifier. */
export function getDisplayName(value: DisplayNameSource | null | undefined, fallback = ''): string {
  for (const candidate of [value?.displayName, value?.name, value?.code]) {
    if (candidate?.trim()) return candidate.trim()
  }
  return fallback
}

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

import { defaultResourcePresentation } from './defaultResourcePresentation'
import type { ResourcePresentation, ResourceRendering } from './ResourcePresentation'

export interface ResourcePresentationRegistry {
  /** Rendering for a resource type code, falling back to the default for unknown types. */
  resolve: (resourceTypeCode: string) => ResourceRendering
  /** Registered presentations in registration order, for example for the type filter. */
  list: () => readonly ResourcePresentation[]
}

/** Built once from a static list; a duplicate resource type code is a programming error. */
export function createResourcePresentationRegistry(
  presentations: readonly ResourcePresentation[],
  fallback: ResourceRendering = defaultResourcePresentation,
): ResourcePresentationRegistry {
  const byCode = new Map<string, ResourcePresentation>()

  for (const presentation of presentations) {
    if (byCode.has(presentation.code)) {
      throw new Error(`Duplicate resource presentation code: ${presentation.code}`)
    }
    byCode.set(presentation.code, presentation)
  }

  return {
    resolve: resourceTypeCode => byCode.get(resourceTypeCode) ?? fallback,
    list: () => presentations,
  }
}

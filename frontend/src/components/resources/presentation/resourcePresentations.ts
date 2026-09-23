import { containerPresentation } from './ContainerPresentation'
import { nodePresentation } from './NodePresentation'
import { createResourcePresentationRegistry } from './ResourcePresentationRegistry'
import type { ResourcePresentation } from './ResourcePresentation'

/** The single registration point: one line per resource type the frontend can display. */
export const resourcePresentations: readonly ResourcePresentation[] = [
  nodePresentation,
  containerPresentation,
]

export const resourcePresentationRegistry = createResourcePresentationRegistry(resourcePresentations)

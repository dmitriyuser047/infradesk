import type { EnvironmentReference, LocatedResourceResponse } from '../../types/infrastructure'
import type { ResourceResponse } from '../../types/resource'
import type { FilteredResourceNode } from '../resources/resourceFilter'
import { buildResourceTree, type ResourceTreeNode } from '../resources/resourceTreeModel'

export interface EnvironmentResourceGroup {
  environment: EnvironmentReference
  roots: FilteredResourceNode[]
}

/**
 * A connection's resources as one tree per environment, in the order the backend returned them
 * (by environment name). One pass over the list; a child whose parent is not in the list becomes a
 * root of its group rather than disappearing.
 */
export function groupResourcesByEnvironment(resources: readonly LocatedResourceResponse[]): EnvironmentResourceGroup[] {
  const groups = new Map<string, { environment: EnvironmentReference; resources: LocatedResourceResponse[] }>()
  for (const resource of resources) {
    const group = groups.get(resource.environment.id)
    if (group) group.resources.push(resource)
    else groups.set(resource.environment.id, { environment: resource.environment, resources: [resource] })
  }
  return [...groups.values()].map(group => ({ environment: group.environment, roots: asTree(group.resources) }))
}

/** A plain resource list as a tree the resource tree component renders, every node a result. */
export function asTree(resources: readonly ResourceResponse[]): FilteredResourceNode[] {
  return buildResourceTree(resources).map(matched)
}

function matched(node: ResourceTreeNode): FilteredResourceNode {
  return { resource: node.resource, matched: true, children: node.children.map(matched) }
}

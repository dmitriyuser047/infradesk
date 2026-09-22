import type { ResourceResponse } from '../../types/resource'

export interface ResourceTreeNode {
  resource: ResourceResponse
  children: ResourceTreeNode[]
}

export function buildResourceTree(resources: readonly ResourceResponse[]): ResourceTreeNode[] {
  const nodesById = new Map<string, ResourceTreeNode>()

  for (const resource of resources) {
    nodesById.set(resource.id, {
      resource,
      children: [],
    })
  }

  const roots: ResourceTreeNode[] = []

  for (const resource of resources) {
    const node = nodesById.get(resource.id)
    if (node === undefined) {
      continue
    }

    const parent = resource.parentResourceId === null
      ? undefined
      : nodesById.get(resource.parentResourceId)

    if (parent === undefined) {
      roots.push(node)
    } else {
      parent.children.push(node)
    }
  }

  return roots
}

import type { ResourceResponse } from '../../types/resource'

export function filterResourcesForTree(resources: readonly ResourceResponse[], search: string,
  type: string): ResourceResponse[] {
  const query = search.trim().toLocaleLowerCase()
  if (!query && type === 'ALL') return [...resources]
  const byId = new Map(resources.map(resource => [resource.id, resource]))
  const visible = new Set<string>()
  for (const resource of resources) {
    if ((type === 'ALL' || resource.resourceTypeCode === type) &&
      (!query || `${resource.name} ${resource.code}`.toLocaleLowerCase().includes(query))) {
      let current: ResourceResponse | undefined = resource
      while (current && !visible.has(current.id)) {
        visible.add(current.id)
        current = current.parentResourceId ? byId.get(current.parentResourceId) : undefined
      }
    }
  }
  return resources.filter(resource => visible.has(resource.id))
}

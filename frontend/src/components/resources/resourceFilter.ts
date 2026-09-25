import type { I18n } from '../../i18n'
import type { ResourceResponse } from '../../types/resource'
import type { ResourceCondition } from './presentation/ResourcePresentation'
import type { ResourcePresentationRegistry } from './presentation/ResourcePresentationRegistry'
import { buildResourceTree, type ResourceTreeNode } from './resourceTreeModel'

/**
 * Search and filters over the resources an environment already loaded. Selection stays with the
 * backend; this only decides which of the loaded resources are shown, so it never makes a request.
 */
export type ResourceConditionFilter = 'ALL' | 'RUNNING' | 'INACTIVE'

export interface ResourceFilterCriteria {
  query: string
  /** `ALL`, or a resource type code as the backend reports it. */
  type: string
  condition: ResourceConditionFilter
}

export const noResourceFilter: ResourceFilterCriteria = { query: '', type: 'ALL', condition: 'ALL' }

export function isResourceFilterActive(criteria: ResourceFilterCriteria): boolean {
  return criteria.query.trim() !== '' || criteria.type !== 'ALL' || criteria.condition !== 'ALL'
}

/** What the filter reads about one resource. */
export interface ResourceFacts {
  typeCode: string
  condition: ResourceCondition
  /** Everything a search may match, already lower-cased. */
  searchText: string
}

export type DescribeResource = (resource: ResourceResponse) => ResourceFacts

/**
 * Facts taken from each type's presentation, so no resource type is special-cased here: the type
 * label, the extra identity it exposes and the filter group of its status all come from there.
 */
export function describeWithPresentations(registry: ResourcePresentationRegistry, i18n: I18n): DescribeResource {
  return resource => {
    const presentation = registry.resolve(resource.resourceTypeCode)
    const terms = [resource.name, resource.code, resource.resourceTypeCode,
      i18n.t.resources.types[resource.resourceTypeCode] ?? '', ...(presentation.searchTerms?.(resource) ?? [])]
    return {
      typeCode: resource.resourceTypeCode,
      condition: presentation.condition?.(resource) ?? 'unknown',
      searchText: terms.join('\n').toLocaleLowerCase(),
    }
  }
}

const conditionOf: Record<Exclude<ResourceConditionFilter, 'ALL'>, ResourceCondition> = {
  RUNNING: 'running', INACTIVE: 'inactive',
}

/** Every active criterion must hold: a case-insensitive substring search, the type and the status group. */
export function matchesResource(facts: ResourceFacts, criteria: ResourceFilterCriteria): boolean {
  const query = criteria.query.trim().toLocaleLowerCase()
  return (query === '' || facts.searchText.includes(query))
    && (criteria.type === 'ALL' || facts.typeCode === criteria.type)
    && (criteria.condition === 'ALL' || facts.condition === conditionOf[criteria.condition])
}

export interface FilteredResourceNode extends ResourceTreeNode {
  /** False for a parent shown only so a matching child keeps its place; it does not count as found. */
  matched: boolean
  children: FilteredResourceNode[]
}

export interface FilteredResourceTree {
  roots: FilteredResourceNode[]
  /** Resources that meet every criterion; contextual parents are not included. */
  matched: number
  total: number
}

/**
 * The resource tree as the filters leave it:
 * - without criteria, the whole tree;
 * - a resource that matches is shown; its children only if they match as well;
 * - a parent that does not match is kept, as context, when one of its descendants matches;
 * - a parent with neither is hidden, together with everything below it.
 */
export function buildFilteredResourceTree(
  resources: readonly ResourceResponse[],
  criteria: ResourceFilterCriteria,
  describe: DescribeResource,
): FilteredResourceTree {
  const tree = buildResourceTree(resources)
  if (!isResourceFilterActive(criteria)) {
    const all = (node: ResourceTreeNode): FilteredResourceNode => ({ ...node, matched: true, children: node.children.map(all) })
    return { roots: tree.map(all), matched: resources.length, total: resources.length }
  }

  let matched = 0
  const keep = (node: ResourceTreeNode): FilteredResourceNode | null => {
    const children = node.children.map(keep).filter((child): child is FilteredResourceNode => child !== null)
    const isMatch = matchesResource(describe(node.resource), criteria)
    if (isMatch) matched += 1
    return isMatch || children.length > 0 ? { resource: node.resource, matched: isMatch, children } : null
  }
  const roots = tree.map(keep).filter((root): root is FilteredResourceNode => root !== null)
  return { roots, matched, total: resources.length }
}

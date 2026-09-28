import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type {
  ConfigurationAssignment,
  ConfigurationAssignmentDetail,
  ConfigurationAssignmentFilter,
  ConfigurationAssignmentPreview,
  ConfigurationValue,
  CreateConfigurationAssignmentRequest,
  UpdateConfigurationAssignmentRequest,
} from '../types/configurationAssignment'

/** One page of assignments; a longer list continues from the last row instead of being cut off. */
export const AssignmentPageSize = 50

const base = (organizationId: string) => `/api/v1/organizations/${encodeURIComponent(organizationId)}/configuration-assignments`
const assignmentPath = (organizationId: string, id: string) => `${base(organizationId)}/${encodeURIComponent(id)}`

export const assignmentKeys = {
  /** Every assignment list of the organization — by resource, by profile — and nothing else. */
  lists: (organizationId: string) => ['configurationAssignments', organizationId] as const,
  list: (organizationId: string, filter: ConfigurationAssignmentFilter) =>
    ['configurationAssignments', organizationId, { resourceId: filter.resourceId ?? null, profileId: filter.profileId ?? null }] as const,
  detail: (organizationId: string, id: string) => ['configurationAssignment', organizationId, id] as const,
}

type Cursor = Pick<ConfigurationAssignment, 'createdAt' | 'id'>

export function getAssignments(organizationId: string, filter: ConfigurationAssignmentFilter, before?: Cursor) {
  const query = new URLSearchParams({ limit: String(AssignmentPageSize) })
  if (filter.resourceId) query.set('resourceId', filter.resourceId)
  if (filter.profileId) query.set('profileId', filter.profileId)
  if (before) {
    // Both halves together, so rows created at the same instant are neither skipped nor repeated.
    query.set('beforeCreatedAt', before.createdAt)
    query.set('beforeId', before.id)
  }
  return requestJson<ConfigurationAssignment[]>(`${base(organizationId)}?${query.toString()}`)
}

export function useConfigurationAssignmentPages(organizationId: string, filter: ConfigurationAssignmentFilter, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: assignmentKeys.list(organizationId, filter),
    queryFn: ({ pageParam }) => getAssignments(organizationId, filter, pageParam),
    initialPageParam: undefined as Cursor | undefined,
    getNextPageParam: (page: ConfigurationAssignment[]) => page.length < AssignmentPageSize ? undefined : page[page.length - 1],
    enabled,
  })
}

export function useConfigurationAssignment(organizationId: string, id: string, enabled: boolean) {
  return useQuery({
    queryKey: assignmentKeys.detail(organizationId, id),
    queryFn: () => requestJson<ConfigurationAssignmentDetail>(assignmentPath(organizationId, id)),
    enabled,
  })
}

/** The lists that can show this assignment, and the assignment itself; nothing else is refetched. */
function refresh(client: QueryClient, organizationId: string, detail: ConfigurationAssignmentDetail) {
  client.setQueryData(assignmentKeys.detail(organizationId, detail.id), detail)
  void client.invalidateQueries({ queryKey: assignmentKeys.lists(organizationId) })
}

export function useCreateConfigurationAssignment(organizationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateConfigurationAssignmentRequest) =>
      requestJson<ConfigurationAssignmentDetail>(base(organizationId), { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: detail => refresh(client, organizationId, detail),
  })
}

/** The whole desired state at once, based on the version this browser last saw. */
export function useUpdateConfigurationAssignment(organizationId: string, id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: UpdateConfigurationAssignmentRequest) =>
      requestJson<ConfigurationAssignmentDetail>(assignmentPath(organizationId, id), { method: 'PATCH', body: JSON.stringify(body) }),
    onSuccess: detail => refresh(client, organizationId, detail),
  })
}

/** Removes the desired state from InfraDesk; the file on the server is not touched. */
export function useRemoveConfigurationAssignment(organizationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (assignment: Pick<ConfigurationAssignment, 'id' | 'version'>) =>
      requestJson<ConfigurationAssignmentDetail>(`${assignmentPath(organizationId, assignment.id)}?expectedVersion=${assignment.version}`,
        { method: 'DELETE' }),
    onSuccess: detail => refresh(client, organizationId, detail),
  })
}

/** Renders a draft on the server — the authority — without storing anything. */
export function usePreviewConfigurationAssignment(organizationId: string) {
  return useMutation({
    mutationFn: (body: { profileId: string; profileRevisionNumber: number; values: ConfigurationValue[] }) =>
      requestJson<ConfigurationAssignmentPreview>(`${base(organizationId)}/preview`, { method: 'POST', body: JSON.stringify(body) }),
  })
}

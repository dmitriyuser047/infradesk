import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { requestJson } from './httpClient'
import type { AdministrationMember, AdministrationOrganization, AdministrationPage, AdministrationUser, ChangeMembershipRequest, CreateAdministrationUserRequest } from '../types/administration'

const globalPath = '/api/v1/administration'
const orgPath = (org: string) => `/api/v1/organizations/${encodeURIComponent(org)}/members`
const cursor = (after: string | null) => after ? `?after=${encodeURIComponent(after)}` : ''
export function useAdministrationUsers(enabled: boolean) {
  return useInfiniteQuery({ queryKey: ['administration', 'users'], enabled, initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => requestJson<AdministrationPage<AdministrationUser>>(`${globalPath}/users${cursor(pageParam)}`),
    getNextPageParam: page => page.nextCursor ?? undefined, retry: false })
}
export function useAdministrationOrganizations(enabled: boolean) {
  return useInfiniteQuery({ queryKey: ['administration', 'organizations'], enabled, initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => requestJson<AdministrationPage<AdministrationOrganization>>(`${globalPath}/organizations${cursor(pageParam)}`),
    getNextPageParam: page => page.nextCursor ?? undefined, retry: false })
}
export function useOrganizationMembers(org: string, enabled: boolean) {
  return useInfiniteQuery({ queryKey: ['administration', 'members', org], enabled, initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => requestJson<AdministrationPage<AdministrationMember>>(`${orgPath(org)}${cursor(pageParam)}`),
    getNextPageParam: page => page.nextCursor ?? undefined, retry: false })
}
export function useAdministrationUser(id: string, enabled: boolean) {
  return useQuery({ queryKey: ['administration', 'user', id], enabled,
    queryFn: () => requestJson<AdministrationUser>(`${globalPath}/users/${encodeURIComponent(id)}`), retry: false })
}
export function useUserMemberships(id: string, enabled: boolean) {
  return useInfiniteQuery({ queryKey: ['administration', 'user-memberships', id], enabled, initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => requestJson<AdministrationPage<AdministrationMember>>(`${globalPath}/users/${encodeURIComponent(id)}/memberships${cursor(pageParam)}`),
    getNextPageParam: page => page.nextCursor ?? undefined, retry: false })
}
export function useAdministrationMembership(org: string, user: string, enabled: boolean) {
  return useQuery({ queryKey: ['administration', 'membership', org, user], enabled,
    queryFn: () => requestJson<AdministrationMember | null>(`${globalPath}/organizations/${encodeURIComponent(org)}/members/${encodeURIComponent(user)}`), retry: false })
}
export function useAdministrationMutation<T, R>(mutationFn: (input: T) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({ mutationFn, retry: false, gcTime: 0, onSuccess: async () => {
    await Promise.all([client.invalidateQueries({ queryKey: ['administration'] }),
      client.invalidateQueries({ queryKey: ['my-organizations'] }), client.invalidateQueries({ queryKey: ['me'] })])
  } })
}
export const createOrganization = (input: { requestId: string; code: string; name: string }) =>
  requestJson<AdministrationOrganization>('/api/v1/organizations', { method: 'POST', body: JSON.stringify(input) })
export const createAdministrationUser = (org: string | null, input: CreateAdministrationUserRequest) =>
  requestJson<AdministrationUser>(org ? `${orgPath(org)}/users` : `${globalPath}/users`, { method: 'POST', body: JSON.stringify(input) })
export const changeMembership = (global: boolean, org: string, user: string, input: ChangeMembershipRequest) =>
  requestJson<AdministrationMember>(`${global ? `${globalPath}/organizations/${encodeURIComponent(org)}/members` : orgPath(org)}/${encodeURIComponent(user)}`,
    { method: 'PUT', body: JSON.stringify(input) })
export const addMemberByEmail = (org: string, input: { email: string; role: string; isActive: boolean; expectedUpdatedAt: null }) =>
  requestJson<AdministrationMember>(orgPath(org), { method: 'POST', body: JSON.stringify(input) })
export const changeUserStatus = (user: string, input: { isActive: boolean; isAdministrator: boolean; expectedUpdatedAt: string }) =>
  requestJson<AdministrationUser>(`${globalPath}/users/${encodeURIComponent(user)}`, { method: 'PATCH', body: JSON.stringify(input) })

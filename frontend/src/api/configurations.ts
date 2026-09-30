import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { ApiError, requestJson } from './httpClient'
import type {
  ConfigurationContentRequest,
  ConfigurationDiagnostic,
  ConfigurationProfile,
  ConfigurationProfileDetail,
  ConfigurationRevision,
  ConfigurationRevisionSummary,
  ConfigurationValidationResponse,
  CreateConfigurationProfileRequest,
} from '../types/configuration'

/** The list is bounded: an organization with more profiles than this sees the first ones by name. */
export const ConfigurationListLimit = 200

const base = (organizationId: string) => `/api/v1/organizations/${encodeURIComponent(organizationId)}/configuration-profiles`
const profilePath = (organizationId: string, profileId: string) => `${base(organizationId)}/${encodeURIComponent(profileId)}`

export const configurationKeys = {
  profiles: (organizationId: string, archived: boolean) =>
    ['configurationProfiles', organizationId, { archived, kind: 'FILE_TEMPLATE' }] as const,
  allProfiles: (organizationId: string) => ['configurationProfiles', organizationId] as const,
  profile: (organizationId: string, profileId: string) => ['configurationProfile', organizationId, profileId] as const,
  revisions: (organizationId: string, profileId: string) => ['configurationRevisions', organizationId, profileId] as const,
  revision: (organizationId: string, profileId: string, revisionNumber: number) =>
    ['configurationRevision', organizationId, profileId, revisionNumber] as const,
}

export function useConfigurationProfiles(organizationId: string, archived: boolean, enabled: boolean) {
  return useQuery({
    queryKey: configurationKeys.profiles(organizationId, archived),
    queryFn: () => requestJson<ConfigurationProfile[]>(`${base(organizationId)}?archived=${archived}&limit=${ConfigurationListLimit}&kind=FILE_TEMPLATE`),
    enabled,
  })
}

export function useConfigurationProfile(organizationId: string, profileId: string, enabled: boolean) {
  return useQuery({
    queryKey: configurationKeys.profile(organizationId, profileId),
    queryFn: () => requestJson<ConfigurationProfileDetail>(profilePath(organizationId, profileId)),
    enabled,
  })
}

/** The newest versions of a profile; the history is bounded like every list. */
export function useConfigurationRevisions(organizationId: string, profileId: string, enabled: boolean) {
  return useQuery({
    queryKey: configurationKeys.revisions(organizationId, profileId),
    queryFn: () => requestJson<ConfigurationRevisionSummary[]>(`${profilePath(organizationId, profileId)}/revisions?limit=200`),
    enabled,
  })
}

export function useConfigurationRevision(organizationId: string, profileId: string, revisionNumber: number | null) {
  return useQuery({
    queryKey: configurationKeys.revision(organizationId, profileId, revisionNumber ?? 0),
    queryFn: () => requestJson<ConfigurationRevision>(`${profilePath(organizationId, profileId)}/revisions/${revisionNumber}`),
    enabled: revisionNumber !== null,
    // A revision never changes once written.
    staleTime: Infinity,
  })
}

/** Checks content on the server — the authority — without storing anything. */
export function useValidateConfiguration(organizationId: string) {
  return useMutation({
    mutationFn: (body: ConfigurationContentRequest & { previewValues?: { name: string; value: string }[] }) =>
      requestJson<ConfigurationValidationResponse>(`${base(organizationId)}/validate`, { method: 'POST', body: JSON.stringify(body) }),
  })
}

export function useCreateConfigurationProfile(organizationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateConfigurationProfileRequest) =>
      requestJson<ConfigurationProfileDetail>(base(organizationId), { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: detail => {
      client.setQueryData(configurationKeys.profile(organizationId, detail.profile.id), detail)
      void client.invalidateQueries({ queryKey: configurationKeys.allProfiles(organizationId) })
    },
  })
}

/** A new version: the server numbers it, whatever this browser last saw. */
export function useCreateConfigurationRevision(organizationId: string, profileId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: ConfigurationContentRequest) =>
      requestJson<ConfigurationRevision>(`${profilePath(organizationId, profileId)}/revisions`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: revision => {
      client.setQueryData(configurationKeys.revision(organizationId, profileId, revision.revisionNumber), revision)
      void client.invalidateQueries({ queryKey: configurationKeys.profile(organizationId, profileId) })
      void client.invalidateQueries({ queryKey: configurationKeys.revisions(organizationId, profileId) })
      void client.invalidateQueries({ queryKey: configurationKeys.allProfiles(organizationId) })
    },
  })
}

/** Name and description; never a new version. */
export function useUpdateConfigurationProfile(organizationId: string, profileId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { name: string; description: string | null }) =>
      requestJson<ConfigurationProfile>(profilePath(organizationId, profileId), { method: 'PATCH', body: JSON.stringify(body) }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: configurationKeys.profile(organizationId, profileId) })
      void client.invalidateQueries({ queryKey: configurationKeys.allProfiles(organizationId) })
    },
  })
}

/** Archiving keeps the profile and its history; it only stops new versions. */
export function useArchiveConfigurationProfile(organizationId: string, profileId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => requestJson<ConfigurationProfile>(profilePath(organizationId, profileId), { method: 'DELETE' }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: configurationKeys.profile(organizationId, profileId) })
      void client.invalidateQueries({ queryKey: configurationKeys.allProfiles(organizationId) })
    },
  })
}

/** The findings of a refused save, when the refusal carried them. */
export function refusedDiagnostics(error: unknown): ConfigurationDiagnostic[] | null {
  if (!(error instanceof ApiError) || error.code !== 'INVALID_CONFIGURATION') return null
  const body = error.body as { diagnostics?: unknown } | undefined
  return Array.isArray(body?.diagnostics) ? body.diagnostics as ConfigurationDiagnostic[] : null
}

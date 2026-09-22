import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'

import { requestJson } from './httpClient'
import type { ConnectionResponse, SaveSshConnectionRequest } from '../types/connection'
import { requestVoid } from './httpClient'

export function getConnections(organizationId: string): Promise<ConnectionResponse[]> {
  return requestJson<ConnectionResponse[]>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/connections`,
  )
}

export function getConnection(
  organizationId: string,
  connectionId: string,
): Promise<ConnectionResponse> {
  return requestJson<ConnectionResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connectionId)}`,
  )
}

export function useConnections(organizationId: string | undefined) {
  return useQuery({
    queryKey: ['connections', organizationId],
    queryFn: () => getConnections(requireId(organizationId)),
    enabled: Boolean(organizationId),
  })
}

export function useConnection(
  organizationId: string | undefined,
  connectionId: string | undefined,
) {
  return useQuery({
    queryKey: ['connection', organizationId, connectionId],
    queryFn: () => getConnection(requireId(organizationId), requireId(connectionId)),
    enabled: Boolean(organizationId && connectionId),
  })
}

const path = (organizationId: string) =>
  `/api/v1/organizations/${encodeURIComponent(organizationId)}/connections`

export function useCreateConnection(organizationId: string) {
  const queryClient = useQueryClient()
  const pending = useRef<SaveSshConnectionRequest | null>(null)
  const mutation = useMutation({
    mutationFn: async () => {
      const body = pending.current
      if (!body) throw new Error('Missing connection request')
      try { return await requestJson<ConnectionResponse>(path(organizationId), {
        method: 'POST', body: JSON.stringify(body),
      }) } finally { pending.current = null }
    },
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['connections', organizationId] }) },
  })
  return { ...mutation, submit: (body: SaveSshConnectionRequest, onSuccess: (value: ConnectionResponse) => void) => {
    pending.current = body
    mutation.mutate(undefined, { onSuccess })
  } }
}

export function useUpdateConnection(organizationId: string, connectionId: string) {
  const queryClient = useQueryClient()
  const pending = useRef<SaveSshConnectionRequest | null>(null)
  const mutation = useMutation({
    mutationFn: async () => {
      const body = pending.current
      if (!body) throw new Error('Missing connection request')
      try { return await requestJson<ConnectionResponse>(
        `${path(organizationId)}/${encodeURIComponent(connectionId)}`, { method: 'PUT', body: JSON.stringify(body) },
      ) } finally { pending.current = null }
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['connections', organizationId] })
      void queryClient.invalidateQueries({ queryKey: ['connection', organizationId, connectionId] })
    },
  })
  return { ...mutation, submit: (body: SaveSshConnectionRequest, onSuccess: (value: ConnectionResponse) => void) => {
    pending.current = body
    mutation.mutate(undefined, { onSuccess })
  } }
}

export function useDeactivateConnection(organizationId: string, connectionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => requestVoid(`${path(organizationId)}/${encodeURIComponent(connectionId)}`, { method: 'DELETE' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['connections', organizationId] })
      void queryClient.invalidateQueries({ queryKey: ['connection', organizationId, connectionId] })
    },
  })
}

export function useTestSshConnection(organizationId: string) {
  type TestBody = { host: string; port: number; username: string; credentials: { type: 'PASSWORD'; password: string } }
  const pending = useRef<TestBody | null>(null)
  const mutation = useMutation({
    mutationFn: async () => {
      const body = pending.current
      if (!body) throw new Error('Missing SSH test request')
      try { return await requestJson<{ success: boolean; hostKeyFingerprint: string }>(`${path(organizationId)}/ssh/test`, {
        method: 'POST', body: JSON.stringify(body),
      }) } finally { pending.current = null }
    },
  })
  return { ...mutation, submit: (body: TestBody, onSuccess: (value: { success: boolean; hostKeyFingerprint: string }) => void) => {
    pending.current = body
    mutation.mutate(undefined, { onSuccess })
  } }
}

function requireId(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    throw new Error('Missing route identifier')
  }

  return value
}

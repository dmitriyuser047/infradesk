import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { requestJson } from './httpClient'
import type { NotificationChannelResponse, SaveNotificationChannelRequest, TestNotificationResponse } from '../types/notificationChannel'

export const notificationChannelsKey = (organizationId: string) => ['notification-channels', organizationId] as const
export const notificationChannelKey = (organizationId: string, channelId: string) => ['notification-channel', organizationId, channelId] as const
export const notificationChannelStaleTime = 30_000
const path = (org: string) => `/api/v1/organizations/${encodeURIComponent(org)}/notification-channels`
export const getNotificationChannels = (org: string) => requestJson<NotificationChannelResponse[]>(path(org))
export const getNotificationChannel = (org: string, id: string) => requestJson<NotificationChannelResponse>(`${path(org)}/${encodeURIComponent(id)}`)
type SaveCommand = {
  input: SaveNotificationChannelRequest
  onSuccess?: (value: NotificationChannelResponse) => void
  onError?: (error: Error) => void
}
export function useNotificationChannels(org?: string, enabled = true) {
  return useQuery({ queryKey: ['notification-channels', org], queryFn: () => getNotificationChannels(requireId(org)),
    enabled: Boolean(org && enabled), staleTime: notificationChannelStaleTime })
}
export function useNotificationChannel(org?: string, id?: string, enabled = true) {
  return useQuery({ queryKey: ['notification-channel', org, id], queryFn: () => getNotificationChannel(requireId(org), requireId(id)),
    enabled: Boolean(org && id && enabled), staleTime: notificationChannelStaleTime })
}
function setChannel(client: ReturnType<typeof useQueryClient>, org: string, value: NotificationChannelResponse) {
  client.setQueryData(notificationChannelKey(org, value.id), value)
  client.setQueryData<NotificationChannelResponse[]>(notificationChannelsKey(org), old => old?.some(item => item.id === value.id)
    ? old.map(item => item.id === value.id ? value : item) : old ? [...old, value] : old)
}
export function useSaveNotificationChannel(org: string, id?: string) {
  const client = useQueryClient()
  const inFlight = useRef(false)
  const mutation = useMutation<NotificationChannelResponse, Error, SaveCommand>({
    gcTime: 0,
    mutationFn: command => requestJson<NotificationChannelResponse>(
      id ? `${path(org)}/${encodeURIComponent(id)}` : path(org),
      { method: id ? 'PUT' : 'POST', body: JSON.stringify(command.input) },
    ),
    onSuccess: (value, command) => {
      setChannel(client, org, value)
      command.onSuccess?.(value)
    },
    onError: (error, command) => command.onError?.(error),
    onSettled: () => {
      inFlight.current = false
      // Detach the completed mutation immediately so request credentials do not remain in cache.
      queueMicrotask(() => mutation.reset())
    },
  })
  return { ...mutation, submit: (input: SaveNotificationChannelRequest, options?: {
    onSuccess?: (value: NotificationChannelResponse) => void; onError?: (error: Error) => void
  }) => {
    if (inFlight.current) return
    inFlight.current = true
    mutation.mutate({ input, ...options })
  } }
}
export function useSetNotificationChannelEnabled(org: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => requestJson<NotificationChannelResponse>(
    `${path(org)}/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`, { method: 'POST' }),
    onSuccess: value => setChannel(client, org, value) })
}
export function useTestNotificationChannel(org: string) {
  const pending = useRef(false)
  const mutation = useMutation({ mutationFn: (id: string) => requestJson<TestNotificationResponse>(
    `${path(org)}/${encodeURIComponent(id)}/test`, { method: 'POST' }) })
  return { ...mutation, submit: (id: string) => {
    if (pending.current) return
    pending.current = true
    mutation.mutate(id, { onSettled: () => { pending.current = false } })
  } }
}
function requireId(value?: string): string { if (!value) throw new Error('Missing route identifier'); return value }

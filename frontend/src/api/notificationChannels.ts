import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { requestJson } from './httpClient'
import type { NotificationChannelResponse, SaveNotificationChannelRequest, TestNotificationResponse } from '../types/notificationChannel'

export const notificationChannelsKey = (organizationId: string) => ['notification-channels', organizationId] as const
export const notificationChannelKey = (organizationId: string, channelId: string) => ['notification-channel', organizationId, channelId] as const
const path = (org: string) => `/api/v1/organizations/${encodeURIComponent(org)}/notification-channels`
export const getNotificationChannels = (org: string) => requestJson<NotificationChannelResponse[]>(path(org))
export const getNotificationChannel = (org: string, id: string) => requestJson<NotificationChannelResponse>(`${path(org)}/${encodeURIComponent(id)}`)
export function useNotificationChannels(org?: string, enabled = true) {
  return useQuery({ queryKey: ['notification-channels', org], queryFn: () => getNotificationChannels(requireId(org)), enabled: Boolean(org && enabled) })
}
export function useNotificationChannel(org?: string, id?: string, enabled = true) {
  return useQuery({ queryKey: ['notification-channel', org, id], queryFn: () => getNotificationChannel(requireId(org), requireId(id)), enabled: Boolean(org && id && enabled) })
}
function setChannel(client: ReturnType<typeof useQueryClient>, org: string, value: NotificationChannelResponse) {
  client.setQueryData(notificationChannelKey(org, value.id), value)
  client.setQueryData<NotificationChannelResponse[]>(notificationChannelsKey(org), old => old?.some(item => item.id === value.id)
    ? old.map(item => item.id === value.id ? value : item) : old ? [...old, value] : old)
}
export function useSaveNotificationChannel(org: string, id?: string) {
  const client = useQueryClient()
  const pending = useRef<SaveNotificationChannelRequest | null>(null)
  const mutation = useMutation({ mutationFn: async () => {
    const body = pending.current
    if (!body) throw new Error('Missing notification channel request')
    try {
      return await requestJson<NotificationChannelResponse>(id ? `${path(org)}/${encodeURIComponent(id)}` : path(org),
        { method: id ? 'PUT' : 'POST', body: JSON.stringify(body) })
    } finally { pending.current = null }
  }, onSuccess: value => setChannel(client, org, value) })
  return { ...mutation, submit: (body: SaveNotificationChannelRequest, options?: {
    onSuccess?: (value: NotificationChannelResponse) => void; onError?: (error: Error) => void
  }) => { pending.current = body; mutation.mutate(undefined, options) } }
}
export function useSetNotificationChannelEnabled(org: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => requestJson<NotificationChannelResponse>(
    `${path(org)}/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`, { method: 'POST' }),
    onSuccess: value => setChannel(client, org, value) })
}
export function useTestNotificationChannel(org: string) {
  return useMutation({ mutationFn: (id: string) => requestJson<TestNotificationResponse>(
    `${path(org)}/${encodeURIComponent(id)}/test`, { method: 'POST' }) })
}
function requireId(value?: string): string { if (!value) throw new Error('Missing route identifier'); return value }

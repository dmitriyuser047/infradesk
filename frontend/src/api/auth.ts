import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson, requestVoid } from './httpClient'
import type { MeResponse, MyOrganizationResponse } from '../types/auth'

export function login(email: string, password: string): Promise<MeResponse> {
  return requestJson<MeResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
}

export function logout(): Promise<void> {
  return requestVoid('/api/v1/auth/logout', { method: 'POST' })
}

export function getMe(): Promise<MeResponse> {
  return requestJson<MeResponse>('/api/v1/me')
}

export function getMyOrganizations(): Promise<MyOrganizationResponse[]> {
  return requestJson<MyOrganizationResponse[]>('/api/v1/me/organizations')
}

export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: getMe, retry: false })
}

export function useMyOrganizations() {
  return useQuery({ queryKey: ['my-organizations'], queryFn: getMyOrganizations })
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) => login(email, password),
    onSuccess: (me) => {
      queryClient.removeQueries()
      queryClient.setQueryData(['me'], me)
    },
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: logout,
    onSuccess: () => queryClient.removeQueries(),
  })
}

export interface MeResponse {
  id: string
  email: string
  displayName: string
}

export interface MyOrganizationResponse {
  id: string
  code: string
  name: string
  role: string
}

export interface SessionResponse {
  id: string
  createdAt: string
  expiresAt: string
  current: boolean
}

export const OrganizationRole = {
  owner: 'OWNER',
  member: 'MEMBER',
} as const

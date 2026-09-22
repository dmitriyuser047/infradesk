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

export const OrganizationRole = {
  owner: 'OWNER',
  member: 'MEMBER',
} as const

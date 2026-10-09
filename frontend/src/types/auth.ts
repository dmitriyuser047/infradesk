export interface MeResponse {
  isAdministrator?: boolean
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

export interface SecurityEventResponse {
  id: string
  type: 'LOGIN_SUCCEEDED' | 'PASSWORD_CHANGED' | 'SESSION_REVOKED' | 'OTHER_SESSIONS_REVOKED' | 'ALL_SESSIONS_REVOKED'
  occurredAt: string
  sessionId?: string
  source?: string
  affectedSessionCount?: number
}

export interface SecurityEventPageResponse {
  items: SecurityEventResponse[]
  nextCursor?: { occurredAt: string; id: string }
}

export const OrganizationRole = {
  owner: 'OWNER',
  administrator: 'ADMINISTRATOR',
  operator: 'OPERATOR',
  member: 'MEMBER',
} as const

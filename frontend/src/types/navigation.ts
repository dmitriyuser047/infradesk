export interface OrganizationResponse {
  id: string
  code: string
  name: string
}

export interface ProjectResponse {
  id: string
  organizationId: string
  code: string
  name: string
  description: string | null
}

export interface EnvironmentResponse {
  id: string
  organizationId: string
  projectId: string
  code: string
  name: string
  kind: string
}

export interface EnvironmentContextResponse {
  project: ProjectResponse
  environment: EnvironmentResponse
}

export const EnvironmentKind = {
  dev: 'DEV',
  test: 'TEST',
  stage: 'STAGE',
  prod: 'PROD',
  custom: 'CUSTOM',
} as const

export type EnvironmentKindCode = typeof EnvironmentKind[keyof typeof EnvironmentKind]

export interface CreateProjectRequest {
  code: string
  name: string
  description: string | null
}

export interface CreateEnvironmentRequest {
  code: string
  name: string
  kind: EnvironmentKindCode
}

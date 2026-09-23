import type { CreateEnvironmentRequest, CreateProjectRequest, EnvironmentKindCode } from '../../types/navigation'

export function buildCreateProjectRequest(code: string, name: string, description: string): CreateProjectRequest {
  return { code: code.trim(), name: name.trim(), description: description.trim() || null }
}

export function buildCreateEnvironmentRequest(code: string, name: string,
  kind: EnvironmentKindCode): CreateEnvironmentRequest {
  return { code: code.trim(), name: name.trim(), kind }
}

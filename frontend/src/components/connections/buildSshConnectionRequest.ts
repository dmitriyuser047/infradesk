import type { SaveSshConnectionRequest } from '../../types/connection'

export interface SshConnectionFormValues {
  code: string
  name: string
  projectId: string
  environmentId: string
  host: string
  port: string
  username: string
  password: string
  scheduleEnabled: boolean
  intervalSeconds: string
}

export const MIN_SSH_SYNC_INTERVAL_SECONDS = 300

export function buildSshConnectionRequest(values: SshConnectionFormValues): SaveSshConnectionRequest {
  const intervalSeconds = Number(values.intervalSeconds)
  if (!Number.isSafeInteger(intervalSeconds) || intervalSeconds < MIN_SSH_SYNC_INTERVAL_SECONDS) {
    throw new Error('SSH sync interval must be at least 300 seconds')
  }
  return {
    connectorType: 'SSH',
    code: values.code.trim(),
    name: values.name.trim(),
    scope: values.environmentId
      ? { type: 'ENVIRONMENT', projectId: values.projectId, environmentId: values.environmentId }
      : values.projectId ? { type: 'PROJECT', projectId: values.projectId } : { type: 'ORGANIZATION' },
    ssh: { host: values.host.trim(), port: Number(values.port), username: values.username.trim() },
    ...(values.password ? { credentials: { type: 'PASSWORD' as const, password: values.password } } : {}),
    schedule: { enabled: values.scheduleEnabled, intervalSeconds },
  }
}

import type {
  SaveSshConnectionRequest,
  SshAuthenticationType,
  SshCredentialsRequest,
} from '../../types/connection'

export interface SshConnectionFormValues {
  code: string
  name: string
  projectId: string
  environmentId: string
  host: string
  port: string
  username: string
  authenticationType: SshAuthenticationType
  password: string
  privateKey: string
  passphrase: string
  /** The host identity the operator confirmed after probing it. */
  hostKeyFingerprint: string
  scheduleEnabled: boolean
  intervalSeconds: string
}

/**
 * An empty credential means "keep the stored one", so it is left out entirely rather than sent
 * as a blank value. Switching the authentication method requires a new credential, which the
 * form enforces before it gets here.
 */
export function buildSshCredentials(
  values: SshConnectionFormValues,
): SshCredentialsRequest | undefined {
  if (values.authenticationType === 'PRIVATE_KEY') {
    return values.privateKey.trim() === ''
      ? undefined
      : {
          type: 'PRIVATE_KEY',
          privateKey: values.privateKey,
          ...(values.passphrase === '' ? {} : { passphrase: values.passphrase }),
        }
  }

  return values.password === '' ? undefined : { type: 'PASSWORD', password: values.password }
}

export const MIN_SSH_SYNC_INTERVAL_SECONDS = 300

export function buildSshConnectionRequest(values: SshConnectionFormValues): SaveSshConnectionRequest {
  const credentials = buildSshCredentials(values)
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
    ssh: {
      host: values.host.trim(),
      port: Number(values.port),
      username: values.username.trim(),
      authenticationType: values.authenticationType,
      ...(values.hostKeyFingerprint.trim() === ''
        ? {}
        : { hostKeyFingerprint: values.hostKeyFingerprint.trim() }),
    },
    ...(credentials === undefined ? {} : { credentials }),
    schedule: { enabled: values.scheduleEnabled, intervalSeconds },
  }
}

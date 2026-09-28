export type DeploymentState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK' | 'ROLLBACK_FAILED' | 'CANCELLED'
export type Activation = 'NONE' | 'SYSTEMD_RELOAD' | 'SYSTEMD_RESTART'

export interface DeploymentPreview {
  assignmentVersion: number
  profileRevisionNumber: number
  targetPath: string
  remote: { exists: boolean; sha256: string | null }
  desired: { sha256: string }
  changed: boolean
  diff: { text: string; truncated: boolean; addedLines: number; removedLines: number }
  connection: { id: string; name: string; updatedAt: string }
}

export interface DeploymentExecution {
  activation: Activation
  unitName: string | null
  validator: { executable: string; args: string[] } | null
  newFileMode: number
}

export interface DeploymentDetail {
  id: string
  assignmentId: string
  assignmentVersion: number
  resourceId: string
  profileId: string
  profileRevisionNumber: number
  targetPath: string
  connectionId: string
  desiredSha256: string
  expectedRemoteSha256: string | null
  state: DeploymentState
  phase: string
  failureCode: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  rolloutId: string | null
}

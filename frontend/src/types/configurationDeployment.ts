export type DeploymentState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK' | 'ROLLBACK_FAILED' | 'CANCELLED'
export type DeploymentPhase = 'PRECHECK' | 'UPLOAD' | 'VALIDATE' | 'REPLACE' | 'ACTIVATE' | 'VERIFY' | 'CLEANUP' | 'ROLLBACK'
export type Activation = 'NONE' | 'SYSTEMD_RELOAD' | 'SYSTEMD_RESTART'

export const ActiveDeploymentStates: readonly DeploymentState[] = ['QUEUED', 'RUNNING']

export interface DeploymentDiff {
  text: string
  truncated: boolean
  approximate: boolean
  addedLines: number
  removedLines: number
}

/** What the server has now and what the assignment wants, read once and never stored. */
export interface DeploymentPreview {
  assignmentVersion: number
  profileRevisionNumber: number
  targetPath: string
  remote: { exists: boolean; sha256: string | null; text: boolean }
  desired: { sha256: string }
  changed: boolean
  atomicReplaceSupported: boolean
  diff: DeploymentDiff
  connection: { id: string; name: string; updatedAt: string }
}

export interface DeploymentValidator { executable: string; args: string[] }

export interface DeploymentExecution {
  activation: Activation
  unitName: string | null
  validator: DeploymentValidator | null
  newFileMode: number
}

export interface Deployment {
  id: string
  assignmentId: string
  assignmentVersion: number
  resource: { id: string; name: string }
  profileId: string
  profileRevisionNumber: number
  targetPath: string
  connection: { id: string; name: string }
  desiredSha256: string
  expectedRemoteSha256: string | null
  expectedRemoteMissing: boolean
  execution: DeploymentExecution
  state: DeploymentState
  phase: DeploymentPhase
  rollbackFromPhase: DeploymentPhase | null
  failureCode: string | null
  cancelRequested: boolean
  backupRetained: boolean
  actor: { id: string; name: string }
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  rolloutId: string | null
  retryOfDeploymentId: string | null
}

export interface DeploymentEvent { sequence: number; type: string; occurredAt: string }
export interface DeploymentDetail extends Deployment { events: DeploymentEvent[] }

export interface HistoryCursor { beforeCreatedAt: string; beforeId: string }
export interface HistoryPage<T> { items: T[]; nextCursor: HistoryCursor | null }

/** A historical fact about what InfraDesk applied, never a claim about the file as it is now. */
export interface DeploymentSummary {
  assignmentId: string
  lastSuccessfulDeployment: { deploymentId: string; revision: number; desiredSha256: string; finishedAt: string } | null
  activeDeployment: { deploymentId: string; state: DeploymentState; phase: DeploymentPhase } | null
}

import type { DeploymentExecution, DeploymentPhase, DeploymentState } from './configurationDeployment'

export interface PromotionSelection { assignmentId: string; expectedVersion: number }
export interface PromotionIssue { code: string; variableName: string | null }
export interface PromotionPreview {
  revisionNumber: number
  compatible: boolean
  items: Array<{ assignmentId: string; expectedVersion: number; resourceName: string | null;
    currentRevisionNumber: number | null; compatible: boolean; issues: PromotionIssue[] }>
}
export interface PromotionResult { revisionNumber: number; assignments: Array<{ assignmentId: string; version: number }> }

export interface RolloutTarget {
  assignmentId: string
  expectedVersion: number
  connectionId: string
  execution: DeploymentExecution
}
export interface RolloutPreflightItem {
  assignmentId: string; expectedVersion: number; connectionId: string; connectionName: string | null
  ready: boolean; desiredSha256: string | null; connectionUpdatedAt: string | null
  remote: { exists: boolean; sha256: string | null } | null
  changed: boolean; addedLines: number; removedLines: number; errorCode: string | null
}
export interface RolloutPreflight { items: RolloutPreflightItem[]; ready: boolean }
export interface ApprovedRolloutTarget extends RolloutTarget {
  connectionUpdatedAt: string; desiredSha256: string; expectedRemoteSha256: string | null
  expectedRemoteMissing: boolean
}
export type RollbackMode = 'FAILED_TARGET_ONLY' | 'ALL_APPLIED'
export interface RolloutStrategy {
  canaryCount: number; batchSize: number; pauseSeconds: number; stopOnFailure: boolean; rollbackMode: RollbackMode
}
export type RolloutState = 'QUEUED' | 'RUNNING' | 'PAUSED' | 'FAILED' | 'ROLLING_BACK' | 'ROLLED_BACK' | 'SUCCEEDED' | 'CANCELLED'
export type RolloutItemState = 'PENDING' | 'DEPLOYING' | 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK' | 'SKIPPED'

export const ActiveRolloutStates: readonly RolloutState[] = ['QUEUED', 'RUNNING', 'PAUSED', 'ROLLING_BACK']

export interface Rollout {
  id: string; profileId: string; profileRevisionNumber: number; state: RolloutState
  strategy: RolloutStrategy; cancelRequested: boolean; rollbackRequested: boolean
  actor: { id: string; name: string }
  counts: { items: number; succeeded: number; failed: number }
  createdAt: string; startedAt: string | null; finishedAt: string | null; nextActionAt: string | null
}
export interface RolloutItem {
  id: string; position: number; assignmentId: string; assignmentVersion: number
  resource: { id: string; name: string }; targetPath: string; state: RolloutItemState
  deployment: { id: string; state: DeploymentState | null; phase: DeploymentPhase | null; failureCode: string | null;
    startedAt: string | null; finishedAt: string | null } | null
}
export interface RolloutDetail extends Rollout { items: RolloutItem[] }

import type { DeploymentExecution } from './configurationDeployment'

export interface PromotionSelection { assignmentId: string; expectedVersion: number }
export interface PromotionPreview {
  revisionNumber: number
  compatible: boolean
  items: Array<{ assignmentId: string; expectedVersion: number; compatible: boolean;
    errorCode: string | null; variableName: string | null }>
}
export interface PromotionResult { revisionNumber: number; assignments: Array<{ assignmentId: string; version: number }> }

export interface RolloutTarget { assignmentId: string; expectedVersion: number; connectionId: string;
  execution: DeploymentExecution }
export interface RolloutPreflightItem {
  assignmentId: string; expectedVersion: number; connectionId: string; ready: boolean
  desiredSha256: string | null; connectionUpdatedAt: string | null
  remote: { exists: boolean; sha256: string | null } | null
  addedLines: number; removedLines: number; errorCode: string | null
}
export interface ApprovedRolloutTarget extends RolloutTarget {
  connectionUpdatedAt: string; desiredSha256: string; expectedRemoteSha256: string | null;
  expectedRemoteMissing: boolean
}
export interface RolloutStrategy {
  canaryCount: number; batchSize: number; pauseSeconds: number; stopOnFailure: boolean
  rollbackMode: 'FAILED_TARGET_ONLY' | 'ALL_APPLIED'
}
export interface RolloutSummary extends RolloutStrategy {
  id: string; profileId: string; profileRevisionNumber: number
  state: 'QUEUED' | 'RUNNING' | 'PAUSED' | 'FAILED' | 'ROLLING_BACK' | 'ROLLED_BACK' | 'SUCCEEDED' | 'CANCELLED'
  createdAt: string; startedAt: string | null; finishedAt: string | null
}
export interface RolloutDetail {
  rollout: RolloutSummary
  items: Array<{ id: string; position: number; assignmentId: string; assignmentVersion: number;
    resourceId: string; targetPath: string; state: 'PENDING' | 'DEPLOYING' | 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK' | 'SKIPPED';
    deploymentId: string | null }>
}

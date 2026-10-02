export type ProvisioningState = 'PLANNED' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export type ProvisioningStepState = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'UNKNOWN'

export interface ProvisioningInputSnapshot {
  schemaVersion: number
  runKind: 'SERVER_BASELINE_CHECK' | 'SERVER_PROFILE_APPLY'
  resourceId: string
  resourceType: string
  resourceKind: string
  connectionId: string
  connectionUpdatedAt: string
  steps: string[]
}
export interface ProvisioningStep {
  id: string
  position: number
  kind: string
  displayName: string
  attempt: number
  state: ProvisioningStepState
  startedAt: string | null
  finishedAt: string | null
  facts: Record<string, string>
  failureCode: string | null
  safeMessage: string | null
  outputSummary: string | null
  verificationResult: boolean | null
  outputTruncated: boolean
}
export interface ProvisioningRun {
  id: string
  organizationId: string
  resourceId: string
  requestId: string | null
  requestedByUserId: string | null
  currentStep: string | null
  state: ProvisioningState
  createdAt: string
  updatedAt: string
  startedAt: string | null
  finishedAt: string | null
  failureCode: string | null
  safeMessage: string | null
  inputSnapshot: ProvisioningInputSnapshot
}
export interface ProvisioningPlan {
  run: ProvisioningRun
  approvalInput: ProvisioningInputSnapshot
  connectionName: string
  steps: ProvisioningStep[]
  warnings: string[]
  blockingProblems: string[]
}
export interface ProvisioningDetail { run: ProvisioningRun; steps: ProvisioningStep[] }

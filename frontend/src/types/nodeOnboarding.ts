import type { NodeApiCompatibility } from './integration'

export interface NodeOnboardingServer {
  id: string; name: string; address: string; environmentName: string; sshStatus: string
  serverProfileName: string | null; revisionNumber: number | null; serverProfileStatus: string
  blockingProblems: string[]
}
export interface NodeOnboardingProfile { id: string; name: string; inbounds: { id: string; name: string }[] }
export interface NodeOnboardingOptions {
  nodeApi: NodeApiCompatibility | null; servers: NodeOnboardingServer[]; profiles: NodeOnboardingProfile[]
}
export interface NodeOnboardingRun {
  id: string; organizationId: string; integrationId: string; resourceId: string; requestId: string | null
  state: 'PLANNED' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'; phase: string
  nodeName: string; address: string; nodePort: number; externalNodeId: string | null; baselineRunId: string | null
  syncSessionId: string | null; failureCode: string | null; safeMessage: string | null; createdAt: string; updatedAt: string
  startedAt: string | null; finishedAt: string | null
  correlationId?: string
  recovery?: NodeOnboardingRecoverySummary
}
export type NodeOnboardingRecoveryState = 'PRESENT_EXACT' | 'PRESENT_UNHEALTHY' | 'CONFIRMED_NOT_FOUND' | 'PRESENT_CONFLICT' | 'UNKNOWN'
export type NodeOnboardingRecoveryAction = 'RECOVER' | 'RECREATE' | 'DELETE_RECREATE'
export interface NodeOnboardingRecoverySummary {
  state: NodeOnboardingRecoveryState; action: NodeOnboardingRecoveryAction; sourceRunId: string
  previousExternalNodeId: string | null; previousCorrelationId: string; installationOwnerId: string
}
export interface NodeOnboardingPreview {
  run: NodeOnboardingRun; serverName: string; serverProfileName: string; revisionNumber: number
  configProfileName: string; inboundNames: string[]; nodeImage: string | null
  changes: string[]; warnings: string[]; blockingProblems: string[]
  nodeApi?: NodeApiCompatibility; panelCidrs?: string[]
  recovery?: NodeOnboardingRecoverySummary
  localInstallationState?: 'ABSENT' | 'OWNED_COMPLETE' | 'OWNED_PARTIAL' | 'OWNED_DAMAGED' | 'FOREIGN' | 'PORT_CONFLICT' | 'UNKNOWN'
}
export interface NodeOnboardingRunDetail {
  run: NodeOnboardingRun
  phases: { phase: string; state: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'; startedAt: string | null; finishedAt: string | null; failureCode: string | null }[]
}
export interface NodeOnboardingPreviewRequest {
  resourceId: string; nodeName: string; address: string; nodePort: number; configProfileId: string
  activeInboundIds: string[]; panelCidrs: string[]; desiredState: 'ENABLED'
}

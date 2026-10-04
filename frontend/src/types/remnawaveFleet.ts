export type FleetCompliance = 'COMPLIANT' | 'DRIFTED' | 'UNKNOWN' | 'BLOCKED'
export type FleetHealth = 'HEALTHY' | 'DEGRADED' | 'UNKNOWN'
export type FleetDesiredNodeState = 'ENABLED' | 'DISABLED'

export interface FleetSummary {
  totalNodes: number; compliant: number; drifted: number; unknown: number; blocked: number
  healthy: number; degraded: number; healthUnknown: number; assessed: number
  lastAssessmentAt: string | null; oldestEvidenceAt: string | null
}

export interface FleetDesiredConfiguration {
  serverProfileId: string; serverProfileRevisionNumber: number
  inventoryConfigProfileId: string; externalConfigProfileId: string; configurationProfileId: string
  configRevisionNumber: number; activeInboundIds: string[]; nodePort: number; panelCidrs: string[]
  desiredNodeState: FleetDesiredNodeState
}

export interface Fleet {
  id: string; code: string; name: string; description: string | null
  desiredRevisionId: string | null; desiredRevisionNumber: number | null
  version: number; archived: boolean; createdAt: string; updatedAt: string; summary: FleetSummary
}

export interface FleetRevision {
  id: string; number: number; schemaVersion: number; contentHash: string; createdAt: string
  desired: boolean; desiredConfiguration: FleetDesiredConfiguration
}

export interface FleetAssessment {
  fleetRevisionId: string; compliance: FleetCompliance; health: FleetHealth
  driftReasons: string[]; healthReasons: string[]; rolloutBlockers: string[]
  inventoryObservedAt: string | null; serverObservedAt: string | null; localObservedAt: string | null
  oldestEvidenceAt: string | null; computedAt: string
}

export interface FleetMember {
  membershipId: string; membershipVersion: number; inventoryNodeId: string; resourceId: string
  nodeName: string; nodeAddress: string; countryCode: string | null
  resourceName: string; resourceActive: boolean; connected: boolean; disabled: boolean
  localManaged: boolean
  actualServerProfileName: string | null; actualServerRevisionNumber: number | null
  actualConfigProfileName: string | null; actualConfigProfileExternalId: string | null
  actualInboundIds: string[] | null; actualDesiredNodeState: FleetDesiredNodeState | null
  assessment: FleetAssessment | null
}

export interface FleetDetail {
  fleet: Fleet; desiredRevision: FleetRevision | null
  serverProfileName: string | null; configurationProfileName: string | null
  revisions: FleetRevision[]; members: FleetMember[]
}

export interface FleetCandidate {
  inventoryNodeId: string; externalId: string; nodeName: string; resourceId: string; resourceName: string
  eligible: boolean; blockedBy: string | null; currentFleetName: string | null
}

/** What promoting a candidate revision would mean. Reading it changes nothing. */
export interface FleetRevisionImpact {
  currentRevisionNumber: number | null; candidateRevisionNumber: number
  currentServerProfileName: string | null; candidateServerProfileName: string
  currentServerRevisionNumber: number | null; candidateServerRevisionNumber: number
  currentConfigProfileName: string | null; candidateConfigProfileName: string
  currentConfigRevisionNumber: number | null; candidateConfigRevisionNumber: number
  currentPanelCidrs: string[] | null; candidatePanelCidrs: string[]
  currentNodePort: number | null; candidateNodePort: number
  currentDesiredNodeState: FleetDesiredNodeState | null; candidateDesiredNodeState: FleetDesiredNodeState
  members: number; compliantAfter: number; expectedDrift: number; unknown: number; blocked: number
}

export interface FleetDesiredRequest {
  serverProfileId: string; serverProfileRevisionNumber: number
  inventoryConfigProfileId: string; configRevisionNumber: number
  activeInboundIds: string[]; nodePort: number; panelCidrs: string[]
  desiredNodeState: FleetDesiredNodeState
}

export interface CreateFleetRequest {
  code: string; name: string; description: string | null
  desiredConfiguration: FleetDesiredRequest; memberNodeIds: string[]
}

export type RolloutState = 'PLANNED' | 'QUEUED' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
  | 'ROLLING_BACK' | 'ROLLED_BACK'
export type RolloutScope = 'CURRENT_WAVE' | 'ALL_COMPLETED'

export interface RolloutIssue { code: string; node: string | null }

export interface FleetRollout {
  id: string; fleetId: string; integrationId: string; revisionId: string; state: RolloutState; phase: string
  expired: boolean; currentWave: number; waveCount: number; pauseAfterCanary: boolean
  automaticRollback: boolean; rollbackScope: RolloutScope; createdAt: string; expiresAt: string
  startedAt: string | null; finishedAt: string | null; failureCode: string | null; rollbackIncomplete: boolean
  pauseReason: string | null; pauseRequested: boolean; rollbackRequested: boolean; updatedAt: string
}

export interface RolloutPlanMember {
  membershipId: string; inventoryNodeId: string; nodeName: string; wave: number; position: number
  skipReason: string | null; actions: string[]; baselineCompliance: string; baselineHealth: string
  rollbackCapabilities?: { kind: string; supported: boolean; reason: string | null }[]
}

export interface RolloutSnapshot {
  revisionId: string; revisionNumber: number
  policy: { waveSize: number; canaryMembershipIds: string[]; pauseAfterCanary: boolean
    automaticRollback: boolean; rollbackScope: RolloutScope }
  sharedConfig: { required: boolean; revisionNumber: number; externalNodes: number; externalUnhealthyNodes: number
    baselineRevisionNumber?: number | null; rollbackSupported?: boolean
    consumers?: { inventoryNodeId: string; externalNodeId: string; nodeName: string; disabled: boolean
      connected: boolean; inFleet: boolean }[] }
  waveCount: number; estimatedMutations: number; members: RolloutPlanMember[]
}

export type RolloutPreview =
  | { status: 'READY'; planId: string; expiresAt: string; estimatedMutations: number
      warnings: RolloutIssue[]; plan: RolloutSnapshot }
  | { status: 'REFRESH_REQUIRED'; issues: RolloutIssue[] }
  | { status: 'BLOCKED'; issues: RolloutIssue[]; warnings: RolloutIssue[] }

export interface RolloutMember {
  id: string; membershipId: string; nodeName: string | null; wave: number; position: number; state: string
  skipReason: string | null; plannedActions: string[]; failureCode: string | null
  rollbackFailureCode: string | null; finishedAt: string | null
}

export interface RolloutAction {
  id: string; memberId: string | null; rollback: boolean; kind: string; sequence: number; state: string
  serverProfileRunId: string | null; configRolloutId: string | null; failureCode: string | null
  desiredStateActionId?: string | null
  finishedAt: string | null
}

export interface FleetRolloutDetail extends FleetRollout {
  snapshot: RolloutSnapshot; members: RolloutMember[]; actions: RolloutAction[]
}

export interface RolloutPreviewRequest {
  revisionId: string; canaryMemberIds: string[]; waveSize: number; automaticRollback: boolean
  pauseAfterCanary: boolean
}

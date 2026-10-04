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

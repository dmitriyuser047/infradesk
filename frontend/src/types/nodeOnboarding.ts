import type { PackageProbeFinding } from './serverProfile'
import type { NodeApiCompatibility } from './integration'
export type NodeProtocolSettings = { version: 1; kind: 'HYSTERIA2'; port: number; serverName: string } |
  { version: 1; kind: 'SHADOWSOCKS'; port: number; method: 'chacha20-ietf-poly1305' | 'aes-128-gcm' | 'aes-256-gcm' }

export interface NodeOnboardingServer {
  id: string; name: string; address: string; environmentName: string; sshStatus: string
  serverProfileName: string | null; revisionNumber: number | null; serverProfileStatus: string
  blockingProblems: string[]; previousBindingReview?: boolean
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
  panelSource?: PanelSourceEvidence | null
  connectivityFinding?: { component: 'PANEL_CONNECTIVITY'; localNode: 'HEALTHY'; panelNode: 'CONNECTED' | 'DISCONNECTED'; firewall: 'CONFIGURED'; panelSources: string[]; sourceEvidence: string } | null
  observedPanelSource?: { status: 'AUTO_OBSERVED' | 'NOT_REQUIRED' | 'NO_TRAFFIC' | 'AMBIGUOUS' | 'UNAVAILABLE'; sources: string[] } | null
  connectivityCompletion?: 'PROMOTED' | 'ROLLED_BACK' | null
  protocol?: NodeProtocolSettings | null
  protocolBinding?: { profileId: string; inboundIds: string[]; configSha256: string } | null
  clientTrafficVerification?: 'NOT_RUN' | null
}
export interface PanelSourceEvidence {
  mode: 'AUTO' | 'MANUAL'; sources: string[]; method: 'DNS_BASE_URL' | 'MANAGED_PANEL_RESOURCE' | 'MANUAL'
  confidence: 'AUTO_CANDIDATE' | 'MANUAL' | 'UNRESOLVED'; endpointFingerprint: string
}
export type NodeOnboardingRecoveryState = 'PRESENT_EXACT' | 'PRESENT_UNHEALTHY' | 'CONFIRMED_NOT_FOUND' | 'PRESENT_CONFLICT' | 'UNKNOWN'
export type NodeOnboardingRecoveryAction = 'RECOVER' | 'RECREATE' | 'DELETE_RECREATE' | 'REPAIR_PANEL_CONNECTIVITY' | 'RECREATE_WITH_NEW_CONFIG'
export interface NodeOnboardingRecoverySummary {
  state: NodeOnboardingRecoveryState; action: NodeOnboardingRecoveryAction; sourceRunId: string
  previousExternalNodeId: string | null; previousCorrelationId: string; installationOwnerId: string
  localInstallation?: LocalInstallationObservation
  previousInstallation?: { nodeName: string; address: string; nodePort: number; protocol: NodeProtocolSettings | null; certificateId: string | null; inventoryObjectId: string | null }
}
export interface LocalInstallationObservation {
  state: 'ABSENT' | 'OWNED_COMPLETE' | 'OWNED_PARTIAL' | 'OWNED_DAMAGED' | 'FOREIGN' | 'PORT_CONFLICT' | 'UNKNOWN'
  diagnosis: string | null
  remediation?: 'MANUAL_ONLY' | 'DIAGNOSIS_ONLY' | 'RECREATE' | 'CONTROLLED_RETIREMENT'
}
export interface NodeOnboardingPreview {
  run: NodeOnboardingRun; serverName: string; serverProfileName: string; revisionNumber: number
  configProfileName: string; inboundNames: string[]; nodeImage: string | null
  changes: string[]; warnings: string[]; blockingProblems: string[]; packageFindings?: PackageProbeFinding[]
  nodeApi?: NodeApiCompatibility; panelCidrs?: string[]
  protocolPorts?: { port: number; transport: 'tcp' | 'udp'; state: 'FREE' | 'OWNED_EXPECTED' | 'FOREIGN_LISTENER' | 'FIREWALL_CONFLICT' | 'OBSERVATION_UNKNOWN' }[]
  panelSource?: PanelSourceEvidence
  input?: Omit<NodeOnboardingPreviewRequest, 'panelCidrs' | 'panelSourceMode'>
  recovery?: NodeOnboardingRecoverySummary
  localInstallationState?: 'ABSENT' | 'OWNED_COMPLETE' | 'OWNED_PARTIAL' | 'OWNED_DAMAGED' | 'FOREIGN' | 'PORT_CONFLICT' | 'UNKNOWN'
}
export interface NodeOnboardingRunDetail {
  run: NodeOnboardingRun
  certificate?: { domain: string; expiresAt: string; automaticRenewal: false } | null
  phases: { phase: string; state: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'; outcome?: string | null; startedAt: string | null; finishedAt: string | null; failureCode: string | null }[]
}
export interface NodeOnboardingPreviewRequest {
  resourceId: string; nodeName: string; address: string; nodePort: number; configProfileId?: string
  activeInboundIds?: string[]; protocol?: NodeProtocolSettings; tlsCertificateId?: string
  tlsHttp01?: { certificateId: string; email: string; agreeTerms: true }
  panelCidrs?: string[]; panelSourceMode?: 'AUTO' | 'MANUAL'; nodeAddressMode?: 'PUBLIC_IP' | 'DOMAIN'; desiredState: 'ENABLED'
}

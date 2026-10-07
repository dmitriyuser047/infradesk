export interface PackageProbeFinding { name: string; observedState: string | null; classification: 'BROKEN' | 'UNKNOWN'; suggestedAction: 'REPAIR' | 'MANUAL' }
export interface ServerProfileContent {
  schemaVersion: 1
  packages: { enabled: boolean; packages: string[] }
  network: { enabled: boolean; bbr: boolean; sysctl: Record<string, string> }
  limits: { enabled: boolean; nofileSoft: number; nofileHard: number; systemdDefaultLimitNofile: number }
  firewall: { enabled: boolean; rules: { id: string; protocol: 'tcp' | 'udp'; port: number; sources: string[] }[] }
  fail2ban: { enabled: boolean }
  docker: { enabled: boolean }
  caddy: { enabled: boolean; domain: string | null; localHttpsPort: number; siteRoot: string; compression: 'gzip' | 'zstd'; redirect: 'NONE' | 'HTTP_TO_HTTPS' }
  site: { enabled: boolean; domain: string | null; root: string; template: 'DEFAULT_PLACEHOLDER'; templateVersion: 1 }
}

export interface ServerProfile {
  id: string; organizationId: string; code: string; name: string; description: string | null
  archived: boolean; latestRevision: number; createdAt: string; updatedAt: string
}
export interface ServerProfileRevision {
  id: string; profileId: string; number: number; contentHash: string; content: ServerProfileContent; createdAt: string
}
export interface ServerProfileAssignment {
  id: string; resourceId: string; profileId: string; revisionId: string; revisionNumber: number; version: number
}
export interface ServerProfileAssignmentView extends ServerProfileAssignment { resourceName: string; resourceActive: boolean }
export interface ServerProfileAssessment {
  compliant: boolean; modules: { module: string; compliant: boolean }[]
  changes: { module: string; code: string; detail: string | null; before: string | null; after: string | null }[]
}
export interface ServerProfileObservation { id: string; content: Record<string, unknown>; contentHash: string; observedAt: string }
export interface ServerProfilePlan {
  run: { id: string; state: string }
  connectionName: string; resourceKind: string; profileName: string; revisionNumber: number
  steps: { kind: string; position: number }[]; dependencyPackages: string[]; endpoint: string | null
  assessment: ServerProfileAssessment; warnings: string[]; blockingProblems: string[]; packageFindings?: PackageProbeFinding[]
}
export interface ServerProfileAutomation {
  state: 'UNOBSERVED' | 'APPLYING' | 'COMPLIANT' | 'DRIFTED' | 'APPLY_FAILED' | 'UNKNOWN' | 'WAITING_REFRESH'
  operationsBlocked: boolean; assignment: ServerProfileAssignment | null; profile: ServerProfile | null
  revision: ServerProfileRevision | null; observation: ServerProfileObservation | null
  assessment: ServerProfileAssessment | null; activeRun: { id: string; state: string } | null
}
export interface ServerProfileDetail {
  profile: ServerProfile; revisions: ServerProfileRevision[]; assignments: ServerProfileAssignmentView[]
}
export interface ServerProfileList { items: { profile: ServerProfile; assignmentCount: number }[] }

export const emptyServerProfileContent = (): ServerProfileContent => ({
  schemaVersion: 1,
  packages: { enabled: false, packages: [] },
  network: { enabled: false, bbr: false, sysctl: {} },
  limits: { enabled: false, nofileSoft: 65536, nofileHard: 65536, systemdDefaultLimitNofile: 65536 },
  firewall: { enabled: false, rules: [] },
  fail2ban: { enabled: false },
  docker: { enabled: false },
  caddy: { enabled: false, domain: null, localHttpsPort: 8080, siteRoot: '/var/www/infradesk/default', compression: 'zstd', redirect: 'NONE' },
  site: { enabled: false, domain: null, root: '/var/www/infradesk/default', template: 'DEFAULT_PLACEHOLDER', templateVersion: 1 },
})

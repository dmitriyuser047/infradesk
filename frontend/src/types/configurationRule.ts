export interface Label { key: string; value: string }
export interface LabelSet { version: number; labels: Label[] }

/** Structured fields only: empty project/environment lists mean any. */
export interface RuleSelector {
  projects: string[]
  environments: string[]
  requiredLabels: Label[]
  excludedLabels: Label[]
}

export interface ConfigurationRule {
  id: string
  code: string
  name: string
  description: string | null
  profile: { id: string; code: string; name: string; archived: boolean; latestRevisionNumber: number }
  profileRevisionNumber: number
  targetPath: string
  selector: RuleSelector
  enabled: boolean
  archived: boolean
  version: number
  counts: { matched: number; managed: number; issues: number; excluded: number }
  createdAt: string
  updatedAt: string
  lastReconciledAt: string | null
  nextReconcileAt: string | null
}

export type RuleTargetStatus = 'ASSIGNED' | 'PENDING' | 'NEEDS_VALUES' | 'TARGET_PATH_CONFLICT' | 'OTHER_RULE_CONFLICT'
  | 'PROFILE_ARCHIVED' | 'ASSIGNMENT_INVALID' | 'EXCLUDED' | 'NO_LONGER_MATCHING'

export interface RuleTarget {
  resource: { id: string; name: string; code: string; active: boolean }
  project: { id: string; name: string }
  environment: { id: string; name: string }
  matchesSelector: boolean
  excluded: boolean
  assignment: { id: string; version: number | null; revision: number | null; managedByRule: boolean; sourceRuleId: string | null } | null
  status: RuleTargetStatus
  issue: { code: string; variableName: string | null; conflictingAssignmentId: string | null } | null
}

export interface SelectorPreview {
  matchedCount: number
  eligibleCount: number
  needsValuesCount: number
  conflictCount: number
  manualConflictCount: number
  ruleConflictCount: number
  missingVariable: string | null
  items: Array<{ resourceId: string; resourceName: string; environmentName: string; projectName: string
    state: 'ELIGIBLE' | 'ADOPTABLE' | 'NEEDS_VALUES' | 'TARGET_PATH_CONFLICT' | 'OTHER_RULE_CONFLICT'; assignmentId: string | null }>
}

export interface RulePromotionPreview {
  ruleVersion: number
  fromRevision: number
  targetRevision: number
  compatible: boolean
  assignmentCount: number
  items: Array<{ assignmentId: string; expectedVersion: number; resourceName: string | null; compatible: boolean;
    issues: Array<{ code: string; variableName: string | null }> }>
}

export interface RuleDraft {
  code: string
  name: string
  description: string | null
  profileId: string
  profileRevisionNumber: number
  targetPath: string
  selector: RuleSelector
  enabled: boolean
}

import type { ConfigurationActor, ConfigurationVariable } from './configuration'

/**
 * Desired state: which exact version of a profile, with which values, one file on one node should
 * hold. It says nothing about what the server holds now — nothing is applied yet.
 */
export interface ConfigurationAssignment {
  id: string
  /** The assignment's own change counter, sent back as `expectedVersion`; not a configuration version. */
  version: number
  targetPath: string
  profileRevisionNumber: number
  removedAt: string | null
  createdAt: string
  updatedAt: string
  resource: {
    id: string
    name: string
    code: string
    resourceTypeCode: string
    active: boolean
    project: { id: string; name: string }
    environment: { id: string; name: string; kind: string }
  }
  profile: { id: string; code: string; name: string; archived: boolean; latestRevisionNumber: number }
}

export interface ConfigurationValue {
  name: string
  value: string
}

/** An assignment as its editor needs it: the pinned version's definitions and the explicit values only. */
export interface ConfigurationAssignmentDetail extends ConfigurationAssignment {
  revision: { revisionNumber: number; variables: ConfigurationVariable[]; createdBy: ConfigurationActor; createdAt: string }
  values: ConfigurationValue[]
}

export interface CreateConfigurationAssignmentRequest {
  resourceId: string
  profileId: string
  profileRevisionNumber: number
  targetPath: string
  values: ConfigurationValue[]
}

export interface UpdateConfigurationAssignmentRequest {
  expectedVersion: number
  profileRevisionNumber: number
  targetPath: string
  values: ConfigurationValue[]
}

export interface ConfigurationAssignmentPreview {
  valid: boolean
  resolvedRevisionNumber: number
  renderedPreview: string | null
  error: { code: string; variableName: string } | null
}

export interface ConfigurationAssignmentFilter {
  resourceId?: string
  profileId?: string
}

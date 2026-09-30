/** No secret-like types: a value here is plain text, stored and shown as such. */
export type ConfigurationValueType = 'STRING' | 'INTEGER' | 'BOOLEAN'

export const ConfigurationValueTypes: readonly ConfigurationValueType[] = ['STRING', 'INTEGER', 'BOOLEAN']

export interface ConfigurationVariable {
  name: string
  type: ConfigurationValueType
  required: boolean
  defaultValue: string | null
  description: string | null
}

export interface ConfigurationActor {
  id: string
  displayName: string
}

/** A profile's metadata. Its content lives in revisions and never appears in a list. */
export interface ConfigurationProfile {
  id: string
  kind: 'FILE_TEMPLATE' | 'REMNAWAVE_CONFIG'
  code: string
  name: string
  description: string | null
  archived: boolean
  latestRevisionNumber: number
  latestRevisionCreatedAt: string | null
  createdAt: string
  updatedAt: string
}

/** One immutable version of a profile's content. */
export interface ConfigurationRevision {
  revisionNumber: number
  template: string
  variables: ConfigurationVariable[]
  createdBy: ConfigurationActor
  createdAt: string
}

export interface ConfigurationRevisionSummary {
  revisionNumber: number
  variableCount: number
  createdBy: ConfigurationActor
  createdAt: string
}

export interface ConfigurationProfileDetail {
  profile: ConfigurationProfile
  latestRevision: ConfigurationRevision
}

export interface ConfigurationContentRequest {
  template: string
  variables: { name: string; type: ConfigurationValueType; required: boolean; defaultValue?: string; description?: string }[]
}

export interface CreateConfigurationProfileRequest extends ConfigurationContentRequest {
  code: string
  name: string
  description: string | null
}

export interface ConfigurationDiagnostic {
  code: string
  severity: 'ERROR' | 'WARNING'
  variableName: string | null
  line: number | null
  column: number | null
}

export interface ConfigurationValidationResponse {
  valid: boolean
  referencedVariables: string[]
  diagnostics: ConfigurationDiagnostic[]
  renderedPreview: string | null
  previewError: { code: string; variableName: string } | null
}

/** The body of a refused save: the same findings the validation endpoint reports. */
export interface InvalidConfigurationResponse {
  code: 'INVALID_CONFIGURATION'
  message: string
  diagnostics: ConfigurationDiagnostic[]
}

import { describe, expect, it } from 'vitest'

import { EnvironmentKind } from '../../types/navigation'
import { buildCreateEnvironmentRequest, buildCreateProjectRequest } from './buildWorkspaceRequests'

describe('workspace create requests', () => {
  it('normalizes project fields and omits a blank description', () => {
    expect(buildCreateProjectRequest(' svinpeak ', ' Svinpeak ', '  ')).toEqual({
      code: 'svinpeak', name: 'Svinpeak', description: null,
    })
    expect(buildCreateProjectRequest(' prod ', ' Production ', ' Main workspace ')).toEqual({
      code: 'prod', name: 'Production', description: 'Main workspace',
    })
  })

  it('uses the existing environment kind code', () => {
    expect(buildCreateEnvironmentRequest(' prod ', ' Production ', EnvironmentKind.prod)).toEqual({
      code: 'prod', name: 'Production', kind: 'PROD',
    })
  })
})

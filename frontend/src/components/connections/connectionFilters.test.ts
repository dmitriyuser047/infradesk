import { describe, expect, it } from 'vitest'
import type { ConnectionResponse } from '../../types/connection'
import { filterConnections } from './connectionFilters'

function connection(id: string, name: string, connectorType: string, active: boolean): ConnectionResponse {
  return { id, name, code: id, connectorType, active, scope: { type: 'ORGANIZATION' },
    schedule: null, lastSync: null, ssh: null, createdAt: '', updatedAt: '' }
}

describe('connection grid filters', () => {
  const rows = [connection('finland', 'Finland', 'SSH', true), connection('docker-1', 'Docker host', 'DOCKER', false)]

  it('filters by name or code without altering the loaded data', () => {
    expect(filterConnections(rows, ' FIN ', 'ALL', 'ALL').map(row => row.id)).toEqual(['finland'])
    expect(rows).toHaveLength(2)
  })

  it('combines type and status locally', () => {
    expect(filterConnections(rows, '', 'DOCKER', 'INACTIVE').map(row => row.id)).toEqual(['docker-1'])
    expect(filterConnections(rows, '', 'SSH', 'INACTIVE')).toEqual([])
  })
})

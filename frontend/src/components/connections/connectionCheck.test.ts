import { describe, expect, it } from 'vitest'

import { checkKey, currentResult, type CheckDraft } from './connectionCheck'

const draft: CheckDraft = { host: 'example.test', port: 22, username: 'deploy', authenticationType: 'PRIVATE_KEY',
  fingerprint: 'SHA256:trusted', credentialRevision: 3 }

describe('connection check binding', () => {
  it('changes with every setting a check depends on', () => {
    const changes: Partial<CheckDraft>[] = [{ host: 'other.test' }, { port: 2222 }, { username: 'root' },
      { authenticationType: 'PASSWORD' }, { fingerprint: 'SHA256:other' }, { credentialRevision: 4 }]
    for (const change of changes) expect(checkKey({ ...draft, ...change })).not.toBe(checkKey(draft))
    // Surrounding spaces are not a different server or user.
    expect(checkKey({ ...draft, host: ' example.test ', username: ' deploy ' })).toBe(checkKey(draft))
  })

  it('keeps no secret, only a revision of it', () => {
    expect(checkKey(draft)).not.toMatch(/PRIVATE KEY|password/i)
  })

  it('shows a result only for the draft it was made for', () => {
    const result = { key: checkKey(draft), value: 'ok' }

    expect(currentResult(result, checkKey(draft))).toBe('ok')
    expect(currentResult(result, checkKey({ ...draft, host: 'other.test' }))).toBeNull()
    expect(currentResult(null, checkKey(draft))).toBeNull()
  })
})

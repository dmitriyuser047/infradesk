// @vitest-environment jsdom
import {afterEach,describe,expect,it,vi} from 'vitest'
import {readPendingSubmission,storePendingSubmission} from './pendingSubmission'
const identity={planId:'plan',requestId:'d1111111-1111-4111-8111-111111111111'}
afterEach(()=>{sessionStorage.clear();vi.restoreAllMocks()})
describe('pending plan submission identity',()=>{
  it('stores only IDs, scopes recovery, and clears resolved submissions',()=>{
    storePendingSubmission('org:resource:apply',identity)
    expect(readPendingSubmission('org:resource:apply')).toEqual(identity)
    expect(readPendingSubmission('other:resource:apply')).toBeNull()
    expect(JSON.parse(sessionStorage.getItem('org:resource:apply')!)).toEqual(identity)
    storePendingSubmission('org:resource:apply',null)
    expect(readPendingSubmission('org:resource:apply')).toBeNull()
  })
  it('does not restore malformed or incomplete submissions',()=>{
    for (const raw of ['{','null','{}',JSON.stringify({...identity,requestId:'bad'}),JSON.stringify({...identity,planId:''})]) {
      sessionStorage.setItem('key',raw);expect(readPendingSubmission('key')).toBeNull()
    }
  })
  it('tolerates unavailable session storage while the caller retains its identity',()=>{
    vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('denied')})
    vi.spyOn(Storage.prototype,'getItem').mockImplementation(()=>{throw new Error('denied')})
    expect(()=>storePendingSubmission('key',identity)).not.toThrow()
    expect(readPendingSubmission('key')).toBeNull()
  })
})

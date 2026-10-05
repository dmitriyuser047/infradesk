// @vitest-environment jsdom
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {I18nProvider} from '../../i18n'
import {ProvisioningPanel} from './ProvisioningPanel'

const getRandomValues = crypto.getRandomValues.bind(crypto)
beforeEach(()=>vi.stubGlobal('crypto',{getRandomValues}))
const base = '/api/v1/organizations/org'
const run = {id:'plan-1',state:'PLANNED',createdAt:'2026-10-05T12:00:00Z',inputSnapshot:{runKind:'SERVER_BASELINE_CHECK',resourceKind:'new_vps_test'}}
const plan = {run,approvalInput:run.inputSnapshot,connectionName:'Production SSH',steps:[],warnings:[],blockingProblems:[]}
const clients:QueryClient[]=[]
function setup({blockers=[] , fail=false, pending=false}:{blockers?:string[];fail?:boolean;pending?:boolean}={}) {
  const posts:{planId:string;requestId:string}[]=[]
  let complete!:(response:Response)=>void
  vi.stubGlobal('fetch',vi.fn(async(url:string,init?:RequestInit)=>{
    if (url===`${base}/provisioning/plan`) return Response.json({...plan,blockingProblems:blockers})
    if (url===`${base}/provisioning/runs` && init?.method==='POST') {
      posts.push(JSON.parse(String(init.body)))
      if (pending) return new Promise<Response>(resolve=>{complete=resolve})
      if (fail) return Response.json({code:'HTTP_ERROR',message:'raw error'},{status:503})
      return Response.json({...run,id:'queued-1',state:'QUEUED'})
    }
    if (url.endsWith('/resources/resource/provisioning/runs')) return Response.json({items:[]})
    if (url.endsWith('/provisioning/runs/plan-1')) return Response.json({run,steps:[]})
    if (url.endsWith('/provisioning/runs/queued-1')) return Response.json({run:{...run,id:'queued-1',state:'SUCCEEDED'},steps:[]})
    throw new Error(`Unexpected request ${url}`)
  }))
  const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});clients.push(client)
  const onQueued=vi.fn()
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}><ProvisioningPanel organizationId="org" resourceId="resource" resourceName="VPS" canRun onRunQueued={onQueued}/></QueryClientProvider></I18nProvider>)
  return {posts,onQueued,complete:()=>complete(Response.json({...run,id:'queued-1',state:'QUEUED'}))}
}
async function review(){fireEvent.click(screen.getByRole('button',{name:'Review readiness check'}));return screen.findByRole('dialog')}
afterEach(()=>{cleanup();clients.splice(0).forEach(client=>client.clear());vi.unstubAllGlobals();sessionStorage.clear()})
describe('Provisioning submission on insecure HTTP',()=>{
  it('starts exactly once with the reviewed plan and UUID even without randomUUID',async()=>{
    const test=setup();const dialog=await review()
    fireEvent.click(within(dialog).getByRole('button',{name:'Approve and run checks'}))
    await waitFor(()=>expect(test.onQueued).toHaveBeenCalledWith('queued-1'))
    expect(test.posts).toEqual([{planId:'plan-1',requestId:expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)}])
    expect(screen.queryByRole('dialog')).toBeNull()
  })
  it('retains the identity on uncertainty and recovers it after remount without automatic start',async()=>{
    const first=setup({fail:true});const dialog=await review()
    fireEvent.click(within(dialog).getByRole('button',{name:'Approve and run checks'}))
    const error=await within(dialog).findByRole('alert')
    const button=within(dialog).getByRole('button',{name:'Approve and run checks'})
    expect(button.parentElement?.parentElement?.contains(error)).toBe(true)
    fireEvent.click(button)
    await waitFor(()=>expect(first.posts).toHaveLength(2))
    expect(first.posts[0]).toEqual(first.posts[1])
    cleanup();clients.splice(0).forEach(client=>client.clear())
    const second=setup();const retry=await screen.findByRole('button',{name:'Retry unconfirmed submission'}) as HTMLButtonElement
    await waitFor(()=>expect(retry.disabled).toBe(false));expect(second.posts).toHaveLength(0)
    fireEvent.click(retry);await waitFor(()=>expect(second.onQueued).toHaveBeenCalledWith('queued-1'))
    expect(second.posts).toEqual([first.posts[0]])
  })
  it('creates a new request ID for a newly reviewed plan',async()=>{
    const test=setup();let dialog=await review()
    fireEvent.click(within(dialog).getByRole('button',{name:'Approve and run checks'}))
    await waitFor(()=>expect(screen.queryByRole('dialog')).toBeNull())
    dialog=await review();fireEvent.click(within(dialog).getByRole('button',{name:'Approve and run checks'}))
    await waitFor(()=>expect(test.posts).toHaveLength(2))
    expect(test.posts[1].requestId).not.toBe(test.posts[0].requestId)
  })
  it('does not start a blocked plan',async()=>{
    const test=setup({blockers:['PROVISIONING_DISK_INSUFFICIENT']});const dialog=await review()
    const button=within(dialog).getByRole('button',{name:'Approve and run checks'}) as HTMLButtonElement
    expect(button.disabled).toBe(true);fireEvent.click(button);expect(test.posts).toHaveLength(0)
  })
  it('does not submit a second time while start is pending',async()=>{
    const test=setup({pending:true});const dialog=await review()
    fireEvent.click(within(dialog).getByRole('button',{name:'Approve and run checks'}))
    await waitFor(()=>expect(test.posts).toHaveLength(1))
    const button=await within(dialog).findByRole('button',{name:'Queueing check…'}) as HTMLButtonElement
    expect(button.disabled).toBe(true);fireEvent.click(button);expect(test.posts).toHaveLength(1)
    test.complete();await waitFor(()=>expect(test.onQueued).toHaveBeenCalledWith('queued-1'))
  })
})

// @vitest-environment jsdom
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import {cleanup,fireEvent,render,screen,within} from '@testing-library/react'
import {afterEach,expect,it,vi} from 'vitest'
import {I18nProvider} from '../../i18n'
import {ResourceOperationsPanel} from './ResourceOperationsPanel'
const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}})
afterEach(()=>{cleanup();client.clear();vi.unstubAllGlobals()})
it('keeps a rejected primary operation visible inside its confirmation dialog',async()=>{
  client.setQueryData(['my-organizations'],[{id:'org',role:'OWNER'}])
  client.setQueryData(['resource-operations','org','resource'],{operations:['CONTAINER_RESTART'],unavailableReason:null})
  client.setQueryData(['resource-operation-executions','org','resource'],[])
  vi.stubGlobal('fetch',vi.fn(async()=>Response.json({code:'HTTP_ERROR',message:'raw secret'},{status:503})))
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}><ResourceOperationsPanel organizationId="org" resourceId="resource" resourceName="backend"/></QueryClientProvider></I18nProvider>)
  fireEvent.click(screen.getByRole('button',{name:'Restart'}))
  const dialog=screen.getByRole('dialog')
  fireEvent.click(within(dialog).getByRole('button',{name:'Confirm'}))
  expect((await within(dialog).findByRole('alert')).textContent).toContain('Operation could not be started.')
  expect(dialog.textContent).not.toContain('raw secret')
})

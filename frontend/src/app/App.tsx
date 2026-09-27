import { RouterProvider } from 'react-router-dom'

import { router } from './router'
import { TerminalWorkspaceProvider } from '../components/workspace/TerminalWorkspaceProvider'

export function App() {
  // Above the router: open terminals outlive every page and every route change.
  return <TerminalWorkspaceProvider><RouterProvider router={router} /></TerminalWorkspaceProvider>
}

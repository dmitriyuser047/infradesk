import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'

import { App } from './app/App'
import { queryClient } from './app/queryClient'
import { I18nProvider } from './i18n'
import './styles/tokens.css'
import './styles/components.css'
import './styles/shell.css'
import './styles/pages/overview.css'
import './styles/pages/connections.css'
import './styles/pages/resources.css'
import './styles/pages/incidents.css'
import './styles/pages/workspace.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </I18nProvider>
  </StrictMode>,
)

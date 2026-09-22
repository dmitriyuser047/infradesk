import { AppShell } from '../components/layout/AppShell'

export function InvalidRoutePage() {
  return (
    <AppShell>
      <section className="content-panel state-message">
        <h1>Environment context is missing</h1>
        <p>Open this page with an organization and environment identifier in the URL.</p>
      </section>
    </AppShell>
  )
}

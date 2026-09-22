import { Link, useParams } from 'react-router-dom'

import { useConnection, useConnectionSyncSession } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { formatConnectionDateTime, formatSyncDuration, getSyncFailureMessage } from '../components/connections/connectionPresentation'
import { AppShell } from '../components/layout/AppShell'
import { SyncStatus } from '../types/connection'
import { InvalidRoutePage } from './InvalidRoutePage'

export function ConnectionSyncSessionPage() {
  const { organizationId, connectionId, sessionId } = useParams()
  if (!organizationId || !connectionId || !sessionId) return <InvalidRoutePage />
  return <SessionContent organizationId={organizationId} connectionId={connectionId} sessionId={sessionId} />
}

function SessionContent({ organizationId, connectionId, sessionId }: {
  organizationId: string; connectionId: string; sessionId: string
}) {
  const session = useConnectionSyncSession(organizationId, connectionId, sessionId)
  const connection = useConnection(organizationId, connectionId)
  const back = `/organizations/${organizationId}/connections/${connectionId}`

  return <AppShell><div className="detail-page connection-detail-page">
    <Link className="back-link" to={back}>← Back to connection</Link>
    {session.isPending ? <div className="detail-skeleton" aria-label="Loading synchronization"><span /><span /></div> : null}
    {session.isError ? <section className="content-panel connection-state" role="alert">
      <h1>{session.error instanceof ApiError && session.error.code === 'SYNC_SESSION_NOT_FOUND'
        ? 'Synchronization session not found' : 'Unable to load synchronization'}</h1>
      {!(session.error instanceof ApiError && session.error.code === 'SYNC_SESSION_NOT_FOUND')
        ? <button type="button" className="retry-button" onClick={() => session.refetch()}>Retry</button> : null}
    </section> : null}
    {session.data ? <>
      <header className="resource-header connection-header"><div>
        <p className="eyebrow">Synchronization</p><h1>Synchronization</h1>
        <p className="page-subtitle">Connection: {connection.data?.name ?? connectionId}</p>
      </div><SyncStatusBadge status={session.data.status} /></header>
      <section className="content-panel connection-section"><h2>Execution</h2>
        <dl className="summary-grid connection-summary-grid">
          <div><dt>Status</dt><dd><SyncStatusBadge status={session.data.status} /></dd></div>
          <div><dt>Started</dt><dd>{formatConnectionDateTime(session.data.startedAt)}</dd></div>
          <div><dt>Finished</dt><dd>{session.data.finishedAt
            ? formatConnectionDateTime(session.data.finishedAt) : 'In progress'}</dd></div>
          <div><dt>Duration</dt><dd>{formatSyncDuration(session.data.startedAt, session.data.finishedAt)}</dd></div>
        </dl>
      </section>
      {session.data.status === SyncStatus.failed ? <section className="content-panel connection-section">
        <h2>Error</h2><p>{getSyncFailureMessage(session.data.errorMessage)}</p>
        {session.data.errorCode ? <p className="page-subtitle">Code: {session.data.errorCode}</p> : null}
      </section> : null}
    </> : null}
  </div></AppShell>
}

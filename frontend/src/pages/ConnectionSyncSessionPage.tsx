import { useLocation, useParams } from 'react-router-dom'

import { useConnection, useConnectionSyncSession } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { formatConnectionDateTime, formatSyncDuration, getSyncFailureMessage } from '../components/connections/connectionPresentation'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { AppShell } from '../components/layout/AppShell'
import { PropertyGrid, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
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
  const location = useLocation()
  const connection = useConnection(organizationId, connectionId)
  const back = `/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connectionId)}${location.search}`
  return <AppShell><div className="workspace-page">
    {session.isPending ? <div className="row-skeleton" aria-label="Loading synchronization"><span /><span /></div> : null}
    {session.isError ? <div className="inline-error" role="alert">
      {session.error instanceof ApiError && session.error.code === 'SYNC_SESSION_NOT_FOUND'
        ? 'Synchronization session not found' : 'Unable to load synchronization'}
      {!(session.error instanceof ApiError && session.error.code === 'SYNC_SESSION_NOT_FOUND')
        ? <button type="button" className="text-button" onClick={() => session.refetch()}>Retry</button> : null}
    </div> : null}
    {session.data ? <>
      <WorkspaceHeader title="Synchronization" subtitle={`Connection · ${connection.data?.name ?? connectionId}`}
        back={{ label: 'Connection', to: back }} status={<SyncStatusBadge status={session.data.status} />} />
      <WorkspaceSection title="Execution"><PropertyGrid items={[
        { label: 'Status', value: <SyncStatusBadge status={session.data.status} /> },
        { label: 'Started', value: formatConnectionDateTime(session.data.startedAt) },
        { label: 'Finished', value: session.data.finishedAt ? formatConnectionDateTime(session.data.finishedAt) : 'In progress' },
        { label: 'Duration', value: formatSyncDuration(session.data.startedAt, session.data.finishedAt) },
        ...(session.data.status === SyncStatus.failed ? [
          { label: 'Error', value: getSyncFailureMessage(session.data.errorMessage) },
          ...(session.data.errorCode ? [{ label: 'Code', value: <code>{session.data.errorCode}</code> }] : []),
        ] : []),
      ]} /></WorkspaceSection>
    </> : null}
  </div></AppShell>
}

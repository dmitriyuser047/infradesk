import { useLocation, useParams } from 'react-router-dom'

import { useConnection, useConnectionSyncSession } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { formatSyncDuration, getSyncFailureMessage } from '../components/connections/connectionPresentation'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { AppShell } from '../components/layout/AppShell'
import { PropertyGrid, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
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
  const i18n = useI18n()
  const t = i18n.t.connections.session
  const page = i18n.t.connections.page
  const session = useConnectionSyncSession(organizationId, connectionId, sessionId)
  const location = useLocation()
  const connection = useConnection(organizationId, connectionId)
  const back = `/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connectionId)}${location.search}`
  const notFound = session.error instanceof ApiError && session.error.code === 'SYNC_SESSION_NOT_FOUND'
  return <AppShell><div className="workspace-page">
    {session.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
    {session.isError ? <div className="inline-error" role="alert">
      {notFound ? t.notFound : t.loadError}
      {!notFound ? <button type="button" className="text-button" onClick={() => session.refetch()}>{i18n.t.common.retry}</button> : null}
    </div> : null}
    {session.data ? <>
      <WorkspaceHeader title={t.title} subtitle={t.subtitle(connection.data?.name ?? '…')}
        back={{ label: t.back, to: back }} status={<SyncStatusBadge status={session.data.status} />} />
      <WorkspaceSection title={t.execution}><PropertyGrid items={[
        { label: i18n.t.common.status, value: <SyncStatusBadge status={session.data.status} /> },
        { label: page.started, value: i18n.format.dateTime(session.data.startedAt) },
        { label: page.finished, value: session.data.finishedAt ? i18n.format.dateTime(session.data.finishedAt) : i18n.t.common.inProgress },
        { label: page.duration, value: formatSyncDuration(session.data.startedAt, session.data.finishedAt, i18n) },
        ...(session.data.status === SyncStatus.failed ? [
          { label: page.error, value: getSyncFailureMessage(session.data, i18n) },
          ...(session.data.errorCode ? [{ label: t.errorCode, value: <code>{session.data.errorCode}</code> }] : []),
        ] : []),
      ]} /></WorkspaceSection>
    </> : null}
  </div></AppShell>
}

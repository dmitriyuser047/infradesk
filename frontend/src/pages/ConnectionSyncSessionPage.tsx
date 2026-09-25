import { useLocation, useParams } from 'react-router-dom'

import { useConnection, useConnectionSyncSession } from '../api/connections'
import { ApiError } from '../api/httpClient'
import { formatSyncDuration, getSyncFailureMessage } from '../components/connections/connectionPresentation'
import { SyncStatusBadge } from '../components/connections/SyncStatusBadge'
import { AppShell } from '../components/layout/AppShell'
import { PageLoading, PageUnavailable, PropertyGrid, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
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
  if (session.isPending) return <AppShell><PageLoading title={t.loading} back={{ label: t.back, to: back }} label={t.loading} /></AppShell>
  if (session.isError || !session.data) return <AppShell><PageUnavailable back={{ label: t.back, to: back }} onRetry={() => session.refetch()}
    error={session.error} notFound={notFound} notFoundTitle={t.notFound} errorTitle={t.loadError} /></AppShell>
  return <AppShell><div className="workspace-page">
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
  </div></AppShell>
}

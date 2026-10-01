import { ShieldAlert, ShieldCheck, ShieldQuestion } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { ConnectionResponse } from '../../types/connection'
import type { ConnectionInfrastructureCounts } from '../../types/infrastructure'
import type { EnvironmentResponse, ProjectResponse } from '../../types/navigation'
import { PageActionMenu } from '../layout/PageActionMenu'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { connectionScopeDisplayLabel } from './connectionContextPresentation'
import { getLastSyncSummary, getScheduleSummary } from './connectionPresentation'
import { SyncStatusBadge } from './SyncStatusBadge'

/** One connection: where it points, whether that server is trusted, and how its syncs go. */
export function ConnectionRow({ organizationId, connection, projects, environments, counts, countsKnown = false, canManage = false }: {
  organizationId: string
  connection: ConnectionResponse
  projects: readonly ProjectResponse[] | undefined
  environments: readonly EnvironmentResponse[]
  counts?: ConnectionInfrastructureCounts
  /** False while the counts are loading or failed: the cells say nothing rather than zero. */
  countsKnown?: boolean
  canManage?: boolean
}) {
  const i18n = useI18n()
  const t = i18n.t.connections
  const navigate = useNavigate()
  const location = useLocation()
  const destination = `/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connection.id)}${location.search}`
  const ssh = connection.ssh
  return <tr className={`clickable-row ${connection.active ? '' : 'row-quiet'}`} onClick={() => navigate(destination)}>
    <td><Link className="grid-link" to={destination} onClick={event => event.stopPropagation()}>{connection.name}</Link>
      <small className="cell-secondary">{connectionScopeDisplayLabel(connection.scope, projects, environments, i18n)}
        {connection.active ? null : ` · ${i18n.t.common.inactive}`}</small></td>
    <td>{ssh ? <code className="technical-value">{ssh.username}@{ssh.host}{ssh.port === 22 ? '' : `:${ssh.port}`}</code>
      : <span className="muted-cell">{t.connectorTypes[connection.connectorType] ?? connection.connectorType}</span>}</td>
    <td>{!ssh ? <span className="muted-cell">—</span>
      : connection.lastSync?.errorCode === 'SSH_HOST_KEY_MISMATCH'
        // The latest attempt met a different key: the trust recorded here no longer holds.
        ? <StatusIndicator label={t.form.statusMismatch} tone="danger" icon={ShieldAlert} />
        : <StatusIndicator label={ssh.hostTrusted ? t.trust.trusted : t.trust.untrusted}
          tone={ssh.hostTrusted ? 'success' : 'warning'} icon={ssh.hostTrusted ? ShieldCheck : ShieldQuestion} />}</td>
    <td className="numeric-cell">{countsKnown ? counts?.activeResourceCount ?? 0 : <span className="muted-cell">—</span>}</td>
    <td className="numeric-cell">{!countsKnown ? <span className="muted-cell">—</span>
      : (counts?.openIncidentCount ?? 0) > 0
        ? <StatusIndicator label={i18n.t.infrastructure.openIncidentCount(counts?.openIncidentCount ?? 0)} tone="danger" />
        : <span className="muted-cell">0</span>}</td>
    <td>{connection.lastSync ? <><SyncStatusBadge status={connection.lastSync.status} />
      <small className="cell-secondary">{getLastSyncSummary(connection.lastSync, i18n)}</small></>
      : <span className="muted-cell">{t.neverSynchronized}</span>}</td>
    <td><div className="cell-with-actions"><span>{connection.schedule?.enabled
      ? <><span>{i18n.format.dateTime(connection.schedule.nextRunAt)}</span><small className="cell-secondary">{getScheduleSummary(connection.schedule, i18n)}</small></>
      : <span className="muted-cell">{i18n.t.common.manual}</span>}</span>
    {canManage && connection.active && connection.connectorType === 'SSH'
      ? <PageActionMenu actions={[{ label: i18n.t.common.edit,
        to: `/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connection.id)}/edit${location.search}` }]} /> : null}</div></td>
  </tr>
}

import { Link } from 'react-router-dom'
import { useResourceIntegrationBindings } from '../../api/integrationInventory'
import { useI18n } from '../../i18n'
import { InlineAlert, PropertyGrid, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { nodeStateTones } from './integrationPresentation'

/**
 * What Remnawave reports about the node this resource was bound to by hand. Read-only: it is the
 * control plane's own view and never replaces the resource's status. Shown only when a binding
 * exists; an unbound resource shows nothing.
 */
export function ResourceIntegrationSection({ organizationId, resourceId }: { organizationId: string; resourceId: string }) {
  const i18n = useI18n(); const t = i18n.t.integrationInventory
  const query = useResourceIntegrationBindings(organizationId, resourceId, true)
  if (query.isPending || (query.isSuccess && query.data.length === 0)) return null
  if (query.isError) return <InlineAlert tone="warning" title={t.loadError}
    action={<button className="secondary-button" type="button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>} />
  return <>{query.data.map(({ integration, object }) => <WorkspaceSection key={object.id} title={t.resourceSection}
    description={t.resourceSectionDetail}>
    <PropertyGrid columns={2} items={[
      { label: t.integration, value: <Link to={`/organizations/${encodeURIComponent(organizationId)}/integrations/${encodeURIComponent(integration.id)}?tab=nodes`}>
        {integration.name}</Link> },
      { label: t.observedNode, value: object.displayName },
      { label: t.state, value: object.active
        ? <StatusIndicator label={t.nodeState[object.summary.state] ?? object.summary.state} tone={nodeStateTones[object.summary.state]} />
        : <StatusIndicator label={t.gone} /> },
      { label: t.address, value: object.summary.port === null ? object.summary.address
        : `${object.summary.address}:${object.summary.port}`, technical: true },
      { label: t.version, value: object.summary.xrayVersion ?? '—' },
      { label: t.users, value: object.summary.usersOnline === null ? '—' : i18n.format.number(object.summary.usersOnline) },
      { label: t.lastSeen, value: i18n.format.dateTime(object.lastSeenAt) },
    ]} />
  </WorkspaceSection>)}</>
}

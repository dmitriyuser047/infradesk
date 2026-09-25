import { useMemo, useState } from 'react'
import { SearchX } from 'lucide-react'
import { useParams, useSearchParams } from 'react-router-dom'

import { useEnvironmentResources } from '../api/resources'
import { useEnvironments } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { getEnvironmentKindLabel } from '../components/navigation/navigationPresentation'
import { ResourceTree } from '../components/resources/ResourceTree'
import { resourcePresentationRegistry } from '../components/resources/presentation/resourcePresentations'
import { ResourceFilterBar } from '../components/resources/ResourceFilterBar'
import {
  buildFilteredResourceTree,
  describeWithPresentations,
  isResourceFilterActive,
  noResourceFilter,
} from '../components/resources/resourceFilter'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { InvalidRoutePage } from './InvalidRoutePage'

export function EnvironmentPage() {
  const { organizationId, environmentId } = useParams()

  if (organizationId === undefined || environmentId === undefined) {
    return <InvalidRoutePage />
  }

  return <EnvironmentContent organizationId={organizationId} environmentId={environmentId} />
}

interface EnvironmentContentProps {
  organizationId: string
  environmentId: string
}

function EnvironmentContent({ organizationId, environmentId }: EnvironmentContentProps) {
  const i18n = useI18n()
  const t = i18n.t.resources
  const resourcesQuery = useEnvironmentResources(organizationId, environmentId)
  const [searchParams] = useSearchParams()
  const projectId = searchParams.get('project')
  const environments = useEnvironments(organizationId, projectId)
  const environment = environments.data?.find(item => item.id === environmentId)
  // Local to this page: filtering the loaded list changes nothing in the URL and requests nothing.
  const [criteria, setCriteria] = useState(noResourceFilter)
  const resources = resourcesQuery.data
  const filtered = useMemo(() => resources === undefined ? null
    : buildFilteredResourceTree(resources, criteria, describeWithPresentations(resourcePresentationRegistry, i18n)),
  [resources, criteria, i18n])
  const filtering = isResourceFilterActive(criteria)
  const f = t.filter

  return (
    <AppShell>
      <div className="workspace-page">
      <WorkspaceHeader title={t.title}
        subtitle={environment ? t.subtitle(environment.name, getEnvironmentKindLabel(environment.kind, i18n)) : t.subtitleFallback} />
      <WorkspaceSection title={t.section}
        actions={filtered !== null && filtering ? <span className="resource-count" role="status">{f.shown(filtered.matched, filtered.total)}</span> : null}>
        {resources !== undefined && resources.length > 0
          ? <ResourceFilterBar criteria={criteria} onChange={setCriteria} onRefresh={() => resourcesQuery.refetch()} /> : null}
        {resourcesQuery.isPending ? <div className="tree-skeleton" aria-label={t.loading}><span /><span /><span /><span /></div> : null}
        {resourcesQuery.isError ? (
          <InlineAlert tone="danger" title={t.loadError}
            action={<button className="secondary-button" type="button" onClick={() => resourcesQuery.refetch()}>{i18n.t.common.retry}</button>}>
            {describeError(resourcesQuery.error, i18n)}</InlineAlert>
        ) : null}
        {resources !== undefined && resources.length === 0 ? <EmptyWorkspaceState title={t.empty} detail={t.emptyDetail}
          action={<button className="secondary-button" type="button" onClick={() => resourcesQuery.refetch()}>{i18n.t.common.refresh}</button>} /> : null}
        {filtered !== null && filtered.total > 0 && filtered.roots.length === 0 ? <EmptyWorkspaceState icon={SearchX}
          title={f.noResults} detail={f.noResultsDetail}
          action={<button className="secondary-button" type="button" onClick={() => setCriteria(noResourceFilter)}>{f.reset}</button>} /> : null}
        {filtered !== null && filtered.roots.length > 0 ? <div className="table-scroll">
          <div className="tree-grid-header"><span>{t.columns.name}</span><span>{t.columns.type}</span><span>{t.columns.status}</span></div>
          <ResourceTree roots={filtered.roots} organizationId={organizationId} environmentId={environmentId} />
        </div> : null}
      </WorkspaceSection></div>
    </AppShell>
  )
}

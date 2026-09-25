import { useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'

import { useEnvironmentResources } from '../api/resources'
import { useEnvironments } from '../api/navigation'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { getEnvironmentKindLabel } from '../components/navigation/navigationPresentation'
import { ResourceTree } from '../components/resources/ResourceTree'
import { resourcePresentationRegistry } from '../components/resources/presentation/resourcePresentations'
import { filterResourcesForTree } from '../components/resources/filterResourcesForTree'
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
  const [search, setSearch] = useState('')
  const [type, setType] = useState('ALL')
  const visibleResources = filterResourcesForTree(resourcesQuery.data ?? [], search, type)

  return (
    <AppShell>
      <div className="workspace-page">
      <WorkspaceHeader title={t.title}
        subtitle={environment ? t.subtitle(environment.name, getEnvironmentKindLabel(environment.kind, i18n)) : t.subtitleFallback} />
      <WorkspaceSection title={t.section} actions={resourcesQuery.data ? <span className="resource-count">{i18n.t.common.shown(visibleResources.length, resourcesQuery.data.length)}</span> : null}>
        <div className="filter-bar">
          <label>{i18n.t.common.search}<input type="search" placeholder={i18n.t.common.searchPlaceholder} value={search} onChange={event => setSearch(event.target.value)} /></label>
          <label>{i18n.t.common.type}<select value={type} onChange={event => setType(event.target.value)}><option value="ALL">{i18n.t.common.all}</option>
            {resourcePresentationRegistry.list().map(presentation =>
              <option key={presentation.code} value={presentation.code}>{presentation.label(i18n)}</option>)}</select></label>
          <button className="secondary-button" type="button" onClick={() => resourcesQuery.refetch()}>{i18n.t.common.refresh}</button>
        </div>
        {resourcesQuery.isPending ? <div className="tree-skeleton" aria-label={t.loading}><span /><span /><span /><span /></div> : null}
        {resourcesQuery.isError ? (
          <InlineAlert tone="danger" title={t.loadError}
            action={<button className="secondary-button" type="button" onClick={() => resourcesQuery.refetch()}>{i18n.t.common.retry}</button>}>
            {describeError(resourcesQuery.error, i18n)}</InlineAlert>
        ) : null}
        {resourcesQuery.data !== undefined && resourcesQuery.data.length === 0 ? <EmptyWorkspaceState title={t.empty} detail={t.emptyDetail} /> : null}
        {resourcesQuery.data !== undefined && resourcesQuery.data.length > 0 ? (
          visibleResources.length ? <div className="table-scroll"><div className="tree-grid-header"><span>{t.columns.name}</span><span>{t.columns.type}</span><span>{t.columns.status}</span></div><ResourceTree
            resources={visibleResources}
            organizationId={organizationId}
            environmentId={environmentId}
          /></div> : <EmptyWorkspaceState title={t.noMatch} />
        ) : null}
      </WorkspaceSection></div>
    </AppShell>
  )
}

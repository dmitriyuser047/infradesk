import { useId, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import {
  refusedDiagnostics, useConfigurationProfile, useCreateConfigurationProfile, useCreateConfigurationRevision,
} from '../api/configurations'
import { ApiError } from '../api/httpClient'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { ConfigurationContentEditor } from '../components/configuration/ConfigurationContentEditor'
import { AppShell } from '../components/layout/AppShell'
import { InlineAlert, PageLoading, PageUnavailable, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { ConfigurationContentRequest } from '../types/configuration'
import { configurationPath, configurationsPath } from './ConfigurationsPage'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/configurations.css'

const codePattern = /^[a-z0-9][a-z0-9_-]{0,63}$/

/** A new profile: its details and its first version, created together as version 1. */
export function ConfigurationCreatePage() {
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <AppShell><CreateContent organizationId={organizationId} /></AppShell>
}

function CreateContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.configurations
  const navigate = useNavigate()
  const permissions = useOrganizationPermissions(organizationId)
  const create = useCreateConfigurationProfile(organizationId)
  const ids = { name: useId(), code: useId(), codeHelp: useId(), description: useId() }
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState('')
  const back = { label: t.back, to: configurationsPath(organizationId) }

  if (!permissions.isPending && !permissions.can('manageConfigurations')) {
    return <div className="workspace-page"><WorkspaceHeader title={t.createTitle} back={back} /><InlineAlert tone="danger" title={t.accessDenied} /></div>
  }
  const submit = (content: ConfigurationContentRequest) => {
    setError('')
    if (!name.trim() || name.trim().length > 255) return setError(t.invalidName)
    if (!codePattern.test(code.trim())) return setError(t.invalidCode)
    if (description.trim().length > 4000) return setError(t.invalidDescription)
    create.mutate({ code: code.trim(), name: name.trim(), description: description.trim() || null, ...content }, {
      onSuccess: detail => navigate(configurationPath(organizationId, detail.profile.id), { replace: true }),
    })
  }
  const refused = refusedDiagnostics(create.error)

  return <div className="workspace-page">
    <WorkspaceHeader title={t.createTitle} back={back} />
    <ConfigurationContentEditor organizationId={organizationId} submitLabel={t.createAction} pendingLabel={t.creating}
      pending={create.isPending} onSubmit={submit} refused={refused}
      before={<WorkspaceSection title={t.detailsTitle}>
        <div className="configuration-details-grid">
          <label htmlFor={ids.name}>{t.name}<input id={ids.name} value={name} maxLength={255} autoComplete="off"
            onChange={event => setName(event.target.value)} /></label>
          {/* The hint describes the field without becoming part of its name. */}
          <div className="configuration-field">
            <label htmlFor={ids.code}>{t.code}<input id={ids.code} className="technical-input" value={code} maxLength={64} autoComplete="off"
              spellCheck={false} aria-describedby={ids.codeHelp} onChange={event => setCode(event.target.value)} /></label>
            <span id={ids.codeHelp} className="field-hint">{t.codeHelp}</span>
          </div>
          <label htmlFor={ids.description} className="configuration-details-wide">{t.description}
            <textarea id={ids.description} rows={2} value={description} maxLength={4000} onChange={event => setDescription(event.target.value)} /></label>
        </div>
      </WorkspaceSection>} />
    {error ? <InlineAlert tone="danger" title={error} /> : null}
    {create.isError && !refused ? <InlineAlert tone="danger" title={describeError(create.error, i18n)} /> : null}
  </div>
}

/** The next version of a profile, starting from its latest one. The server numbers it. */
export function ConfigurationVersionPage() {
  const { organizationId, profileId } = useParams()
  if (!organizationId || !profileId) return <InvalidRoutePage />
  return <AppShell><VersionContent organizationId={organizationId} profileId={profileId} /></AppShell>
}

function VersionContent({ organizationId, profileId }: { organizationId: string; profileId: string }) {
  const i18n = useI18n()
  const t = i18n.t.configurations
  const navigate = useNavigate()
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConfigurations')
  const query = useConfigurationProfile(organizationId, profileId, canManage)
  const save = useCreateConfigurationRevision(organizationId, profileId)
  const back = { label: query.data?.profile.name ?? t.back, to: configurationPath(organizationId, profileId) }

  if (!permissions.isPending && !canManage) {
    return <div className="workspace-page"><WorkspaceHeader title={t.newVersion} back={back} /><InlineAlert tone="danger" title={t.accessDenied} /></div>
  }
  if (permissions.isPending || query.isPending) return <PageLoading title={t.newVersion} back={back} label={t.loading} />
  if (query.isError || !query.data) {
    return <PageUnavailable back={back} onRetry={() => query.refetch()} error={query.error}
      notFound={query.error instanceof ApiError && query.error.code === 'CONFIGURATION_PROFILE_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={t.detailLoadError} />
  }
  const { profile, latestRevision } = query.data
  if (profile.archived) {
    return <div className="workspace-page"><WorkspaceHeader title={t.newVersionTitle(profile.name)} back={back} />
      <InlineAlert tone="warning" title={t.archivedNotice} /></div>
  }
  const refused = refusedDiagnostics(save.error)
  return <div className="workspace-page">
    <WorkspaceHeader title={t.newVersionTitle(profile.name)} subtitle={`${profile.code} · ${t.version(profile.latestRevisionNumber)}`} back={back} />
    <ConfigurationContentEditor organizationId={organizationId} initialTemplate={latestRevision.template}
      initialVariables={latestRevision.variables} submitLabel={t.saveVersion} pendingLabel={t.savingVersion}
      pending={save.isPending} refused={refused}
      onSubmit={content => save.mutate(content, { onSuccess: () => navigate(configurationPath(organizationId, profileId), { replace: true }) })} />
    {save.isError && !refused ? <InlineAlert tone="danger" title={describeError(save.error, i18n)} /> : null}
  </div>
}

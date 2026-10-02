import { useEffect, useState, type FormEvent } from 'react'
import { useLocation, useNavigate, useParams, useSearchParams, Link } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { useArchiveServerProfile, useAppendServerProfileRevision, useCreateServerProfile, useServerProfile, useServerProfiles } from '../api/serverProfiles'
import { ApiError } from '../api/httpClient'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceFormSection, WorkspaceHeader, WorkspaceSection, WorkspaceTabs } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { withWorkspaceContext } from '../components/layout/workspaceNavigation'
import { InvalidRoutePage } from './InvalidRoutePage'
import type { ServerProfileContent } from '../types/serverProfile'
import { emptyServerProfileContent } from '../types/serverProfile'
import '../styles/pages/configurations.css'
import '../styles/pages/server-profiles.css'

const configurationsPath = (organizationId: string) => `/organizations/${encodeURIComponent(organizationId)}/configurations`

export function ServerProfilesListPage() {
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <AppShell><ServerProfilesList organizationId={organizationId} /></AppShell>
}

export function ServerProfileCreatePage() {
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <AppShell><ServerProfileEditor organizationId={organizationId} /></AppShell>
}

export function ServerProfileDetailPage() {
  const { organizationId, serverProfileId } = useParams()
  if (!organizationId || !serverProfileId) return <InvalidRoutePage />
  return <AppShell><ServerProfileDetailContent organizationId={organizationId} profileId={serverProfileId} /></AppShell>
}

export function ServerProfilesList({ organizationId, canManage = false }: { organizationId: string; canManage?: boolean }) {
  const i18n = useI18n(); const t = i18n.t.serverProfiles
  const permissions = useOrganizationPermissions(organizationId)
  const [params, setParams] = useSearchParams()
  const archived = params.get('state') === 'archived'
  const query = useServerProfiles(organizationId, archived, permissions.can('readOrganization'))
  const select = (value: boolean) => setParams(prev => { const next = new URLSearchParams(prev); value ? next.set('state', 'archived') : next.delete('state'); return next }, { replace: true })
  const createPath = withWorkspaceContext(`${configurationsPath(organizationId)}/server-profiles/new`, params)
  return <div className="workspace-page">
    <WorkspaceHeader title={t.title} subtitle={t.subtitle} actions={permissions.can('manageConfigurations') ? <Link className="primary-button" to={createPath}><Plus aria-hidden size={16} />{t.create}</Link> : null} />
    <WorkspaceTabs tabs={[{ id: 'file', label: t.categoryLegacy, disabled: !canManage }, { id: 'server', label: t.categoryServer }]} active="server" onChange={id => {
      if (id === 'file' && !canManage) return
      setParams(prev => { const next = new URLSearchParams(prev); id === 'server' ? next.set('type', 'server') : next.delete('type'); return next })
    }} />
    <div role="tabpanel" id="panel-server" aria-labelledby="tab-server">
    <WorkspaceSection title={archived ? t.archivedList : t.title}>
      <div className="filter-bar"><label className="segmented-option"><input type="checkbox" checked={archived} onChange={e => select(e.target.checked)} />{t.archived}</label></div>
      {query.isPending ? <p>{t.loading}</p> : null}
      {query.isError ? <InlineAlert tone="danger" title={t.loadError} action={<button className="secondary-button" type="button" onClick={() => void query.refetch()}>{i18n.t.common.retry}</button>} /> : null}
      {query.data?.items.length === 0 ? <EmptyWorkspaceState title={t.empty} detail={t.emptyDetail} /> : null}
      {query.data?.items.length ? <div className="table-scroll"><table className="data-grid"><thead><tr><th>{t.name}</th><th>{t.version}</th><th>{t.updated}</th><th>{t.active}</th></tr></thead><tbody>
        {query.data.items.map(({ profile, assignmentCount }) => <tr key={profile.id}><td><Link className="grid-link" to={withWorkspaceContext(`${configurationsPath(organizationId)}/server-profiles/${encodeURIComponent(profile.id)}`, params)}>{profile.name}</Link><small className="cell-secondary">{t.assignmentCount(assignmentCount)}</small></td><td>{t.currentRevision(profile.latestRevision)}</td><td>{i18n.format.dateTime(profile.updatedAt)}</td><td><StatusIndicator label={profile.archived ? t.archived : t.active} tone={profile.archived ? 'neutral' : 'success'} /></td></tr>)}
      </tbody></table></div> : null}
    </WorkspaceSection>
    </div>
  </div>
}

function ServerProfileEditor({ organizationId, initial, profileId, readOnly = false }: { organizationId: string; initial?: ServerProfileContent; profileId?: string; readOnly?: boolean }) {
  const i18n = useI18n(); const t = i18n.t.serverProfiles
  const permissions = useOrganizationPermissions(organizationId)
  const [workspaceParams] = useSearchParams()
  const create = useCreateServerProfile(organizationId)
  const append = useAppendServerProfileRevision(organizationId, profileId ?? '')
  const [content, setContent] = useState<ServerProfileContent>(() => structuredClone(initial ?? emptyServerProfileContent()))
  const [name, setName] = useState(''); const [code, setCode] = useState(''); const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const navigate = useNavigate()
  useEffect(() => { setContent(structuredClone(initial ?? emptyServerProfileContent())) }, [initial])
  type ModuleKey = 'packages' | 'network' | 'limits' | 'firewall' | 'fail2ban' | 'docker' | 'caddy' | 'site'
  const setModule = <K extends ModuleKey>(key: K, patch: Partial<ServerProfileContent[K]>) => {
    setSaved(false)
    setContent(current => ({ ...current, [key]: { ...current[key], ...patch } }))
  }
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError(null); setSaved(false)
    const serverProfileErrors: Record<string, string> = { ...i18n.t.provisioning.errors, ...t.errors }
    try {
      if (profileId) { await append.mutateAsync(content); setSaved(true); return }
      const result = await create.mutateAsync({ code: code.trim(), name: name.trim(), description: description.trim() || null, content })
      navigate(withWorkspaceContext(`${configurationsPath(organizationId)}/server-profiles/${encodeURIComponent(result.profile.id)}`, workspaceParams))
    } catch (e) { setError(e instanceof ApiError ? serverProfileErrors[e.code] ?? t.createError : t.createError) }
  }
  const busy = create.isPending || append.isPending
  const toggle = (label: string, checked: boolean, onChange: (value: boolean) => void) => <label className="checkbox-row"><input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} />{label}</label>
  const viewOnly = readOnly || !permissions.can('manageConfigurations')
  return <div className={profileId ? 'server-profile-revision-editor' : 'workspace-page'}>{!profileId ? <WorkspaceHeader title={t.createTitle} back={{ label: t.back, to: withWorkspaceContext(`${configurationsPath(organizationId)}?type=server`, workspaceParams) }} /> : null}
    {!permissions.can('manageConfigurations') && !profileId ? <InlineAlert tone="danger" title={t.permission} /> : <form className="server-profile-editor" onSubmit={submit}><fieldset disabled={viewOnly} className="server-profile-editor-fields">
      {viewOnly ? <InlineAlert tone="info" title={t.readOnly} /> : null}
      {!profileId ? <WorkspaceSection title={t.details}>
        <label>{t.name}<input required maxLength={255} value={name} onChange={e => setName(e.target.value)} /></label>
        <label>{t.code}<input required pattern="[a-z0-9][a-z0-9_-]{0,63}" value={code} onChange={e => setCode(e.target.value)} /><small>{t.codeHelp}</small></label>
        <label>{t.description}<textarea maxLength={4000} value={description} onChange={e => setDescription(e.target.value)} /></label>
      </WorkspaceSection> : null}
      <WorkspaceSection title={t.config} description={t.emptyDescription}>
        <fieldset><legend>{t.packages}</legend>{toggle(t.enabled, content.packages.enabled, enabled => setModule('packages', { enabled }))}<label>{t.packageNames}<textarea disabled={!content.packages.enabled} value={content.packages.packages.join('\n')} onChange={e => setModule('packages', { packages: e.target.value.split(/[\n,]/).map(x => x.trim()).filter(Boolean) })} /></label></fieldset>
        <fieldset><legend>{t.network}</legend>{toggle(t.enabled, content.network.enabled, enabled => setModule('network', { enabled }))}{toggle(t.bbr, content.network.bbr, bbr => setModule('network', { bbr }))}<label>{t.sysctl}<textarea disabled={!content.network.enabled} value={Object.entries(content.network.sysctl).map(([k,v]) => `${k}=${v}`).join('\n')} onChange={e => setModule('network', { sysctl: Object.fromEntries(e.target.value.split('\n').filter(Boolean).map(line => { const i=line.indexOf('='); return i < 0 ? [line, ''] : [line.slice(0,i).trim(),line.slice(i+1).trim()] })) })} /></label></fieldset>
        <fieldset><legend>{t.limits}</legend>{toggle(t.enabled, content.limits.enabled, enabled => setModule('limits', { enabled }))}<div className="form-grid"><label>{t.nofileSoft}<input type="number" min="1024" max="16777216" disabled={!content.limits.enabled} value={content.limits.nofileSoft} onChange={e => setModule('limits', { nofileSoft: Number(e.target.value) })} /></label><label>{t.nofileHard}<input type="number" min="1024" max="16777216" disabled={!content.limits.enabled} value={content.limits.nofileHard} onChange={e => setModule('limits', { nofileHard: Number(e.target.value) })} /></label><label>{t.systemdLimit}<input type="number" min="1024" max="16777216" disabled={!content.limits.enabled} value={content.limits.systemdDefaultLimitNofile} onChange={e => setModule('limits', { systemdDefaultLimitNofile: Number(e.target.value) })} /></label></div><p>{t.limitsNote}</p></fieldset>
        <fieldset><legend>{t.firewall}</legend>{toggle(t.enabled, content.firewall.enabled, enabled => setModule('firewall', { enabled }))}{content.firewall.rules.map((rule, i) => <div className="form-grid" key={i}><label>{t.ruleId}<input value={rule.id} onChange={e => setModule('firewall', { rules: content.firewall.rules.map((r,n) => n===i ? {...r,id:e.target.value} : r) })} /></label><label>{t.protocol}<select value={rule.protocol} onChange={e => setModule('firewall', { rules: content.firewall.rules.map((r,n) => n===i ? {...r,protocol:e.target.value as 'tcp'|'udp'} : r) })}><option value="tcp">TCP</option><option value="udp">UDP</option></select></label><label>{t.port}<input type="number" min="1" max="65535" value={rule.port} onChange={e => setModule('firewall', { rules: content.firewall.rules.map((r,n) => n===i ? {...r,port:Number(e.target.value)} : r) })} /></label><label>{t.sources}<input value={rule.sources.join(', ')} onChange={e => setModule('firewall', { rules: content.firewall.rules.map((r,n) => n===i ? {...r,sources:e.target.value.split(',').map(x=>x.trim()).filter(Boolean)} : r) })} /></label><button className="text-button" type="button" onClick={() => setModule('firewall', { rules: content.firewall.rules.filter((_,n)=>n!==i) })}>{t.removeRule}</button></div>)}<button className="secondary-button" type="button" onClick={() => setModule('firewall', { rules: [...content.firewall.rules, { id: `rule-${content.firewall.rules.length+1}`, protocol: 'tcp', port: 443, sources: ['0.0.0.0/0'] }] })}>{t.addRule}</button></fieldset>
        <fieldset><legend>{t.fail2ban}</legend>{toggle(t.enabled, content.fail2ban.enabled, enabled => setModule('fail2ban', { enabled }))}</fieldset>
        <fieldset><legend>{t.docker}</legend>{toggle(t.enabled, content.docker.enabled, enabled => setModule('docker', { enabled }))}</fieldset>
        <fieldset><legend>{t.caddy}</legend>{toggle(t.enabled, content.caddy.enabled, enabled => setModule('caddy', { enabled }))}<div className="form-grid"><label>{t.domain}<input disabled={!content.caddy.enabled} value={content.caddy.domain ?? ''} onChange={e => setModule('caddy', { domain: e.target.value || null })} /></label><label>{t.localHttpsPort}<input type="number" min="1" max="65535" disabled={!content.caddy.enabled} value={content.caddy.localHttpsPort} onChange={e => setModule('caddy', { localHttpsPort: Number(e.target.value) })} /></label><label>{t.siteRoot}<input disabled={!content.caddy.enabled} value={content.caddy.siteRoot} onChange={e => setModule('caddy', { siteRoot: e.target.value })} /></label><label>{t.compression}<select disabled={!content.caddy.enabled} value={content.caddy.compression} onChange={e => setModule('caddy', { compression: e.target.value as 'gzip'|'zstd' })}><option value="gzip">gzip</option><option value="zstd">zstd</option></select></label><label>{t.redirect}<select disabled={!content.caddy.enabled} value={content.caddy.redirect} onChange={e => setModule('caddy', { redirect: e.target.value as 'NONE'|'HTTP_TO_HTTPS' })}><option value="NONE">{t.none}</option><option value="HTTP_TO_HTTPS">{t.httpToHttps}</option></select></label></div></fieldset>
        <fieldset><legend>{t.site}</legend>{toggle(t.enabled, content.site.enabled, enabled => setModule('site', { enabled }))}<label>{t.domain}<input disabled={!content.site.enabled} value={content.site.domain ?? ''} onChange={e => setModule('site', { domain: e.target.value || null })} /></label><label>{t.siteRootPath}<input disabled={!content.site.enabled} value={content.site.root} onChange={e => setModule('site', { root: e.target.value })} /></label></fieldset>
      </WorkspaceSection>
      {error ? <InlineAlert tone="danger" title={profileId ? t.saveError : t.createError}>{error}</InlineAlert> : null}
      {saved ? <InlineAlert tone="success" title={t.revisionSaved} /> : null}
      {!viewOnly ? <div className="form-actions"><button className="primary-button" disabled={busy || !name.trim() && !profileId}>{busy ? profileId ? t.appending : t.saving : profileId ? t.append : t.save}</button></div> : null}
    </fieldset></form>}
  </div>
}

function ServerProfileDetailContent({ organizationId, profileId }: { organizationId: string; profileId: string }) {
  const i18n = useI18n(); const t = i18n.t.serverProfiles
  const permissions = useOrganizationPermissions(organizationId)
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const currentContext = new URLSearchParams(location.search)
  const query = useServerProfile(organizationId, profileId, permissions.can('readOrganization'))
  const archive = useArchiveServerProfile(organizationId, profileId)
  const tab = (['settings','revisions','servers'] as const).find(x => x === params.get('tab')) ?? 'settings'
  const setTab = (value: string) => setParams(prev => { const next = new URLSearchParams(prev); value === 'settings' ? next.delete('tab') : next.set('tab', value); return next }, { replace: true })
  if (query.isPending) return <div className="workspace-page">{t.loading}</div>
  if (!query.data || query.isError) return <div className="workspace-page"><WorkspaceHeader title={t.title} back={{label:t.back,to:withWorkspaceContext(`${configurationsPath(organizationId)}?type=server`,params)}} /><InlineAlert tone="danger" title={query.error instanceof ApiError && query.error.code === 'SERVER_PROFILE_NOT_FOUND' ? t.noProfile : t.detailError} action={query.error instanceof ApiError && query.error.code === 'SERVER_PROFILE_NOT_FOUND' ? undefined : <button className="secondary-button" type="button" onClick={() => void query.refetch()}>{i18n.t.common.retry}</button>} /></div>
  const { profile, revisions, assignments } = query.data
  const fieldNames: Record<string, string> = { ...t.fieldNames }
  const latest = revisions.find(x => x.number === profile.latestRevision)
  return <div className="workspace-page"><WorkspaceHeader title={profile.name} subtitle={profile.description ?? undefined} back={{label:t.back,to:withWorkspaceContext(`${configurationsPath(organizationId)}?type=server`, params)}} status={<StatusIndicator label={profile.archived ? t.archived : t.currentRevision(profile.latestRevision)} tone={profile.archived ? 'neutral' : 'info'} />} actions={permissions.can('manageConfigurations') && !profile.archived ? <button className="secondary-button" type="button" disabled={archive.isPending || assignments.length>0} onClick={() => void archive.mutateAsync().catch(() => undefined)}>{t.archive}</button> : null} />
    {profile.archived ? <InlineAlert tone="warning" title={t.archivedNotice} /> : null}
    <WorkspaceTabs tabs={(['settings','revisions','servers'] as const).map(id => ({id,label:t.tabs[id]}))} active={tab} onChange={setTab} />
    <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
    {tab === 'settings' ? <>{latest ? <ServerProfileEditor organizationId={organizationId} profileId={profileId} initial={latest.content} readOnly={profile.archived || !permissions.can('manageConfigurations')} /> : null}</> : null}
    {tab === 'revisions' ? <WorkspaceSection title={t.revisions} actions={permissions.can('manageConfigurations') && !profile.archived && latest ? <Link className="primary-button" to={withWorkspaceContext(`${configurationsPath(organizationId)}/server-profiles/${encodeURIComponent(profileId)}?tab=settings`, currentContext)}>{t.append}</Link> : null}><div className="table-scroll"><table className="data-grid"><thead><tr><th>{t.version}</th><th>{t.updated}</th><th>{t.comparison}</th></tr></thead><tbody>{[...revisions].sort((a,b)=>b.number-a.number).map(rev => {
      const previous = revisions.find(candidate => candidate.number === rev.number - 1)
      const display = (value: unknown, key: string): string => {
        if (typeof value === 'boolean') return key.endsWith('.enabled') ? value ? t.enabled : t.notManaged : value ? t.valueEnabled : t.valueDisabled
        if (key === 'firewall.rules' && Array.isArray(value)) return (value as ServerProfileContent['firewall']['rules']).map(rule => `${rule.id} · ${rule.protocol.toUpperCase()}/${rule.port} · ${rule.sources.join(', ')}`).join('; ')
        if (Array.isArray(value)) return value.join(', ')
        if (value === null) return '—'
        if (value === 'DEFAULT_PLACEHOLDER') return t.site
        if (value === 'HTTP_TO_HTTPS') return t.httpToHttps
        if (value === 'NONE') return t.none
        return String(value)
      }
      const flatten = (value: unknown, prefix = ''): [string, string][] => value && typeof value === 'object' && !Array.isArray(value)
        ? Object.entries(value as Record<string, unknown>).flatMap(([key, child]) => flatten(child, prefix ? `${prefix}.${key}` : key))
        : [[prefix, display(value,prefix)]]
      const oldValues = new Map(previous ? flatten(previous.content) : [])
      const differences = flatten(rev.content).filter(([key, value]) => oldValues.get(key) !== value).map(([key, after]) => ({ key, before: oldValues.get(key) ?? '—', after }))
      return <tr key={rev.id}><td>{t.currentRevision(rev.number)}</td><td>{i18n.format.dateTime(rev.createdAt)}</td><td><details><summary>{t.compare}</summary>{previous ? differences.length ? <ul>{differences.map(change => <li key={change.key}><strong>{(t.changeModules as Record<string,string>)[change.key.split('.')[0]]} · {change.key.startsWith('network.sysctl.') ? change.key.slice('network.sysctl.'.length) : fieldNames[change.key] ?? fieldNames[change.key.split('.').at(-1) ?? ''] ?? t.config}</strong>: {change.before} → {change.after}</li>)}</ul> : <p>{t.noRevisionChanges}</p> : <p>{t.firstRevision}</p>}</details></td></tr>
    })}</tbody></table></div></WorkspaceSection> : null}
    {tab === 'servers' ? <WorkspaceSection title={t.assigned}>{assignments.length===0 ? <EmptyWorkspaceState compact title={t.unassigned} /> : <div className="table-scroll"><table className="data-grid"><thead><tr><th>{t.target}</th><th>{t.version}</th><th>{t.active}</th></tr></thead><tbody>{assignments.map(a=><tr key={a.id}><td>{a.resourceName}</td><td>{t.currentRevision(a.revisionNumber)}</td><td><StatusIndicator label={a.resourceActive ? t.active : t.archived} tone={a.resourceActive ? 'success' : 'neutral'} /></td></tr>)}</tbody></table></div>}</WorkspaceSection> : null}
    </div>
  </div>
}

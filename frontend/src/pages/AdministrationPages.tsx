import { useState, type FormEvent, type ReactNode } from 'react'
import { Building2, Crown, LockKeyhole, Plus, Search, Shield, ShieldCheck, UserRound, Users } from 'lucide-react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMe } from '../api/auth'
import { addMemberByEmail, changeMembership, changeUserStatus, createAdministrationUser, createOrganization,
  useAdministrationMutation, useAdministrationOrganizations, useAdministrationUser, useAdministrationUsers,
  useOrganizationMembers, useUserMemberships, useAdministrationMembership } from '../api/administration'
import { ApiError } from '../api/httpClient'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { IntegrationDialog } from '../components/integrations/IntegrationDialog'
import { AppShell } from '../components/layout/AppShell'
import { PermissionGate } from '../components/layout/WorkspaceGate'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceHeader, WorkspaceSection, WorkspaceTabs } from '../components/layout/WorkspacePrimitives'
import { useI18n, type I18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { AdministrationMember, AdministrationUser, ChangeMembershipRequest, CreateAdministrationUserRequest } from '../types/administration'
import { InvalidRoutePage } from './InvalidRoutePage'

export const administrationRoles = ['OWNER', 'ADMINISTRATOR', 'OPERATOR', 'MEMBER'] as const
export function assignableRoles(actorRole?: string) {
  return actorRole === 'ADMINISTRATOR' ? administrationRoles.filter(role => role === 'OPERATOR' || role === 'MEMBER') : administrationRoles
}
function errorText(error: unknown, i18n: I18n) {
  return error instanceof ApiError ? (i18n.t.administration.errors as Record<string, string>)[error.code] ?? describeError(error, i18n) : describeError(error, i18n)
}
function Failure({ error }: { error: unknown }) {
  const i18n = useI18n()
  return <InlineAlert tone="danger" title={i18n.t.administration.failed}>{errorText(error, i18n)}</InlineAlert>
}
export function InstallationGate({ children }: { children: ReactNode }) {
  const { t } = useI18n(); const me = useMe()
  if (me.isPending) return <div className="row-skeleton" aria-label={t.administration.loading}><span /><span /></div>
  if (me.isError) return <InlineAlert tone="danger" title={t.administration.accessCheckFailed}
    action={<button className="secondary-button" onClick={() => void me.refetch()}>{t.common.retry}</button>} />
  if (!me.data.isAdministrator) return <EmptyWorkspaceState icon={LockKeyhole} title={t.administration.forbidden} />
  return <>{children}</>
}
function RoleGuide() {
  const t = useI18n().t.administration
  const icons = [Crown, ShieldCheck, Shield, UserRound]
  return <section className="administration-role-guide" aria-label={t.roleGuide}>
    {administrationRoles.map((role, index) => { const Icon = icons[index]; return <div key={role}>
      <Icon size={18} aria-hidden /><strong>{t.roles[role]}</strong><p>{t.roleDetails[role]}</p>
    </div> })}
  </section>
}
function UserIdentity({ user }: { user: AdministrationUser }) {
  return <div className="administration-identity"><span className="administration-avatar" aria-hidden>{user.displayName.slice(0, 1).toLocaleUpperCase()}</span>
    <div><strong>{user.displayName}</strong><span>{user.email}</span></div></div>
}
function LoadMore({ available, busy, onClick }: { available: boolean; busy: boolean; onClick: () => void }) {
  const t = useI18n().t.administration
  return available ? <button type="button" className="secondary-button administration-load-more" disabled={busy} onClick={onClick}>{t.loadMore}</button> : null
}

export function AdministrationPage() {
  const t = useI18n().t.administration
  return <AppShell><div className="workspace-page administration-page"><WorkspaceHeader title={t.title} subtitle={t.subtitle} />
    <InstallationGate><AdministrationDirectory /></InstallationGate></div></AppShell>
}
function AdministrationDirectory() {
  const t = useI18n().t.administration
  const [params, setParams] = useSearchParams()
  const active = params.get('tab') === 'organizations' ? 'organizations' : 'users'
  return <><WorkspaceTabs active={active} tabs={[{ id: 'users', label: t.users }, { id: 'organizations', label: t.organizations }]}
    onChange={tab => setParams(tab === 'users' ? {} : { tab })} />
    <p className="administration-scope-note"><ShieldCheck size={16} aria-hidden />{t.scopeNote}</p>
    <div id={`panel-${active}`} role="tabpanel" aria-labelledby={`tab-${active}`}>
      {active === 'users' ? <UserDirectory /> : <OrganizationDirectory />}</div></>
}
function UserDirectory() {
  const i18n = useI18n(); const t = i18n.t.administration
  const users = useAdministrationUsers(true); const [search, setSearch] = useState('')
  const items = users.data?.pages.flatMap(page => page.items) ?? []
  const filtered = items.filter(user => `${user.displayName} ${user.email}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))
  return <WorkspaceSection title={t.users} actions={<Link className="primary-button" to="/administration/users/new"><Plus size={16} aria-hidden />{t.createUser}</Link>}>
    <div className="filter-bar list-filter-bar"><div className="search-field"><Search size={16} aria-hidden className="search-field-icon" />
      <input aria-label={t.search} placeholder={t.search} value={search} onChange={event => setSearch(event.target.value)} /></div>
      <span className="section-meta">{t.results(items.length)}</span></div>
    {users.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : users.isError ? <Failure error={users.error} /> :
      filtered.length === 0 ? <EmptyWorkspaceState compact icon={Users} title={t.emptyUsers} /> :
      <div className="table-scroll"><table className="data-grid administration-table"><thead><tr><th>{t.users}</th><th>{t.status}</th><th>{t.role}</th><th>{t.access}</th></tr></thead>
        <tbody>{filtered.map(user => <tr key={user.id}><td><UserIdentity user={user} /></td>
          <td><StatusIndicator label={user.isActive ? t.active : t.blocked} tone={user.isActive ? 'success' : 'neutral'} /></td>
          <td>{user.isAdministrator ? <span className="administration-global-role"><ShieldCheck size={14} aria-hidden />{t.globalAdministrator}</span> : '—'}</td>
          <td><Link className="secondary-button" to={`/administration/users/${encodeURIComponent(user.id)}`}>{t.editAccess}</Link></td></tr>)}</tbody></table></div>}
    <LoadMore available={!!users.hasNextPage} busy={users.isFetchingNextPage} onClick={() => void users.fetchNextPage()} />
  </WorkspaceSection>
}
function OrganizationDirectory() {
  const t = useI18n().t.administration; const organizations = useAdministrationOrganizations(true)
  return <WorkspaceSection title={t.organizations} actions={<Link className="primary-button" to="/organizations/new"><Plus size={16} aria-hidden />{t.createOrganization}</Link>}>
    {organizations.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : organizations.isError ? <Failure error={organizations.error} /> :
      <div className="administration-organizations">{organizations.data.pages.flatMap(page => page.items).map(org => <div key={org.id}>
        <Building2 size={20} aria-hidden /><div><strong>{org.name}</strong><span>{org.code}</span></div></div>)}</div>}
    <LoadMore available={!!organizations.hasNextPage} busy={organizations.isFetchingNextPage} onClick={() => void organizations.fetchNextPage()} />
  </WorkspaceSection>
}

export function OrganizationCreatePage() {
  const i18n = useI18n(); const t = i18n.t.administration; const navigate = useNavigate()
  const create = useAdministrationMutation(createOrganization)
  const [requestId] = useState(() => crypto.randomUUID()); const [name, setName] = useState(''); const [code, setCode] = useState('')
  function submit(event: FormEvent) { event.preventDefault(); create.mutate({ requestId, name: name.trim(), code: code.trim() },
    { onSuccess: org => navigate(`/organizations/${encodeURIComponent(org.id)}/overview`) }) }
  return <AppShell><div className="workspace-page form-page"><WorkspaceHeader title={t.createOrganization} subtitle={t.organizationHint}
    back={{ label: t.organizations, to: '/organizations' }} />
    <form className="workspace-form" onSubmit={submit}><WorkspaceSection title={t.organization}>
      <fieldset disabled={create.isPending} className="field-grid administration-fields">
        <label>{i18n.t.common.name}<input required maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>
        <label>{i18n.t.common.code}<input aria-label={i18n.t.common.code} aria-describedby="organization-code-hint" required maxLength={64} pattern="[A-Za-z0-9][A-Za-z0-9_-]{0,63}" value={code} onChange={event => setCode(event.target.value)} spellCheck={false} /><small id="organization-code-hint">{t.codeHint}</small></label>
      </fieldset></WorkspaceSection>{create.isError ? <Failure error={create.error} /> : null}
      <div className="form-toolbar"><Link className="secondary-button" to="/organizations">{i18n.t.common.cancel}</Link>
        <button className="primary-button" disabled={create.isPending} type="submit">{t.createOrganization}</button></div>
    </form></div></AppShell>
}

export function AdministrationUserCreatePage() {
  const { organizationId } = useParams(); const t = useI18n().t.administration
  const form = <UserCreateForm organizationId={organizationId ?? null} />
  return organizationId ? <PermissionGate organizationId={organizationId} permission="manageMembers" title={t.createUser}
    back={{ label: t.members, to: `/organizations/${encodeURIComponent(organizationId)}/members` }}
    texts={{ ...t, ownersOnly: t.forbidden }}><AppShell>{form}</AppShell></PermissionGate>
    : <AppShell><InstallationGate>{form}</InstallationGate></AppShell>
}
function UserCreateForm({ organizationId }: { organizationId: string | null }) {
  const i18n = useI18n(); const t = i18n.t.administration; const navigate = useNavigate()
  const permissions = useOrganizationPermissions(organizationId ?? undefined)
  const organizations = useAdministrationOrganizations(organizationId === null)
  const create = useAdministrationMutation((input: CreateAdministrationUserRequest) => createAdministrationUser(organizationId, input))
  const [requestId] = useState(() => crypto.randomUUID()); const [email, setEmail] = useState(''); const [name, setName] = useState(''); const [password, setPassword] = useState('')
  const [org, setOrg] = useState(organizationId ?? ''); const [role, setRole] = useState('MEMBER'); const [administrator, setAdministrator] = useState(false)
  const back = organizationId ? `/organizations/${encodeURIComponent(organizationId)}/members` : '/administration'
  function submit(event: FormEvent) { event.preventDefault(); create.mutate({ requestId, email, displayName: name, password,
    organizationId: org || null, role, isAdministrator: administrator }, { onSuccess: user => { setPassword(''); create.reset();
      navigate(organizationId ? back : `/administration/users/${encodeURIComponent(user.id)}`) } }) }
  return <div className="workspace-page form-page"><WorkspaceHeader title={t.createUser} subtitle={organizationId ? t.membersSubtitle : t.subtitle}
    back={{ label: t.back, to: back }} /><RoleGuide />
    <form className="workspace-form" onSubmit={submit}><WorkspaceSection title={t.users}>
      <fieldset className="field-grid administration-fields" disabled={create.isPending}>
        <label>{t.name}<input required maxLength={255} value={name} onChange={event => setName(event.target.value)} autoComplete="off" /></label>
        <label>{t.email}<input required type="email" maxLength={320} value={email} onChange={event => setEmail(event.target.value)} autoComplete="off" /></label>
        <label className="field-span">{t.password}<input aria-label={t.password} aria-describedby="user-password-hint" required type="password" minLength={12} maxLength={72} value={password} onChange={event => setPassword(event.target.value)} autoComplete="new-password" /><small id="user-password-hint">{t.passwordHint}</small></label>
      </fieldset></WorkspaceSection>
      <WorkspaceSection title={t.access} description={t.scopeNote}><fieldset className="field-grid administration-fields" disabled={create.isPending}>
        {!organizationId ? <label>{t.organization}<select value={org} onChange={event => setOrg(event.target.value)}><option value="">{t.noOrganization}</option>
          {organizations.data?.pages.flatMap(page => page.items).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label> : null}
        {org ? <label>{t.role}<select value={role} onChange={event => setRole(event.target.value)}>{assignableRoles(organizationId ? permissions.role : undefined).map(item =>
          <option key={item} value={item}>{t.roles[item]}</option>)}</select><small>{t.roleDetails[role as keyof typeof t.roleDetails]}</small></label> : null}
        {!organizationId ? <label className="administration-check field-span"><input type="checkbox" checked={administrator} onChange={event => setAdministrator(event.target.checked)} />
          <span><strong>{t.globalAdministrator}</strong><small>{t.globalHint}</small></span></label> : null}
      </fieldset><LoadMore available={!!organizations.hasNextPage} busy={organizations.isFetchingNextPage} onClick={() => void organizations.fetchNextPage()} />
      {organizations.isError ? <Failure error={organizations.error} /> : null}</WorkspaceSection>
      {create.isError ? <Failure error={create.error} /> : null}<div className="form-toolbar"><Link className="secondary-button" to={back}>{i18n.t.common.cancel}</Link>
        <button className="primary-button" type="submit" disabled={create.isPending}>{t.createUser}</button></div>
    </form></div>
}

export function OrganizationMembersPage() {
  const { organizationId } = useParams(); const t = useI18n().t.administration
  if (!organizationId) return <InvalidRoutePage />
  return <PermissionGate organizationId={organizationId} permission="manageMembers" title={t.members}
    back={{ label: t.back, to: `/organizations/${encodeURIComponent(organizationId)}/overview` }} texts={{ ...t, ownersOnly: t.forbidden }}>
    <AppShell><MemberDirectory organizationId={organizationId} /></AppShell></PermissionGate>
}
function MemberDirectory({ organizationId }: { organizationId: string }) {
  const i18n = useI18n(); const t = i18n.t.administration
  const permissions = useOrganizationPermissions(organizationId); const members = useOrganizationMembers(organizationId, true)
  const [editing, setEditing] = useState<AdministrationMember | null>(null); const [adding, setAdding] = useState(false)
  return <div className="workspace-page administration-page"><WorkspaceHeader title={t.members} subtitle={t.membersSubtitle}
    actions={<><button className="secondary-button" type="button" onClick={() => setAdding(true)}>{t.addExisting}</button>
      <Link className="primary-button" to={`/organizations/${encodeURIComponent(organizationId)}/members/new`}><Plus size={16} aria-hidden />{t.createUser}</Link></>} />
    <RoleGuide /><WorkspaceSection title={t.members}>
      {members.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : members.isError ? <Failure error={members.error} /> :
        <MemberTable items={members.data.pages.flatMap(page => page.items)} actorRole={permissions.role} onEdit={setEditing} />}
      <LoadMore available={!!members.hasNextPage} busy={members.isFetchingNextPage} onClick={() => void members.fetchNextPage()} />
    </WorkspaceSection>
    {editing ? <MembershipDialog member={editing} organizationId={organizationId} global={false} actorRole={permissions.role} onClose={() => setEditing(null)} /> : null}
    {adding ? <ExistingMemberDialog organizationId={organizationId} actorRole={permissions.role} onClose={() => setAdding(false)} /> : null}
  </div>
}
function MemberTable({ items, actorRole, onEdit, showOrganization = false }: {
  items: AdministrationMember[]; actorRole?: string; onEdit: (member: AdministrationMember) => void; showOrganization?: boolean
}) {
  const t = useI18n().t.administration
  if (items.length === 0) return <EmptyWorkspaceState compact icon={Users} title={t.emptyMembers} detail={t.emptyHint} />
  return <div className="table-scroll"><table className="data-grid administration-table"><thead><tr>
    <th>{showOrganization ? t.organization : t.users}</th><th>{t.role}</th><th>{t.status}</th><th>{t.access}</th></tr></thead>
    <tbody>{items.map(member => <tr key={`${member.user.id}-${member.organizationId}`}>
      <td>{showOrganization ? <strong>{member.organizationName}</strong> : <UserIdentity user={member.user} />}</td>
      <td><strong>{t.roles[member.role as keyof typeof t.roles] ?? member.role}</strong><small className="administration-role-detail">{t.roleDetails[member.role as keyof typeof t.roleDetails]}</small></td>
      <td><StatusIndicator label={!member.user.isActive ? t.blocked : member.isActive ? t.granted : t.revoked} tone={member.user.isActive && member.isActive ? 'success' : 'neutral'} /></td>
      <td>{member.user.isActive && !(actorRole === 'ADMINISTRATOR' && ['OWNER', 'ADMINISTRATOR'].includes(member.role)) ?
        <button className="secondary-button" type="button" onClick={() => onEdit(member)}>{t.editAccess}</button> : '—'}</td>
    </tr>)}</tbody></table></div>
}
function MembershipDialog({ member, organizationId, userId, global, actorRole, onClose }: {
  member?: AdministrationMember; organizationId: string; userId?: string; global: boolean; actorRole?: string; onClose: () => void
}) {
  const i18n = useI18n(); const t = i18n.t.administration
  const [role, setRole] = useState(member?.role ?? 'MEMBER'); const [active, setActive] = useState(member?.isActive ?? true)
  const mutation = useAdministrationMutation((input: ChangeMembershipRequest) => changeMembership(global, organizationId, member?.user.id ?? userId!, input))
  function submit(event: FormEvent) { event.preventDefault(); mutation.mutate({ role, isActive: active, expectedUpdatedAt: member?.updatedAt ?? null }, { onSuccess: onClose }) }
  return <IntegrationDialog title={t.editAccess} description={member?.organizationName ?? t.organization} onClose={onClose} busy={mutation.isPending}
    actions={<><button type="button" className="secondary-button" disabled={mutation.isPending} onClick={onClose}>{i18n.t.common.cancel}</button>
      <button type="submit" form="membership-form" className="primary-button" disabled={mutation.isPending}>{t.save}</button></>}>
    <form id="membership-form" className="workspace-form" onSubmit={submit}><label>{t.role}<select value={role} disabled={mutation.isPending} onChange={event => setRole(event.target.value)}>
      {assignableRoles(actorRole).map(item => <option key={item} value={item}>{t.roles[item]}</option>)}</select><small>{t.roleDetails[role as keyof typeof t.roleDetails]}</small></label>
      <label className="administration-check"><input type="checkbox" checked={active} disabled={mutation.isPending} onChange={event => setActive(event.target.checked)} /><span>{t.granted}</span></label>
      <p className="muted-copy">{t.revokeHint}</p>{mutation.isError ? <Failure error={mutation.error} /> : null}</form>
  </IntegrationDialog>
}
function ExistingMemberDialog({ organizationId, actorRole, onClose }: { organizationId: string; actorRole?: string; onClose: () => void }) {
  const i18n = useI18n(); const t = i18n.t.administration; const [email, setEmail] = useState(''); const [role, setRole] = useState('MEMBER')
  const mutation = useAdministrationMutation((input: { email: string; role: string; isActive: boolean; expectedUpdatedAt: null }) => addMemberByEmail(organizationId, input))
  return <IntegrationDialog title={t.addExisting} description={t.existingHint} onClose={onClose} busy={mutation.isPending}
    actions={<button type="submit" form="existing-member-form" className="primary-button" disabled={mutation.isPending}>{t.addExisting}</button>}>
    <form id="existing-member-form" className="workspace-form" onSubmit={event => { event.preventDefault(); mutation.mutate({ email, role, isActive: true, expectedUpdatedAt: null }, { onSuccess: onClose }) }}>
      <label>{t.email}<input type="email" required maxLength={320} value={email} disabled={mutation.isPending} onChange={event => setEmail(event.target.value)} /></label>
      <label>{t.role}<select value={role} disabled={mutation.isPending} onChange={event => setRole(event.target.value)}>{assignableRoles(actorRole).map(item => <option key={item} value={item}>{t.roles[item]}</option>)}</select></label>
      {mutation.isError ? <Failure error={mutation.error} /> : null}</form></IntegrationDialog>
}

export function AdministrationUserPage() {
  const { userId } = useParams(); if (!userId) return <InvalidRoutePage />
  return <AppShell><div className="workspace-page administration-page"><InstallationGate><UserAccess userId={userId} /></InstallationGate></div></AppShell>
}
function UserAccess({ userId }: { userId: string }) {
  const i18n = useI18n(); const t = i18n.t.administration; const user = useAdministrationUser(userId, true)
  const memberships = useUserMemberships(userId, true); const organizations = useAdministrationOrganizations(true)
  const [editing, setEditing] = useState<AdministrationMember | null>(null); const [selectedOrg, setSelectedOrg] = useState(''); const [adding, setAdding] = useState(false); const [settings, setSettings] = useState(false)
  const selectedMembership = useAdministrationMembership(selectedOrg, userId, !!selectedOrg)
  if (user.isPending) return <div className="row-skeleton" aria-label={t.loading}><span /><span /></div>
  if (user.isError) return <Failure error={user.error} />
  const items = memberships.data?.pages.flatMap(page => page.items) ?? []
  return <><WorkspaceHeader title={user.data.displayName} subtitle={user.data.email} back={{ label: t.users, to: '/administration' }}
    actions={<button className="secondary-button" type="button" onClick={() => setSettings(true)}>{t.editUser}</button>} />
    <div className="administration-account-strip"><UserIdentity user={user.data} /><StatusIndicator label={user.data.isActive ? t.active : t.blocked} tone={user.data.isActive ? 'success' : 'neutral'} />
      {user.data.isAdministrator ? <span className="administration-global-role"><ShieldCheck size={16} aria-hidden />{t.globalAdministrator}</span> : null}</div>
    <RoleGuide /><WorkspaceSection title={t.access} description={t.scopeNote}>
      {memberships.isError ? <Failure error={memberships.error} /> : memberships.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> :
        <MemberTable items={items} showOrganization onEdit={setEditing} />}
      <LoadMore available={!!memberships.hasNextPage} busy={memberships.isFetchingNextPage} onClick={() => void memberships.fetchNextPage()} />
      {user.data.isActive ? <div className="administration-add-access"><label>{t.organization}<select value={selectedOrg} onChange={event => setSelectedOrg(event.target.value)}>
        <option value="">{t.organization}</option>{organizations.data?.pages.flatMap(page => page.items).map(org => <option key={org.id} value={org.id}>{org.name}</option>)}</select></label>
        <button className="primary-button" type="button" disabled={!selectedOrg || !selectedMembership.isSuccess || selectedMembership.isFetching} onClick={() => {
          const existing = selectedMembership.data
          if (existing) setEditing(existing); else setAdding(true)
        }}><Plus size={16} aria-hidden />{t.editAccess}</button></div> : null}
      <LoadMore available={!!organizations.hasNextPage} busy={organizations.isFetchingNextPage} onClick={() => void organizations.fetchNextPage()} />
      {organizations.isError ? <Failure error={organizations.error} /> : null}
      {selectedMembership.isError ? <Failure error={selectedMembership.error} /> : null}
    </WorkspaceSection>
    {editing ? <MembershipDialog member={editing} organizationId={editing.organizationId} global onClose={() => setEditing(null)} /> : null}
    {adding ? <MembershipDialog organizationId={selectedOrg} userId={userId} global onClose={() => setAdding(false)} /> : null}
    {settings ? <UserStatusDialog user={user.data} onClose={() => setSettings(false)} /> : null}
  </>
}
function UserStatusDialog({ user, onClose }: { user: AdministrationUser; onClose: () => void }) {
  const i18n = useI18n(); const t = i18n.t.administration; const [active, setActive] = useState(user.isActive); const [admin, setAdmin] = useState(user.isAdministrator)
  const mutation = useAdministrationMutation((input: { isActive: boolean; isAdministrator: boolean; expectedUpdatedAt: string }) => changeUserStatus(user.id, input))
  return <IntegrationDialog title={t.editUser} description={user.displayName} onClose={onClose} busy={mutation.isPending}
    actions={<button form="user-status-form" type="submit" className="primary-button" disabled={mutation.isPending}>{t.save}</button>}>
    <form id="user-status-form" className="workspace-form" onSubmit={event => { event.preventDefault(); mutation.mutate({ isActive: active, isAdministrator: active && admin, expectedUpdatedAt: user.updatedAt }, { onSuccess: onClose }) }}>
      <label className="administration-check"><input type="checkbox" checked={active} disabled={mutation.isPending} onChange={event => setActive(event.target.checked)} /><span>{t.active}</span></label>
      <label className="administration-check"><input type="checkbox" checked={active && admin} disabled={!active || mutation.isPending} onChange={event => setAdmin(event.target.checked)} /><span><strong>{t.globalAdministrator}</strong><small>{t.globalHint}</small></span></label>
      <p className="muted-copy">{t.accountHint}</p>{mutation.isError ? <Failure error={mutation.error} /> : null}
    </form></IntegrationDialog>
}

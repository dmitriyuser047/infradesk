import type { ReactNode } from 'react'
import { LockKeyhole } from 'lucide-react'

import { useMyOrganizations } from '../../api/auth'
import { useI18n } from '../../i18n'
import { canOrganization, type OrganizationPermission } from '../auth/authorization'
import { AppShell } from './AppShell'
import { EmptyWorkspaceState, InlineAlert, WorkspaceHeader } from './WorkspacePrimitives'

/**
 * A page that only someone with a permission may use. Until membership is known it shows the
 * page's own frame; without the permission it explains why, with the way back, instead of a
 * control that cannot work.
 */
export function PermissionGate({ organizationId, permission, title, back, texts, children }: {
  organizationId: string
  permission: OrganizationPermission
  title: string
  back: { label: string; to: string }
  texts: { loading: string; accessCheckFailed: string; ownersOnly: string }
  children: ReactNode
}) {
  const i18n = useI18n()
  const membership = useMyOrganizations()
  if (membership.isPending) return <AppShell><div className="workspace-page form-page" aria-busy="true">
    <WorkspaceHeader title={title} back={back} />
    <div className="row-skeleton" aria-label={texts.loading}><span /><span /><span /></div>
  </div></AppShell>
  if (membership.isError) return <AppShell><div className="workspace-page form-page">
    <WorkspaceHeader title={title} back={back} />
    <InlineAlert tone="danger" title={texts.accessCheckFailed}
      action={<button className="secondary-button" type="button" onClick={() => membership.refetch()}>{i18n.t.common.retry}</button>} />
  </div></AppShell>
  if (!canOrganization(membership.data?.find(value => value.id === organizationId)?.role, permission)) {
    return <AppShell><div className="workspace-page form-page">
      <WorkspaceHeader title={title} back={back} />
      <EmptyWorkspaceState icon={LockKeyhole} title={texts.ownersOnly} />
    </div></AppShell>
  }
  return <>{children}</>
}

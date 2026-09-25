import { useState } from 'react'

import {
  useCreateMonitorRule,
  useMonitorRules,
  useUpdateMonitorRule,
} from '../../api/monitorRules'
import type { MonitorRuleRequest, MonitorRuleResponse } from '../../types/monitorRule'
import { useOrganizationPermissions } from '../auth/authorization'
import { MonitorRuleDialog } from './MonitorRuleDialog'
import { MonitorRuleRow } from './MonitorRuleRow'
import { EmptyWorkspaceState, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { Plus } from 'lucide-react'

import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'

interface MonitorRulesSectionProps {
  organizationId: string
  resourceId: string
}

export function MonitorRulesSection({ organizationId, resourceId }: MonitorRulesSectionProps) {
  const i18n = useI18n()
  const t = i18n.t.monitoring
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageMonitoring')
  const rulesQuery = useMonitorRules(organizationId, resourceId)
  const createMutation = useCreateMonitorRule(organizationId, resourceId)
  const updateMutation = useUpdateMonitorRule(organizationId, resourceId)
  const [dialogRule, setDialogRule] = useState<MonitorRuleResponse | undefined>()
  const [dialogOpen, setDialogOpen] = useState(false)

  const openCreate = () => {
    createMutation.reset()
    updateMutation.reset()
    setDialogRule(undefined)
    setDialogOpen(true)
  }

  const openEdit = (rule: MonitorRuleResponse) => {
    createMutation.reset()
    updateMutation.reset()
    setDialogRule(rule)
    setDialogOpen(true)
  }

  const closeDialog = () => {
    if (!createMutation.isPending && !updateMutation.isPending) {
      setDialogOpen(false)
    }
  }

  const save = async (request: MonitorRuleRequest) => {
    try {
      if (dialogRule === undefined) {
        await createMutation.mutateAsync(request)
      } else {
        await updateMutation.mutateAsync({ monitorRuleId: dialogRule.id, request })
      }
      setDialogOpen(false)
    } catch {
      // The mutation error is rendered inside the dialog; do not leak a rejected submit promise.
    }
  }

  const mutationError = createMutation.error ?? updateMutation.error

  return (
    <WorkspaceSection title={t.title} actions={canManage ?
      <button className="primary-button" type="button" onClick={openCreate}><Plus aria-hidden size={16} />{t.addRule}</button> : undefined}>
      {rulesQuery.isPending ? <RulesSkeleton label={t.loading} /> : null}
      {rulesQuery.isError ? (
        <div className="monitor-rules-error" role="alert">
          <p>{t.loadError}</p>
          <span>{describeError(rulesQuery.error, i18n)}</span>
          <button className="retry-button" type="button" onClick={() => rulesQuery.refetch()}>{i18n.t.common.retry}</button>
        </div>
      ) : null}
      {!rulesQuery.isPending && !rulesQuery.isError && rulesQuery.data?.length === 0 ? (
        <EmptyWorkspaceState title={t.empty} detail={t.emptyDetail} />
      ) : null}
      {!rulesQuery.isPending && !rulesQuery.isError && rulesQuery.data !== undefined && rulesQuery.data.length > 0 ? (
        <div className="table-scroll"><table className="data-grid">
          <thead><tr><th>{t.columns.metric}</th><th>{t.columns.condition}</th><th>{t.columns.threshold}</th><th>{t.columns.duration}</th><th>{t.columns.state}</th>
            {canManage ? <th>{t.columns.action}</th> : null}</tr></thead>
          <tbody>{rulesQuery.data.map(rule => <MonitorRuleRow key={rule.id} rule={rule}
            onEdit={canManage ? openEdit : undefined} />)}</tbody>
        </table></div>
      ) : null}
      {canManage && dialogOpen ? (
        <MonitorRuleDialog
          rule={dialogRule}
          pending={createMutation.isPending || updateMutation.isPending}
          errorMessage={mutationError === null ? undefined : describeError(mutationError, i18n)}
          onSubmit={save}
          onClose={closeDialog}
        />
      ) : null}
    </WorkspaceSection>
  )
}

function RulesSkeleton({ label }: { label: string }) {
  return (
    <div className="rules-skeleton" aria-label={label}>
      <span /><span /><span />
    </div>
  )
}

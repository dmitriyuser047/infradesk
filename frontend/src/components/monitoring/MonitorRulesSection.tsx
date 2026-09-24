import { useState } from 'react'

import { ApiError } from '../../api/httpClient'
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

interface MonitorRulesSectionProps {
  organizationId: string
  resourceId: string
}

export function MonitorRulesSection({ organizationId, resourceId }: MonitorRulesSectionProps) {
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
    <WorkspaceSection title="Monitor rules" actions={canManage ?
      <button className="primary-button" type="button" onClick={openCreate}>+ Add rule</button> : undefined}>
      {rulesQuery.isPending ? <RulesSkeleton /> : null}
      {rulesQuery.isError ? (
        <div className="monitor-rules-error" role="alert">
          <p>Unable to load monitor rules</p>
          <span>{safeErrorMessage(rulesQuery.error)}</span>
          <button className="retry-button" type="button" onClick={() => rulesQuery.refetch()}>Retry</button>
        </div>
      ) : null}
      {!rulesQuery.isPending && !rulesQuery.isError && rulesQuery.data?.length === 0 ? (
        <EmptyWorkspaceState title="No monitor rules configured" detail="Add a rule to watch this resource's metrics." />
      ) : null}
      {!rulesQuery.isPending && !rulesQuery.isError && rulesQuery.data !== undefined && rulesQuery.data.length > 0 ? (
        <div className="table-scroll"><table className="data-grid">
          <thead><tr><th>Metric</th><th>Condition</th><th>Threshold</th><th>Duration</th><th>State</th>
            {canManage ? <th>Action</th> : null}</tr></thead>
          <tbody>{rulesQuery.data.map(rule => <MonitorRuleRow key={rule.id} rule={rule}
            onEdit={canManage ? openEdit : undefined} />)}</tbody>
        </table></div>
      ) : null}
      {canManage && dialogOpen ? (
        <MonitorRuleDialog
          rule={dialogRule}
          pending={createMutation.isPending || updateMutation.isPending}
          errorMessage={mutationError === null ? undefined : safeErrorMessage(mutationError)}
          onSubmit={save}
          onClose={closeDialog}
        />
      ) : null}
    </WorkspaceSection>
  )
}

function RulesSkeleton() {
  return (
    <div className="rules-skeleton" aria-label="Loading monitor rules">
      <span /><span /><span />
    </div>
  )
}

function safeErrorMessage(error: Error | null): string {
  return error instanceof ApiError ? error.message : 'Please try again shortly.'
}

import { useState } from 'react'

import { ApiError } from '../../api/httpClient'
import {
  useCreateMonitorRule,
  useMonitorRules,
  useUpdateMonitorRule,
} from '../../api/monitorRules'
import type { MonitorRuleRequest, MonitorRuleResponse } from '../../types/monitorRule'
import { MonitorRuleDialog } from './MonitorRuleDialog'
import { MonitorRuleRow } from './MonitorRuleRow'

interface MonitorRulesSectionProps {
  organizationId: string
  resourceId: string
}

export function MonitorRulesSection({ organizationId, resourceId }: MonitorRulesSectionProps) {
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
    <section className="content-panel monitor-rules-section" aria-labelledby="monitor-rules-heading">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Policy</p>
          <h2 id="monitor-rules-heading">Monitor rules</h2>
        </div>
        <button className="primary-button" type="button" onClick={openCreate}>+ Add rule</button>
      </div>
      {rulesQuery.isPending ? <RulesSkeleton /> : null}
      {rulesQuery.isError ? (
        <div className="monitor-rules-error" role="alert">
          <p>Unable to load monitor rules</p>
          <span>{safeErrorMessage(rulesQuery.error)}</span>
          <button className="retry-button" type="button" onClick={() => rulesQuery.refetch()}>Retry</button>
        </div>
      ) : null}
      {!rulesQuery.isPending && !rulesQuery.isError && rulesQuery.data?.length === 0 ? (
        <div className="monitor-rules-empty">
          <strong>No monitor rules configured</strong>
          <span>Add a rule to watch this resource's metrics.</span>
        </div>
      ) : null}
      {!rulesQuery.isPending && !rulesQuery.isError && rulesQuery.data !== undefined && rulesQuery.data.length > 0 ? (
        <div className="monitor-rules-list">
          {rulesQuery.data.map((rule) => <MonitorRuleRow key={rule.id} rule={rule} onEdit={openEdit} />)}
        </div>
      ) : null}
      {dialogOpen ? (
        <MonitorRuleDialog
          rule={dialogRule}
          pending={createMutation.isPending || updateMutation.isPending}
          errorMessage={mutationError === null ? undefined : safeErrorMessage(mutationError)}
          onSubmit={save}
          onClose={closeDialog}
        />
      ) : null}
    </section>
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

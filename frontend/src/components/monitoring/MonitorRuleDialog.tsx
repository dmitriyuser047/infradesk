import { useEffect } from 'react'

import type { MonitorRuleRequest, MonitorRuleResponse } from '../../types/monitorRule'
import { MonitorRuleForm } from './MonitorRuleForm'

interface MonitorRuleDialogProps {
  rule?: MonitorRuleResponse
  pending: boolean
  errorMessage?: string
  onSubmit: (request: MonitorRuleRequest) => Promise<void>
  onClose: () => void
}

export function MonitorRuleDialog({
  rule,
  pending,
  errorMessage,
  onSubmit,
  onClose,
}: MonitorRuleDialogProps) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !pending) {
        onClose()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose, pending])

  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !pending) {
        onClose()
      }
    }}>
      <section className="monitor-rule-dialog" role="dialog" aria-modal="true" aria-labelledby="monitor-rule-dialog-title">
        <div className="dialog-heading">
          <h2 id="monitor-rule-dialog-title">{rule === undefined ? 'Add monitor rule' : 'Edit monitor rule'}</h2>
          <button className="dialog-close" type="button" aria-label="Close" onClick={onClose} disabled={pending}>×</button>
        </div>
        <MonitorRuleForm
          key={rule?.id ?? 'new'}
          rule={rule}
          pending={pending}
          errorMessage={errorMessage}
          onSubmit={onSubmit}
          onCancel={onClose}
        />
      </section>
    </div>
  )
}

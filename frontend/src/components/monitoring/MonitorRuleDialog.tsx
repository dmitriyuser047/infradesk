import { useEffect } from 'react'

import type { MonitorRuleRequest, MonitorRuleResponse } from '../../types/monitorRule'
import { MonitorRuleForm } from './MonitorRuleForm'
import { useI18n } from '../../i18n'

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
  const { t } = useI18n()
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
          <h2 id="monitor-rule-dialog-title">{rule === undefined ? t.monitoring.dialogAdd : t.monitoring.dialogEdit}</h2>
          <button className="dialog-close" type="button" aria-label={t.common.close} title={t.common.close} onClick={onClose} disabled={pending}>×</button>
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

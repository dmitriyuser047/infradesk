import { useI18n } from '../../i18n'
import { codeText } from '../../i18n/errors'
import type { I18n } from '../../i18n'
import type { DeploymentDiff, DeploymentExecution, DeploymentState } from '../../types/configurationDeployment'
import type { RolloutItemState, RolloutState } from '../../types/configurationRollout'
import { InlineAlert, type StatusTone } from '../layout/WorkspacePrimitives'

export const deploymentTone = (state: DeploymentState): StatusTone => ({
  QUEUED: 'neutral', RUNNING: 'info', SUCCEEDED: 'success', FAILED: 'danger',
  ROLLED_BACK: 'warning', ROLLBACK_FAILED: 'danger', CANCELLED: 'neutral',
} as const)[state]

export const rolloutTone = (state: RolloutState): StatusTone => ({
  QUEUED: 'neutral', RUNNING: 'info', PAUSED: 'info', FAILED: 'danger', ROLLING_BACK: 'warning',
  ROLLED_BACK: 'warning', SUCCEEDED: 'success', CANCELLED: 'neutral',
} as const)[state]

export const rolloutItemTone = (state: RolloutItemState): StatusTone => ({
  PENDING: 'neutral', DEPLOYING: 'info', SUCCEEDED: 'success', FAILED: 'danger', ROLLED_BACK: 'warning', SKIPPED: 'neutral',
} as const)[state]

/** A stored failure code in words; an unknown code is shown as itself, never as a raw message. */
export function failureText(code: string | null | undefined, i18n: I18n): string | null {
  if (!code) return null
  return codeText(code, i18n) ?? code
}

const UnitPattern = /^[A-Za-z0-9_.@:-]+\.service$/
const ExecutablePattern = /^\/[A-Za-z0-9_.+-]+(\/[A-Za-z0-9_.+-]+)*$/

export const validUnit = (value: string) => value.length <= 255 && UnitPattern.test(value) && !value.startsWith('-')

/** The same rules the backend enforces; the backend stays the authority. */
export function validValidator(executable: string, args: string[]): boolean {
  if (executable === '' && args.length === 0) return true
  const segments = executable.split('/')
  const argOk = (arg: string) => {
    const bare = arg.split('{candidate}').join('').split('{target}').join('')
    // eslint-disable-next-line no-control-regex
    return arg.length <= 1024 && !/[\u0000-\u001f\u007f]/.test(arg) && !/[{}$`]/.test(bare)
  }
  return executable.length <= 1024 && ExecutablePattern.test(executable) &&
    !segments.includes('..') && !segments.includes('.') && args.length <= 32 && args.every(argOk)
}

export function validExecution(execution: DeploymentExecution): boolean {
  const unitOk = execution.activation === 'NONE' ? execution.unitName === null : validUnit(execution.unitName ?? '')
  return unitOk && (execution.validator === null || validValidator(execution.validator.executable, execution.validator.args))
}

/**
 * A unified diff as plain text lines. Every line is a text node: nothing from the remote file is
 * ever interpreted as markup.
 */
export function DeploymentDiffView({ diff }: { diff: DeploymentDiff }) {
  const i18n = useI18n()
  const t = i18n.t.deployments
  const lines = diff.text === '' ? [] : diff.text.replace(/\n$/, '').split('\n')
  const kind = (line: string) => line.startsWith('+++') || line.startsWith('---') ? 'diff-header'
    : line.startsWith('@@') ? 'diff-hunk' : line.startsWith('+') ? 'diff-added'
    : line.startsWith('-') ? 'diff-removed' : line.startsWith('\\') ? 'diff-note' : 'diff-context'
  return <div className="deployment-diff">
    <p className="deployment-diff-counts">{t.changedLines(diff.addedLines, diff.removedLines)}</p>
    {lines.length > 0 ? <pre className="configuration-code diff-code" tabIndex={0} aria-label={t.diffLabel}>
      {lines.map((line, index) => <span key={index} className={`diff-line ${kind(line)}`}>{line}{'\n'}</span>)}
    </pre> : null}
    {diff.approximate ? <InlineAlert tone="info" title={t.approximate} />
      : diff.truncated ? <InlineAlert tone="info" title={t.truncated} /> : null}
  </div>
}

import { Play, RotateCw, Square, Wrench, type LucideIcon } from 'lucide-react'

import type { StatusTone } from '../layout/WorkspacePrimitives'
import type {
  AvailableResourceOperationsResponse,
  OperationExecutionResponse,
  OperationExecutionStatus,
} from '../../types/resourceOperation'

/**
 * How a controlled operation is shown. What an operation does and whether it is allowed stay with
 * the backend; this only says how visibly it interrupts a running service.
 */
export type OperationImpact = 'normal' | 'caution' | 'disruptive'

const appearances: Record<string, { Icon: LucideIcon; impact: OperationImpact }> = {
  CONTAINER_START: { Icon: Play, impact: 'normal' },
  CONTAINER_RESTART: { Icon: RotateCw, impact: 'caution' },
  CONTAINER_STOP: { Icon: Square, impact: 'disruptive' },
}

/** An operation this frontend does not know is still shown, as a plain action. */
export function operationAppearance(code: string): { Icon: LucideIcon; impact: OperationImpact } {
  return appearances[code] ?? { Icon: Wrench, impact: 'normal' }
}

export const operationStatusTones: Record<OperationExecutionStatus, StatusTone> = {
  RUNNING: 'info', SUCCEEDED: 'success', FAILED: 'danger', UNKNOWN: 'warning',
}

/**
 * Whether a resource has operations to show: the backend offers some for it, or some ran before.
 * While either answer is missing the question stays open; a failed answer shows its error.
 */
export function operationsApplicability(
  available: { data?: AvailableResourceOperationsResponse; isError: boolean },
  history: { data?: readonly OperationExecutionResponse[]; isError: boolean },
): 'applicable' | 'not-applicable' | 'unknown' {
  if ((available.data?.operations.length ?? 0) > 0 || (history.data?.length ?? 0) > 0) return 'applicable'
  if (available.isError || history.isError) return 'applicable'
  if (available.data !== undefined && history.data !== undefined) return 'not-applicable'
  return 'unknown'
}

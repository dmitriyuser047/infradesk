import { useI18n } from '../../i18n'
import { StatusIndicator } from '../layout/WorkspacePrimitives'

export function ConnectionStatusBadge({ active }: { active: boolean }) {
  const { t } = useI18n()
  return <StatusIndicator label={active ? t.common.active : t.common.inactive} tone={active ? 'success' : 'neutral'} />
}

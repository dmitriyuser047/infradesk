import { StatusIndicator } from '../layout/WorkspacePrimitives'

export function ConnectionStatusBadge({ active }: { active: boolean }) {
  return <StatusIndicator label={active ? 'Active' : 'Inactive'} tone={active ? 'success' : 'neutral'} />
}

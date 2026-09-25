import { Box } from 'lucide-react'

import { useI18n } from '../../../i18n'
import { EmptyWorkspaceState } from '../../layout/WorkspacePrimitives'
import type { ResourceRendering } from './ResourcePresentation'

function UnknownOverview() {
  const { t } = useI18n()
  return <EmptyWorkspaceState title={t.resources.detailsUnavailable} />
}

/**
 * Used for a resource type this frontend does not know yet, so a backend that ships a new type
 * still renders: common resource data from the generic shell plus a neutral placeholder here.
 */
export const defaultResourcePresentation: ResourceRendering = {
  Icon: Box,
  rowStatus: (_resource, i18n) => ({ label: i18n.t.common.unknown, tone: 'neutral' }),
  Overview: UnknownOverview,
}

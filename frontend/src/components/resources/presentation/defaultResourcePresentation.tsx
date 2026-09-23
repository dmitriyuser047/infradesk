import { Box } from 'lucide-react'

import { EmptyWorkspaceState } from '../../layout/WorkspacePrimitives'
import type { ResourceRendering } from './ResourcePresentation'

/**
 * Used for a resource type this frontend does not know yet, so a backend that ships a new type
 * still renders: common resource data from the generic shell plus a neutral placeholder here.
 */
export const defaultResourcePresentation: ResourceRendering = {
  Icon: Box,
  rowStatus: () => ({ label: 'Unknown', tone: 'neutral' }),
  Overview: () => <EmptyWorkspaceState title="Details are not available for this resource type" />,
}

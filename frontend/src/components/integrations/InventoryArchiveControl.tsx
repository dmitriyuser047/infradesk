import { useState } from 'react'
import { Trash2 } from 'lucide-react'
import { useArchiveInventoryObject } from '../../api/integrationInventory'
import { useOrganizationPermissions } from '../auth/authorization'
import { InlineAlert } from '../layout/WorkspacePrimitives'
import { IntegrationDialog } from './IntegrationDialog'
import { useI18n } from '../../i18n'
import { describeIntegrationError } from './integrationPresentation'

export function InventoryArchiveControl({ organizationId, integrationId, item }: {
  organizationId: string; integrationId: string; item: { id: string; displayName: string; active: boolean }
}) {
  const i18n = useI18n()
  const t = i18n.t.integrationInventory.archive
  const permissions = useOrganizationPermissions(organizationId)
  const mutation = useArchiveInventoryObject(organizationId, integrationId)
  const [open, setOpen] = useState(false)
  if (item.active || !permissions.can('manageIntegrations') || !permissions.can('executeOperations')) return null
  return <>
    <button className="icon-button inventory-archive-button" type="button" aria-label={`${t.remove}: ${item.displayName}`}
      title={t.remove} onClick={() => { mutation.reset(); setOpen(true) }}><Trash2 size={16} aria-hidden /></button>
    {open ? <IntegrationDialog title={t.title} busy={mutation.isPending} onClose={() => setOpen(false)}
      actions={<><button className="secondary-button" type="button" disabled={mutation.isPending} onClick={() => setOpen(false)}>{i18n.t.common.cancel}</button>
        <button className="primary-button danger-button" type="button" disabled={mutation.isPending}
          onClick={() => mutation.mutate(item.id, { onSuccess: () => setOpen(false) })}>{t.remove}</button></>}>
      <strong>{item.displayName}</strong><p>{t.detail}</p>
      {mutation.isError ? <InlineAlert tone="danger" title={describeIntegrationError(mutation.error, i18n)} /> : null}
    </IntegrationDialog> : null}
  </>
}

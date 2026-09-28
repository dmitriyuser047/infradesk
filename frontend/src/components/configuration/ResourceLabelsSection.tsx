import { useState, type FormEvent } from 'react'

import { useReplaceResourceLabels, useResourceLabels } from '../../api/configurationRules'
import { ApiError } from '../../api/httpClient'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import type { Label } from '../../types/configurationRule'
import { InlineAlert, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { LabelListEditor, MaxResourceLabels, canonicalLabels, labelProblem } from './ConfigurationRules'

/**
 * A node's labels. The whole set is saved at the version it was read at, so two editors never
 * silently overwrite each other; a label change may let a rule create desired state, never deploy.
 */
export function ResourceLabelsSection({ organizationId, resourceId, canEdit }: {
  organizationId: string; resourceId: string; canEdit: boolean
}) {
  const i18n = useI18n()
  const t = i18n.t.rules.labels
  const query = useResourceLabels(organizationId, resourceId)
  const replace = useReplaceResourceLabels(organizationId, resourceId)
  const [draft, setDraft] = useState<Label[] | null>(null)
  const [problem, setProblem] = useState('')
  const stale = replace.error instanceof ApiError && replace.error.code === 'RESOURCE_LABELS_CHANGED'

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!draft || !query.data) return
    setProblem('')
    const labels = canonicalLabels(draft.filter(label => label.key.trim() !== '' || label.value !== ''))
    const found = labelProblem(labels, MaxResourceLabels, true)
    if (found === 'invalid') return setProblem(i18n.t.rules.invalidLabels)
    if (found === 'duplicate') return setProblem(i18n.t.rules.duplicateLabels)
    if (found === 'tooMany') return setProblem(i18n.t.rules.tooManyLabels(MaxResourceLabels))
    replace.mutate({ expectedVersion: query.data.version, labels }, { onSuccess: () => setDraft(null) })
  }

  return <WorkspaceSection title={t.title} description={t.description}
    actions={canEdit && draft === null && query.data
      ? <button type="button" className="secondary-button" onClick={() => { replace.reset(); setDraft(query.data.labels) }}>{t.edit}</button>
      : undefined}>
    {query.isPending ? <div className="row-skeleton" aria-label={t.title}><span /></div> : null}
    {query.isError ? <InlineAlert tone="danger" title={t.loadError}
      action={<button className="secondary-button" type="button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(query.error, i18n)}</InlineAlert> : null}
    {query.data && draft === null ? (query.data.labels.length === 0
      ? <p className="field-hint">{t.empty}</p>
      : <ul className="label-chips" aria-label={t.list}>{query.data.labels.map(label =>
        <li key={label.key} className="label-chip technical-value">{label.key}={label.value}</li>)}</ul>) : null}
    {draft !== null ? <form onSubmit={submit} noValidate className="configuration-editor">
      <p role="note" className="field-hint">{t.notSecret}</p>
      <LabelListEditor legend={t.list} labels={draft} max={MaxResourceLabels} onChange={setDraft} />
      {problem ? <InlineAlert tone="danger" title={problem} /> : null}
      {stale ? <InlineAlert tone="warning" title={t.changed}
        action={<button type="button" className="secondary-button" onClick={() => {
          replace.reset(); setDraft(null); void query.refetch()
        }}>{t.reload}</button>} />
        : replace.isError ? <InlineAlert tone="danger" title={describeError(replace.error, i18n)} /> : null}
      <div className="configuration-editor-actions">
        <button type="button" className="secondary-button" onClick={() => { replace.reset(); setDraft(null) }}>{t.cancel}</button>
        <button type="submit" className="primary-button" disabled={replace.isPending}>{replace.isPending ? t.saving : t.save}</button>
      </div>
    </form> : null}
  </WorkspaceSection>
}

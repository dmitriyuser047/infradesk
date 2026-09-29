import { useQueries } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { useConfigurationRevisions } from '../../api/configurations'
import { useConfigurationRules, useCreateRule, useSelectorPreview } from '../../api/configurationRules'
import { getEnvironments, useProjects } from '../../api/navigation'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import type { ConfigurationProfile } from '../../types/configuration'
import type { ConfigurationRule, Label, RuleSelector, SelectorPreview } from '../../types/configurationRule'
import type { EnvironmentResponse } from '../../types/navigation'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceFormSection, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { isTargetPath } from './configurationTargetSupport'
import '../../styles/pages/configurations.css'

export const rulePath = (organizationId: string, ruleId: string) =>
  `/organizations/${encodeURIComponent(organizationId)}/configuration-rules/${encodeURIComponent(ruleId)}`

/** The backend's limits, mirrored only to answer early; the backend stays the authority. */
export const MaxSelectorLabels = 16
export const MaxResourceLabels = 32
const LabelKey = /^[a-z][a-z0-9_.-]{0,62}$/
const RuleCode = /^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/
// Control characters and the Unicode line and paragraph separators.
const ControlCharacters = /[\p{Cc}\p{Zl}\p{Zp}]/u

export const EmptySelector: RuleSelector = { projects: [], environments: [], requiredLabels: [], excludedLabels: [] }

/** Keys are canonical lowercase; values are kept exactly, case included. */
export function canonicalLabels(labels: Label[]): Label[] {
  return labels.map(label => ({ key: label.key.trim().toLowerCase(), value: label.value }))
}

export type LabelProblem = 'invalid' | 'duplicate' | 'tooMany' | null

export function labelProblem(labels: Label[], max: number, uniqueKeys: boolean): LabelProblem {
  if (labels.length > max) return 'tooMany'
  if (labels.some(label => !LabelKey.test(label.key) || label.value.length < 1 || label.value.length > 128 ||
    ControlCharacters.test(label.value))) return 'invalid'
  const identities = labels.map(label => uniqueKeys ? label.key : `${label.key}=${label.value}`)
  return new Set(identities).size === identities.length ? null : 'duplicate'
}

export function validRuleCode(code: string): boolean {
  return RuleCode.test(code)
}

/** Every environment of every project, from the same cache entries the rest of the app reads. */
export function useAllEnvironments(organizationId: string) {
  const projects = useProjects(organizationId)
  const environments = useQueries({
    queries: (projects.data ?? []).map(project => ({
      queryKey: ['environments', organizationId, project.id],
      queryFn: () => getEnvironments(organizationId, project.id),
    })),
  })
  return {
    projects: projects.data ?? [],
    environments: environments.flatMap(query => query.data ?? []) as EnvironmentResponse[],
  }
}

/** A key=value list editor. An empty row is dropped on save; the backend validates what remains. */
export function LabelListEditor({ legend, labels, onChange, max }: {
  legend: string; labels: Label[]; onChange: (labels: Label[]) => void; max: number
}) {
  const t = useI18n().t.rules
  const set = (index: number, change: Partial<Label>) =>
    onChange(labels.map((label, position) => position === index ? { ...label, ...change } : label))
  return <fieldset className="label-editor">
    <legend>{legend}</legend>
    {labels.map((label, index) => <div className="label-editor-row" key={index}>
      <input className="technical-input" aria-label={t.labelKeyOf(legend, index + 1)} placeholder={t.labelKey}
        value={label.key} maxLength={63} autoComplete="off" spellCheck={false}
        onChange={event => set(index, { key: event.target.value })} />
      <span aria-hidden>=</span>
      <input className="technical-input" aria-label={t.labelValueOf(legend, index + 1)} placeholder={t.labelValue}
        value={label.value} maxLength={128} autoComplete="off" spellCheck={false}
        onChange={event => set(index, { value: event.target.value })} />
      <button type="button" className="text-button danger-text" aria-label={t.removeLabel(legend, index + 1)}
        onClick={() => onChange(labels.filter((_, position) => position !== index))}>×</button>
    </div>)}
    <button type="button" className="text-button" disabled={labels.length >= max}
      onClick={() => onChange([...labels, { key: '', value: '' }])}>{t.addLabel}</button>
  </fieldset>
}

/** Projects, environments and labels: structured fields only, never an expression. */
export function SelectorEditor({ organizationId, value, onChange }: {
  organizationId: string; value: RuleSelector; onChange: (selector: RuleSelector) => void
}) {
  const t = useI18n().t.rules
  const { projects, environments } = useAllEnvironments(organizationId)
  const toggle = (list: string[], id: string) => list.includes(id) ? list.filter(item => item !== id) : [...list, id]
  return <div className="selector-editor">
    <fieldset className="selector-choices">
      <legend>{t.selectorProjects}</legend>
      {projects.map(project => <label key={project.id} className="checkbox-field">
        <input type="checkbox" checked={value.projects.includes(project.id)}
          onChange={() => onChange({ ...value, projects: toggle(value.projects, project.id) })} />{project.name}</label>)}
    </fieldset>
    <fieldset className="selector-choices">
      <legend>{t.selectorEnvironments}</legend>
      {environments.map(environment => {
        const project = projects.find(item => item.id === environment.projectId)
        return <label key={environment.id} className="checkbox-field">
          <input type="checkbox" checked={value.environments.includes(environment.id)}
            onChange={() => onChange({ ...value, environments: toggle(value.environments, environment.id) })} />
          {environment.name}{project && projects.length > 1 ? <small className="cell-secondary"> · {project.name}</small> : null}</label>
      })}
    </fieldset>
    <LabelListEditor legend={t.requiredLabels} labels={value.requiredLabels} max={MaxSelectorLabels}
      onChange={requiredLabels => onChange({ ...value, requiredLabels })} />
    <LabelListEditor legend={t.excludedLabels} labels={value.excludedLabels} max={MaxSelectorLabels}
      onChange={excludedLabels => onChange({ ...value, excludedLabels })} />
  </div>
}

/** What a selector problem is, in words; null when the selector can be sent. */
export function selectorProblem(selector: RuleSelector, t: ReturnType<typeof useI18n>['t']['rules']): string | null {
  for (const labels of [selector.requiredLabels, selector.excludedLabels]) {
    const problem = labelProblem(labels, MaxSelectorLabels, false)
    if (problem === 'invalid') return t.invalidLabels
    if (problem === 'duplicate') return t.duplicateLabels
    if (problem === 'tooMany') return t.tooManyLabels(MaxSelectorLabels)
  }
  const excluded = new Set(selector.excludedLabels.map(label => `${label.key}=${label.value}`))
  return selector.requiredLabels.some(label => excluded.has(`${label.key}=${label.value}`)) ? t.contradiction : null
}

export function cleanSelector(selector: RuleSelector): RuleSelector {
  const filled = (labels: Label[]) => canonicalLabels(labels.filter(label => label.key.trim() !== '' || label.value !== ''))
  return { ...selector, requiredLabels: filled(selector.requiredLabels), excludedLabels: filled(selector.excludedLabels) }
}

/** A selector in one line of names and key=value pairs. */
export function SelectorSummary({ organizationId, selector }: { organizationId: string; selector: RuleSelector }) {
  const t = useI18n().t.rules
  const { projects, environments } = useAllEnvironments(organizationId)
  const names = (ids: string[], all: { id: string; name: string }[]) =>
    ids.map(id => all.find(item => item.id === id)?.name ?? '…').join(', ')
  const parts: string[] = []
  if (selector.projects.length > 0) parts.push(names(selector.projects, projects))
  if (selector.environments.length > 0) parts.push(names(selector.environments, environments))
  parts.push(...selector.requiredLabels.map(label => `${label.key}=${label.value}`))
  parts.push(...selector.excludedLabels.map(label => `!${label.key}=${label.value}`))
  return <span className="selector-summary">{parts.length === 0 ? t.everyNode : parts.join(' · ')}</span>
}

export function RuleStateIndicator({ rule }: { rule: Pick<ConfigurationRule, 'enabled' | 'archived'> }) {
  const t = useI18n().t.rules
  if (rule.archived) return <StatusIndicator label={t.archived} tone="neutral" />
  return rule.enabled ? <StatusIndicator label={t.enabled} tone="success" /> : <StatusIndicator label={t.disabled} tone="neutral" />
}

/** The Automation tab of a profile: its rules, and a form for a new one. */
export function AutomationPanel({ organizationId, profile }: { organizationId: string; profile: ConfigurationProfile }) {
  const i18n = useI18n()
  const t = i18n.t.rules
  const [creating, setCreating] = useState(false)
  const pages = useConfigurationRules(organizationId, profile.id)
  const rows = pages.data?.pages.flatMap(page => page.items) ?? []
  return <>
    <WorkspaceSection title={t.title} description={t.description}
      actions={!profile.archived && !creating
        ? <button type="button" className="secondary-button" onClick={() => setCreating(true)}>{t.create}</button> : undefined}>
      {pages.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
      {pages.isError ? <InlineAlert tone="danger" title={t.loadError}
        action={<button className="secondary-button" type="button" onClick={() => pages.refetch()}>{i18n.t.common.retry}</button>}>
        {describeError(pages.error, i18n)}</InlineAlert> : null}
      {pages.isSuccess && rows.length === 0 ? <EmptyWorkspaceState compact title={t.empty} detail={t.emptyDetail} /> : null}
      {rows.length > 0 ? <div className="table-scroll"><table className="data-grid">
        <thead><tr><th scope="col">{t.columns.name}</th><th scope="col">{t.columns.state}</th>
          <th scope="col">{t.columns.revision}</th><th scope="col">{t.columns.selector}</th>
          <th scope="col">{t.columns.targets}</th></tr></thead>
        <tbody>{rows.map(rule => <tr key={rule.id}>
          <td><Link className="grid-link" to={rulePath(organizationId, rule.id)}>{rule.name}</Link>
            <small className="cell-secondary technical-value">{rule.code}</small></td>
          <td><RuleStateIndicator rule={rule} /></td>
          <td>{i18n.t.configurations.version(rule.profileRevisionNumber)}
            {!rule.archived && rule.profile.latestRevisionNumber > rule.profileRevisionNumber
              ? <StatusIndicator label={t.newerAvailable(rule.profile.latestRevisionNumber)} tone="info" /> : null}</td>
          <td><SelectorSummary organizationId={organizationId} selector={rule.selector} /></td>
          <td>{t.managedCount(rule.counts.managed)}
            {rule.counts.issues > 0 ? <small className="cell-secondary">{t.blockedCount(rule.counts.issues)}</small> : null}</td>
        </tr>)}</tbody>
      </table></div> : null}
      {pages.hasNextPage ? <button type="button" className="secondary-button" disabled={pages.isFetchingNextPage}
        onClick={() => pages.fetchNextPage()}>{t.showMore}</button> : null}
    </WorkspaceSection>
    {creating ? <RuleCreateForm organizationId={organizationId} profile={profile} onCancel={() => setCreating(false)} /> : null}
  </>
}

/**
 * Name, code, exact version, path and selector, then a preview of the nodes the rule would take.
 * A rule can always be created disabled; enabling on creation needs a preview of exactly this draft.
 */
function RuleCreateForm({ organizationId, profile, onCancel }: {
  organizationId: string; profile: ConfigurationProfile; onCancel: () => void
}) {
  const i18n = useI18n()
  const t = i18n.t.rules
  const navigate = useNavigate()
  const ids = { name: useId(), code: useId(), codeHelp: useId(), description: useId(), revision: useId(),
    revisionHelp: useId(), path: useId(), pathHelp: useId() }
  const revisions = useConfigurationRevisions(organizationId, profile.id, true)
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [revision, setRevision] = useState(profile.latestRevisionNumber)
  const [targetPath, setTargetPath] = useState('')
  const [selector, setSelector] = useState<RuleSelector>(EmptySelector)
  const [problem, setProblem] = useState('')
  const preview = useSelectorPreview(organizationId)
  const create = useCreateRule(organizationId)
  const changed = () => { preview.reset(); setProblem('') }

  const draft = () => {
    const cleaned = cleanSelector(selector)
    const refusal = !name.trim() || name.trim().length > 255 ? t.invalidName
      : !validRuleCode(code) ? t.invalidCode
        : !isTargetPath(targetPath.trim()) ? i18n.t.assignments.invalidPath
          : selectorProblem(cleaned, t)
    if (refusal) { setProblem(refusal); return null }
    return { cleaned, path: targetPath.trim() }
  }
  const runPreview = () => {
    setProblem('')
    const current = draft()
    if (current) preview.mutate({ profileId: profile.id, revisionNumber: revision, targetPath: current.path, selector: current.cleaned })
  }
  const submit = (enabled: boolean) => (event?: FormEvent) => {
    event?.preventDefault()
    setProblem('')
    const current = draft()
    if (!current) return
    if (enabled && !preview.data) return setProblem(t.previewRequired)
    create.mutate({ code, name: name.trim(), description: description.trim() || null, profileId: profile.id,
      profileRevisionNumber: revision, targetPath: current.path, selector: current.cleaned, enabled },
    { onSuccess: rule => navigate(rulePath(organizationId, rule.id)) })
  }
  const listed = (revisions.data ?? []).map(item => item.revisionNumber)
  const options = listed.includes(revision) ? listed : [revision, ...listed]

  return <WorkspaceFormSection title={t.createTitle} headingId={`${ids.name}-title`} contentClassName="configuration-editor"
    onSubmit={submit(false)} noValidate aria-labelledby={`${ids.name}-title`}>
    <InlineAlert tone="info" title={t.notDeployedNote} />
    <div className="assignment-grid-fields">
      <div className="configuration-field"><label htmlFor={ids.name}>{t.name}</label>
        <input id={ids.name} value={name} maxLength={255} onChange={event => { changed(); setName(event.target.value) }} /></div>
      <div className="configuration-field"><label htmlFor={ids.code}>{t.code}</label>
        <input id={ids.code} className="technical-input" value={code} maxLength={64} autoComplete="off" spellCheck={false}
          aria-describedby={ids.codeHelp} onChange={event => { changed(); setCode(event.target.value.toLowerCase()) }} />
        <span id={ids.codeHelp} className="field-hint">{t.codeHelp}</span></div>
      <div className="configuration-field"><label htmlFor={ids.revision}>{t.revision}</label>
        <select id={ids.revision} value={revision} aria-describedby={ids.revisionHelp}
          onChange={event => { changed(); setRevision(Number(event.target.value)) }}>
          {options.map(number => <option key={number} value={number}>{number === profile.latestRevisionNumber
            ? i18n.t.assignments.versionLatest(number) : i18n.t.configurations.version(number)}</option>)}
        </select>
        <span id={ids.revisionHelp} className="field-hint">{t.revisionHelp}</span></div>
      <div className="configuration-field"><label htmlFor={ids.path}>{t.targetPath}</label>
        <input id={ids.path} className="technical-input" value={targetPath} maxLength={4096} autoComplete="off" spellCheck={false}
          placeholder="/etc/xray/config.json" aria-describedby={ids.pathHelp}
          onChange={event => { changed(); setTargetPath(event.target.value) }} />
        <span id={ids.pathHelp} className="field-hint">{t.targetPathHelp}</span></div>
      <div className="configuration-field configuration-details-wide"><label htmlFor={ids.description}>{t.descriptionField}</label>
        <textarea id={ids.description} rows={2} value={description} maxLength={2000}
          onChange={event => setDescription(event.target.value)} /></div>
    </div>
    <WorkspaceSection title={t.selectorTitle} description={t.selectorHelp}>
      <SelectorEditor organizationId={organizationId} value={selector} onChange={value => { changed(); setSelector(value) }} />
    </WorkspaceSection>
    <WorkspaceSection title={t.preview} description={t.previewNote}>
      <button type="button" className="secondary-button" disabled={preview.isPending} onClick={runPreview}>
        {preview.isPending ? t.previewing : t.preview}</button>
      {preview.isError ? <InlineAlert tone="danger" title={describeError(preview.error, i18n)} /> : null}
      {preview.data ? <SelectorPreviewView preview={preview.data} /> : null}
    </WorkspaceSection>
    {problem ? <InlineAlert tone="danger" title={problem} /> : null}
    {create.isError ? <InlineAlert tone="danger" title={describeError(create.error, i18n)} /> : null}
    <div className="configuration-editor-actions">
      <button type="button" className="secondary-button" onClick={onCancel}>{t.cancel}</button>
      <button type="submit" className="secondary-button" disabled={create.isPending}>{create.isPending ? t.creating : t.createDisabled}</button>
      <button type="button" className="primary-button" disabled={create.isPending || !preview.data} onClick={() => submit(true)()}>
        {t.createEnabled}</button>
    </div>
  </WorkspaceFormSection>
}

/** The counts over every match and the first page of matched nodes, each with what would happen to it. */
export function SelectorPreviewView({ preview }: { preview: SelectorPreview }) {
  const i18n = useI18n()
  const t = i18n.t.rules
  const tone = (state: string) => state === 'ELIGIBLE' ? 'success' : state === 'ADOPTABLE' ? 'info' : 'warning'
  return <div className="selector-preview" role="status">
    <dl className="property-grid property-grid-2">
      <div className="property-row"><dt>{t.previewCounts.matched}</dt><dd>{preview.matchedCount}</dd></div>
      <div className="property-row"><dt>{t.previewCounts.eligible}</dt><dd>{preview.eligibleCount}</dd></div>
      <div className="property-row"><dt>{t.previewCounts.needsValues}</dt><dd>{preview.needsValuesCount}</dd></div>
      <div className="property-row"><dt>{t.previewCounts.conflicts}</dt><dd>{preview.conflictCount}</dd></div>
    </dl>
    {preview.missingVariable ? <InlineAlert tone="warning" title={t.missingVariable(preview.missingVariable)} /> : null}
    {preview.items.length > 0 ? <div className="table-scroll"><table className="data-grid">
      <thead><tr><th scope="col">{t.targetColumns.resource}</th><th scope="col">{t.targetColumns.environment}</th>
        <th scope="col">{t.targetColumns.status}</th></tr></thead>
      <tbody>{preview.items.map(item => <tr key={item.resourceId}>
        <td>{item.resourceName}</td>
        <td>{item.environmentName}<small className="cell-secondary">{item.projectName}</small></td>
        <td><StatusIndicator label={t.previewStates[item.state] ?? item.state} tone={tone(item.state)} /></td>
      </tr>)}</tbody>
    </table></div> : null}
    {preview.items.length < preview.matchedCount ? <p className="field-hint">{t.previewShown(preview.items.length, preview.matchedCount)}</p> : null}
  </div>
}

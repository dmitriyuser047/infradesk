import { useId, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'

import { useConfigurationRevision, useConfigurationRevisions } from '../api/configurations'
import { useDeploymentSummaries } from '../api/configurationDeployments'
import {
  useCompleteRuleTarget, useConfigurationRule, useManagedAssignmentAction, usePromoteRule, useReconcileRule,
  useRuleExclusion, useRuleLifecycle, useRulePromotionPreview, useRuleTargets, useUpdateRule,
} from '../api/configurationRules'
import { ApiError } from '../api/httpClient'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { assignmentPath } from '../components/configuration/ConfigurationAssignmentList'
import {
  SelectorEditor, SelectorSummary, RuleStateIndicator, cleanSelector, selectorProblem,
} from '../components/configuration/ConfigurationRules'
import { AppShell } from '../components/layout/AppShell'
import {
  EmptyWorkspaceState, InlineAlert, PageLoading, PageUnavailable, PropertyGrid, StatusIndicator, WorkspaceFormSection, WorkspaceHeader,
  WorkspaceSection, type StatusTone,
} from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { codeText, describeError } from '../i18n/errors'
import type { ConfigurationRule, RuleSelector, RuleTarget } from '../types/configurationRule'
import type { DeploymentSummary } from '../types/configurationDeployment'
import { configurationPath, configurationsPath } from './ConfigurationsPage'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/configurations.css'

export function ConfigurationRulePage() {
  const { organizationId, ruleId } = useParams()
  if (!organizationId || !ruleId) return <InvalidRoutePage />
  return <AppShell><RuleContent organizationId={organizationId} ruleId={ruleId} /></AppShell>
}

/** Where a promoted rule's nodes are rolled out: the existing rollout wizard, preselected. */
export function ruleRolloutPath(organizationId: string, rule: Pick<ConfigurationRule, 'id' | 'profile'>, revision: number) {
  return `${configurationPath(organizationId, rule.profile.id)}?tab=targets&rolloutRule=${encodeURIComponent(rule.id)}&rolloutRevision=${revision}`
}

/**
 * One automation rule: what it selects, the exact version it keeps assigned, and every node it
 * touches. Everything here changes desired state only; deploying stays the rollout's job.
 */
function RuleContent({ organizationId, ruleId }: { organizationId: string; ruleId: string }) {
  const i18n = useI18n()
  const t = i18n.t.rules
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConfigurations')
  const query = useConfigurationRule(organizationId, canManage ? ruleId : null)
  const lifecycle = useRuleLifecycle(organizationId, ruleId)
  const reconcile = useReconcileRule(organizationId, ruleId)
  const [editing, setEditing] = useState(false)
  const rule = query.data
  const back = rule
    ? { label: rule.profile.name, to: `${configurationPath(organizationId, rule.profile.id)}?tab=automation` }
    : { label: i18n.t.configurations.back, to: configurationsPath(organizationId) }

  if (!permissions.isPending && !canManage) {
    return <div className="workspace-page"><WorkspaceHeader title={t.title} back={back} /><InlineAlert tone="danger" title={i18n.t.configurations.accessDenied} /></div>
  }
  if (permissions.isPending || query.isPending) return <PageLoading title={t.title} back={back} label={t.loading} />
  if (query.isError || !rule) {
    return <PageUnavailable back={back} onRetry={() => query.refetch()} error={query.error}
      notFound={query.error instanceof ApiError && query.error.code === 'CONFIGURATION_RULE_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={t.detailLoadError} />
  }
  const act = (action: 'enable' | 'disable' | 'archive') => lifecycle.mutate({ action, expectedVersion: rule.version })
  const changed = lifecycle.error instanceof ApiError && lifecycle.error.code === 'CONFIGURATION_RULE_CHANGED'
  const newer = !rule.archived && rule.profile.latestRevisionNumber > rule.profileRevisionNumber

  return <div className="workspace-page">
    <WorkspaceHeader title={rule.name} back={back}
      subtitle={<span className="technical-value">{rule.code}</span>}
      status={<RuleStateIndicator rule={rule} />}
      actions={rule.archived ? null : <>
        <button type="button" className="secondary-button" aria-expanded={editing} onClick={() => setEditing(value => !value)}>{t.edit}</button>
        {rule.enabled ? <button type="button" className="secondary-button" disabled={reconcile.isPending}
          onClick={() => reconcile.mutate()}>{t.reconcileNow}</button> : null}
        {rule.enabled
          ? <button type="button" className="secondary-button" disabled={lifecycle.isPending} onClick={() => act('disable')}>{t.disable}</button>
          : <button type="button" className="primary-button" disabled={lifecycle.isPending || rule.profile.archived}
            onClick={() => act('enable')}>{t.enable}</button>}
        <details className="toolbar-overflow"><summary aria-label={t.moreActions} title={t.moreActions}>⋯</summary>
          <button type="button" className="danger-action" disabled={lifecycle.isPending} onClick={() => {
            if (window.confirm(t.archiveConfirm(rule.name))) act('archive')
          }}>{t.archive}</button>
        </details>
      </>} />
    {rule.description ? <p className="configuration-description">{rule.description}</p> : null}
    {rule.archived ? <InlineAlert tone="info" title={t.archivedNotice} /> : null}
    {!rule.archived && rule.profile.archived ? <InlineAlert tone="warning" title={t.profileArchivedNotice} /> : null}
    {!rule.archived ? <InlineAlert tone="info" title={t.disableNote} /> : null}
    {reconcile.isSuccess ? <InlineAlert tone="success" title={t.reconcileScheduled} /> : null}
    {changed ? <InlineAlert tone="warning" title={t.changed}
      action={<button type="button" className="secondary-button" onClick={() => { lifecycle.reset(); void query.refetch() }}>{t.reload}</button>} />
      : lifecycle.isError ? <InlineAlert tone="danger" title={describeError(lifecycle.error, i18n)} /> : null}
    {reconcile.isError ? <InlineAlert tone="danger" title={describeError(reconcile.error, i18n)} /> : null}

    <WorkspaceSection title={i18n.t.assignments.targetSection}>
      <PropertyGrid columns={2} items={[
        { label: t.profile, value: <Link className="grid-link" to={configurationPath(organizationId, rule.profile.id)}>{rule.profile.name}</Link> },
        { label: t.revision, value: <>{i18n.t.configurations.version(rule.profileRevisionNumber)}
          {newer ? <> <StatusIndicator label={t.newerAvailable(rule.profile.latestRevisionNumber)} tone="info" /></> : null}</> },
        { label: t.targetPath, value: rule.targetPath, technical: true },
        { label: t.selectorTitle, value: <SelectorSummary organizationId={organizationId} selector={rule.selector} /> },
        { label: t.lastReconciled, value: rule.lastReconciledAt ? i18n.format.dateTime(rule.lastReconciledAt) : t.notYet },
        { label: t.nextReconcile, value: rule.enabled && rule.nextReconcileAt ? i18n.format.dateTime(rule.nextReconcileAt) : t.notScheduled },
        { label: t.health, value: rule.counts.issues > 0
          ? <StatusIndicator label={t.attention(rule.counts.issues)} tone="warning" />
          : <StatusIndicator label={t.healthy} tone="success" /> },
        { label: t.columns.targets, value: `${t.matchedCount(rule.counts.matched)} · ${t.managedCount(rule.counts.managed)} · ${t.excludedCount(rule.counts.excluded)}` },
      ]} />
    </WorkspaceSection>

    {editing && !rule.archived ? <RuleEditForm key={rule.version} organizationId={organizationId} rule={rule}
      onDone={() => setEditing(false)} onReload={() => void query.refetch()} /> : null}
    {!rule.archived && !rule.profile.archived
      ? <PromotionSection organizationId={organizationId} rule={rule}
        canDeploy={permissions.can('deployConfigurations')} /> : null}
    <TargetsSection organizationId={organizationId} rule={rule} />
  </div>
}

/** Name, description and selector, at the version the user saw. Code, path and version never change here. */
function RuleEditForm({ organizationId, rule, onDone, onReload }: {
  organizationId: string; rule: ConfigurationRule; onDone: () => void; onReload: () => void
}) {
  const i18n = useI18n()
  const t = i18n.t.rules
  const ids = { name: useId(), description: useId() }
  const update = useUpdateRule(organizationId, rule.id)
  const [name, setName] = useState(rule.name)
  const [description, setDescription] = useState(rule.description ?? '')
  const [selector, setSelector] = useState<RuleSelector>(rule.selector)
  const [problem, setProblem] = useState('')
  const stale = update.error instanceof ApiError && update.error.code === 'CONFIGURATION_RULE_CHANGED'
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem('')
    const cleaned = cleanSelector(selector)
    const refusal = !name.trim() || name.trim().length > 255 ? t.invalidName : selectorProblem(cleaned, t)
    if (refusal) return setProblem(refusal)
    update.mutate({ expectedVersion: rule.version, name: name.trim(), description: description.trim() || null, selector: cleaned },
      { onSuccess: onDone })
  }
  return <WorkspaceFormSection title={t.edit} contentClassName="configuration-editor" onSubmit={submit} noValidate>
    <div className="configuration-details-grid">
      <label htmlFor={ids.name}>{t.name}<input id={ids.name} value={name} maxLength={255} onChange={event => setName(event.target.value)} /></label>
      <label htmlFor={ids.description} className="configuration-details-wide">{t.descriptionField}
        <textarea id={ids.description} rows={2} value={description} maxLength={2000} onChange={event => setDescription(event.target.value)} /></label>
    </div>
    <p className="field-hint">{t.selectorHelp}</p>
    <SelectorEditor organizationId={organizationId} value={selector} onChange={setSelector} />
    {problem ? <InlineAlert tone="danger" title={problem} /> : null}
    {stale ? <InlineAlert tone="warning" title={t.changed}
      action={<button type="button" className="secondary-button" onClick={() => { update.reset(); onReload() }}>{t.reload}</button>} />
      : update.isError ? <InlineAlert tone="danger" title={describeError(update.error, i18n)} /> : null}
    <div className="configuration-editor-actions">
      <button type="button" className="secondary-button" onClick={onDone}>{t.cancel}</button>
      <button type="submit" className="primary-button" disabled={update.isPending}>{update.isPending ? t.saving : t.save}</button>
    </div>
  </WorkspaceFormSection>
}

/**
 * A newer version, previewed against every managed assignment, then one all-or-nothing promotion of
 * the rule and all of them. Nothing is deployed: the rollout is offered as the next, separate step.
 */
function PromotionSection({ organizationId, rule, canDeploy }: { organizationId: string; rule: ConfigurationRule; canDeploy: boolean }) {
  const i18n = useI18n()
  const t = i18n.t.rules
  const id = useId()
  const revisions = useConfigurationRevisions(organizationId, rule.profile.id, true)
  // The latest version is known from the rule itself, so the choice never waits for the history.
  const listed = (revisions.data ?? []).map(item => item.revisionNumber)
  const newer = (listed.includes(rule.profile.latestRevisionNumber) ? listed : [rule.profile.latestRevisionNumber, ...listed])
    .filter(number => number > rule.profileRevisionNumber)
  const [target, setTarget] = useState<number | null>(null)
  const chosen = target ?? newer[0] ?? null
  const preview = useRulePromotionPreview(organizationId, rule.id)
  const promote = usePromoteRule(organizationId, rule.id)
  const [promoted, setPromoted] = useState<{ revision: number; count: number } | null>(null)

  if (promoted) {
    return <WorkspaceSection title={t.promotionTitle}>
      <InlineAlert tone="success" title={t.promoted(promoted.revision, promoted.count)}
        action={canDeploy && promoted.count > 0
          ? <Link className="primary-button" to={ruleRolloutPath(organizationId, rule, promoted.revision)}>{t.rollOut(promoted.revision)}</Link>
          : undefined} />
    </WorkspaceSection>
  }
  if (rule.profile.latestRevisionNumber <= rule.profileRevisionNumber) {
    return <WorkspaceSection title={t.promotionTitle}><p className="field-hint">{t.upToDate}</p></WorkspaceSection>
  }
  // A preview answers for one version of the rule; any change to the rule asks for a new one.
  const current = preview.data && preview.data.targetRevision === chosen && preview.data.ruleVersion === rule.version ? preview.data : null
  return <WorkspaceSection title={t.promotionTitle} description={t.promotionDescription}>
    <div className="configuration-editor-actions assignment-preview-actions">
      <div className="configuration-field"><label htmlFor={id}>{t.promotionTarget}</label>
        <select id={id} value={chosen ?? ''} onChange={event => { setTarget(Number(event.target.value)); preview.reset() }}>
          {newer.map(number => <option key={number} value={number}>{i18n.t.configurations.version(number)}</option>)}
        </select></div>
      <button type="button" className="secondary-button" disabled={chosen === null || preview.isPending}
        onClick={() => chosen !== null && preview.mutate({ targetRevisionNumber: chosen, expectedRuleVersion: rule.version })}>
        {preview.isPending ? t.previewingPromotion : t.previewPromotion}</button>
    </div>
    {preview.isError ? <InlineAlert tone="danger" title={describeError(preview.error, i18n)} /> : null}
    {current ? <>
      {current.compatible
        ? <InlineAlert tone="success" title={t.promotionCompatible(current.assignmentCount)} />
        : <InlineAlert tone="warning" title={t.promotionBlocked} />}
      {current.items.some(item => !item.compatible) ? <ul className="rollout-matrix" aria-label={t.promotionTitle}>
        {current.items.filter(item => !item.compatible).map(item => <li key={item.assignmentId}>
          <span className="rollout-node">{item.resourceName ?? item.assignmentId}</span>
          <span className="rollout-issues">{item.issues.map(issue => <StatusIndicator key={`${issue.code}-${issue.variableName}`} tone="danger"
            label={i18n.t.rollouts.issue[issue.code] && issue.variableName ? i18n.t.rollouts.issue[issue.code](issue.variableName)
              : codeText(issue.code, i18n) ?? issue.code} />)}</span>
          <Link className="text-button" to={assignmentPath(organizationId, item.assignmentId)}>{t.openAssignment}</Link>
        </li>)}</ul> : null}
      <button type="button" className="primary-button" disabled={!current.compatible || promote.isPending}
        onClick={() => promote.mutate({ targetRevisionNumber: current.targetRevision, expectedRuleVersion: current.ruleVersion,
          assignments: current.items.map(item => ({ assignmentId: item.assignmentId, expectedVersion: item.expectedVersion })) },
        { onSuccess: result => setPromoted({ revision: result.revisionNumber, count: result.assignments.length }) })}>
        {promote.isPending ? t.promoting : t.promote(current.targetRevision)}</button>
    </> : null}
    {promote.isError ? <InlineAlert tone="danger" title={describeError(promote.error, i18n)} /> : null}
  </WorkspaceSection>
}

const StatusTones: Record<string, StatusTone> = {
  ASSIGNED: 'success', PENDING: 'info', NEEDS_VALUES: 'warning', TARGET_PATH_CONFLICT: 'danger', OTHER_RULE_CONFLICT: 'danger',
  PROFILE_ARCHIVED: 'neutral', ASSIGNMENT_INVALID: 'danger', EXCLUDED: 'neutral', NO_LONGER_MATCHING: 'warning',
}

/** Every node the rule touches, a page at a time, with the action that fits how it stands. */
function TargetsSection({ organizationId, rule }: { organizationId: string; rule: ConfigurationRule }) {
  const i18n = useI18n()
  const t = i18n.t.rules
  const pages = useRuleTargets(organizationId, rule.id)
  const rows = pages.data?.pages.flatMap(page => page.items) ?? []
  // One request for every loaded managed row, never one per node.
  const managed = rows.flatMap(row => row.assignment?.managedByRule ? [row.assignment.id] : [])
  const summaries = useDeploymentSummaries(organizationId, managed.slice(0, 100))
  const exclusion = useRuleExclusion(organizationId, rule.id)
  const managedAction = useManagedAssignmentAction(organizationId)
  const [completing, setCompleting] = useState<RuleTarget | null>(null)
  const error = exclusion.error ?? managedAction.error
  const readOnly = rule.archived

  const run = (row: RuleTarget, action: 'detach' | 'adopt' | 'exclude-and-remove') => {
    const assignment = row.assignment
    if (!assignment || assignment.version === null) return
    if (action === 'detach' && !window.confirm(t.detachConfirm)) return
    if (action === 'exclude-and-remove' && !window.confirm(t.excludeRemoveConfirm)) return
    managedAction.mutate({ ruleId: rule.id, assignmentId: assignment.id, action, expectedVersion: assignment.version })
  }

  return <WorkspaceSection title={t.targetsTitle} description={<>{t.targetsDescription} {i18n.t.deployments.driftNote}</>}>
    {pages.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
    {pages.isError ? <InlineAlert tone="danger" title={describeError(pages.error, i18n)}
      action={<button className="secondary-button" type="button" onClick={() => pages.refetch()}>{i18n.t.common.retry}</button>} /> : null}
    {error ? <InlineAlert tone="danger" title={describeError(error, i18n)} /> : null}
    {pages.isSuccess && rows.length === 0 ? <EmptyWorkspaceState compact title={t.targetsEmpty} detail={t.targetsEmptyDetail} /> : null}
    {rows.length > 0 ? <div className="table-scroll"><table className="data-grid">
      <thead><tr><th scope="col">{t.targetColumns.resource}</th><th scope="col">{t.targetColumns.environment}</th>
        <th scope="col">{t.targetColumns.status}</th><th scope="col">{t.targetColumns.desired}</th>
        <th scope="col">{t.targetColumns.deployment}</th>
        <th scope="col"><span className="visually-hidden">{t.targetColumns.actions}</span></th></tr></thead>
      <tbody>{rows.map(row => {
        const node = row.resource.name
        const ownAssignment = row.assignment?.managedByRule ? row.assignment : null
        return <tr key={row.resource.id}>
          <td><Link className="grid-link" to={`/organizations/${encodeURIComponent(organizationId)}/environments/${
            encodeURIComponent(row.environment.id)}/resources/${encodeURIComponent(row.resource.id)}?tab=configurations`}>{node}</Link>
            {!row.resource.active ? <small className="cell-secondary">{i18n.t.assignments.targetInactive}</small> : null}</td>
          <td>{row.environment.name}<small className="cell-secondary">{row.project.name}</small></td>
          <td><StatusIndicator label={t.status[row.status] ?? row.status} tone={StatusTones[row.status] ?? 'neutral'} />
            <TargetDetail row={row} /></td>
          <td>{ownAssignment?.revision ? i18n.t.configurations.version(ownAssignment.revision) : '—'}</td>
          <td>{ownAssignment ? <DeploymentCell desired={ownAssignment.revision}
            summary={summaries.data?.find(summary => summary.assignmentId === ownAssignment.id)} /> : '—'}</td>
          <td className="assignment-actions">{readOnly ? null : <>
            {row.status === 'NEEDS_VALUES' ? <button type="button" className="text-button"
              aria-label={t.actionOn(t.complete, node)} onClick={() => setCompleting(row)}>{t.complete}</button> : null}
            {row.status === 'TARGET_PATH_CONFLICT' && row.assignment ? <button type="button" className="text-button"
              disabled={managedAction.isPending} aria-label={t.actionOn(t.adopt, node)} onClick={() => run(row, 'adopt')}>{t.adopt}</button> : null}
            {row.assignment && !ownAssignment ? <Link className="text-button" to={assignmentPath(organizationId, row.assignment.id)}
              aria-label={t.actionOn(t.openAssignment, node)}>{t.openAssignment}</Link> : null}
            {ownAssignment ? <>
              <button type="button" className="text-button" disabled={managedAction.isPending}
                aria-label={t.actionOn(t.detach, node)} onClick={() => run(row, 'detach')}>{t.detach}</button>
              <button type="button" className="text-button danger-text" disabled={managedAction.isPending}
                aria-label={t.actionOn(t.excludeAndRemove, node)} onClick={() => run(row, 'exclude-and-remove')}>{t.excludeAndRemove}</button>
            </> : row.status === 'EXCLUDED'
              ? <button type="button" className="text-button" disabled={exclusion.isPending} aria-label={t.actionOn(t.include, node)}
                onClick={() => exclusion.mutate({ resourceId: row.resource.id, exclude: false })}>{t.include}</button>
              : <button type="button" className="text-button" disabled={exclusion.isPending} aria-label={t.actionOn(t.exclude, node)}
                onClick={() => exclusion.mutate({ resourceId: row.resource.id, exclude: true })}>{t.exclude}</button>}
          </>}</td>
        </tr>
      })}</tbody>
    </table></div> : null}
    {pages.hasNextPage ? <button type="button" className="secondary-button" disabled={pages.isFetchingNextPage}
      onClick={() => pages.fetchNextPage()}>{t.showMore}</button> : null}
    {completing ? <CompleteAssignmentForm key={completing.resource.id} organizationId={organizationId} rule={rule} target={completing}
      onDone={() => setCompleting(null)} /> : null}
  </WorkspaceSection>
}

function TargetDetail({ row }: { row: RuleTarget }) {
  const t = useI18n().t.rules
  const text = row.status === 'NEEDS_VALUES' && row.issue?.variableName ? t.missingValue(row.issue.variableName)
    : row.status === 'TARGET_PATH_CONFLICT' ? t.conflictManual
      : row.status === 'OTHER_RULE_CONFLICT' ? t.conflictRule
        : row.status === 'NO_LONGER_MATCHING' ? t.noLongerMatchingDetail : null
  return text ? <small className="cell-secondary">{text}</small> : null
}

/** What InfraDesk last deployed against what the rule now wants; never "in sync". */
function DeploymentCell({ desired, summary }: { desired: number | null; summary: DeploymentSummary | undefined }) {
  const i18n = useI18n()
  const t = i18n.t.deployments
  if (!summary) return <span className="cell-secondary">—</span>
  if (summary.activeDeployment) return <StatusIndicator label={t.active} tone="info" />
  const last = summary.lastSuccessfulDeployment
  if (!last) return <span className="assignment-status"><StatusIndicator label={t.neverDeployed} tone="neutral" />
    <small className="cell-secondary">{i18n.t.rules.notDeployedNote}</small></span>
  if (last.revision === desired) return <span>{t.lastDeployedCurrent(last.revision)}</span>
  return <span className="assignment-status"><StatusIndicator label={t.deploymentRequired} tone="warning" />
    <small className="cell-secondary">{t.lastDeployed(last.revision)}</small></span>
}

/** Values for a node the rule could not assign on defaults alone; saving creates a valid managed assignment. */
function CompleteAssignmentForm({ organizationId, rule, target, onDone }: {
  organizationId: string; rule: ConfigurationRule; target: RuleTarget; onDone: () => void
}) {
  const i18n = useI18n()
  const t = i18n.t.rules
  const revision = useConfigurationRevision(organizationId, rule.profile.id, rule.profileRevisionNumber)
  const complete = useCompleteRuleTarget(organizationId, rule.id)
  const [values, setValues] = useState<Record<string, string>>({})
  const headingId = useId()
  const submit = (event: FormEvent) => {
    event.preventDefault()
    complete.mutate({ resourceId: target.resource.id,
      values: Object.entries(values).filter(([, value]) => value !== '').map(([name, value]) => ({ name, value })) },
    { onSuccess: onDone })
  }
  return <form className="configuration-editor rule-complete-form" onSubmit={submit} noValidate aria-labelledby={headingId}>
    <h3 id={headingId}>{t.completeTitle(target.resource.name)}</h3>
    <p className="field-hint">{t.completeNote}</p>
    <p role="note" className="configuration-secret-hint field-hint">{i18n.t.assignments.secretsWarning}</p>
    {revision.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
    {revision.isError ? <InlineAlert tone="danger" title={describeError(revision.error, i18n)} /> : null}
    {revision.data ? <div className="assignment-grid-fields">{revision.data.variables.map(variable =>
      <div className="configuration-field" key={variable.name}>
        <label htmlFor={`${headingId}-${variable.name}`}><code className="technical-value">{variable.name}</code></label>
        <input id={`${headingId}-${variable.name}`} className="technical-input" value={values[variable.name] ?? ''} maxLength={4096}
          autoComplete="off" spellCheck={false} placeholder={variable.defaultValue ?? ''}
          onChange={event => setValues(current => ({ ...current, [variable.name]: event.target.value }))} />
        <span className="field-hint">{variable.defaultValue !== null ? i18n.t.assignments.usingDefault(variable.defaultValue)
          : variable.required ? i18n.t.assignments.requiredNoDefault : i18n.t.assignments.notSet}</span>
      </div>)}</div> : null}
    {complete.isError ? <InlineAlert tone="danger" title={describeError(complete.error, i18n)} /> : null}
    <div className="configuration-editor-actions">
      <button type="button" className="secondary-button" onClick={onDone}>{t.cancel}</button>
      <button type="submit" className="primary-button" disabled={complete.isPending || !revision.data}>
        {complete.isPending ? t.completing : t.completeSave}</button>
    </div>
  </form>
}

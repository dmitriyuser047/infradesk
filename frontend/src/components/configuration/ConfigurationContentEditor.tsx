import { useId, useRef, useState, type ReactNode } from 'react'
import { CheckCircle2, CircleAlert, Copy, Plus, Trash2 } from 'lucide-react'

import { useValidateConfiguration } from '../../api/configurations'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import {
  ConfigurationValueTypes,
  type ConfigurationContentRequest,
  type ConfigurationDiagnostic,
  type ConfigurationValidationResponse,
  type ConfigurationValueType,
  type ConfigurationVariable,
} from '../../types/configuration'
import { InlineAlert, WorkspaceSection } from '../layout/WorkspacePrimitives'

interface VariableRow {
  key: number
  name: string
  type: ConfigurationValueType
  required: boolean
  defaultValue: string
  description: string
}

/** The content of a revision as the server takes it: empty optional fields are left out. */
export function contentRequest(template: string, rows: readonly Omit<VariableRow, 'key'>[]): ConfigurationContentRequest {
  return {
    template,
    variables: rows.map(row => ({
      name: row.name.trim(), type: row.type, required: row.required,
      ...(row.defaultValue !== '' ? { defaultValue: row.defaultValue } : {}),
      ...(row.description.trim() !== '' ? { description: row.description.trim() } : {}),
    })),
  }
}

/** A finding in words: what is wrong, and where when the server said where. */
export function describeDiagnostic(diagnostic: ConfigurationDiagnostic, i18n: ReturnType<typeof useI18n>): string {
  const t = i18n.t.configurations
  const message = t.diagnostics[diagnostic.code]?.(diagnostic.variableName ?? '') ?? t.unknownDiagnostic
  return diagnostic.line !== null && diagnostic.column !== null ? `${t.location(diagnostic.line, diagnostic.column)}: ${message}` : message
}

/**
 * The template and its variables, as one editable piece of content, checked by the server — the
 * authority — before it is saved. A template is plain text: nothing typed here is ever executed,
 * and the preview is rendered text shown back, nothing more.
 */
export function ConfigurationContentEditor({ organizationId, initialTemplate = '', initialVariables = [], submitLabel,
  pendingLabel, pending, onSubmit, refused, before }: {
  organizationId: string
  initialTemplate?: string
  initialVariables?: readonly ConfigurationVariable[]
  submitLabel: string
  pendingLabel: string
  pending: boolean
  onSubmit: (content: ConfigurationContentRequest) => void
  /** Findings of a save the server refused, shown like a validation. */
  refused?: ConfigurationDiagnostic[] | null
  /** Fields that belong to the same form, above the content: a new profile's name and code. */
  before?: ReactNode
}) {
  const i18n = useI18n()
  const t = i18n.t.configurations
  const nextKey = useRef(initialVariables.length)
  const templateId = useId()
  const [template, setTemplate] = useState(initialTemplate)
  const [rows, setRows] = useState<VariableRow[]>(() => initialVariables.map((variable, key) => ({
    key, name: variable.name, type: variable.type, required: variable.required,
    defaultValue: variable.defaultValue ?? '', description: variable.description ?? '',
  })))
  const [result, setResult] = useState<ConfigurationValidationResponse | null>(null)
  const [copied, setCopied] = useState<string | null>(null)
  const validate = useValidateConfiguration(organizationId)

  // Any change makes the last answer stale: it must not keep saying "valid".
  const changed = () => { setResult(null); validate.reset() }
  const update = (key: number, change: Partial<VariableRow>) => {
    setRows(current => current.map(row => row.key === key ? { ...row, ...change } : row))
    changed()
  }
  const content = () => contentRequest(template, rows)
  const runValidation = () => validate.mutate(content(), { onSuccess: setResult })
  const copy = async (name: string) => {
    try {
      await navigator.clipboard.writeText(`{{ ${name} }}`)
      setCopied(name)
    } catch { setCopied(null) }
  }
  const errors = result && !result.valid ? result.diagnostics : refused ?? []
  const unused = new Set((result?.diagnostics ?? []).filter(item => item.code === 'CONFIGURATION_VARIABLE_UNUSED').map(item => item.variableName))

  return <form className="configuration-editor" noValidate onSubmit={event => { event.preventDefault(); if (!pending) onSubmit(content()) }}>
    {before}
    <WorkspaceSection title={t.template} description={t.templateHelp}>
      <label className="visually-hidden" htmlFor={templateId}>{t.template}</label>
      <textarea id={templateId} className="configuration-template-input" value={template} spellCheck={false} autoCapitalize="off"
        autoCorrect="off" wrap="off" onChange={event => { setTemplate(event.target.value); changed() }}
        onKeyDown={event => {
          // Tab indents inside the template instead of leaving it; Escape then Tab still leaves.
          if (event.key !== 'Tab' || event.shiftKey || event.altKey || event.ctrlKey || event.metaKey) return
          event.preventDefault()
          const input = event.currentTarget
          const start = input.selectionStart
          const next = `${template.slice(0, start)}  ${template.slice(input.selectionEnd)}`
          setTemplate(next)
          changed()
          requestAnimationFrame(() => { input.selectionStart = input.selectionEnd = start + 2 })
        }} />
      <p className="field-hint configuration-secret-hint" role="note">{t.secretsHint}</p>
    </WorkspaceSection>

    <WorkspaceSection title={t.variables} actions={<button type="button" className="secondary-button" onClick={() => {
      setRows(current => [...current, { key: nextKey.current++, name: '', type: 'STRING', required: true, defaultValue: '', description: '' }])
      changed()
    }}><Plus aria-hidden size={16} />{t.addVariable}</button>}>
      {rows.length === 0 ? <p className="muted-copy">{t.noVariables}</p> : <div className="table-scroll"><table className="data-grid configuration-variables">
        <thead><tr><th scope="col">{t.variableColumns.name}</th><th scope="col">{t.variableColumns.type}</th>
          <th scope="col">{t.variableColumns.required}</th><th scope="col">{t.variableColumns.default}</th>
          <th scope="col">{t.variableColumns.description}</th><th scope="col"><span className="visually-hidden">{t.variableColumns.actions}</span></th></tr></thead>
        <tbody>{rows.map((row, index) => <tr key={row.key}>
          <td><input className="technical-input" value={row.name} aria-label={t.variableName(index + 1)} spellCheck={false}
            onChange={event => update(row.key, { name: event.target.value })} />
            {unused.has(row.name.trim()) ? <small className="cell-secondary">{t.unused}</small> : null}</td>
          <td><select value={row.type} aria-label={t.variableType(index + 1)} onChange={event => {
            const type = event.target.value as ConfigurationValueType
            update(row.key, { type, defaultValue: type === 'BOOLEAN' && !['', 'true', 'false'].includes(row.defaultValue) ? '' : row.defaultValue })
          }}>{ConfigurationValueTypes.map(type => <option key={type} value={type}>{t.types[type]}</option>)}</select></td>
          <td><input type="checkbox" checked={row.required} aria-label={t.variableRequired(index + 1)}
            onChange={event => update(row.key, { required: event.target.checked })} /></td>
          <td>{row.type === 'BOOLEAN'
            ? <select value={row.defaultValue} aria-label={t.variableDefault(index + 1)} onChange={event => update(row.key, { defaultValue: event.target.value })}>
              <option value="">{t.noDefault}</option><option value="true">true</option><option value="false">false</option></select>
            : <input className="technical-input" value={row.defaultValue} aria-label={t.variableDefault(index + 1)}
              inputMode={row.type === 'INTEGER' ? 'numeric' : undefined} spellCheck={false}
              onChange={event => update(row.key, { defaultValue: event.target.value })} />}</td>
          <td><input value={row.description} aria-label={t.variableDescription(index + 1)} maxLength={1000}
            onChange={event => update(row.key, { description: event.target.value })} /></td>
          <td className="configuration-variable-actions">
            <button type="button" className="icon-button" disabled={!row.name.trim()} title={t.copyPlaceholder(row.name.trim())}
              aria-label={t.copyPlaceholder(row.name.trim())} onClick={() => void copy(row.name.trim())}><Copy aria-hidden size={15} /></button>
            <button type="button" className="icon-button" title={t.removeVariable(row.name.trim())} aria-label={t.removeVariable(row.name.trim())}
              onClick={() => { setRows(current => current.filter(item => item.key !== row.key)); changed() }}><Trash2 aria-hidden size={15} /></button>
          </td>
        </tr>)}</tbody>
      </table></div>}
      {copied ? <p className="visually-hidden" role="status">{t.copied}</p> : null}
    </WorkspaceSection>

    <div className="configuration-editor-actions">
      <button type="button" className="secondary-button" disabled={validate.isPending} onClick={runValidation}>
        {validate.isPending ? t.validating : t.validate}</button>
      <button type="submit" className="primary-button" disabled={pending}>{pending ? pendingLabel : submitLabel}</button>
    </div>

    {validate.isError ? <InlineAlert tone="danger" title={describeError(validate.error, i18n)} /> : null}
    {result?.valid ? <div className="configuration-result" role="status">
      <p className="configuration-result-title status-success-text"><CheckCircle2 aria-hidden size={16} />{t.valid}</p>
      <p className="muted-copy">{t.referenced(result.referencedVariables.length)}</p>
    </div> : null}
    {errors.length > 0 ? <div className="configuration-result configuration-result-error" role="alert">
      <p className="configuration-result-title"><CircleAlert aria-hidden size={16} />{result ? t.invalid : t.validateFirst}</p>
      <ul className="configuration-diagnostics">{errors.map((item, index) =>
        <li key={`${item.code}-${item.variableName}-${item.line}-${item.column}-${index}`}>{describeDiagnostic(item, i18n)}</li>)}</ul>
    </div> : null}
    {result?.valid && result.renderedPreview !== null ? <section className="configuration-preview" aria-label={t.preview}>
      <h3>{t.preview}</h3>
      <pre className="configuration-code" tabIndex={0}>{result.renderedPreview}</pre>
      <p className="field-hint">{t.previewNote}</p>
    </section> : null}
    {result?.valid && result.previewError ? <p className="field-hint" role="status">{result.previewError.code === 'CONFIGURATION_VALUE_INVALID'
      ? t.previewInvalid(result.previewError.variableName) : t.previewUnavailable(result.previewError.variableName)}</p> : null}
  </form>
}

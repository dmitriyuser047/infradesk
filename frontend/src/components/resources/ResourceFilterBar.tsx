import { RotateCcw, Search, X } from 'lucide-react'

import { useI18n } from '../../i18n'
import { resourcePresentationRegistry } from './presentation/resourcePresentations'
import { isResourceFilterActive, noResourceFilter, type ResourceConditionFilter, type ResourceFilterCriteria } from './resourceFilter'

/**
 * Search, type and state over the loaded resources. The type choices come from the registered
 * presentations, so a new resource type appears here by being registered.
 */
export function ResourceFilterBar({ criteria, onChange, onRefresh }: {
  criteria: ResourceFilterCriteria
  onChange: (next: ResourceFilterCriteria) => void
  onRefresh: () => void
}) {
  const i18n = useI18n()
  const t = i18n.t.resources.filter
  const types = [{ value: 'ALL', label: t.all }, ...resourcePresentationRegistry.list().map(presentation =>
    ({ value: presentation.code, label: t.types[presentation.code] ?? presentation.label(i18n) }))]
  const conditions: { value: ResourceConditionFilter; label: string }[] = [
    { value: 'ALL', label: t.all }, { value: 'RUNNING', label: t.running }, { value: 'INACTIVE', label: t.inactive },
  ]

  return <div className="filter-bar resource-filter-bar">
    <div className="search-field">
      <Search className="search-field-icon" aria-hidden size={16} />
      <input type="search" aria-label={t.search} placeholder={t.searchPlaceholder} value={criteria.query}
        maxLength={200} autoComplete="off" spellCheck={false}
        onChange={event => onChange({ ...criteria, query: event.target.value })}
        onKeyDown={event => { if (event.key === 'Escape' && criteria.query !== '') { event.preventDefault(); onChange({ ...criteria, query: '' }) } }} />
      {criteria.query !== '' ? <button type="button" className="search-field-clear" aria-label={t.clearSearch}
        onClick={() => onChange({ ...criteria, query: '' })}><X aria-hidden size={14} /></button> : null}
    </div>
    <Segmented name="resource-type" label={t.type} options={types} value={criteria.type}
      onChange={type => onChange({ ...criteria, type })} />
    <Segmented name="resource-condition" label={t.condition} options={conditions} value={criteria.condition}
      onChange={condition => onChange({ ...criteria, condition })} />
    <div className="filter-bar-actions">
      {isResourceFilterActive(criteria) ? <button className="text-button" type="button" onClick={() => onChange(noResourceFilter)}>
        <RotateCcw aria-hidden size={14} />{t.reset}</button> : null}
      <button className="secondary-button" type="button" onClick={onRefresh}>{i18n.t.common.refresh}</button>
    </div>
  </div>
}

/** A group of radio buttons drawn as segments: arrow keys move between them, as for any radio group. */
function Segmented<T extends string>({ name, label, options, value, onChange }: {
  name: string
  label: string
  options: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return <fieldset className="segmented" aria-label={label}>
    {options.map(option => <label key={option.value} className={`segmented-option ${value === option.value ? 'selected' : ''}`}>
      <input type="radio" name={name} value={option.value} checked={value === option.value} onChange={() => onChange(option.value)} />
      {option.label}
    </label>)}
  </fieldset>
}

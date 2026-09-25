import { describe, expect, it } from 'vitest'

import { createI18n } from '../../../i18n'
import { containerStatusPresentation } from './containerStatusPresentation'

describe('container status presentation', () => {
  it('uses the existing Docker state to choose a semantic tone', () => {
    const en = createI18n('en')
    expect(containerStatusPresentation('running', en)).toEqual({ label: 'Running', tone: 'success' })
    expect(containerStatusPresentation('EXITED', en)).toEqual({ label: 'Stopped', tone: 'danger' })
    expect(containerStatusPresentation('dead', en).tone).toBe('danger')
    expect(containerStatusPresentation('paused', en).tone).toBe('warning')
    expect(containerStatusPresentation('created', en).tone).toBe('info')
    expect(containerStatusPresentation(null, en)).toEqual({ label: 'Unknown', tone: 'neutral' })
  })

  it('translates known states and shows an unknown state as Docker reported it', () => {
    const ru = createI18n('ru')
    expect(containerStatusPresentation('running', ru)).toEqual({ label: 'Работает', tone: 'success' })
    expect(containerStatusPresentation('exited', ru).label).toBe('Остановлен')
    expect(containerStatusPresentation('removing', ru)).toEqual({ label: 'removing', tone: 'neutral' })
  })
})

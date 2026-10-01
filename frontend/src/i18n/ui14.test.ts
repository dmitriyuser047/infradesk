import { describe, expect, it } from 'vitest'
import { createI18n } from './index'

const ru = createI18n('ru').t
const en = createI18n('en').t

describe('UI-1.4 resource terminology', () => {
  it.each([
    [1, '1 ранее обнаруженный ресурс больше не найден'],
    [2, '2 ранее обнаруженных ресурса больше не найдены'],
    [4, '4 ранее обнаруженных ресурса больше не найдены'],
    [5, '5 ранее обнаруженных ресурсов больше не найдены'],
    [11, '11 ранее обнаруженных ресурсов больше не найдены'],
    [21, '21 ранее обнаруженный ресурс больше не найден'],
    [101, '101 ранее обнаруженный ресурс больше не найден'],
  ])('declines %i previously discovered resources', (count, expected) => {
    expect(ru.infrastructure.inactiveCount(count as number)).toBe(expected)
  })

  it('uses singular and plural in English', () => {
    expect(en.infrastructure.inactiveCount(1)).toBe('1 previously discovered resource is no longer found')
    expect(en.infrastructure.inactiveCount(2)).toBe('2 previously discovered resources are no longer found')
  })

  it.each([
    [1, '1 сервер', '1 контейнер'],
    [2, '2 сервера', '2 контейнера'],
    [5, '5 серверов', '5 контейнеров'],
    [21, '21 сервер', '21 контейнер'],
  ])('declines %i active servers and containers', (count, server, container) => {
    expect(ru.infrastructure.resourceTypeCount('NODE', count as number)).toBe(server)
    expect(ru.infrastructure.resourceTypeCount('CONTAINER', count as number)).toBe(container)
  })

  it('shares no-longer-found terminology across resource surfaces', () => {
    for (const t of [ru, en]) {
      expect(t.resources.page.inactive).toBe(t.integrationInventory.goneOnly)
      expect(t.integrationInventory.gone).toBe(t.integrationInventory.goneOnly)
      expect(t.infrastructure.noActiveIncidentsDetail).toBe(t.overview.noAttentionDetail)
      expect(t.incidents.empty.OPEN.detail).toBe(t.overview.noAttentionDetail)
    }
  })
})

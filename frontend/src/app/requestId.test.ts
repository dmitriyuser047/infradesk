import { describe, expect, it, vi } from 'vitest'

import { createRequestId } from './requestId'

const UuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('createRequestId', () => {
  it('uses the native randomUUID when the context provides it', () => {
    const randomUUID = vi.fn(() => '123e4567-e89b-42d3-a456-426614174000' as `${string}-${string}-${string}-${string}-${string}`)
    const getRandomValues = vi.fn()
    expect(createRequestId({ randomUUID, getRandomValues } as unknown as Crypto)).toBe('123e4567-e89b-42d3-a456-426614174000')
    expect(randomUUID).toHaveBeenCalledOnce()
    expect(getRandomValues).not.toHaveBeenCalled()
  })

  it('falls back to getRandomValues where randomUUID is missing, as over plain HTTP', () => {
    const source = { getRandomValues: <T extends ArrayBufferView>(array: T) => crypto.getRandomValues(array) }
    const random = vi.spyOn(Math, 'random')
    const ids = Array.from({ length: 200 }, () => createRequestId(source))
    for (const id of ids) {
      expect(id).toMatch(UuidV4)
      expect(id[14]).toBe('4')
      expect('89ab').toContain(id[19])
    }
    expect(new Set(ids).size).toBe(ids.length)
    expect(random).not.toHaveBeenCalled()
    random.mockRestore()
  })

  it('sets the version and variant bits whatever the random bytes are', () => {
    for (const fill of [0x00, 0xff]) {
      const id = createRequestId({ getRandomValues: <T extends ArrayBufferView>(array: T) => {
        new Uint8Array(array.buffer).fill(fill)
        return array
      } })
      expect(id).toMatch(UuidV4)
    }
    expect(createRequestId({ getRandomValues: <T extends ArrayBufferView>(array: T) => {
      new Uint8Array(array.buffer).fill(0)
      return array
    } })).toBe('00000000-0000-4000-8000-000000000000')
  })
})

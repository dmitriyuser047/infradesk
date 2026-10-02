import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { en } from './en'
import { ru } from './ru'

// Read the backend contract so new ProfileApply steps also require translations.
const provisioningSource = readFileSync(
  new URL('../../../src/main/scala/domain/provisioning/ProvisioningRun.scala', import.meta.url), 'utf8',
)
const stepDefinitions = new Map(
  [...provisioningSource.matchAll(/case object (\w+) extends ProvisioningStepKind \{ val code = "([A-Z0-9_]+)"/g)]
    .map((match) => [match[1], match[2]]),
)
const profileApply = provisioningSource.match(/val ProfileApply = List\(([^)]+)\)/)?.[1]
if (!profileApply) throw new Error('Backend ProfileApply step list was not found')
const stepKinds = profileApply.split(',').map((name) => {
  const code = stepDefinitions.get(name.trim())
  if (!code) throw new Error(`Backend step code was not found for ${name.trim()}`)
  return code
})

describe.each([['ru', ru], ['en', en]] as const)('%s server profile step labels', (_locale, dictionary) => {
  it.each(stepKinds)('provides a user-facing label for %s', (kind) => {
    const labels: Record<string, string> = dictionary.serverProfiles.runSteps
    expect(labels).toHaveProperty(kind)
    expect(labels[kind].trim()).not.toBe('')
    expect(labels[kind]).not.toBe(kind)
  })
})

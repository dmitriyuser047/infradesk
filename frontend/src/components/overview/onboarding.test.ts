import { describe, expect, it } from 'vitest'

import { needsOnboarding, onboardingState } from './onboarding'

const nothing = { projects: 0, environments: 0, connections: 0, synchronized: false, resources: 0 }

describe('first-run onboarding', () => {
  it('starts with the organization done and asks for a project next', () => {
    const state = onboardingState(nothing)

    expect(state.steps.map(step => [step.id, step.done])).toEqual([
      ['organization', true], ['project', false], ['environment', false],
      ['connection', false], ['sync', false], ['monitoring', false],
    ])
    expect(state.next?.id).toBe('project')
    expect(state.next?.permission).toBe('manageWorkspace')
  })

  it('derives each next step from what already exists', () => {
    expect(onboardingState({ ...nothing, projects: 1 }).next?.id).toBe('environment')
    expect(onboardingState({ ...nothing, projects: 1, environments: 2 }).next?.id).toBe('connection')
    expect(onboardingState({ ...nothing, projects: 1, environments: 2, connections: 1 }).next).toMatchObject({
      id: 'sync', permission: 'runConnectionSync',
    })
    expect(onboardingState({ ...nothing, projects: 1, environments: 2, connections: 1, synchronized: true }).next).toBeNull()
  })

  it('never blocks on an unknown environment count while it loads', () => {
    expect(onboardingState({ ...nothing, projects: 1, environments: null }).next?.id).toBe('environment')
  })

  it('is shown only for a whole organization without discovered infrastructure', () => {
    expect(needsOnboarding(true, { resources: 0 })).toBe(true)
    expect(needsOnboarding(true, { resources: 3 })).toBe(false)
    expect(needsOnboarding(false, { resources: 0 })).toBe(false)
  })
})

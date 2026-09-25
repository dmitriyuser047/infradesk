import type { OrganizationPermission } from '../auth/authorization'

/**
 * First-run setup, derived from facts the application already has. Nothing about it is stored:
 * a step is done when the thing it asks for exists.
 */
export type OnboardingStepId = 'organization' | 'project' | 'environment' | 'connection' | 'sync' | 'monitoring'

export interface OnboardingFacts {
  projects: number
  /** Null while the environments of the projects are still loading. */
  environments: number | null
  connections: number
  /** At least one connection has completed a synchronization. */
  synchronized: boolean
  resources: number
}

export interface OnboardingStep {
  id: OnboardingStepId
  done: boolean
  /** The capability needed to perform the step; null when there is nothing to perform. */
  permission: OrganizationPermission | null
}

export interface OnboardingState {
  steps: OnboardingStep[]
  /** The first step that is not done yet, if any. */
  next: OnboardingStep | null
  completed: number
}

export function onboardingState(facts: OnboardingFacts): OnboardingState {
  const steps: OnboardingStep[] = [
    { id: 'organization', done: true, permission: null },
    { id: 'project', done: facts.projects > 0, permission: 'manageWorkspace' },
    { id: 'environment', done: (facts.environments ?? 0) > 0, permission: 'manageWorkspace' },
    { id: 'connection', done: facts.connections > 0, permission: 'manageConnections' },
    { id: 'sync', done: facts.synchronized || facts.resources > 0, permission: 'runConnectionSync' },
    // Monitoring is configured per server, so it can only start once servers exist.
    { id: 'monitoring', done: false, permission: null },
  ]
  const next = steps.find(step => !step.done && step.id !== 'monitoring') ?? null
  return { steps, next, completed: steps.filter(step => step.done).length }
}

/** The guide replaces the empty summary while the organization has no discovered infrastructure. */
export function needsOnboarding(scopeIsOrganization: boolean, facts: Pick<OnboardingFacts, 'resources'>): boolean {
  return scopeIsOrganization && facts.resources === 0
}

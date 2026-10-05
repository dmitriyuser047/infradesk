/** Only submission identity is saved; plans, remote output and credentials stay out of storage. */
export type PendingSubmission = { planId: string; requestId: string }
export function readPendingSubmission(key: string): PendingSubmission | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) ?? 'null')
    if (value && typeof value.planId === 'string' && value.planId.length > 0 &&
      typeof value.requestId === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.requestId))
      return { planId: value.planId, requestId: value.requestId }
  } catch { /* Storage can be unavailable; the mounted flow retains its identity. */ }
  return null
}
export function storePendingSubmission(key: string, value: PendingSubmission | null): void {
  try {
    if (value) sessionStorage.setItem(key, JSON.stringify(value))
    else sessionStorage.removeItem(key)
  } catch { /* Storage can be unavailable; the mounted flow retains its identity. */ }
}

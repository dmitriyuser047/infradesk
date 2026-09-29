import { useEffect, useState } from 'react'

/** How long a skeleton may stand alone before the page says the wait is unusual. */
export const SlowPendingMillis = 5000

/**
 * True once a load has been pending for longer than `afterMs`. A quick load keeps its skeleton; a
 * slow one can then say so and offer a retry instead of showing unlabelled bars indefinitely.
 */
export function useSlowPending(pending: boolean, afterMs: number = SlowPendingMillis): boolean {
  const [slow, setSlow] = useState(false)
  useEffect(() => {
    if (!pending) { setSlow(false); return }
    const timer = window.setTimeout(() => setSlow(true), afterMs)
    return () => window.clearTimeout(timer)
  }, [pending, afterMs])
  return pending && slow
}

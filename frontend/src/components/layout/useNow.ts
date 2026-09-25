import { useEffect, useState } from 'react'

/**
 * The current time, advanced every `intervalMs` by one timer for the whole component that asks.
 * Lists that show "ongoing for …" read it once and pass it down, instead of a timer per row.
 */
export function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs])
  return now
}

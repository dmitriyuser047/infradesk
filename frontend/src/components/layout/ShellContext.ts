import { createContext } from 'react'

// Keep the frame identity stable when AppShell is hot-reloaded and lazy pages remain mounted.
export const ShellContext = createContext(false)

import { createContext, useContext } from 'react'

/** Opens the declare dialog; undefined for roles that may not declare (`can(role, 'respond')`). */
export const DeclareContext = createContext<(() => void) | undefined>(undefined)

export const useDeclare = () => useContext(DeclareContext)

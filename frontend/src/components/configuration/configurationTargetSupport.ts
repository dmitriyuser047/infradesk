/**
 * Which resource types can receive a configuration assignment today: nodes only. The backend holds
 * the same rule and is the authority; this only decides what the pages offer.
 */
export function supportsConfigurationAssignment(resourceTypeCode: string | undefined): boolean {
  return resourceTypeCode === 'NODE'
}

/** The same rule the backend enforces, mirrored only to answer early: a normalized absolute path. */
export function isTargetPath(path: string): boolean {
  if (path.length === 0 || path.length > 4096 || !path.startsWith('/') || path.endsWith('/')) return false
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/.test(path)) return false
  return path.slice(1).split('/').every(segment => segment !== '' && segment !== '.' && segment !== '..')
}

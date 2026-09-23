/**
 * Which resource types monitoring applies to today: metrics are collected and monitor rules are
 * evaluated for nodes only. This is a feature rule, not a display rule, so it lives with the
 * monitoring feature instead of in the resource presentation contract.
 */
export function supportsResourceMonitoring(resourceTypeCode: string | undefined): boolean {
  return resourceTypeCode === 'NODE'
}

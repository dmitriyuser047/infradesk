/** Which resources can receive the read-only server baseline check. */
export function supportsProvisioning(resourceTypeCode: string | undefined): boolean {
  return resourceTypeCode === 'NODE'
}

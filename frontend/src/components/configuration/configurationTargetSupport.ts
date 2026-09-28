/**
 * Which resource types can receive a configuration assignment today: nodes only. The backend holds
 * the same rule and is the authority; this only decides what the pages offer.
 */
export function supportsConfigurationAssignment(resourceTypeCode: string | undefined): boolean {
  return resourceTypeCode === 'NODE'
}

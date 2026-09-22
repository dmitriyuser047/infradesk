export function ConnectionStatusBadge({ active }: { active: boolean }) {
  return (
    <span className={`connection-status connection-status-${active ? 'active' : 'inactive'}`}>
      {active ? 'Active' : 'Inactive'}
    </span>
  )
}

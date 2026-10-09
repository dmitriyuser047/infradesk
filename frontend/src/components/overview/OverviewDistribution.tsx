import { ArrowUpRight } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { OverviewSummaryResponse } from '../../types/overview'
import { WorkspaceSection } from '../layout/WorkspacePrimitives'

const ringWidth = 12
/** Share of the ring, out of 100, left empty between two segments. */
const segmentGap = 1.6

/** Unobserved states remain visible; totals never imply that the remaining objects are healthy. */
export function distributionRemainder(total: number, counts: readonly number[]): number {
  return Math.max(0, total - counts.reduce((sum, count) => sum + count, 0))
}

type Segment = { label: string; count: number; tone: 'success' | 'danger' | 'warning' | 'neutral' }

export function OverviewDistribution({ summary, resourcesPath, connectionsPath }: {
  summary: OverviewSummaryResponse
  resourcesPath: string
  connectionsPath: string
}) {
  const i18n = useI18n()
  const t = i18n.t.overview.distribution
  const { nodes, containers, connections } = summary
  const groups = [
    { id: 'servers', title: t.servers, total: nodes.total, path: resourcesPath, segments: [
      { label: t.online, count: nodes.online, tone: 'success' },
      { label: t.offline, count: nodes.offline, tone: 'danger' },
      { label: t.unknown, count: distributionRemainder(nodes.total, [nodes.online, nodes.offline]), tone: 'neutral' },
    ] },
    { id: 'containers', title: t.containers, total: containers.total, path: resourcesPath, segments: [
      { label: t.running, count: containers.running, tone: 'success' },
      { label: t.stopped, count: containers.stopped, tone: 'warning' },
      { label: t.otherState, count: distributionRemainder(containers.total, [containers.running, containers.stopped]), tone: 'neutral' },
    ] },
    { id: 'connections', title: t.connections, total: connections.total, path: connectionsPath, segments: [
      { label: t.synchronized, count: connections.healthy, tone: 'success' },
      { label: t.failed, count: connections.failing, tone: 'danger' },
      { label: t.notSynced, count: connections.neverSynced, tone: 'warning' },
      { label: t.unknown, count: distributionRemainder(connections.total, [connections.healthy, connections.failing, connections.neverSynced]), tone: 'neutral' },
    ] },
  ] satisfies { id: string; title: string; total: number; path: string; segments: Segment[] }[]

  return <section className="overview-distributions" aria-label={t.title}>
    {groups.map(group => {
      let offset = 0
      const visible = group.segments.filter(segment => segment.count > 0)
      // Neighbouring segments are separated by a gap so each stays readable; a single one is a full ring.
      const gap = visible.length > 1 ? segmentGap : 0
      return <WorkspaceSection key={group.id} title={group.title} className="distribution-card"
        actions={<Link className="distribution-open" to={group.path} aria-label={i18n.t.overview.openItem(group.title)}>
          <ArrowUpRight size={17} aria-hidden /></Link>}>
        <div className="distribution-body">
          <div className="distribution-ring">
            <svg viewBox="0 0 120 120" aria-hidden="true" focusable="false">
              <circle className="distribution-track" cx="60" cy="60" r="48" fill="none" strokeWidth={ringWidth} />
              {visible.map(segment => {
                const length = group.total > 0 ? segment.count / group.total * 100 : 0
                const start = offset
                offset += length
                const drawn = Math.max(length - gap, 0.5)
                return <circle key={segment.label} className={`distribution-stroke distribution-${segment.tone}`}
                  cx="60" cy="60" r="48" fill="none" strokeWidth={ringWidth} pathLength="100"
                  strokeDasharray={`${drawn} ${100 - drawn}`} strokeDashoffset={-(start + gap / 2)} transform="rotate(-90 60 60)" />
              })}
            </svg>
            <div className="distribution-total"><strong>{i18n.format.number(group.total)}</strong><span>{t.total}</span></div>
          </div>
          <dl className="distribution-legend">
            {group.segments.map(segment => <div key={segment.label}>
              <dt><span className={`distribution-dot distribution-${segment.tone}`} aria-hidden />{segment.label}</dt>
              <dd>{i18n.format.number(segment.count)}</dd>
            </div>)}
          </dl>
        </div>
        <p className="distribution-note">{group.total === 0 ? t.empty : t.snapshot}</p>
      </WorkspaceSection>
    })}
  </section>
}

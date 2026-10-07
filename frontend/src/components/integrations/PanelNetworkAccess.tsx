import { useI18n } from '../../i18n'
import type { PanelSourceEvidence } from '../../types/nodeOnboarding'

export function PanelNetworkAccess({ source }: { source: PanelSourceEvidence }) {
  const { locale } = useI18n(); const ru = locale === 'ru'
  return <section><h4>{ru ? 'Доступ Remnawave Panel к Node' : 'Remnawave Panel access to Node'}</h4>
    {source.confidence === 'UNRESOLVED' ? <p role="alert">{ru
      ? 'InfraDesk не смог автоматически определить, с какого адреса Remnawave Panel подключается к Node. Проверьте снова или откройте расширенные настройки сети.'
      : 'InfraDesk could not determine a network source for Remnawave Panel. Check again or open advanced network settings.'}</p>
      : <><p>{source.mode === 'AUTO' ? ru ? 'Адреса Panel определены автоматически. Это кандидаты; подключение ещё не подтверждено.' : 'Panel addresses were resolved automatically. These are candidates; connectivity is not confirmed yet.'
        : ru ? 'Ручная настройка исходящих адресов Panel.' : 'Manual Panel network sources.'}</p>
        <ul>{source.sources.map(value => <li key={value}>{source.mode === 'MANUAL' ? value : value.split('/')[0]}</li>)}</ul>
        <p>{ru ? 'InfraDesk разрешит подключение только с этих адресов.' : 'InfraDesk will allow connections only from these addresses.'}</p></>}
    <details><summary>{ru ? 'Показать технические данные' : 'Show technical details'}</summary>
      <p>{source.sources.join(', ') || '—'}</p><p>{ru ? 'Метод' : 'Method'}: {source.method}</p><p>{ru ? 'Статус' : 'Status'}: {source.confidence}</p>
    </details>
  </section>
}

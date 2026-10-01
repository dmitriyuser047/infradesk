# UI-7 — Final consistency pass

UI-1—UI-6 приняты как завершённая база. Это точечная доводка текущего интерфейса. AGENTS.md и config.toml/worker.config.toml учтены: основной агент изучил архитектуру и определил решения, worker внёс реализацию; основной агент проверил diff, отправил точечные замечания и завершил полный прогон после лимита worker.

## Изменения

1. **Все изменённые файлы** перечислены ниже.
2. **«Ресурсы» в навигации:** resources.title → «Серверы»; resources.section и resources.treeLabel → «Серверы и контейнеры»; resources.page.back → «Серверы». Это затрагивает ResourcesIndexPage, EnvironmentPage, ResourceTree и Server Detail back link. workspace.openResources/openResourcesOf → «Открыть серверы»/«Открыть серверы окружения …». Route `/resources` и внутренние Resource identifiers сохранены.
3. **Context Switcher:** имя организации / имя проекта / имя окружения, например «InfraDesk / Test Project / Тестовое окружение», если такие имена присутствуют в данных. Те же имена используются в select options и связанных headers/aria-label. Навигация и сброс scope не менялись.
4. **Display name fallback:** первый непустой `displayName → name → code`. UUID/id не участвует; при отсутствии данных используется существующий локализованный placeholder. Текущие DTO содержат name/code; новый API field не добавлялся. Если name само является техническим кодом и другого имени в данных нет, человекочитаемое имя не придумывается.
5. **Integration card:** локальные gap/padding и paragraph margins уменьшены по существующим tokens; provider и base URL объединены в компактную строку с wrapping. Inventory выводится независимо от lastSync: «2 ноды · 2 хоста · 2 профиля конфигурации». Недоступные counts опускаются; nodes-only вариант проверен. Sync status и относительное время выводятся отдельно. Open primary и существующий overflow сохранены.
6. **Notification actions:** «Отправить тест» — primary; «Изменить» и «Включить/Выключить» — в существующем PageActionMenu. Delete не добавлен. Permissions, pending states, mutation handlers, cache behavior и test delivery не изменены.
7. **Edit notification back:** общий WorkspaceHeader back link «← Каналы уведомлений» над заголовком. Правая дублирующая ссылка удалена; routes и query scope сохранены.
8. **Bot token copy:** «Показать токен бота» / «Скрыть токен бота»; EN «Show bot token» / «Hide bot token». Field label, password/text reveal behavior, value handling и secret storage сохранены.
9. **Incidents empty state:** только локальное уменьшение padding через incidents-page selector. Текст, icon/tone, фильтры, refresh и структура сохранены.
10. **Смысл «Источников»:** ResourceContextResponse.sourceConnections содержит InfraDesk Connections. SourceConnectionLinks ведёт в connectionPath; PostgresInfrastructureContextQuery.sourcesOf выбирает connection через external_ref.connection_id. Это не общий список integration/discovery providers.
11. **Итоговый source label:** «Подключения · N» / «Connections · N», поскольку текущая модель и содержимое списка — именно подключения. Подтверждено RU/EN тестами без изменения source model.
12. **Remnawave Nodes density:** изменений нет; desired/actual state и «Изменить правило» сохранены. Конкретного безопасного дефекта плотности не выявлено.
13. **I18n keys:** workspace.openResources, workspace.openResourcesOf, resources.title, resources.section, resources.treeLabel, resources.page.back, infrastructure.sourcesCount; integrationInventory.listSummary заменён на inventorySummary; добавлены notifications.showBotToken/hideBotToken. RU/EN синхронизированы; RU plurals используют существующий Intl.PluralRules helper.
14. **Tests:** AppShell — displayName для всех трёх scopes и code-only fallback; navigationPresentation — приоритет имени и отсутствие id fallback; EnvironmentPage/routesSmoke/resourcePageRendering — новые titles/section/back; ResourcePage — Connections/source links и RU label; IntegrationsPage/IntegrationDetailPage — независимые summary/status/open/overflow и nodes-only counts; notifications — hierarchy, lifecycle/cache, общий back, RU/EN copy и reveal behavior; OrganizationPage — связанные navigation labels; i18n/ui14 — plural forms и пропуск отсутствующих counts.
15. **Frontend tests:** полный suite — **71 файл, 599 passed, 0 failed**.
16. **Production build:** **успешно**, TypeScript + Vite. Backend testFull также завершился успешно: **916 passed, 18 skipped, 0 failures/errors**; backend не менялся, использован существующий task cache. git diff --check без ошибок.
17. **Границы:** backend/API/DB/domain/routes/business logic/permissions не изменены. Не добавлены dependencies, API calls или новые routes. Overview, sidebar, logo, цветовая/типографическая/spacing/status/table/responsive системы, Server Detail и Connections architecture сохранены. UI-8 не начинался.

## Copy / enum audit

Оставшиеся «Ресурсы» относятся к смешанным обнаруженным объектам, loading/search empty states, счётчикам, assignments и вкладке объектов внутри Connections, поэтому сохранены по контексту. Прочие точные устаревшие строки из списка задания не найдены в рабочем RU frontend. Известные sync statuses, REMOTE_DRIFT, server/container types отображаются через существующие presentation mappings; internal enums не переименовывались.

Полный browser QA в UI-7 не выполнялся; результаты выше относятся к code review, component tests и сборке.

## Изменённые файлы
- `docs/ui7-report.md`
- `frontend/src/app/routesSmoke.test.tsx`
- `frontend/src/components/layout/AppShell.test.tsx`
- `frontend/src/components/layout/ContextSwitcher.tsx`
- `frontend/src/components/navigation/navigationPresentation.test.ts`
- `frontend/src/components/navigation/navigationPresentation.ts`
- `frontend/src/components/resources/presentation/resourcePageRendering.test.tsx`
- `frontend/src/i18n/en.ts`
- `frontend/src/i18n/ru.ts`
- `frontend/src/i18n/ui14.test.ts`
- `frontend/src/pages/EnvironmentPage.test.tsx`
- `frontend/src/pages/IncidentsPage.tsx`
- `frontend/src/pages/IntegrationDetailPage.test.tsx`
- `frontend/src/pages/IntegrationsPage.test.tsx`
- `frontend/src/pages/IntegrationsPage.tsx`
- `frontend/src/pages/NotificationChannelFormPage.test.tsx`
- `frontend/src/pages/NotificationChannelFormPage.tsx`
- `frontend/src/pages/NotificationChannelsPage.test.tsx`
- `frontend/src/pages/NotificationChannelsPage.tsx`
- `frontend/src/pages/OrganizationPage.test.tsx`
- `frontend/src/pages/OrganizationPage.tsx`
- `frontend/src/pages/ResourcePage.test.tsx`
- `frontend/src/pages/ResourcesIndexPage.tsx`
- `frontend/src/styles/pages/incidents.css`
- `frontend/src/styles/pages/integrations.css`

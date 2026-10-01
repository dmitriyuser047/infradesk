# UI-6: итог изменений и проверки

UI-1—UI-5 приняты как существующая база. Commit включает их ранее незафиксированные изменения. AGENTS.md прочитан после git pull. Основной агент определил подход, делегировал реализацию worker, проверил diff и потребовал исправления.

## Представление

1. Tokens: focus-ring использует primary; добавлены sidebar-on-hover, overlay-backdrop, control-height-compact (32px), status-height-compact (20px), status-height-default (24px). Неопределённых CSS-переменных нет.
2. Typography: существующие title/section/body/caption tokens сохранены; компактные статусы используют caption 12px.
3. Spacing: уведомления и обычные элементы терминала используют шкалу 4/8/12/16/20/24px, общие радиусы и тени.
4. Buttons: icon buttons внутри data-grid — 32px; существующая Primary/Secondary/Ghost/Danger hierarchy сохранена.
5. Status indicators: StatusIndicator поддерживает small/default. Текст и маркер сохранены; семантика тонов и counters UI-3—UI-5 не менялась.
6. Tables: компактные badges и icon controls; общий ритм таблиц сохранён.
7. Forms: контрастный focus-visible для input/select/textarea; согласованные отступы уведомлений; submit logic не менялась.
8. Alerts/empty/loading/error: существующие shared patterns сохранены, альтернативные компоненты не добавлены.
9. Menus/dialogs/tooltips: inset focus для menu-item; dialogs ограничены viewport с vh/dvh fallback и внутренним скроллом. Context switcher и terminal menu также ограничены viewport. Close/sidebar/account controls получили локализованный title при сохранённом aria-label.
10. Sidebar: архитектура UI-2 сохранена, interactive colors используют tokens. Context semantics и routing UI-6 не менял.
11. Brand: `[ID]` заменён общим декоративным SVG InfraDeskMark — инфраструктурный куб с соединениями. Используется на Login и sidebar; аналогичный SVG добавлен как favicon. Wordmark InfraDesk сохранён.
12. Responsive: сохранены drawer/wrapping/responsive layouts; добавлен scroll высоких dialogs/menus. DOM-тест длинного hostname проверяет сохранение значения и technical class, но не доказывает визуальный layout.
13. Accessibility: декоративный mark aria-hidden/focusable=false; отчётливый keyboard focus; статусы содержат текст. Existing reduced-motion rules сохранены.
14. RU/EN: tooltips используют существующие локализованные ключи; UI-6 не добавляет строки/enums. Ранее подготовленные исправления UI-1—UI-5 входят в общий commit.
15. Hardcoded: белый sidebar hover переведён в token; notification success fallback hex удалён; backdrop и terminal menu shadow заменены tokens. Палитра terminal screen сохранена; самостоятельный favicon содержит собственные цвета.
16. Reusable components: новый InfraDeskMark; обновлены StatusIndicator, AppShell, AccountMenu, IntegrationDialog, MonitorRuleDialog.
17. Tests: обновлены AppShell/WorkspacePrimitives tests; добавлен LoginPage test для shared mark. Tests UI-1—UI-5 также входят в общий commit.

## Проверки и ограничения

- Полный frontend suite: **71 файл, 590 tests passed**.
- Production frontend build: **успешно**, TypeScript + Vite.
- Backend testFull с отдельной PostgreSQL test DB: **916 passed, 18 skipped, 0 failed/errors**. Локально JDK 25; JDK 17 несовместим с HttpClient.close в существующих tests. CI использует JDK 21.
- git diff --check: **без ошибок**. Замечания Git о CRLF не являются ошибками diff.
- Browser console: **полностью не проверена**, подключённый browser tool недоступен. Отсутствие новых warnings/errors не подтверждено.
- Визуально просмотрена реальная страница **Login, 1440×1000**, отрендеренная локальным headless Edge: общий mark и поля отображаются корректно.
- Полный ручной обход Overview, servers/detail, connections/detail, terminal, incidents, notifications, integrations/Remnawave, configurations, projects/environments/context, всех ширин, zoom 200%, keyboard/reduced-motion **не завершён**. Component tests его не заменяют. Acceptance criteria 45–46 остаются неподтверждёнными.
- Dark mode **оставлен за scope**.
- Изменений UI-6 за пределами presentation layer **нет**; tests/docs/favicon — вспомогательные изменения. Backend/API/DB/permissions/business logic/dependencies не менялись.
- Следующий этап автоматически не начинался.


## Файлы непосредственно UI-6

- `frontend/index.html`
- `frontend/public/brand-mark.svg`
- `frontend/src/components/integrations/IntegrationDialog.tsx`
- `frontend/src/components/layout/AccountMenu.tsx`
- `frontend/src/components/layout/AppShell.test.tsx`
- `frontend/src/components/layout/AppShell.tsx`
- `frontend/src/components/layout/InfraDeskMark.tsx`
- `frontend/src/components/layout/WorkspacePrimitives.test.tsx`
- `frontend/src/components/layout/WorkspacePrimitives.tsx`
- `frontend/src/components/monitoring/MonitorRuleDialog.tsx`
- `frontend/src/pages/LoginPage.test.tsx`
- `frontend/src/pages/LoginPage.tsx`
- `frontend/src/styles/components.css`
- `frontend/src/styles/pages/notifications.css`
- `frontend/src/styles/pages/terminal.css`
- `frontend/src/styles/shell.css`
- `frontend/src/styles/tokens.css`

## Все файлы общего commit UI-1—UI-6

- `docs/ui6-report.md`
- `frontend/index.html`
- `frontend/public/brand-mark.svg`
- `frontend/src/components/configuration/ConfigurationAssignmentList.tsx`
- `frontend/src/components/configuration/ConfigurationRules.tsx`
- `frontend/src/components/connections/ConnectionList.tsx`
- `frontend/src/components/connections/ConnectionRow.tsx`
- `frontend/src/components/connections/connectionPresentation.test.ts`
- `frontend/src/components/incidents/IncidentList.test.tsx`
- `frontend/src/components/incidents/IncidentRow.tsx`
- `frontend/src/components/infrastructure/InfrastructureContextPath.tsx`
- `frontend/src/components/infrastructure/ScopedIncidentsPanel.tsx`
- `frontend/src/components/integrations/DesiredStateControls.tsx`
- `frontend/src/components/integrations/IntegrationDialog.test.tsx`
- `frontend/src/components/integrations/IntegrationDialog.tsx`
- `frontend/src/components/integrations/NodeActionControls.tsx`
- `frontend/src/components/integrations/integrationPresentation.ts`
- `frontend/src/components/layout/AccountMenu.tsx`
- `frontend/src/components/layout/AppShell.permissions.test.tsx`
- `frontend/src/components/layout/AppShell.test.tsx`
- `frontend/src/components/layout/AppShell.tsx`
- `frontend/src/components/layout/ContextSwitcher.tsx`
- `frontend/src/components/layout/InfraDeskMark.tsx`
- `frontend/src/components/layout/PageActionMenu.test.tsx`
- `frontend/src/components/layout/PageActionMenu.tsx`
- `frontend/src/components/layout/RefreshWarning.tsx`
- `frontend/src/components/layout/WorkspacePrimitives.test.tsx`
- `frontend/src/components/layout/WorkspacePrimitives.tsx`
- `frontend/src/components/layout/useWorkspaceRouteContext.ts`
- `frontend/src/components/layout/workspaceNavigation.test.ts`
- `frontend/src/components/layout/workspaceNavigation.ts`
- `frontend/src/components/monitoring/MonitorRuleDialog.tsx`
- `frontend/src/components/overview/OverviewActivity.tsx`
- `frontend/src/components/overview/OverviewHealth.tsx`
- `frontend/src/components/overview/overviewActivityPresentation.test.ts`
- `frontend/src/components/overview/overviewActivityPresentation.ts`
- `frontend/src/components/overview/overviewPresentation.test.ts`
- `frontend/src/components/overview/overviewPresentation.ts`
- `frontend/src/components/resources/ResourceFilterBar.tsx`
- `frontend/src/components/resources/ResourceTree.tsx`
- `frontend/src/components/resources/ResourceTreeItem.tsx`
- `frontend/src/components/resources/presentation/NodePresentation.tsx`
- `frontend/src/components/resources/presentation/ResourcePresentation.ts`
- `frontend/src/components/resources/presentation/ResourcePresentationRegistry.test.ts`
- `frontend/src/components/resources/presentation/resourcePageRendering.test.tsx`
- `frontend/src/components/resources/resourceInventoryPresentation.test.ts`
- `frontend/src/components/resources/resourceInventoryPresentation.ts`
- `frontend/src/components/terminal/TerminalPanel.test.tsx`
- `frontend/src/components/terminal/TerminalPanel.tsx`
- `frontend/src/i18n/en.ts`
- `frontend/src/i18n/ru.ts`
- `frontend/src/i18n/ui14.test.ts`
- `frontend/src/pages/ConfigurationAssignmentPages.tsx`
- `frontend/src/pages/ConfigurationEditorPages.tsx`
- `frontend/src/pages/ConfigurationProfilePage.tsx`
- `frontend/src/pages/ConfigurationRulePage.tsx`
- `frontend/src/pages/Configurations.test.tsx`
- `frontend/src/pages/ConfigurationsPage.tsx`
- `frontend/src/pages/ConnectionPage.test.tsx`
- `frontend/src/pages/ConnectionPage.tsx`
- `frontend/src/pages/ConnectionsPage.test.tsx`
- `frontend/src/pages/ConnectionsPage.tsx`
- `frontend/src/pages/EnvironmentPage.test.tsx`
- `frontend/src/pages/EnvironmentPage.tsx`
- `frontend/src/pages/IncidentPage.test.tsx`
- `frontend/src/pages/IncidentPage.tsx`
- `frontend/src/pages/IncidentsPage.test.tsx`
- `frontend/src/pages/IncidentsPage.tsx`
- `frontend/src/pages/IntegrationConfigProfilePage.test.tsx`
- `frontend/src/pages/IntegrationConfigProfilePage.tsx`
- `frontend/src/pages/IntegrationDetailPage.test.tsx`
- `frontend/src/pages/IntegrationDetailPage.tsx`
- `frontend/src/pages/IntegrationsPage.test.tsx`
- `frontend/src/pages/IntegrationsPage.tsx`
- `frontend/src/pages/LoginPage.test.tsx`
- `frontend/src/pages/LoginPage.tsx`
- `frontend/src/pages/NotificationChannelsPage.test.tsx`
- `frontend/src/pages/OverviewPage.test.tsx`
- `frontend/src/pages/OverviewPage.tsx`
- `frontend/src/pages/ResourcePage.test.tsx`
- `frontend/src/pages/ResourcePage.tsx`
- `frontend/src/pages/WorkingScreens.test.tsx`
- `frontend/src/pages/WorkspaceForms.test.tsx`
- `frontend/src/styles/components.css`
- `frontend/src/styles/pages/incidents.css`
- `frontend/src/styles/pages/integrations.css`
- `frontend/src/styles/pages/notifications.css`
- `frontend/src/styles/pages/overview.css`
- `frontend/src/styles/pages/resources.css`
- `frontend/src/styles/pages/terminal.css`
- `frontend/src/styles/shell.css`
- `frontend/src/styles/tokens.css`

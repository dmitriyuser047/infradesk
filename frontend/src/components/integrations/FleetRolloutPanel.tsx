import { useEffect, useState } from 'react'
import { createRequestId } from '../../app/requestId'
import { readPendingSubmission, storePendingSubmission, type PendingSubmission } from '../../app/pendingSubmission'
import { ApiError } from '../../api/httpClient'
import { activeRolloutState, useFleetRollout, useFleetRolloutControl, useFleetRollouts, usePreviewFleetRollout, useRefreshFleet,
  useStartFleetRollout } from '../../api/remnawaveFleets'
import { useI18n } from '../../i18n'
import { EmptyWorkspaceState, InlineAlert, PropertyGrid, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import type { StatusTone } from '../layout/WorkspacePrimitives'
import { IntegrationDialog } from './IntegrationDialog'
import type { FleetMember, FleetRollout, RolloutIssue, RolloutPreview, RolloutScope } from '../../types/remnawaveFleet'

const texts = {
  en: {
    title: 'Rollout', deploy: 'Deploy', detail: 'Roll the desired revision out to the members in controlled waves.',
    none: 'No rollouts yet', previewTitle: 'Plan a rollout', target: 'Target revision', canary: 'Canary nodes',
    waveSize: 'Wave size', automaticRollback: 'Roll back automatically when a wave fails its health check',
    pauseAfterCanary: 'Pause after the canary', makePlan: 'Build plan', start: 'Start rollout', close: 'Close',
    cancel: 'Cancel', planDetail: 'Nothing is changed on any server until you start the rollout.',
    sharedConfig: 'Shared Remnawave configuration', sharedChange: (revision: number, outside: number) =>
      `Revision ${revision} is applied once. Nodes outside this fleet that share it: ${outside}.`,
    sharedNone: 'No shared configuration change', waves: 'Waves', estimate: 'Estimated changes', node: 'Node',
    wave: 'Wave', changes: 'Changes', canaryMark: 'canary', skipped: 'Already compliant', warnings: 'Warnings',
    blockers: 'Blockers', refreshRequired: 'Fresh evidence is needed', refreshDetail: 'Refresh the fleet state and build the plan again.',
    blocked: 'The rollout cannot start', expiresAt: 'The plan is valid until',
    state: 'State', phase: 'Phase', progress: (wave: number, waves: number, done: number, total: number) =>
      `Wave ${wave}/${waves} · ${done}/${total} nodes`,
    inProgress: 'Rollout in progress', pause: 'Pause', resume: 'Resume', rollback: 'Roll back',
    rollbackScope: 'Rollback scope', currentWave: 'Current wave', allCompleted: 'All completed nodes',
    history: 'Rollout history', created: 'Created', open: 'Details', requestError: 'The request could not be completed.',
    pausedGate: 'Paused: a node is compliant but not healthy. Check it, then resume or roll back.',
    pausedCanary: 'Paused after the canary. Check it, then resume or roll back.',
    pausedByOperator: 'Paused by an operator.', pauseRequested: 'Pause requested; the running step will finish first.',
    rollbackRequested: 'Rollback requested.', rollbackIncomplete: 'Rollback did not complete on every node.',
    unknownTitle: 'The outcome is unknown',
    unknownDetail: 'InfraDesk could not confirm the result. No step was retried and nothing was rolled back automatically. Inspect the node and Remnawave manually before starting a new rollout.',
    failedTitle: 'The rollout failed', lastPhase: 'Last phase', failure: 'Reason', childRun: 'Child run',
    members: 'Nodes', actions: 'Steps', expired: 'Expired', refresh: 'Refresh fleet',
    refreshQueued: 'Refresh queued. Wait for fresh evidence, then build the plan again.',
    baseline: 'Baseline compliance / health', rollbackFailure: 'Rollback failure',
    rollbackCapabilities: 'Rollback capabilities', available: 'Available', unavailable: 'Unavailable',
    sharedConsumers: 'Nodes affected by the shared configuration', fleetMembership: 'Fleet membership',
    inFleet: 'Inside this fleet', outsideFleet: 'Outside this fleet', configRevision: 'Configuration revision',
    sharedRollback: 'Shared configuration rollback', sharedRollbackSafe: 'Available when all affected waves are included',
    connected: 'Connected', disconnected: 'Disconnected', enabled: 'Enabled', disabled: 'Disabled',
    pausedEvidence: 'Paused: fresh verification evidence did not arrive. Refresh the fleet and inspect it before resuming.',
    pausedRefresh: 'Paused: evidence is stale. Refresh the fleet, wait for fresh observations, then resume.',
    baselines: { COMPLIANT: 'Compliant', DRIFTED: 'Drifted', UNKNOWN: 'Unknown', BLOCKED: 'Blocked',
      HEALTHY: 'Healthy', DEGRADED: 'Degraded' } as Record<string, string>,
    phases: { VALIDATE: 'Validation', PREPARE_SHARED_CONFIG: 'Prepare shared configuration',
      APPLY_SHARED_CONFIG: 'Apply shared configuration', VERIFY_SHARED_CONFIG: 'Verify shared configuration',
      PREPARE_CANARY: 'Prepare canary', APPLY_CANARY: 'Apply canary', VERIFY_CANARY: 'Verify canary',
      APPLY_WAVES: 'Apply waves', VERIFY_WAVE: 'Verify wave', FINAL_VERIFY: 'Final verification',
      ROLLBACK: 'Rollback', COMPLETE: 'Complete' } as Record<string, string>,
    states: { PLANNED: 'Planned', QUEUED: 'Queued', RUNNING: 'Running', PAUSED: 'Paused', SUCCEEDED: 'Succeeded',
      FAILED: 'Failed', UNKNOWN: 'Unknown', ROLLING_BACK: 'Rolling back', ROLLED_BACK: 'Rolled back' } as Record<string, string>,
    memberStates: { PENDING: 'Pending', RUNNING: 'Running', SUCCEEDED: 'Succeeded', FAILED: 'Failed',
      UNKNOWN: 'Unknown', SKIPPED: 'Skipped', ROLLING_BACK: 'Rolling back', ROLLED_BACK: 'Rolled back' } as Record<string, string>,
    kinds: { SERVER_PROFILE_ASSIGN: 'Assign server profile', SERVER_PROFILE_APPLY: 'Apply server profile',
      NETWORK_FIREWALL: 'Firewall', NODE_PORT: 'Node port', DESIRED_STATE: 'Node state', VERIFY: 'Verify',
      CONFIG_ROLLOUT: 'Shared configuration' } as Record<string, string>,
    issues: {
      REMNAWAVE_FLEET_ROLLOUT_REFRESH_REQUIRED: 'Fresh evidence is required. Refresh the fleet before starting or resuming.',
      REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED: 'The approved conditions changed. Refresh the fleet and build a new plan.',
      REFRESH_REQUIRED: 'Evidence is stale', REMNAWAVE_FLEET_ARCHIVED: 'The fleet is archived',
      NODE_PORT_CHANGE_UNSUPPORTED: 'Changing the node port is not supported',
      REMNAWAVE_FLEET_SHARED_CONFIG_EXTERNAL_DEPENDENCY: 'Nodes outside the fleet depend on the shared configuration and cannot be verified',
      MEMBER_UNKNOWN: 'A node has unknown state', MEMBER_BLOCKED: 'A node is blocked', MEMBER_DEGRADED: 'A node is degraded',
      CANARY_REQUIRED: 'Choose a canary node', NOTHING_TO_DO: 'Nothing to change',
      MEMBER_COMPLIANCE_UNKNOWN: 'Node compliance is unknown', MEMBER_EVIDENCE_INCOMPLETE: 'Node evidence is incomplete',
      LOCAL_INSTALLATION_CHANGE_UNSUPPORTED: 'Changing the local installation is not supported',
      NODE_CONFIG_BINDING_CHANGE_UNSUPPORTED: 'Changing the node configuration binding is not supported',
      NODE_RESOURCE_UNAVAILABLE: 'The node resource is unavailable', NO_TRUSTED_SSH: 'Trusted SSH is required',
      NO_MANAGED_LOCAL_INSTALLATION: 'A managed local installation is required',
      API_CONTRACT_UNCONFIRMED: 'The API contract is unconfirmed', BINDING_INVALID: 'The node binding is invalid',
      UNKNOWN_REMOTE_STATE: 'The remote state is unknown', ACTIVE_CONFLICTING_OPERATION: 'A conflicting operation is active',
      REFERENCED_OBJECT_UNAVAILABLE: 'A referenced object is unavailable',
      INTEGRATION_CONFIG_ROLLOUT_DISABLED: 'Shared configuration rollout is disabled',
      INTEGRATION_CONFIG_PROFILE_INACTIVE: 'The configuration profile is inactive',
      INTEGRATION_CONFIG_PROFILE_NOT_FOUND: 'The configuration profile was not found',
      INTEGRATION_CONFIG_PROFILE_UNAVAILABLE: 'The configuration profile is unavailable',
      INTEGRATION_CONFIG_DEPLOYMENT_ALREADY_RUNNING: 'A configuration deployment is already running',
      INTEGRATION_CONFIG_REMOTE_DRIFT: 'The remote configuration has drifted',
      INTEGRATION_CONFIG_ROLLOUT_REQUIRES_SYNC: 'Configuration synchronization is required',
      INTEGRATION_CONFIG_ALREADY_APPLIED: 'The configuration is already applied',
      INTEGRATION_CONFIG_ROLLOUT_HEALTH_REGRESSION: 'Configuration rollout degraded node health',
      INTEGRATION_CONFIG_ROLLOUT_VERIFICATION_TIMEOUT: 'Configuration verification timed out',
      INTEGRATION_CONFIG_ROLLBACK_FAILED: 'Configuration rollback failed',
      INTEGRATION_CONFIG_ROLLBACK_NOT_APPLIED: 'Configuration rollback was not applied',
      INTEGRATION_CONFIG_ROLLOUT_NOT_APPLIED: 'Configuration rollout was not applied',
      INTEGRATION_CONFIG_ROLLOUT_PLAN_DRIFT: 'The configuration plan has changed',
      INTEGRATION_CONFIG_ROLLOUT_REMOTE_CHANGED: 'The remote configuration changed',
      REMNAWAVE_FLEET_ROLLOUT_REVISION_NOT_DESIRED: 'The revision is no longer desired',
      REMNAWAVE_FLEET_ROLLOUT_INTEGRATION_DISABLED: 'The integration is disabled',
      REMNAWAVE_FLEET_ROLLOUT_NO_MEMBERS: 'The fleet has no nodes',
      REMNAWAVE_FLEET_ROLLOUT_WAVE_SIZE_INVALID: 'The wave size is invalid',
      REMNAWAVE_FLEET_ROLLOUT_MANAGEMENT_MODE_REQUIRED: 'Selected-node management is required',
      REMNAWAVE_FLEET_ROLLOUT_CANARY_INVALID: 'Select eligible canary nodes within the wave size',
      REMNAWAVE_FLEET_ROLLOUT_NOTHING_TO_DO: 'Nothing to change',
      SHARED_CONFIG_AFFECTS_EXTERNAL_NODES: 'The shared configuration also affects nodes outside this fleet',
      SKIPPED_ALREADY_COMPLIANT: 'Already compliant', COVERED_BY_SHARED_CONFIG: 'Covered by shared configuration',
      REMNAWAVE_FLEET_ROLLOUT_SHARED_CONFIG_UNAVAILABLE: 'Shared configuration is unavailable',
      REMNAWAVE_FLEET_ROLLOUT_CHILD_FAILED: 'A child run failed',
      REMNAWAVE_FLEET_ROLLOUT_ACTION_TIMEOUT: 'The step timed out', REMNAWAVE_FLEET_ROLLOUT_ACTION_UNKNOWN: 'The step outcome is unknown',
      REMNAWAVE_FLEET_ROLLOUT_ASSIGNMENT_CHANGED: 'The profile assignment changed',
      REMNAWAVE_FLEET_ROLLOUT_FORWARD_UNCERTAIN: 'The deployment outcome is uncertain',
      REMNAWAVE_FLEET_ROLLOUT_LEASE_LOST: 'The execution lease was lost',
      REMNAWAVE_FLEET_ROLLOUT_NO_PREVIOUS_FIREWALL: 'No previous firewall baseline is available',
      REMNAWAVE_FLEET_ROLLOUT_NO_PREVIOUS_PROFILE: 'No previous server profile is available',
      NO_PREVIOUS_PROFILE: 'No previous server profile is available',
      REMNAWAVE_FLEET_ROLLOUT_FIREWALL_RESULT_UNKNOWN: 'The firewall outcome is unknown',
      REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_TIMEOUT: 'Rollback verification timed out',
      REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_UNKNOWN: 'The rollback verification outcome is unknown',
      REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_FAILED: 'Rollback verification failed',
      REMNAWAVE_FLEET_ROLLBACK_SHARED_SCOPE_UNSAFE: 'The rollback scope does not include every affected wave',
      REMNAWAVE_FLEET_ROLLBACK_SHARED_FORWARD_UNCERTAIN: 'The shared configuration deployment outcome is uncertain',
      REMNAWAVE_FLEET_ROLLBACK_NO_SHARED_BASELINE: 'No previous shared configuration revision is available',
      REMNAWAVE_FLEET_ROLLBACK_SHARED_UNKNOWN: 'The shared configuration rollback outcome is unknown',
      REMNAWAVE_FLEET_ROLLOUT_SOURCE_BUSY: 'The SSH source is busy',
      REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY: 'The node resource is busy',
      REMNAWAVE_FLEET_ROLLOUT_SHARED_CONFIG_BUSY: 'The shared configuration is busy',
      REMNAWAVE_FLEET_ROLLOUT_MEMBER_NOT_COMPLIANT: 'The node does not match the desired revision',
      REMNAWAVE_FLEET_ROLLOUT_NO_TRUSTED_SSH: 'Trusted SSH is required',
      REMNAWAVE_FLEET_ROLLOUT_NO_MANAGED_INSTALLATION: 'A managed installation is required',
      REMNAWAVE_FLEET_ROLLBACK_INCOMPLETE: 'Rollback did not complete on every node',
      REMNAWAVE_FLEET_ROLLOUT_SNAPSHOT_INVALID: 'The rollout snapshot is invalid',
      SERVER_PROFILE_APPLY_FAILED: 'Applying the server profile failed', SERVER_PROFILE_APPLY_UNKNOWN: 'The server profile outcome is unknown',
      INTEGRATION_CONFIG_ROLLOUT_FAILED: 'The shared configuration rollout failed',
      INTEGRATION_CONFIG_ROLLOUT_UNKNOWN: 'The shared configuration outcome is unknown',
      INTEGRATION_CONFIG_ROLLOUT_CANCELLED: 'The shared configuration rollout was cancelled',
      INTEGRATION_CONFIG_ROLLOUT_ROLLED_BACK: 'The shared configuration was rolled back',
    } as Record<string, string>,
  },
  ru: {
    title: 'Раскатка', deploy: 'Развернуть', detail: 'Выкатите желаемую ревизию на узлы управляемыми волнами.',
    none: 'Раскаток пока нет', previewTitle: 'План раскатки', target: 'Целевая ревизия', canary: 'Канареечные узлы',
    waveSize: 'Размер волны', automaticRollback: 'Откатывать автоматически, если волна не прошла проверку здоровья',
    pauseAfterCanary: 'Пауза после канарейки', makePlan: 'Построить план', start: 'Запустить раскатку', close: 'Закрыть',
    cancel: 'Отмена', planDetail: 'Пока вы не запустите раскатку, на серверах ничего не меняется.',
    sharedConfig: 'Общая конфигурация Remnawave', sharedChange: (revision: number, outside: number) =>
      `Ревизия ${revision} применяется один раз. Узлов вне fleet с этой конфигурацией: ${outside}.`,
    sharedNone: 'Общая конфигурация не меняется', waves: 'Волны', estimate: 'Ожидаемых изменений', node: 'Узел',
    wave: 'Волна', changes: 'Изменения', canaryMark: 'канарейка', skipped: 'Уже соответствует', warnings: 'Предупреждения',
    blockers: 'Блокировки', refreshRequired: 'Нужны свежие данные', refreshDetail: 'Обновите состояние fleet и постройте план заново.',
    blocked: 'Раскатку нельзя запустить', expiresAt: 'План действителен до',
    state: 'Состояние', phase: 'Фаза', progress: (wave: number, waves: number, done: number, total: number) =>
      `Волна ${wave}/${waves} · ${done}/${total} узлов`,
    inProgress: 'Идёт раскатка', pause: 'Пауза', resume: 'Продолжить', rollback: 'Откатить',
    rollbackScope: 'Объём отката', currentWave: 'Текущая волна', allCompleted: 'Все завершённые узлы',
    history: 'История раскаток', created: 'Создана', open: 'Подробнее', requestError: 'Не удалось выполнить запрос.',
    pausedGate: 'Пауза: узел соответствует, но нездоров. Проверьте его, затем продолжите или откатите.',
    pausedCanary: 'Пауза после канарейки. Проверьте её, затем продолжите или откатите.',
    pausedByOperator: 'Приостановлено оператором.', pauseRequested: 'Пауза запрошена; текущий шаг завершится.',
    rollbackRequested: 'Откат запрошен.', rollbackIncomplete: 'Откат выполнен не на всех узлах.',
    unknownTitle: 'Результат неизвестен',
    unknownDetail: 'InfraDesk не смог подтвердить результат. Ни один шаг не повторялся и автоматического отката не было. Проверьте узел и Remnawave вручную, прежде чем начинать новую раскатку.',
    failedTitle: 'Раскатка не удалась', lastPhase: 'Последняя фаза', failure: 'Причина', childRun: 'Дочерний запуск',
    members: 'Узлы', actions: 'Шаги', expired: 'Истёк', refresh: 'Обновить fleet',
    refreshQueued: 'Обновление запрошено. Дождитесь свежих данных и постройте план заново.',
    baseline: 'Исходное соответствие / здоровье', rollbackFailure: 'Ошибка отката',
    rollbackCapabilities: 'Возможности отката', available: 'Доступен', unavailable: 'Недоступен',
    sharedConsumers: 'Узлы, затронутые общей конфигурацией', fleetMembership: 'Принадлежность к fleet',
    inFleet: 'Внутри этого fleet', outsideFleet: 'Вне этого fleet', configRevision: 'Ревизия конфигурации',
    sharedRollback: 'Откат общей конфигурации', sharedRollbackSafe: 'Доступен при включении всех затронутых волн',
    connected: 'Подключён', disconnected: 'Отключён', enabled: 'Включён', disabled: 'Выключен',
    pausedEvidence: 'Пауза: свежие данные проверки не поступили. Обновите и проверьте fleet перед продолжением.',
    pausedRefresh: 'Пауза: данные устарели. Обновите fleet, дождитесь свежих наблюдений и продолжите раскатку.',
    baselines: { COMPLIANT: 'Соответствует', DRIFTED: 'Расхождение', UNKNOWN: 'Неизвестно', BLOCKED: 'Заблокировано',
      HEALTHY: 'Здоров', DEGRADED: 'Нездоров' } as Record<string, string>,
    phases: { VALIDATE: 'Валидация', PREPARE_SHARED_CONFIG: 'Подготовка общей конфигурации',
      APPLY_SHARED_CONFIG: 'Применение общей конфигурации', VERIFY_SHARED_CONFIG: 'Проверка общей конфигурации',
      PREPARE_CANARY: 'Подготовка канарейки', APPLY_CANARY: 'Применение канарейки', VERIFY_CANARY: 'Проверка канарейки',
      APPLY_WAVES: 'Применение волн', VERIFY_WAVE: 'Проверка волны', FINAL_VERIFY: 'Финальная проверка',
      ROLLBACK: 'Откат', COMPLETE: 'Завершено' } as Record<string, string>,
    states: { PLANNED: 'Запланирована', QUEUED: 'В очереди', RUNNING: 'Выполняется', PAUSED: 'На паузе', SUCCEEDED: 'Успешно',
      FAILED: 'Ошибка', UNKNOWN: 'Неизвестно', ROLLING_BACK: 'Откат', ROLLED_BACK: 'Откат выполнен' } as Record<string, string>,
    memberStates: { PENDING: 'Ожидает', RUNNING: 'Выполняется', SUCCEEDED: 'Успешно', FAILED: 'Ошибка',
      UNKNOWN: 'Неизвестно', SKIPPED: 'Пропущен', ROLLING_BACK: 'Откат', ROLLED_BACK: 'Откат выполнен' } as Record<string, string>,
    kinds: { SERVER_PROFILE_ASSIGN: 'Назначение серверного профиля', SERVER_PROFILE_APPLY: 'Применение серверного профиля',
      NETWORK_FIREWALL: 'Файрвол', NODE_PORT: 'Порт узла', DESIRED_STATE: 'Состояние узла', VERIFY: 'Проверка',
      CONFIG_ROLLOUT: 'Общая конфигурация' } as Record<string, string>,
    issues: {
      REMNAWAVE_FLEET_ROLLOUT_REFRESH_REQUIRED: 'Нужны свежие данные. Обновите fleet перед запуском или продолжением.',
      REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED: 'Условия согласованного плана изменились. Обновите fleet и постройте новый план.',
      REFRESH_REQUIRED: 'Данные устарели', REMNAWAVE_FLEET_ARCHIVED: 'Fleet в архиве',
      NODE_PORT_CHANGE_UNSUPPORTED: 'Смена порта узла не поддерживается',
      REMNAWAVE_FLEET_SHARED_CONFIG_EXTERNAL_DEPENDENCY: 'От общей конфигурации зависят узлы вне fleet, их нельзя проверить',
      MEMBER_UNKNOWN: 'Состояние узла неизвестно', MEMBER_BLOCKED: 'Узел заблокирован', MEMBER_DEGRADED: 'Узел нездоров',
      CANARY_REQUIRED: 'Выберите канареечный узел', NOTHING_TO_DO: 'Менять нечего',
      MEMBER_COMPLIANCE_UNKNOWN: 'Соответствие узла неизвестно', MEMBER_EVIDENCE_INCOMPLETE: 'Данные узла неполные',
      LOCAL_INSTALLATION_CHANGE_UNSUPPORTED: 'Изменение локальной установки не поддерживается',
      NODE_CONFIG_BINDING_CHANGE_UNSUPPORTED: 'Изменение привязки конфигурации узла не поддерживается',
      NODE_RESOURCE_UNAVAILABLE: 'Ресурс узла недоступен', NO_TRUSTED_SSH: 'Нужен доверенный SSH',
      NO_MANAGED_LOCAL_INSTALLATION: 'Нужна управляемая локальная установка',
      API_CONTRACT_UNCONFIRMED: 'Контракт API не подтверждён', BINDING_INVALID: 'Привязка узла некорректна',
      UNKNOWN_REMOTE_STATE: 'Удалённое состояние неизвестно', ACTIVE_CONFLICTING_OPERATION: 'Выполняется конфликтующая операция',
      REFERENCED_OBJECT_UNAVAILABLE: 'Связанный объект недоступен',
      INTEGRATION_CONFIG_ROLLOUT_DISABLED: 'Раскатка общей конфигурации отключена',
      INTEGRATION_CONFIG_PROFILE_INACTIVE: 'Профиль конфигурации неактивен',
      INTEGRATION_CONFIG_PROFILE_NOT_FOUND: 'Профиль конфигурации не найден',
      INTEGRATION_CONFIG_PROFILE_UNAVAILABLE: 'Профиль конфигурации недоступен',
      INTEGRATION_CONFIG_DEPLOYMENT_ALREADY_RUNNING: 'Развертывание конфигурации уже выполняется',
      INTEGRATION_CONFIG_REMOTE_DRIFT: 'Расхождение удалённой конфигурации',
      INTEGRATION_CONFIG_ROLLOUT_REQUIRES_SYNC: 'Нужна синхронизация конфигурации',
      INTEGRATION_CONFIG_ALREADY_APPLIED: 'Конфигурация уже применена',
      INTEGRATION_CONFIG_ROLLOUT_HEALTH_REGRESSION: 'Раскатка конфигурации ухудшила здоровье узлов',
      INTEGRATION_CONFIG_ROLLOUT_VERIFICATION_TIMEOUT: 'Истекло время проверки конфигурации',
      INTEGRATION_CONFIG_ROLLBACK_FAILED: 'Ошибка отката конфигурации',
      INTEGRATION_CONFIG_ROLLBACK_NOT_APPLIED: 'Откат конфигурации не применён',
      INTEGRATION_CONFIG_ROLLOUT_NOT_APPLIED: 'Раскатка конфигурации не применена',
      INTEGRATION_CONFIG_ROLLOUT_PLAN_DRIFT: 'План конфигурации изменился',
      INTEGRATION_CONFIG_ROLLOUT_REMOTE_CHANGED: 'Удалённая конфигурация изменилась',
      REMNAWAVE_FLEET_ROLLOUT_REVISION_NOT_DESIRED: 'Ревизия больше не является желаемой',
      REMNAWAVE_FLEET_ROLLOUT_INTEGRATION_DISABLED: 'Интеграция отключена',
      REMNAWAVE_FLEET_ROLLOUT_NO_MEMBERS: 'Во fleet нет узлов',
      REMNAWAVE_FLEET_ROLLOUT_WAVE_SIZE_INVALID: 'Неверный размер волны',
      REMNAWAVE_FLEET_ROLLOUT_MANAGEMENT_MODE_REQUIRED: 'Нужен режим управления выбранными узлами',
      REMNAWAVE_FLEET_ROLLOUT_CANARY_INVALID: 'Выберите допустимые канареечные узлы в пределах размера волны',
      REMNAWAVE_FLEET_ROLLOUT_NOTHING_TO_DO: 'Менять нечего',
      SHARED_CONFIG_AFFECTS_EXTERNAL_NODES: 'Общая конфигурация также затрагивает узлы вне fleet',
      SKIPPED_ALREADY_COMPLIANT: 'Уже соответствует', COVERED_BY_SHARED_CONFIG: 'Покрывается общей конфигурацией',
      REMNAWAVE_FLEET_ROLLOUT_SHARED_CONFIG_UNAVAILABLE: 'Общая конфигурация недоступна',
      REMNAWAVE_FLEET_ROLLOUT_CHILD_FAILED: 'Ошибка дочернего запуска',
      REMNAWAVE_FLEET_ROLLOUT_ACTION_TIMEOUT: 'Истекло время выполнения шага', REMNAWAVE_FLEET_ROLLOUT_ACTION_UNKNOWN: 'Результат шага неизвестен',
      REMNAWAVE_FLEET_ROLLOUT_ASSIGNMENT_CHANGED: 'Назначение профиля изменилось',
      REMNAWAVE_FLEET_ROLLOUT_FORWARD_UNCERTAIN: 'Результат развертывания не подтверждён',
      REMNAWAVE_FLEET_ROLLOUT_LEASE_LOST: 'Потеряна аренда выполнения',
      REMNAWAVE_FLEET_ROLLOUT_NO_PREVIOUS_FIREWALL: 'Нет исходных правил файрвола',
      REMNAWAVE_FLEET_ROLLOUT_NO_PREVIOUS_PROFILE: 'Нет предыдущего серверного профиля',
      NO_PREVIOUS_PROFILE: 'Нет предыдущего серверного профиля',
      REMNAWAVE_FLEET_ROLLOUT_FIREWALL_RESULT_UNKNOWN: 'Результат изменения файрвола неизвестен',
      REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_TIMEOUT: 'Истекло время проверки отката',
      REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_UNKNOWN: 'Результат проверки отката неизвестен',
      REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_FAILED: 'Проверка отката не пройдена',
      REMNAWAVE_FLEET_ROLLBACK_SHARED_SCOPE_UNSAFE: 'Объём отката не включает все затронутые волны',
      REMNAWAVE_FLEET_ROLLBACK_SHARED_FORWARD_UNCERTAIN: 'Результат развертывания общей конфигурации не подтверждён',
      REMNAWAVE_FLEET_ROLLBACK_NO_SHARED_BASELINE: 'Нет предыдущей ревизии общей конфигурации',
      REMNAWAVE_FLEET_ROLLBACK_SHARED_UNKNOWN: 'Результат отката общей конфигурации неизвестен',
      REMNAWAVE_FLEET_ROLLOUT_SOURCE_BUSY: 'Источник SSH занят',
      REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY: 'Ресурс узла занят',
      REMNAWAVE_FLEET_ROLLOUT_SHARED_CONFIG_BUSY: 'Общая конфигурация занята',
      REMNAWAVE_FLEET_ROLLOUT_MEMBER_NOT_COMPLIANT: 'Узел не соответствует желаемой ревизии',
      REMNAWAVE_FLEET_ROLLOUT_NO_TRUSTED_SSH: 'Нужен доверенный SSH',
      REMNAWAVE_FLEET_ROLLOUT_NO_MANAGED_INSTALLATION: 'Нужна управляемая установка',
      REMNAWAVE_FLEET_ROLLBACK_INCOMPLETE: 'Откат выполнен не на всех узлах',
      REMNAWAVE_FLEET_ROLLOUT_SNAPSHOT_INVALID: 'Снимок раскатки некорректен',
      SERVER_PROFILE_APPLY_FAILED: 'Не удалось применить серверный профиль', SERVER_PROFILE_APPLY_UNKNOWN: 'Результат применения серверного профиля неизвестен',
      INTEGRATION_CONFIG_ROLLOUT_FAILED: 'Ошибка раскатки общей конфигурации',
      INTEGRATION_CONFIG_ROLLOUT_UNKNOWN: 'Результат общей конфигурации неизвестен',
      INTEGRATION_CONFIG_ROLLOUT_CANCELLED: 'Раскатка общей конфигурации отменена',
      INTEGRATION_CONFIG_ROLLOUT_ROLLED_BACK: 'Общая конфигурация откачена',
    } as Record<string, string>,
  },
}

type Copy = typeof texts['en']

const stateTone = (state: string): StatusTone => state === 'SUCCEEDED' ? 'success'
  : state === 'FAILED' ? 'danger'
    : state === 'UNKNOWN' || state === 'PAUSED' || state === 'ROLLING_BACK' || state === 'ROLLED_BACK' ? 'warning'
      : state === 'RUNNING' || state === 'QUEUED' ? 'info' : 'neutral'

const issueLabel = (copy: Copy, issue: RolloutIssue) =>
  `${copy.issues[issue.code] ?? issue.code}${issue.node ? ` · ${issue.node}` : ''}`

const errorLabel = (copy: Copy, error: unknown) =>
  error instanceof ApiError ? copy.issues[error.code] ?? copy.requestError : copy.requestError

export function FleetRolloutPanel({ organizationId, integrationId, fleetId, desiredRevisionId, members, canControl }: {
  organizationId: string; integrationId: string; fleetId: string; desiredRevisionId: string | null
  members: FleetMember[]; canControl: boolean
}) {
  const i18n = useI18n(); const copy = texts[i18n.locale]
  const list = useFleetRollouts(organizationId, integrationId, fleetId, true)
  const control = useFleetRolloutControl(organizationId, integrationId, fleetId)
  const recoveryStart = useStartFleetRollout(organizationId, integrationId, fleetId)
  const submissionKey = `fleet-rollout:${organizationId}:${integrationId}:${fleetId}`
  const [unresolved, setUnresolved] = useState<PendingSubmission | null>(() => readPendingSubmission(submissionKey))
  const [planning, setPlanning] = useState(false)
  const [openId, setOpenId] = useState<string | null>(null)
  const items = (list.data?.items ?? []).filter(item => !item.expired)
  const expired = (list.data?.items ?? []).filter(item => item.expired)
  const active = items.find(item => activeRolloutState(item.state)) ?? null
  const shown = active ?? items[0] ?? null
  useEffect(() => {
    const found = unresolved && list.data?.items.find(item => item.id === unresolved.planId && item.state !== 'PLANNED')
    if (!found) return
    storePendingSubmission(submissionKey,null); setUnresolved(null); setOpenId(found.id); setPlanning(false)
  }, [unresolved,list.data,submissionKey])

  return <WorkspaceSection title={copy.title} description={copy.detail}
    actions={canControl && desiredRevisionId && !active
      ? <button className="secondary-button" type="button" disabled={!!unresolved} onClick={() => setPlanning(true)}>{copy.deploy}</button>
      : undefined}>
    {control.isError ? <InlineAlert tone="danger" title={copy.requestError}>{errorLabel(copy, control.error)}</InlineAlert> : null}
    {unresolved && !planning ? <InlineAlert tone="warning" title={i18n.t.common.unresolvedSubmission} action={<button type="button" className="secondary-button" disabled={!canControl || !list.isSuccess || recoveryStart.isPending} onClick={() => recoveryStart.mutate(unresolved,{onSuccess:run=>{storePendingSubmission(submissionKey,null);setUnresolved(null);setOpenId(run.id)}})}>{i18n.t.common.recoverSubmission}</button>} /> : null}
    {recoveryStart.isError ? <InlineAlert tone="danger" title={copy.requestError}>{errorLabel(copy,recoveryStart.error)}</InlineAlert> : null}
    {active ? <InlineAlert tone="info" title={copy.inProgress}>{copy.states[active.state]}</InlineAlert> : null}
    {shown ? <RolloutCard copy={copy} organizationId={organizationId} integrationId={integrationId}
      fleetId={fleetId} rollout={shown} canControl={canControl} busy={control.isPending}
      onControl={(action, scope) => control.mutate({ id: shown.id, action, scope })} />
      : <EmptyWorkspaceState title={copy.none} detail={copy.detail} />}

    {items.length > 0 ? <WorkspaceSection title={copy.history}>
      <div className="table-scroll"><table className="data-grid integration-grid">
        <thead><tr><th>{copy.created}</th><th>{copy.state}</th><th>{copy.phase}</th><th></th></tr></thead>
        <tbody>{items.map(item => <tr key={item.id}>
          <td>{new Date(item.createdAt).toLocaleString()}</td>
          <td><StatusIndicator label={copy.states[item.state] ?? item.state} tone={stateTone(item.state)} /></td>
          <td>{copy.phases[item.phase] ?? item.phase}</td>
          <td><button className="link-button" type="button" onClick={() => setOpenId(item.id)}>{copy.open}</button></td>
        </tr>)}</tbody></table></div>
      {expired.length > 0 ? <p className="muted">{copy.expired}: {expired.length}</p> : null}
    </WorkspaceSection> : null}

    {planning && desiredRevisionId && canControl && !active ? <PlanDialog copy={copy} organizationId={organizationId}
      integrationId={integrationId} fleetId={fleetId} revisionId={desiredRevisionId} members={members}
      unresolved={unresolved} onUnresolved={setUnresolved} onClose={() => setPlanning(false)} /> : null}
    {openId ? <RolloutDetailDialog copy={copy} organizationId={organizationId} integrationId={integrationId}
      fleetId={fleetId} id={openId} onClose={() => setOpenId(null)} /> : null}
  </WorkspaceSection>
}

function RolloutCard({ copy, organizationId, integrationId, fleetId, rollout, canControl, busy, onControl }: {
  copy: Copy; organizationId: string; integrationId: string; fleetId: string; rollout: FleetRollout
  canControl: boolean; busy: boolean; onControl: (action: 'pause' | 'resume' | 'rollback', scope?: RolloutScope) => void
}) {
  const detail = useFleetRollout(organizationId, integrationId, fleetId, rollout.id)
  const [scope, setScope] = useState<RolloutScope>('CURRENT_WAVE')
  const data = detail.data
  const done = data?.members.filter(m => ['SUCCEEDED', 'SKIPPED', 'ROLLED_BACK'].includes(m.state)).length
  const total = data?.members.length
  const pausedText = rollout.state !== 'PAUSED' ? null
    : rollout.pauseReason === 'PAUSED_HEALTH_GATE' ? copy.pausedGate
      : rollout.pauseReason === 'PAUSED_AFTER_CANARY' ? copy.pausedCanary
        : rollout.pauseReason === 'PAUSED_REFRESH_REQUIRED' ? copy.pausedRefresh
          : rollout.pauseReason === 'PAUSED_EVIDENCE_TIMEOUT' ? copy.pausedEvidence : copy.pausedByOperator
  const activeNow = activeRolloutState(rollout.state)
  const failedMember = data?.members.find(m => m.state === 'UNKNOWN' || m.state === 'FAILED')
  const lastRun = data?.actions.filter(a => a.serverProfileRunId || a.configRolloutId || a.desiredStateActionId).slice(-1)[0]

  return <div className="stack">
    <PropertyGrid columns={2} items={[
      { label: copy.state, value: <StatusIndicator label={copy.states[rollout.state] ?? rollout.state}
        tone={stateTone(rollout.state)} /> },
      { label: copy.phase, value: copy.phases[rollout.phase] ?? rollout.phase },
      { label: copy.waves, value: data ? copy.progress(Math.min(rollout.currentWave + 1, rollout.waveCount),
        rollout.waveCount, done!, total!) : '—' },
    ]} />
    {pausedText ? <InlineAlert tone="warning" title={copy.states.PAUSED}>{pausedText}</InlineAlert> : null}
    {rollout.pauseRequested ? <InlineAlert tone="info" title={copy.pause}>{copy.pauseRequested}</InlineAlert> : null}
    {rollout.rollbackRequested ? <InlineAlert tone="info" title={copy.rollback}>{copy.rollbackRequested}</InlineAlert> : null}
    {rollout.rollbackIncomplete ? <InlineAlert tone="danger" title={copy.rollback}>{copy.rollbackIncomplete}</InlineAlert> : null}
    {data?.members.filter(m => m.rollbackFailureCode).map(m => <InlineAlert key={m.id} tone="danger" title={copy.rollbackFailure}>
      {m.nodeName ?? m.membershipId}: {copy.issues[m.rollbackFailureCode!] ?? m.rollbackFailureCode}
    </InlineAlert>)}
    {rollout.state === 'UNKNOWN' ? <>
      <InlineAlert tone="danger" title={copy.unknownTitle}>{copy.unknownDetail}</InlineAlert>
      <PropertyGrid columns={2} items={[
        { label: copy.lastPhase, value: copy.phases[rollout.phase] ?? rollout.phase },
        { label: copy.node, value: failedMember?.nodeName ?? '—' },
        { label: copy.childRun, value: lastRun?.serverProfileRunId ?? lastRun?.configRolloutId ?? lastRun?.desiredStateActionId ?? '—' },
      ]} />
    </> : null}
    {rollout.state === 'FAILED' ? <InlineAlert tone="danger" title={copy.failedTitle}>
      {copy.failure}: {copy.issues[rollout.failureCode ?? ''] ?? rollout.failureCode ?? '—'}</InlineAlert> : null}

    {canControl && activeNow ? <div className="button-row">
      {rollout.state === 'PAUSED'
        ? <button className="secondary-button" type="button" disabled={busy}
          onClick={() => onControl('resume')}>{copy.resume}</button>
        : rollout.state !== 'ROLLING_BACK'
          ? <button className="secondary-button" type="button" disabled={busy || rollout.pauseRequested}
            onClick={() => onControl('pause')}>{copy.pause}</button> : null}
      {rollout.state !== 'ROLLING_BACK' ? <>
        <select aria-label={copy.rollbackScope} value={scope} onChange={e => setScope(e.target.value as RolloutScope)}>
          <option value="CURRENT_WAVE">{copy.currentWave}</option>
          <option value="ALL_COMPLETED">{copy.allCompleted}</option>
        </select>
        <button className="danger-button" type="button" disabled={busy || rollout.rollbackRequested}
          onClick={() => onControl('rollback', scope)}>{copy.rollback}</button>
      </> : null}
    </div> : null}
  </div>
}

function PlanDialog({ copy, organizationId, integrationId, fleetId, revisionId, members, onClose, onUnresolved, unresolved }: {
  copy: Copy; organizationId: string; integrationId: string; fleetId: string; revisionId: string
  members: FleetMember[]; onClose: () => void; onUnresolved: (value:PendingSubmission|null)=>void; unresolved:PendingSubmission|null
}) {
  const preview = usePreviewFleetRollout(organizationId, integrationId, fleetId)
  const start = useStartFleetRollout(organizationId, integrationId, fleetId)
  const refresh = useRefreshFleet(organizationId, integrationId, fleetId)
  const eligible = members.filter(m => m.assessment?.compliance === 'DRIFTED')
  const [canary, setCanary] = useState<string[]>(eligible[0] ? [eligible[0].membershipId] : [])
  const [waveSize, setWaveSize] = useState(2)
  const [automatic, setAutomatic] = useState(true)
  const [pauseAfter, setPauseAfter] = useState(true)
  const [requestId] = useState(() => createRequestId())
  const submissionKey = `fleet-rollout:${organizationId}:${integrationId}:${fleetId}`
  const optionsKey = JSON.stringify([revisionId, [...canary].sort(), waveSize, automatic, pauseAfter])
  const [previewKey, setPreviewKey] = useState<string | null>(null)
  const result: RolloutPreview | undefined = previewKey === optionsKey && !preview.isPending ? preview.data : undefined
  const names = new Map(members.map(m => [m.membershipId, m.nodeName]))
  const toggle = (id: string) => {
    setPreviewKey(null)
    setCanary(current => current.includes(id) ? current.filter(x => x !== id) : [...current, id])
  }

  return <IntegrationDialog title={copy.previewTitle} size="large" busy={start.isPending} onClose={onClose}
    actions={<>
      <button className="secondary-button" type="button" disabled={start.isPending} onClick={onClose}>{copy.cancel}</button>
      <button className="secondary-button" type="button" disabled={!!unresolved || start.isPending || preview.isPending || waveSize < 1}
        onClick={() => { setPreviewKey(optionsKey); preview.mutate({ revisionId, canaryMemberIds: canary, waveSize,
          automaticRollback: automatic, pauseAfterCanary: pauseAfter }) }}>{copy.makePlan}</button>
      {result?.status === 'READY' ? <button className="primary-button" type="button" disabled={start.isPending}
        onClick={() => { const identity = { planId:result.planId,requestId }; storePendingSubmission(submissionKey,identity)
          start.mutate(identity, { onSuccess: () => {storePendingSubmission(submissionKey,null);onUnresolved(null);onClose()},
          onError: error => { if (!(error instanceof ApiError) || error.status >= 500 || error.status===408) onUnresolved(identity)
            else {storePendingSubmission(submissionKey,null);onUnresolved(null)}
            if (error instanceof ApiError && (error.code === 'REMNAWAVE_FLEET_ROLLOUT_REFRESH_REQUIRED' ||
            error.code === 'REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED')) setPreviewKey(null) },
        }) }}>{copy.start}</button> : null}
    </>}>
    <p className="muted">{copy.planDetail}</p>
    <fieldset><legend>{copy.canary}</legend>
      {eligible.map(m => <label key={m.membershipId} className="checkbox-row">
        <input type="checkbox" checked={canary.includes(m.membershipId)} onChange={() => toggle(m.membershipId)} />
        {m.nodeName}</label>)}
    </fieldset>
    <label>{copy.waveSize}
      <input type="number" min={1} value={waveSize} onChange={e => {
        setPreviewKey(null); setWaveSize(Math.max(1, Number(e.target.value) || 1))
      }} /></label>
    <label className="checkbox-row"><input type="checkbox" checked={automatic}
      onChange={e => { setPreviewKey(null); setAutomatic(e.target.checked) }} />{copy.automaticRollback}</label>
    <label className="checkbox-row"><input type="checkbox" checked={pauseAfter}
      onChange={e => { setPreviewKey(null); setPauseAfter(e.target.checked) }} />{copy.pauseAfterCanary}</label>

    {preview.isError || start.isError || refresh.isError ? <InlineAlert tone="danger" title={copy.requestError}>
      {errorLabel(copy, start.error ?? preview.error ?? refresh.error)}</InlineAlert> : null}
    {result?.status === 'REFRESH_REQUIRED' ? <>
      <InlineAlert tone="warning" title={copy.refreshRequired}>{copy.refreshDetail}</InlineAlert>
      <ul>{result.issues.map((i, n) => <li key={n}>{issueLabel(copy, i)}</li>)}</ul>
      <button className="secondary-button" type="button" disabled={refresh.isPending}
        onClick={() => refresh.mutate(undefined, { onSuccess: () => { setPreviewKey(null); preview.reset() } })}>{copy.refresh}</button>
    </> : null}
    {refresh.isSuccess ? <p className="muted">{copy.refreshQueued}</p> : null}
    {result?.status === 'BLOCKED' ? <>
      <InlineAlert tone="danger" title={copy.blocked}>{copy.blocked}</InlineAlert>
      <ul>{result.issues.map((i, n) => <li key={n}>{issueLabel(copy, i)}</li>)}</ul></> : null}
    {result && result.status !== 'REFRESH_REQUIRED' && result.warnings.length > 0 ? <>
      <InlineAlert tone="warning" title={copy.warnings}>{copy.warnings}</InlineAlert>
      <ul>{result.warnings.map((i, n) => <li key={n}>{issueLabel(copy, i)}</li>)}</ul></> : null}
    {result?.status === 'READY' ? <>
      <PropertyGrid columns={2} items={[
        { label: copy.sharedConfig, value: result.plan.sharedConfig.required
          ? copy.sharedChange(result.plan.sharedConfig.revisionNumber, result.plan.sharedConfig.externalNodes)
          : copy.sharedNone },
        { label: copy.target, value: String(result.plan.revisionNumber) },
        { label: copy.waves, value: String(result.plan.waveCount) },
        { label: copy.estimate, value: String(result.estimatedMutations) },
        { label: copy.expiresAt, value: new Date(result.expiresAt).toLocaleString() },
        ...(result.plan.sharedConfig.required && result.plan.sharedConfig.baselineRevisionNumber !== undefined ? [
          { label: copy.configRevision, value: `${result.plan.sharedConfig.baselineRevisionNumber ?? '—'} → ${result.plan.sharedConfig.revisionNumber}` },
        ] : []),
        ...(result.plan.sharedConfig.required && result.plan.sharedConfig.rollbackSupported !== undefined ? [
          { label: copy.sharedRollback, value: result.plan.sharedConfig.rollbackSupported ? copy.sharedRollbackSafe : copy.unavailable },
        ] : []),
      ]} />
      {result.plan.sharedConfig.required && result.plan.sharedConfig.consumers ? <WorkspaceSection title={copy.sharedConsumers}>
        <div className="table-scroll"><table className="data-grid integration-grid">
          <thead><tr><th>{copy.node}</th><th>{copy.fleetMembership}</th><th>{copy.state}</th></tr></thead>
          <tbody>{result.plan.sharedConfig.consumers.map(node => <tr key={node.inventoryNodeId}>
            <td>{node.nodeName}</td><td>{node.inFleet ? copy.inFleet : copy.outsideFleet}</td>
            <td>{node.disabled ? copy.disabled : copy.enabled} · {node.connected ? copy.connected : copy.disconnected}</td>
          </tr>)}</tbody></table></div>
      </WorkspaceSection> : null}
      <div className="table-scroll"><table className="data-grid integration-grid">
        <thead><tr><th>{copy.wave}</th><th>{copy.node}</th><th>{copy.changes}</th><th>{copy.baseline}</th><th>{copy.rollbackCapabilities}</th></tr></thead>
        <tbody>{result.plan.members.map(m => <tr key={m.membershipId}>
          <td>{m.skipReason ? '—' : m.wave + 1}</td>
          <td>{names.get(m.membershipId) ?? m.nodeName}
            {result.plan.policy.canaryMembershipIds.includes(m.membershipId) ? ` (${copy.canaryMark})` : ''}</td>
          <td>{m.skipReason ? copy.issues[m.skipReason] ?? copy.skipped : m.actions.map(a => copy.kinds[a] ?? a).join(', ')}</td>
          <td>{copy.baselines[m.baselineCompliance] ?? m.baselineCompliance} · {copy.baselines[m.baselineHealth] ?? m.baselineHealth}</td>
          <td>{m.rollbackCapabilities ? <ul>{m.rollbackCapabilities.map(capability => <li key={capability.kind}>
            {copy.kinds[capability.kind] ?? capability.kind}: {capability.supported ? copy.available : copy.unavailable}
            {capability.reason ? ` · ${copy.issues[capability.reason] ?? capability.reason}` : ''}
          </li>)}</ul> : '—'}</td>
        </tr>)}</tbody></table></div>
    </> : null}
  </IntegrationDialog>
}

function RolloutDetailDialog({ copy, organizationId, integrationId, fleetId, id, onClose }: {
  copy: Copy; organizationId: string; integrationId: string; fleetId: string; id: string; onClose: () => void
}) {
  const detail = useFleetRollout(organizationId, integrationId, fleetId, id)
  const data = detail.data
  return <IntegrationDialog title={copy.title} onClose={onClose}
    actions={<button className="secondary-button" type="button" onClick={onClose}>{copy.close}</button>}>
    {!data ? null : <>
      <PropertyGrid columns={2} items={[
        { label: copy.state, value: copy.states[data.state] ?? data.state },
        { label: copy.phase, value: copy.phases[data.phase] ?? data.phase },
        { label: copy.failure, value: copy.issues[data.failureCode ?? ''] ?? data.failureCode ?? '—' },
      ]} />
      <div className="table-scroll"><table className="data-grid integration-grid">
        <thead><tr><th>{copy.wave}</th><th>{copy.node}</th><th>{copy.state}</th></tr></thead>
        <tbody>{data.members.map(m => <tr key={m.id}>
          <td>{m.skipReason ? '—' : m.wave + 1}</td><td>{m.nodeName ?? m.membershipId}</td>
          <td>{copy.memberStates[m.state] ?? m.state}{m.failureCode ? ` · ${copy.issues[m.failureCode] ?? m.failureCode}` : ''}
            {m.rollbackFailureCode ? ` · ${copy.rollbackFailure}: ${copy.issues[m.rollbackFailureCode] ?? m.rollbackFailureCode}` : ''}</td>
        </tr>)}</tbody></table></div>
      <div className="table-scroll"><table className="data-grid integration-grid">
        <thead><tr><th>{copy.actions}</th><th>{copy.state}</th><th>{copy.childRun}</th></tr></thead>
        <tbody>{data.actions.map(a => <tr key={a.id}>
          <td>{a.rollback ? `${copy.rollback}: ` : ''}{copy.kinds[a.kind] ?? a.kind}</td>
          <td>{copy.memberStates[a.state] ?? a.state}{a.failureCode ? ` · ${copy.issues[a.failureCode] ?? a.failureCode}` : ''}</td>
          <td>{a.serverProfileRunId ?? a.configRolloutId ?? a.desiredStateActionId ?? '—'}</td>
        </tr>)}</tbody></table></div>
    </>}
  </IntegrationDialog>
}

# InfraDesk — стандарт разработки и Code Review

## 1. Назначение документа

Этот документ определяет обязательные правила разработки и Code Review проекта InfraDesk.

Каждый разработчик должен ознакомиться с ним до внесения существенных изменений в проект. Каждый reviewer обязан проверять изменения не только на предмет компиляции и прохождения тестов, но и на соответствие архитектуре проекта.

Главная цель — не просто получить работающий код, а сохранить систему:

- предсказуемой;
- расширяемой;
- безопасной;
- производительной;
- устойчивой к сбоям;
- понятной следующему разработчику;
- совместимой с уже существующей архитектурой.

InfraDesk использует современные Scala/Cats Effect/Doobie/http4s/React-подходы, но архитектурно ориентируется в том числе на проверенные принципы Global/GlobalFramework.

Мы заимствуем из Global не устаревшие API, XML/JEXL или `NLong/NString`, а инженерные идеи: разделение контекстов, локализацию бизнес-логики, типизацию поведения, master/detail, read projections, объектные операции, управляемые execution contexts, batch processing, явные транзакционные границы и durable processing.

---

# 2. Основной принцип разработки

Перед тем как писать новый код, необходимо ответить на три вопроса:

1. Есть ли в InfraDesk уже похожая задача?
2. Как эта проблема решается архитектурно в Global/GlobalFramework?
3. Как реализовать тот же принцип современными средствами InfraDesk, не создавая второй параллельный механизм?

Нельзя начинать реализацию с создания нового `Service`, `Manager`, `Helper`, executor, repository или abstraction только потому, что так проще реализовать конкретную задачу.

Сначала необходимо изучить существующую архитектуру.

Предпочтительный порядок источников:

1. текущий код InfraDesk;
2. документация текущего Stage;
3. существующие тесты;
4. архитектурные решения предыдущих Stage;
5. документация Global/GlobalFramework;
6. реальные исходники модулей Global, прежде всего BS и RPL;
7. общие Scala/FP/design patterns.

Если существующий механизм подходит для задачи — его необходимо переиспользовать или аккуратно расширить.

Создание второго механизма для уже решённой задачи считается архитектурной проблемой.

---

# 3. Не писать код до понимания execution route

Перед реализацией необходимо определить полный путь выполнения:

`HTTP/UI → application/use-case → domain decision → repository/query → DB → external system → durable result → API response/UI refresh`

Разработчик должен понимать:

- кто начинает операцию;
- где происходит авторизация;
- где принимается бизнес-решение;
- где начинается транзакция;
- сколько выполняется SQL-запросов;
- где происходит HTTP/SSH/Docker/filesystem I/O;
- что произойдёт при падении между шагами;
- можно ли безопасно повторить операцию;
- кто является владельцем состояния;
- как результат будет восстановлен после рестарта процесса.

Если на эти вопросы нет ответа, реализация ещё не спроектирована.

---

# 4. Соблюдение слоёв

В InfraDesk каждый слой должен иметь определённую ответственность.

## Domain

Domain содержит бизнес-состояния, типы, invariants и чистые решения.

Domain не должен знать о:

- HTTP;
- React;
- PostgreSQL;
- Doobie;
- SSH;
- Docker;
- JSON transport DTO;
- конкретном UI.

Предпочтительно использовать типы и ADT вместо строковых комбинаций и флагов.

Например, состояние операции лучше моделировать как:

`Pending | Running | Succeeded | Failed | Unknown`

а не как несколько `Boolean`.

## Application

Application layer реализует use-case и orchestration.

Именно здесь обычно определяется:

- порядок действий;
- permissions;
- transaction boundary;
- business validation;
- взаимодействие нескольких repository;
- создание durable intent;
- запуск typed external engine;
- audit.

Application должен описывать бизнес-процесс, а не детали SQL.

## Ports

Ports описывают необходимые application layer возможности.

Интерфейс должен выражать бизнес-намерение, а не детали хранения.

Хорошо:

`findActiveFleet(...)`

`claimDue(...)`

`saveAction(...)`

`loadCurrentAssessment(...)`

Плохо:

`executeQuery1(...)`

`doUpdate2(...)`

## Persistence

Persistence отвечает за эффективную реализацию доступа к данным и DB invariants.

Он не должен принимать глобальные архитектурные решения за application layer.

Repository не должен неожиданно:

- выполнять HTTP;
- ходить по SSH;
- делать commit бизнес-операции;
- запускать background job;
- скрыто менять несколько несвязанных aggregate.

## Runtime / workers

Worker является исполнителем уже принятого durable решения.

Worker не должен самостоятельно придумывать новый business intent.

В идеале:

`request/intention → DB → commit → worker claim → external action → durable outcome`

## HTTP layer

HTTP layer:

- декодирует transport input;
- проверяет authentication;
- вызывает application service;
- преобразует domain/application result в HTTP response.

HTTP route не должен содержать SQL и основную бизнес-логику.

## Frontend

Frontend отображает серверное состояние и инициирует операции.

React-компонент не должен повторно реализовывать бизнес-правила backend.

Backend остаётся authoritative source.

---

# 5. Принцип Global: Selection и Representation

В Global выборка отвечает за контекст получения данных и пользовательской операции, а representation определяет конкретное представление данных.

В InfraDesk используется современный эквивалент этого принципа.

Не следует создавать отдельную бизнес-логику только потому, что данные показываются:

- таблицей;
- карточкой;
- dashboard;
- modal;
- detail panel.

Данные и операция должны иметь общий application/query context, а UI должен быть представлением этого контекста.

Следует разделять:

**что получаем**

и

**как показываем**.

Read model не обязан совпадать с domain entity.

Для списка из 500 ресурсов не нужно загружать 500 полных domain aggregate, если экрану требуется всего 8 колонок.

Для этого создаётся projection/query/read model.

---

# 6. Type-driven architecture

Поведение должно по возможности определяться типом, capability или registry.

Необходимо избегать распространения конструкций:

```scala
if (resourceType == "NODE") ...
else if (resourceType == "CONTAINER") ...
else if (resourceType == "SYSTEMD") ...
```

по всему проекту.

Добавление нового resource/provider/type не должно требовать изменения десятков несвязанных файлов.

Предпочтительный подход:

`ResourceDefinition`

`Capability`

`Operation`

`Provider`

`Registry`

`Strategy`

или существующий эквивалент проекта.

Специфическое поведение должно жить максимально близко к типу, которому оно принадлежит.

Это аналог принципа Global, где поведение документа, операции, representation и настройки локализованы вокруг класса, подкласса, типа объекта и selection.

---

# 7. Не создавать generic executor без необходимости

Особенно строго это правило применяется к:

- SSH;
- Docker;
- provisioning;
- deployment;
- integration;
- configuration rollout;
- filesystem mutation.

Если в системе уже существует typed engine для операции, необходимо использовать его.

Нельзя для одной новой функции обходить существующий engine и создавать:

`executeCommand(command: String)`

если уже существует типизированный механизм вроде:

`ProvisioningRun`

`ConfigurationDeployment`

`DesiredState`

`ServerProfile`

и т.п.

Typed engine содержит важные гарантии:

- permissions;
- audit;
- validation;
- retry semantics;
- durable state;
- recovery;
- security;
- observability.

Обход такого engine почти всегда создаёт скрытый второй execution path.

---

# 8. Транзакции

Transaction boundary является частью архитектуры.

Её нельзя определять случайно.

Основное правило:

**транзакция должна охватывать один логически атомарный блок работы с БД.**

Внешнее I/O не должно удерживать DB transaction без очень веской причины.

Нельзя делать:

```text
BEGIN
UPDATE ...
SSH ...
HTTP ...
sleep/poll ...
UPDATE ...
COMMIT
```

Это удерживает connection/locks во время неконтролируемого внешнего действия.

Предпочтительный подход:

```text
BEGIN
validate
persist intent
persist state
COMMIT

external operation

BEGIN
persist outcome
COMMIT
```

Если внешний результат может быть неизвестен, это должно быть отражено состоянием операции.

Repository/helper не должен самостоятельно выполнять commit, если transaction ownership принадлежит вызывающему use-case.

Операция, которая логически должна быть атомарной вместе с Audit/History, должна сохранять их в одной транзакции.

---

# 9. SQL и работа с PostgreSQL

SQL является полноценным инструментом архитектуры, а не последним средством.

Как и в Global, сложную фильтрацию, aggregation, batch operations и read projections выгоднее выполнять средствами СУБД, чем загружать тысячи объектов и фильтровать их в Scala.

Нужно всегда оценивать количество запросов.

Код должен отвечать на вопрос:

**сколько SQL statements выполнится для 1, 10, 100 и 10 000 элементов?**

Если количество запросов линейно растёт там, где его можно сделать фиксированным, вероятен N+1.

Вместо:

```scala
items.traverse(item => repository.find(item.id))
```

необходимо проверить возможность:

```scala
repository.findAll(ids)
```

или dedicated projection.

Batch routing должен маршрутизировать партию данных одной/несколькими set-based операциями, а не выполнять запрос для каждого события.

Для сложных read models допускаются специализированные query/repository методы.

Не нужно искусственно протаскивать каждый SELECT через domain aggregate.

---

# 10. DB invariants должны защищаться БД

Если правило можно надёжно выразить в PostgreSQL — это следует рассмотреть.

Используются:

- `PRIMARY KEY`;
- `FOREIGN KEY`;
- `UNIQUE`;
- partial unique index;
- `CHECK`;
- optimistic version;
- `FOR UPDATE`;
- `SKIP LOCKED`;
- advisory lock;
- CAS update;
- trigger, если invariant невозможно выразить проще.

Проверка только вида:

```scala
if (!exists(...))
  insert(...)
```

может быть некорректна при concurrency.

Необходимо рассматривать сценарий двух одновременных транзакций.

---

# 11. Производительность

Reviewer обязан оценивать не только корректность, но и cost model.

Необходимо смотреть:

- SQL count;
- network calls;
- SSH calls;
- Docker calls;
- serialization;
- количество загружаемых строк;
- объём JSON;
- количество одновременно удерживаемых объектов;
- число parallel fibers;
- connection pool usage.

Для больших объёмов используются:

- batch;
- pagination;
- streaming;
- chunking;
- set-based SQL;
- bounded concurrency.

Нельзя делать unbounded:

```scala
items.parTraverse(...)
```

для неизвестного количества объектов.

Concurrency должна быть ограничена и иметь понятную причину.

---

# 12. Durable operations

Любая продолжительная или потенциально опасная операция должна рассматриваться как workflow, а не как обычный function call.

Примеры:

- provisioning;
- deployment;
- rollout;
- reconciliation;
- node mutation;
- configuration apply;
- notification delivery;
- integration processing.

Durable workflow должен иметь достаточно состояния, чтобы после restart определить:

- что планировалось;
- что уже началось;
- что точно завершилось;
- что можно повторить;
- что повторять опасно;
- какой worker владеет работой.

Состояние только в памяти процесса недостаточно.

---

# 13. Idempotency

Каждая операция, которая может прийти повторно из-за:

- retry клиента;
- network timeout;
- restart;
- worker recovery;
- duplicate message;

должна иметь определённую idempotency semantics.

Нужно ответить:

«Что произойдёт, если этот запрос будет выполнен два раза?»

Для mutation request следует по возможности использовать stable request/idempotency key.

Дочерние операции могут использовать deterministic child IDs/request IDs.

Повторный запрос не должен случайно выполнять одну physical mutation второй раз.

---

# 14. Lease и fencing

Если работу могут выполнять несколько worker/process экземпляров, локального `synchronized`, `Mutex` или `Ref` недостаточно.

Ownership должно быть подтверждено durable storage.

Для claim-based worker используются:

- owner;
- token;
- deadline;
- heartbeat;
- fencing check при каждой записи.

Stale worker после потери lease не должен иметь возможность записать результат.

Обновление должно выглядеть концептуально так:

```sql
UPDATE ...
WHERE id = ?
  AND claim_token = ?
  AND claim_deadline > now()
```

Если update вернул 0 строк, ownership потерян.

---

# 15. UNKNOWN — отдельное состояние

`FAILED` означает, что мы знаем, что операция не выполнена успешно.

`UNKNOWN` означает, что невозможно достоверно определить результат внешней операции.

Это принципиально разные состояния.

Например:

1. отправили SSH/HTTP mutation;
2. удалённая сторона могла её выполнить;
3. соединение оборвалось до получения ответа.

Нельзя автоматически повторять такую mutation.

Иначе можно выполнить destructive action дважды.

В подобных случаях состояние должно стать `UNKNOWN`, а дальнейшее действие должно требовать:

- observation;
- recovery;
- reconciliation;
- либо ручной проверки.

---

# 16. Crash recovery проектируется заранее

Нельзя сначала написать happy path, а потом добавить recovery.

Для каждого многошагового workflow необходимо мысленно оборвать процесс:

- до mutation;
- после записи intent;
- во время external action;
- после external action, но до записи результата;
- после результата;
- при rollback.

После каждого такого crash система должна иметь понятное следующее действие.

Если после рестарта невозможно определить безопасное продолжение, архитектура workflow недостаточна.

---

# 17. Compensation и rollback

Rollback внешней операции не является обычным `transaction.rollback()`.

Это новая операция компенсации.

Она должна:

- иметь собственное durable состояние;
- выполняться только для известных successful forward actions;
- быть idempotent/recoverable;
- иметь собственный failure/unknown outcome;
- использовать тот же typed execution engine, что forward operation.

Нельзя обещать rollback, если исходное состояние не было сохранено.

---

# 18. Ошибки

Ошибка должна находиться на правильном уровне.

Domain/application ошибки должны иметь стабильный machine-readable code.

Например:

`RESOURCE_NOT_FOUND`

`ROLLOUT_PLAN_CHANGED`

`REFRESH_REQUIRED`

`RESOURCE_BUSY`

UI может локализовать сообщение по коду.

Нельзя принимать решения по тексту exception.

Внешняя техническая ошибка не должна бесконтрольно вытекать пользователю вместе с:

- stack trace;
- SQL;
- password;
- token;
- SSH key;
- configuration body.

В логах сохраняется техническая информация, пользователю возвращается безопасное сообщение и стабильный code.

---

# 19. Security

Security проверяется при каждом Code Review отдельно.

Минимум необходимо проверить:

### Authentication

Кто выполняет действие?

### Authorization

Имеет ли пользователь право именно на эту операцию?

Наличие доступа к объекту ещё не означает право его изменять.

### Tenant isolation

Каждый запрос должен быть правильно ограничен organization/tenant.

Полученный от клиента UUID нельзя считать достаточным условием доступа.

### IDOR

Нельзя позволять получить чужой объект простым перебором UUID.

### Secrets

Пароли, private keys, tokens, raw credentials не должны появляться в:

- API response;
- logs;
- audit;
- frontend state;
- exception message.

### SSRF

Пользовательские URL/host необходимо валидировать в соответствии с назначением операции.

### SSH

Host identity должна быть проверена до доверенной аутентификации.

### Shell

Не создавать arbitrary command execution там, где достаточно typed command.

---

# 20. Permissions должны проверяться backend

Скрытая кнопка в UI не является security boundary.

Frontend скрывает недоступное действие для UX.

Backend обязан независимо проверять permission.

Это особенно важно для:

- mutation;
- integrations;
- secrets;
- infrastructure operations;
- deployment;
- administrative actions.

---

# 21. Audit

Существенные действия пользователя должны иметь audit trail.

Audit должен отвечать:

- кто;
- когда;
- над каким объектом;
- какое действие запросил;
- какой результат был получен.

Audit не должен содержать secrets.

Если mutation и audit логически образуют одну атомарную операцию, они должны записываться в одной DB transaction.

---

# 22. Логи и observability

Логи должны помогать восстановить execution route.

Для workflow полезны:

- operation ID;
- request ID;
- organization ID;
- resource ID;
- rollout/run ID;
- child ID;
- stable error code;
- state transition.

Не нужно логировать большие payload без необходимости.

Нельзя логировать secret material.

Для performance-sensitive изменения reviewer должен иметь возможность определить:

- какие SQL выполняются;
- сколько их;
- сколько занимает операция;
- где происходит внешний I/O.

Логи должны объяснять систему, а не создавать шум.

---

# 23. Код должен быть простым

Нельзя путать «умный код» с качественным кодом.

Хороший код читается сверху вниз.

Необходимо избегать:

- чрезмерно сложных type-level конструкций без необходимости;
- длинных цепочек combinator, которые невозможно быстро разобрать;
- вложенных `flatMap` на несколько экранов;
- magic values;
- неожиданных implicit conversions;
- абстракций ради абстракций.

Cats Effect и функциональный стиль используются для:

- контроля effects;
- composition;
- resource safety;
- concurrency;
- error handling.

Они не должны использоваться для демонстрации сложности.

Если простой `for`-comprehension читается лучше — используйте его.

---

# 24. Названия

Имя должно описывать intent.

Хорошо:

`claimDueRollouts`

`loadCurrentAssessment`

`requestRollback`

`markDeliverySucceeded`

Плохо:

`process`

`handle`

`doIt`

`execute2`

`helper`

Метод не должен скрывать масштаб side effects.

Название `find` не должно неожиданно изменять десять таблиц.

Название `validate` не должно выполнять remote mutation.

---

# 25. Размер методов

Метод может быть большим, если он линейно и понятно описывает один use-case.

Не нужно механически дробить метод на множество helper только ради количества строк.

Выносить часть следует, когда она:

- имеет самостоятельный смысл;
- повторяется;
- скрывает техническую деталь;
- упрощает основной execution flow;
- имеет отдельный invariant, который стоит тестировать.

Плохой результат декомпозиции — когда для понимания 30 строк логики нужно прыгать по 15 private methods.

---

# 26. Helpers и abstraction

Новая abstraction должна решать существующую проблему, а не гипотетическую будущую.

Перед созданием helper необходимо проверить:

- нет ли уже такого helper;
- нельзя ли выразить логику существующим API;
- действительно ли abstraction уменьшает complexity;
- не скрывает ли она важные side effects.

Не создавать `Utils.scala`, куда постепенно попадает несвязанная логика.

---

# 27. Null и Option

В Global существуют специальные `N*` null-типы из-за особенностей его runtime.

В InfraDesk этот механизм копировать не требуется.

Используются нормальные Scala-модели:

- `Option`;
- `Either`;
- ADT;
- refined domain states.

`null` не должен использоваться в application/domain коде без необходимости взаимодействия с внешним Java API.

---

# 28. Коллекции

Не создавать промежуточные коллекции без необходимости в performance-sensitive коде.

При этом readability имеет приоритет над микрооптимизацией.

Для больших наборов данных необходимо понимать:

- eager или lazy операция;
- создаётся ли новая коллекция;
- сколько проходов выполняется;
- сколько данных находится в памяти.

Не использовать `.toList`, `.toVector`, `.groupBy` на огромном результате, не оценив стоимость.

---

# 29. External I/O

Любой внешний вызов должен иметь определённую политику:

- timeout;
- retry;
- cancellation;
- error mapping;
- idempotency;
- logging;
- concurrency.

Нельзя использовать бесконечный retry.

Retry разрешён только если операция безопасно повторяема или имеет idempotency mechanism.

Timeout не всегда означает failure внешней операции.

Для mutation timeout может означать `UNKNOWN`.

---

# 30. Frontend

Frontend не должен становиться вторым backend.

React-компоненты должны отвечать преимущественно за:

- presentation;
- user interaction;
- local UI state.

Server state должен управляться единообразно через существующий data-fetching механизм проекта.

После mutation необходимо:

- либо вернуть authoritative server state;
- либо invalidate/refetch нужную projection.

Не нужно вручную воспроизводить сложные state transitions на клиенте, если authoritative state хранится на backend.

Permissions из backend должны определять доступные действия.

Тексты ошибок должны опираться на stable error codes.

---

# 31. Миграции

Flyway migration после попадания в используемую историю считается immutable.

Старую migration нельзя исправлять для новой задачи.

Создаётся следующая additive migration.

При проектировании migration необходимо проверить:

- upgrade существующей БД;
- fresh install;
- constraints;
- indexes;
- existing data;
- rollback/compatibility expectations;
- production backup/restore path.

Schema invariant должен соответствовать application invariant.

---

# 32. Backward compatibility

Перед изменением API/schema/state необходимо проверить, существуют ли:

- сохранённые записи старого формата;
- старые workers;
- deployment предыдущей версии;
- migration upgrade path;
- frontend/backend version overlap.

Особенно внимательно проверяются durable workflow records.

Нельзя менять смысл уже сохранённого state так, что после deploy старые записи невозможно восстановить.

---

# 33. Тесты

Тест должен проверять контракт, а не внутреннюю реализацию.

Для новой функции минимум рассматриваются:

### Happy path

Нормальное выполнение.

### Validation

Некорректный input.

### Permissions

Недостаточные права.

### Tenant isolation

Чужой объект недоступен.

### Idempotency

Повторный request.

### Concurrency

Два параллельных исполнителя.

### Crash/recovery

Если workflow durable.

### External uncertainty

Timeout / disconnect после потенциальной mutation.

### Database invariant

Unique/check/locking behavior.

### Regression

Существующий путь, который мог быть затронут изменением.

Не следует создавать test, который всегда проходит независимо от реальной логики.

---

# 34. Тестировать реальные границы

Если критично поведение PostgreSQL, in-memory mock недостаточен.

Для:

- SQL;
- locking;
- transactions;
- constraints;
- `SKIP LOCKED`;
- Flyway;

нужен PostgreSQL integration test.

Если важно HTTP protocol behavior — нужен HTTP-level test.

Если важно production packaging — нужен production build/smoke test.

Mock полезен для локальной unit logic, но он не доказывает корректность integration boundary.

---

# 35. CI

Локально зелёный build не означает, что изменение принято.

Для закрытия Stage/задачи учитывается push CI конкретного commit SHA.

Необходимо проверить все relevant jobs.

В InfraDesk это может включать:

- backend;
- PostgreSQL integration;
- frontend;
- production build;
- images;
- production topology smoke;
- backup/restore;
- self-hosted Linux jobs.

Reviewer не должен писать «CI green», пока не проверен CI именно рассматриваемого SHA.

---

# 36. Не доверять документации без кода и коду без документации

При Code Review необходимо сравнить:

`requirement → implementation → tests → CI`

Документ может говорить, что функция реализована, но reviewer обязан посмотреть production code.

Тест может называться правильно, но reviewer обязан проверить, что он реально доказывает требуемое поведение.

Комментарий не является реализацией.

Название метода не является гарантией.

---

# 37. Stage scope

Каждый Stage имеет определённую границу.

Нельзя незаметно добавлять:

- соседний Stage;
- «небольшой полезный refactor» всего проекта;
- дополнительный новый engine;
- unrelated UI redesign;
- новую инфраструктурную концепцию.

Если обнаружена архитектурная проблема за пределами scope — её нужно зафиксировать отдельно.

Scope creep усложняет review и повышает regression risk.

---

# 38. Работа с существующим кодом

Перед изменением необходимо найти:

- аналогичный domain type;
- application service;
- repository;
- worker;
- route;
- frontend component;
- tests;
- migration pattern.

Новый код должен выглядеть так, будто его писал автор существующего модуля.

Сохраняются:

- package structure;
- naming;
- error model;
- transaction style;
- repository style;
- test style;
- API style.

Нельзя приносить новый architectural style в один модуль без необходимости.

---

# 39. Комментарии

Комментарий должен объяснять **почему**, а не переписывать код словами.

Хороший комментарий:

> Remote mutation cannot be replayed after an uncertain response; recovery must observe the stored child first.

Плохой:

> Increment counter by one.

Особенно полезны комментарии около:

- fencing;
- unusual SQL;
- recovery;
- security decisions;
- deliberate non-retry;
- compatibility workaround;
- blast-radius protection.

---

# 40. Что считается BLOCKER на Code Review

BLOCKER — изменение нельзя принимать до исправления.

Примеры:

- нарушение tenant isolation;
- auth/permission bypass;
- secret leakage;
- remote I/O внутри долгой DB transaction;
- N+1 на потенциально большом наборе;
- unsafe duplicate mutation;
- отсутствие fencing при distributed worker;
- destructive retry после uncertain result;
- неправильная transaction boundary;
- изменение старой migration;
- обход существующего typed engine;
- потеря recovery после restart;
- race condition, нарушающий invariant;
- возможность записать состояние stale worker;
- отсутствие DB constraint для критичного concurrent invariant;
- Stage scope реализован не полностью;
- тесты не доказывают заявленную безопасность;
- production code расходится с requirement.

---

# 41. SHOULD FIX

SHOULD FIX — архитектура в целом корректна, но есть заметное ухудшение качества.

Примеры:

- дублирование существующего helper;
- слишком сложный метод;
- слабое naming;
- лишний SQL;
- ненужная allocation;
- отсутствующий полезный test;
- неудачное разделение файлов;
- недостаточная observability;
- error message/code оформлены не по стилю проекта.

SHOULD FIX желательно исправлять до merge, если стоимость исправления разумна.

---

# 42. NON-BLOCKER

NON-BLOCKER — предложение, которое может улучшить код, но не влияет на корректность или архитектурную безопасность.

Например:

- небольшое упрощение;
- альтернативное naming;
- косметическая перестановка;
- потенциальный future refactor.

Reviewer обязан отличать личные вкусовые предпочтения от архитектурных требований.

---

# 43. Формат Code Review

Review должен начинаться с наиболее серьёзных проблем.

Предпочтительный формат:

```text
BLOCKER

[file:line] Краткое название

Почему это проблема.
Какой сценарий ломается.
Почему существующие тесты этого не покрывают.
Какой архитектурный подход здесь ожидается.
```

После blockers:

```text
SHOULD FIX
```

затем при необходимости:

```text
NON-BLOCKER
```

В конце reviewer даёт verdict:

- `APPROVE`;
- `APPROVE AFTER FIXES`;
- `CHANGES REQUIRED`;
- `STAGE NOT READY`.

Review без конкретного объяснения риска мало полезен.

---

# 44. Reviewer должен проверять сценарии, а не только строки

Для существенного изменения reviewer мысленно проверяет:

1. обычный запрос;
2. повторный запрос;
3. два запроса одновременно;
4. два worker одновременно;
5. restart процесса;
6. DB exception;
7. network timeout;
8. внешний сервис выполнил mutation, но ответ потерян;
9. пользователь потерял permission;
10. resource изменился между preview и start;
11. старая версия данных осталась после migration;
12. операция выполняется на большой партии объектов.

Если какой-то сценарий приводит к неоднозначному состоянию — его нужно исследовать.

---

# 45. Правило Preview → Start

Для опасных операций preview не является бессрочным разрешением на mutation.

Между preview и start мир мог измениться.

Поэтому перед mutation необходимо повторно проверять критичные preconditions:

- version;
- membership;
- target identity;
- health;
- bindings;
- permissions;
- source;
- dependencies;
- freshness.

Preview описывает ожидаемый plan.

Start подтверждает, что этот plan всё ещё безопасен.

---

# 46. Shared resources и blast radius

При изменении shared resource необходимо смотреть не только target object.

Нужно определить всех consumers.

Например, изменение общей configuration может затронуть несколько nodes вне текущей fleet.

Reviewer должен проверить:

- кто ещё использует resource;
- насколько свежи сведения о consumers;
- есть ли unhealthy consumer;
- можно ли безопасно rollback;
- действительно ли canary изолирует изменение.

Canary не помогает, если само shared изменение применяется глобально.

---

# 47. Основной критерий хорошего решения

Хорошее изменение должно быть:

**локальным** — не размазывает новое поведение по системе;

**типизированным** — invalid states трудно представить;

**атомарным там, где нужна атомарность**;

**durable там, где операция переживает процесс**;

**idempotent там, где возможен повтор**;

**fenced там, где есть несколько исполнителей**;

**set-based там, где обрабатывается много данных**;

**observable** — можно понять, что произошло;

**secure by default**;

**совместимым с существующей архитектурой**;

**покрытым тестами на реальные риски**.

---

# 48. Краткий обязательный чек-лист перед PR

Перед созданием PR автор должен самостоятельно ответить «да» на следующие вопросы.

### Архитектура

- Я нашёл существующие аналоги в InfraDesk.
- Я не создал второй механизм для уже решённой задачи.
- Код находится в правильном слое.
- Специфическое поведение локализовано около своего типа/capability.
- Я не вышел за scope задачи.

### База данных

- Я знаю количество SQL-запросов.
- Нет очевидного N+1.
- Transaction boundary осознанна.
- Внешнее I/O не удерживает DB transaction.
- Concurrent invariants защищены БД или подходящим locking mechanism.

### Workflow

- Повторный request безопасен.
- Crash/restart имеет определённое поведение.
- Distributed worker имеет fencing.
- Uncertain external result не превращается автоматически в FAILED.
- Rollback является явной compensation, если он нужен.

### Security

- Проверены permissions.
- Проверен tenant scope.
- Нет IDOR.
- Secrets не попадают в response/log/audit.
- Нет нового arbitrary command execution.

### Performance

- Batch выполняется batch-способом.
- Concurrency bounded.
- Большие dataset не загружаются полностью без причины.
- Network/SSH/Docker calls подсчитаны.

### Tests

- Есть happy path.
- Есть важные failure paths.
- Проверены concurrency/idempotency там, где они существуют.
- DB semantics проверены на PostgreSQL, если они существенны.
- Existing regression suite проходит.

### Delivery

- Migration новая и корректная.
- Production build проходит.
- Рассматриваемый commit имеет зелёный relevant CI.
- Документация соответствует реальному поведению.

---

# 49. Финальное правило

Не нужно стремиться написать больше кода.

Нужно стремиться внести минимальное изменение, которое полностью решает задачу и естественно продолжает архитектуру системы.

Перед добавлением нового механизма необходимо попытаться использовать существующий.

Перед добавлением нового запроса необходимо проверить, нельзя ли получить данные уже существующим запросом или одной set-based операцией.

Перед добавлением нового состояния необходимо понять жизненный цикл объекта.

Перед retry необходимо доказать idempotency.

Перед parallel execution необходимо доказать безопасность concurrency.

Перед external mutation необходимо понять recovery.

Перед merge необходимо доказать решение тестами и CI.

Код считается качественным не тогда, когда он выглядит сложно или использует много abstractions, а тогда, когда следующий разработчик может понять его execution route, invariants, failure semantics и безопасно продолжить развитие системы.
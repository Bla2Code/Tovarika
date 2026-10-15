# Товарики: кошелёк, AI-операции и история

Читай при изменении цен, trial credits, запуска/завершения AI jobs, бесплатного повтора,
истории, себестоимости или recovery. Здесь описан текущий backend, сверенный с кодом
15 октября 2026; для точечной задачи достаточно этого документа и нужной строки карты кода.
Identity/conversion — [TRIAL.md](TRIAL.md), карточки — [CARD_GENERATION.md](CARD_GENERATION.md),
bitmap edits — [IMAGE_EDITING.md](IMAGE_EDITING.md), transport/secrets — [OPENAI.md](OPENAI.md).

Нормативный API — соседний `tovarika-api-contract`: [billing 1.8](../../../tovarika-api-contract/docs/billing/human.md),
[AI-readable правила](../../../tovarika-api-contract/docs/billing/ai-contract.yaml),
[trial contract](../../../tovarika-api-contract/docs/trial/ai-contract.yaml).
Публичная история включает успешные бесплатные AI-операции, анализ и resize.
Требования API хранятся в контракте, текущая backend-документация — в этих правилах.
Известные расхождения кода с контрактным описанием перечислены в разделе 10.

## 1. Единицы, остатки и границы

- `unit=tovarika_cent`: 100 units = 1 Т, 1 unit = 0,01 Т. Java `long`/`Long`, PostgreSQL
  `BIGINT`; допустимо 0..9007199254740991, включая агрегаты. Проверяй переполнение;
  float/double и legacy `Wallet.balance` для этого расчёта не используются.
- `totalUnits` = grants − debits + compensation. `availableUnits` = total − активные
  денежные резервы; всегда 0 <= available <= total. Reserve уменьшает только available;
  release возвращает available и не является новым начислением.
- `usedUnits` = все AI debits − их внутренние компенсации, без фильтров истории.
  Summary читает wallet/used в одном согласованном snapshot с `asOf`.
- Один финансовый эффект обеспечивает PostgreSQL: wallet guards, уникальные references,
  immutable receipts/ledger и fencing. `CARD_BUSY`, один scheduler или project lock
  не заменяют финансовые гарантии между несколькими проектами/серверами.
- Покупки, checkout, подписки, admin API/UI и отдельный бонус регистрации не реализованы.
  Legacy billing DTO/paths сохранены deprecated и не служат основой нового ядра.

Пример: grant 600 → reserve 400: total 600 / available 200 / used 0.
Успех: 200 / 200 / 400 и один debit; ошибка: 600 / 600 / 0, успешной строки нет.
Внутренняя полная компенсация debit вернёт 600 / 600 / 0, сохранив исходный debit.

## 2. HTTP и зафиксированная цена

Пути относительно `/api/v1`; ответы credit API имеют `Cache-Control: no-store`.

| GET endpoint | Содержание | Доступ |
| --- | --- | --- |
| `/billing/credits/wallet` | `CreditWallet`: total/available, unit, id, updatedAt | Bearer или trial cookie |
| `/billing/credits/summary` | wallet, lifetime net usedUnits, asOf | Bearer или trial cookie |
| `/billing/credits/history` | Страницы grant/debit/free activity | Bearer или trial cookie |
| `/billing/credits/prices` | Активная версия, allowance, право entire, цены | Публичный |

Используется общий `WorkspaceIdentityResolver`: Bearer приоритетен, неверный Bearer → 401
без fallback к cookie. Для User контроллер сначала вызывает `AuthenticationStore.initializeBilling`;
это может лениво выдать единственный grant. GET history также материализует durable activity.
Не считать эти DB-проекции методом `readOnly=true`. Prices не создаёт wallet/grant.

У принятых с accounting gate card generation/edit/CPU resize commands и их GET Job есть
`billing: BillingOperationSnapshot`: operationId, operationCode, priceVersion, amountUnits,
funding (`wallet`, `free_entitlement`, `free_operation`). Это immutable цена при успехе,
а не подтверждение debit. У grandfathered jobs/export snapshot отсутствует; analysis — см. раздел 10.
Reserved/captured/released, provider usage и себестоимость публично не выдаются.

Начальная конфигурация `billing-2026-10-14-v1` из миграции 017:

| Серверный code | Текущая ветка | Units / Т |
| --- | --- | --- |
| `image_generation` | Создание первой/следующей Card | 400 / 4 |
| `image_entire_edit` | Entire и текущий retry failed Card (см. раздел 10) | 0 при свободном праве, иначе 400 / 4 |
| `image_region_edit` | Region и legacy rectangle facade | 200 / 2 |
| `image_erase` | Brush erase | 200 / 2 |
| `image_remove_background` | Оба foreground | 200 / 2 |
| `image_outpaint` | Resize, когда `ResizePlan.aiRequired()` | 400 / 4 |
| `product_analysis` | Бесплатный анализ | 0 |
| `cpu_only` | Fit/pad, crop, outpaint без новых полей | 0 |

Trial allowance начально 1600 units = 16 Т (новая опубликованная версия; прежние grants неизменны). Upload, ручные изменения, draft/preview,
Undo/Redo и export бесплатны; они не создают финансовых операций/строк истории.
Классификация resize выполняется сервером по исходным размерам и плану до reserve.
Неподдерживаемые `regenerateCard` и legacy uploaded masks не являются платными путями.

Прайс меняет разработчик в PostgreSQL: в одной транзакции создать draft версии с reason/author,
заполнить все восемь prices, установить published_at, переключить
`billing_configuration.active_version`. Generation = entire, analysis/CPU = 0;
`free_entire_successes=1`. Триггеры запрещают неполную публикацию и изменение published prices.
`JdbcBillingStore.catalog` читает версию и цены одним SQL snapshot без кеша/restart.
`effective_from` — metadata, автоматической будущей активации нет. Откат — прежний published pointer.
Новая цена/allowance действует только на новые admissions/ещё не выданные grants.

Пример публикации generation/entire по 500 units = 5 Т, остальные цены берутся из активной
версии. Используй новый unique version ID и проверенные значения через штатный DB-доступ:

```sql
BEGIN;
INSERT INTO billing_price_versions
    (id, trial_allowance_units, free_entire_successes, effective_from, change_reason)
VALUES ('billing-example-v2', 600, 1, clock_timestamp(), 'Reviewed price revision');
INSERT INTO billing_prices(version_id, operation_code, amount_units)
SELECT 'billing-example-v2', p.operation_code,
       CASE WHEN p.operation_code IN ('image_generation', 'image_entire_edit') THEN 500
            ELSE p.amount_units END
FROM billing_prices p
JOIN billing_configuration c ON c.active_version = p.version_id AND c.singleton = true;
SELECT operation_code, amount_units FROM billing_prices
WHERE version_id = 'billing-example-v2' ORDER BY operation_code;
UPDATE billing_price_versions SET published_at = clock_timestamp()
WHERE id = 'billing-example-v2' AND published_at IS NULL;
UPDATE billing_configuration SET active_version = 'billing-example-v2' WHERE singleton = true;
COMMIT;
```

Перед commit проверь восемь строк и суммы; после — GET prices и новое stub admission.
Replay/pending capture должны сохранить прежний snapshot. Пример сам по себе не меняет прайс.

## 3. Admission, replay и завершение

Основной мост — `cards/CardJobBilling`; финансовые методы `BillingService` для mutations
требуют caller transaction (`MANDATORY`). Используются один datasource и общий JPA/JDBC
transaction manager. Финансы нельзя отделять от job/result через `REQUIRES_NEW`.

1. Проверить identity, ownership и формат запроса; взять owner guard. Найти domain receipt
   по scope/key, включая converted trial aliases. Replay идёт до новой цены/funds/pause,
   busy и version checks; он возвращает ту же job и прежний snapshot даже после failure.
2. Сравнить SHA-256 digest команды и параметров. Same key + другой payload/target/command →
   `409 IDEMPOTENCY_CONFLICT`. Card generation/edit используют общий финансовый namespace
   `card-image-command`; Undo/Redo имеют отдельные domain receipts с проверкой коллизий.
   Не включать цену, баланс, токен, изменяемого владельца или новый job ID в digest.
   Financial Command хранит digestVersion и поддерживаемые digests; сейчас используется v1.
   Не менять canonical serialization без совместимости (включая прежнее отсутствие mask).
   Legacy generation receipt с null command_digest не получает новый billing задним числом.
3. Только для новой команды `prepare` инициализирует User billing и берёт wallet/entitlement
   guards до domain locks. После domain checks `admit` фиксирует цену, funding и reserve;
   job/Card/snapshots и billing link сохраняются в той же транзакции.
4. Платный reserve равен полной принятой цене. `402 WALLET_INSUFFICIENT_FUNDS` содержит
   details: unit, requiredUnits, availableUnits, operationCode, priceVersion.
   Транзакция откатывается: нет accepted job/placeholder/reserve/provider call;
   ключ можно повторить после появления средств. Бесплатные действия доступны при нуле.
5. Claim/reclaim обновляет domain attempt и финансовый fence. AI и MinIO выполняются
   вне транзакций и wallet locks. Terminal transaction повторно проверяет attempt/status,
   для edit — baseVersion/revision/lastImageJobId; сохраняет валидный Asset/result и outcome
   вместе с `finish`. Несогласованный financial fence откатывает весь terminal result.
6. Финансовое состояние `reserved → captured | released`. При успехе с AI `captured`
   создаёт один debit либо consume бесплатного права. При технической ошибке/invalid image/
   storage failure/version conflict/terminal recovery — release и failure без debit.
   Успех без AI также делает release, сохраняя исходный snapshot и бесплатную activity;
   успешный CPU job поэтому может иметь финансовое состояние `released`.

Новый ключ после failure — новая operation; queue recovery сохраняет старую operation/цену.
Provider HTTP success недостаточен для debit. Stale worker не публикует результат и не меняет
деньги/право. Browser abort/download failure не отменяет committed успех.
`BillingService.compensate` добавляет внутреннюю immutable проводку с unique reference,
суммарно не больше исходного debit. UI/API возврата нет; компенсация скрыта из истории,
уменьшает usedUnits и не восстанавливает бесплатное право.

## 4. Бесплатное право entire

- `billing_entitlements(code='free_entire', subject=<stable Card ID>)` хранит одно право
  на весь срок Card. Scope не зависит от image version, владельца или дня.
  Текущий retry failed Card использует то же право, что entire.
- Admission атомарно выставляет reserved_by; денежного reserve нет. Пока право reserved,
  новая команда получает `409 BILLING_ENTITLEMENT_BUSY`, без автоматического платного fallback.
  Domain `CARD_BUSY` может сработать раньше.
- Только committed успешный AI-result выставляет consumed_by. Failure/no-AI возвращают
  право; stale attempt его не меняет. После consume новый entire оплачивается по generation price.
- Undo/Redo, переименование, paid edits, conversion и compensation не обновляют право.
  У таблицы нет FK к Card, удаление workspace не удаляет финансовый audit.

## 5. Таблицы, блокировки и conversion

Все финансовые таблицы имеют префикс `billing_`. Они не имеют FK к user/trial/workspace,
Product/Project/Card или job и не содержат названий/промптов. Job коррелируется непрозрачной
парой `(job_namespace, job_id)`, domain хранит `project_jobs.billing_operation_id/billing_fence`.

| Группа таблиц | Назначение |
| --- | --- |
| accounts, owners, wallets | Финансовая identity, aliases и guarded total/reserved/used |
| wallet_links, ledger_order | Canonical wallet после merge и изменяемая проекция порядка истории |
| price_versions, prices, configuration | Published прайс и активный pointer |
| operations, reservations, entitlements | Immutable accepted snapshot, fence, reserve и lifetime right |
| ledger, trial_grants | Append-only grant/debit/compensation/free journal и unique grant receipt |
| activity_outbox, history_cursors | Durable success receipt и snapshot пагинации |
| ai_attempts, ai_usage, provider_rates | Отдельные технические вызовы/usage/ставки |

Общий порядок locks: auth owner rows (User, trial rows по ID, только admission/conversion) →
topology advisory `(7204,1)` → billing owner advisory guards (лексикографически) → retained
wallet rows по ID → grant/compensation reference guard при необходимости → entitlement →
operation/fence → domain Product/Project/job/Card/version/asset.
Terminal worker не блокирует auth rows. Не добавлять wallet lock после domain locks.
`CardJobBilling.lock/claim` сначала выбирают кандидата без domain lock, затем берут finance
и domain locks и повторно проверяют job. Финансовая блокировка уже взята к моменту `finish`.

Обычные transitions используют shared topology guard; conversion/merge — exclusive.
`convertOwner` должен быть первым billing call в транзакции: shared → exclusive upgrade
отклоняется. `prepareOwners` берёт aliases до wallet locks; AI/storage под topology lock не вызываются.

Trial — обычный grant, не три попытки. Bootstrap/GET атомарно создают/читают wallet и
единственный grant; User без trial получает один grant при регистрации/OAuth, существующий
ACTIVE User — при login/credit read/новом funded admission. Нет массового backfill/grant job.
`trialAllowance` читает сохранённый grant, смена прайса не начисляет повторно.
Подробная identity/cookie политика и deprecated counters — [TRIAL.md](TRIAL.md).

`AuthenticationStore.convertTrial` переносит finance вместе с auth и Product ownership.
Новый User получает alias того же wallet. При существующем OAuth User с wallet merge
суммирует total/reserved/used под user root; старые streams сохраняются через wallet_links.
Ledger/grants/operation IDs/snapshots/fences не переписываются, pending worker завершает
старую operation под canonical lock. Нового grant при наличии прежнего нет; два ранее выданных
grants независимых identities сохраняются каждый один раз.
Replay учитывает исходные aliases; неоднозначный key/digest даёт conflict, а не первый receipt.
Ядро допускает trusted `originWalletId` для replay, это не публичный owner/auth параметр.
Merge перестраивает ledger_order и инвалидирует cursors обоих wallets; простая conversion
одного wallet сохраняет cursor. Ошибка откатывает finance/auth/workspace вместе.

## 6. Публичная история, включая бесплатные результаты

- `trial_grant`, `ai_debit` имеют положительную amountUnits; `free_operation` — ровно 0
  и operationCode. У debit тоже есть operationCode, у grant его нет.
  У free **нет поля** balanceAfterUnits; у денег это immutable total после проводки.
  `BillingJsonConfiguration` обеспечивает отсутствие null-полей без правки generated DTO.
- Включены успешные AI-операции, анализ и resize, в том числе grandfathered/free entitlement.
  Upload, manual changes, Undo/Redo, export, pending/failed/stale, reserve/release/compensation
  отдельными строками не выдаются. Бесплатные строки не меняют total/available/used;
  net used может отличаться от суммы видимых debits после скрытой compensation.
- `BillingActivity.projectJob/analysisJob` сохраняют receipt в activity_outbox **в той же
  транзакции**, что completed domain result. Это insert без нового wallet lock после domain locks;
  unique reference `project_jobs:<id>` / `analysis_jobs:<id>` защищает от дублей.
- `JdbcBillingStore.history` под wallet guard материализует receipts: связывает с уже
  существующим debit либо добавляет нулевую free_operation. Повторное чтение идемпотентно.
  Success receipt переживает удаление workspace/conversion и работает при accounting=false.
  Миграция 022 backfill-ит сохранившиеся completed jobs с исходным finished_at без retroactive
  debit/grant. Удалённые ранее jobs не восстанавливаются. Для новых resize code берётся из
  фактического aiCalled; старый unbilled outpaint определяется по сохранённой команде.
- Фильтры fromDate/toDate включают московские дни: UTC интервал
  `[from 00:00 Europe/Moscow, (to + 1 день) 00:00 Europe/Moscow)`.
  sort=newest|oldest, limit=1..100 (default 10); неверные даты/cursor → 400 VALIDATION_ERROR.
- Порядок — occurredAt, затем wallet sequence (для free — исходный completion time).
  Cursor — opaque DB ID со сроком 7 дней, связан с wallet/filters/sort/limit/last/upper sequence.
  Новые, в том числе поздно записанные старые, события исключены из продолжения snapshot.
  Hidden events фильтруются до LIMIT; limit+1 определяет hasMore, nextCursor только при hasMore.

## 7. Себестоимость отдельно от пользовательского debit

Workers передают явный `AiCallContext` через provider boundary. `billing_ai_attempts` — одна
строка на фактический transport/stub вызов, а не на job; `billing_ai_usage` хранит компоненты
response/image_tool. Учитываются paid/free/analysis/legacy/failed/stale/stub outcomes.
Provider outcome и job outcome различаются: HTTP success может дать failed/stale результат.

`AiUsageService.finish` вызывается в finally после domain/finance, сохранение через
`JdbcAiUsageStore` — отдельная `REQUIRES_NEW` transaction с максимум тремя попытками.
Ошибка metadata не повторяет AI/debit и не отменяет domain success. Process crash до finally
может потерять metadata: durable retry/invoice reconciliation пока нет.

Requested model не заменяет actual из ответа. Отсутствующие counters/rates — unknown,
не нулевой расход. `ProviderCost` использует BigDecimal и отдельно versioned provider_rates
по actual response model/startedAt; ставки production не предзаполнены. Image tool cost
остаётся unknown; response/tool/total и reasoning subsets нельзя складывать повторно.
Stub cost — stub/null; estimated response cost не доказывает полную стоимость или маржу.
Прайс пользователя не зависит от usage/rates. В журнале нет prompts, raw responses, images,
error bodies, credentials или workspace names. Parser/ставки/формат журнала — классы
`ResponsesUsage`, `ProviderCost`, `JdbcAiUsageStore` из карты кода ниже.

## 8. Gates, recovery и эксплуатация

| Property / environment | Default и эффект |
| --- | --- |
| `tovarika.billing.ai-admission-enabled` / `TOVARIKA_BILLING_AI_ADMISSION_ENABLED` | Java default false, локальный compose.yaml default **true**; определяет учёт только новых card jobs |
| `tovarika.billing.ai-launches-enabled` / `TOVARIKA_BILLING_AI_LAUNCHES_ENABLED` | true; false → 503 BILLING_AI_PAUSED для новых generation/retry/AI edits |
| `tovarika.billing.reconciliation-delay-ms` | 60000; период сверки резервов |

Оба gate читаются при старте процесса. Pause сохраняет replay, workers/finalization,
CPU resize и бесплатный анализ. Accounting=false не является pause: новые jobs идут без
operation/debit, ранее funded jobs всё равно capture/release по сохранённой политике.
Grant, free history и technical usage от этого gate не отключаются.

- `AiJobRecovery`: lease 10 минут, heartbeat каждые 30 секунд только для current processing
  attempt, без wallet locks; renewal одного исполнения максимум 20 минут.
  Generation/edit перед external calls проверяют не более трёх attempts и возраст <24 часов;
  analysis использует общий heartbeat/lease и максимум три attempts (раздел 10).
- `BillingReconciliation` каждые 60 секунд проверяет до 100 reserved project_jobs operations;
  после повторной проверки под общими locks освобождает только missing/terminal jobs.
  Queued/processing reserve нельзя освобождать по TTL или истёкшей lease. Completed + reserved —
  anomaly: консервативный release без выдуманного debit и отдельная метрика.
- При unknown commit worker сначала разрешает fenced failure под locks, затем
  `deleteUnreferenced` удаляет object только при отсутствии ссылки из assets.
  Недоступная БД/storage откладывает cleanup. Hard crash после upload может оставить orphan;
  durable object GC не реализован. Однократный debit не означает exactly-once вызов провайдера.
- Метрики: `tovarika.billing.heartbeat.failures`, `reserves.overdue`,
  `reconciliation.{released,failures,terminal_anomaly}`, `artifact.cleanup.deferred`
  (все с префиксом `tovarika.billing.`); `tovarika.billing.ai.usage` с event
  identity_lost/save_failure/metadata_lost. Overdue (>24 h) требует проверки workers/lease/DB,
  terminal_anomaly — расследования целостности. Не освобождать активный reserve вручную.

Порядок rollout:

1. Подготовить совместимые contract/backend/UI и миграции **017–022** без очистки данных.
   Явно задать accounting policy на всех узлах: для первого включения до готовности учёта
   оставить admission=false. Проверить published прайс и сохранность существующих accounts.
2. Установить launches=false на узлах новых card AI-команд; polling, workers и reconciliation
   оставить работающими. Во время rolling restart новые команды не должны попадать на узлы
   с разной accounting policy.
3. Перезапустить все нужные узлы с admission=true, сохраняя pause. Проверить health,
   migrations/prices и pending jobs: legacy сохраняют null operation без debit, funded —
   прежний reserve/snapshot. Старым Users grant выдаёт штатная lazy initialization, не batch.
4. Открыть launches=true после готовности всех узлов. Следить за funds failures,
   capture/release, overdue/anomaly и usage loss.
5. При проблеме pause новых AI, сохраняя учёт и workers/reconciliation до terminal outcomes.
   Не обнулять reserves и не удалять audit/jobs. Accounting=false — отдельный осознанный
   rollback новых admissions к grandfathered policy; он не отменяет pending finance.
   Rollback binary должен поддерживать текущую схему и funded finalization.

## 9. Карта кода и проверки

Java-пути ниже относительно `src/main/java/com/tovarika/tech/`; открывай только нужную границу.

| Задача | Точка входа |
| --- | --- |
| Типы, арифметика, price/funding snapshot | `billing/domain/Billing.java` |
| API wallet/summary/history/prices и JSON optional fields | `billing/api/BillingCreditsController.java`, `BillingJsonConfiguration.java` |
| Admission, replay, claim/finish, grant/compensation, conversion | `billing/application/BillingService.java`, `BillingStore.java` |
| Locks, SQL, catalog, merge и history projection | `billing/infrastructure/JdbcBillingStore.java` |
| Мост job ↔ finance, gates, snapshot polling | `cards/CardJobBilling.java`, `analyses/api/JobsController.java` |
| Generation/retry admission и terminal result | `cards/CardGenerationService.java`, `CardGenerationStore.java`, `CardGenerationWorker.java` |
| Edit/resize classification и result/aiCalled | `cards/editing/application/{CardImageEditingService,CardImageEditingWorker}.java`, `cards/editing/infrastructure/JdbcImageEditingStore.java` |
| Free history receipt | `billing/application/BillingActivity.java`, `analyses/infrastructure/JdbcAnalysisStore.java` |
| Bootstrap/grant и auth merge | `trial/application/TrialSessionService.java`, `auth/infrastructure/persistence/JpaAuthenticationStore.java` |
| Usage/parser/cost/rates | `billing/usage/{AiCallContext,AiUsageService,JdbcAiUsageStore,ResponsesUsage,ProviderCost}.java` |
| Lease, fencing recovery и reserve reconciliation | `billing/recovery/AiJobRecovery.java`, `BillingReconciliation.java` |
| Схема | `src/main/resources/db/changelog/changes/017`–`022` (billing SQL + YAML wrappers) |

Изменения схемы — только новые changesets по [MIGRATIONS.md](MIGRATIONS.md).
Финансовые/concurrency проверки — PostgreSQL 18 Testcontainers, stub/fake AI/storage;
H2 и mocked finance не проверяют guards/fences/merge. Основные существующие suites:

```sh
./gradlew test --tests 'com.tovarika.tech.billing.*' --tests com.tovarika.tech.cards.CardBillingOperationsIntegrationTest
./gradlew test --tests com.tovarika.tech.auth.AuthenticationContractIntegrationTest
```

Первая команда покрывает деньги, history/HTTP serialization, usage/cost, гонки/rollback/
recovery/free receipts; вторая нужна при изменении trial/auth/conversion.
При изменении API сначала собери контракт и generated API по [RULES.md](RULES.md).
Не заменять runtime checks датированными PASS из отчётов предыдущей задачи.

## 10. Известные расхождения описаний и текущего кода

- Контрактный `docs/billing/human.md` относит `retryFailedCard` к image_generation без
  бесплатного права. Фактически `CardGenerationService.retry` принимает image_entire_edit
  с subject=cardId; тест `CardBillingOperationsIntegrationTest.failureReleasesMoneyAndRetryReservesConsumesSharedFreeRight`
  проверяет free_entitlement и последующий платный entire. Это текущее проверяемое поведение;
  контрактное описание требует отдельного согласования с реализацией.
- Контрактное описание обещает billing snapshot и для новых analysis jobs. Фактически
  `AnalysisService` не создаёт billing operation/reserve, `JobsController` не добавляет snapshot
  analysis; успех попадает в free history через BillingActivity, usage учитывается отдельно.
- Отчёты recovery распространяют предел возраста 24 часа на analysis. В текущем
  `AnalysisWorker` проверяется только attempt > 3; вызова `AiJobRecovery.check` нет.

При задаче на эти сценарии сначала согласуй контракт и реализацию в рамках её требований;
не переносить устаревшее обещание в новый код и не менять финансовую политику попутно.

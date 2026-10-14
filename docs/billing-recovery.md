# Billing recovery и rollout

Задание 07, 14 октября 2026. Полный сквозной отчёт и developer-managed инструкции:
[07-verification-result.md](/home/malexey/project/tovarika/tovarika-api-contract/docs/billing/07-verification-result.md).

## Параметры

- OpenAI: connect 10 s, request/body 240 s; отдельный transport от auth.
- MinIO: connect 10 s, read/write 60 s, call 90 s; connection retry выключен.
- Generation/edit/analysis: initial lease 10 min, heartbeat 30 s; renewal только current processing attempt.
- Не более 3 attempts; job возрастом >=24 h завершается fenced failure до provider/storage.
- Heartbeat одного исполнения продлевается не более 20 min. Его остановка не освобождает reserve.
- Reconciliation: каждые 60 s, максимум 100 orphan/terminal reserves за проход; queued/processing не освобождаются по TTL.
- Миграция 021 добавляет partial index pending operations, без workspace FK и backfill.

## Gates

`tovarika.billing.ai-admission-enabled=false` (исходное значение) определяет accounting policy **новых** jobs. Funded jobs финализируются при любом значении; legacy jobs не получают reserve/debit задним числом.

`tovarika.billing.ai-launches-enabled=true` — отдельный operational gate. Значение false даёт HTTP 503 `BILLING_AI_PAUSED` для новых generation/retry/AI edits, сохраняя replay, pending finalization и CPU resize. Бесплатный анализ остаётся доступен. Gates читаются при старте процесса.

При проблеме pause новых launches, сохранив accounting enabled и работающие workers/reconciliation. Accounting false возвращает **новые** jobs к grandfathered политике, поэтому это не emergency pause. Во время rolling change не направлять новые команды на узлы с разной accounting policy. Не обнулять reserves, не удалять ledger и не откатывать на binary без funded finalization.

## Unknown commit

После ошибки finalization сначала разрешается fenced failure под job lock, затем удаляется только object без ссылки в assets. При недоступной БД/storage object сохраняется для дальнейшей проверки. Committed image и единственный debit не уничтожаются из-за потерянного commit acknowledgement. Hard crash после upload может оставить orphan object; durable object GC остаётся отдельным эксплуатационным ограничением.

## Метрики

`tovarika.billing.heartbeat.failures`, `tovarika.billing.reserves.overdue`,
`tovarika.billing.reconciliation.released`, `tovarika.billing.reconciliation.failures`,
`tovarika.billing.reconciliation.terminal_anomaly`, `tovarika.billing.artifact.cleanup.deferred`.

Terminal anomaly означает completed domain job с reserved finance: reconciliation консервативно освобождает резерв без выдуманного debit и требует расследования. Overdue (>24 h) требует проверки workers/DB/current attempt, а не release активного provider call.

Однократный финансовый эффект проверен PostgreSQL тестами. Exactly-once provider execution при hard crash/network uncertainty не гарантируется; recovery может повторно потратить средства сервиса. Живые usage/rates/маржа остаются unknown; stub и fake rates не доказывают экономику.

## Локальная проверка с Docker Compose

`compose.yaml` пробрасывает оба billing gates с безопасными defaults: accounting false,
launches true. Для отдельного включения учёта в локальном окружении:

```sh
TOVARIKA_BILLING_AI_ADMISSION_ENABLED=true docker compose --profile full up -d --no-deps --no-build app
```

Это пересоздаёт только backend с существующим image, не очищает PostgreSQL/MinIO и не
запускает AI само по себе. Выполнять после разрешения на изменение работающего окружения.
Все принятые до включения jobs с null billing_operation_id остаются grandfathered: их
нельзя включать в дебиты задним числом. При начальном прайсе новая успешная генерация
создаёт debit 400 units; первый успешный entire/retry на карточку бесплатен и добавляет
публичную строку free_operation с нулевой суммой. Если Compose не управляет активным container, сначала проверить
имя проекта и используемые Compose-файлы, не запускать второй backend поверх существующего.

## Бесплатные результаты в истории (контракт 1.8)

Миграция 022 и BillingActivity добавляют durable success receipts для AI, анализа и resize,
независимо от accounting gate. History read под wallet guard связывает paid receipt с прежним
одиночным debit либо создаёт нулевую free_operation. Нулевое событие не меняет total/available/used,
не резервирует средства и не consume-ит entitlement. Для free balanceAfterUnits отсутствует;
UI показывает «Бесплатно» и «—» в колонке остатка. Загрузка, ручные правки, Undo/Redo и export
не включены по уточнению пользователя. Старые сохранившиеся completed jobs импортируются
без денежных списаний; удалённые старые jobs не восстанавливаются по догадке.

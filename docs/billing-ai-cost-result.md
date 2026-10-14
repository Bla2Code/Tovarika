# Результат задания 05 — отдельный учёт AI-расходов

Выполнено 14 октября 2026 в `/home/malexey/project/tovarika/Tovarika`,
ветка `feature/billing`. Предыдущие изменения этапов 02–04 сохранены.

## Реализовано

- Liquibase 020: независимые `billing_ai_attempts`, `billing_ai_usage`,
  `billing_provider_rates`, без workspace/financial FK и без production rate seeds.
- Явный worker context через analysis/generation/editing, services, processor,
  adapters и OpenAiClient до Responses transport. HTTP request UUID совпадает
  с invocation ID журнала; financial operation nullable, analysis бесплатен без reserve.
- Отдельные provider/job outcomes, стабильные HTTP категории, явный is_stub даже
  при ошибке stub, actual/requested models, размеры/качество и timestamps.
- Allowlist counters, раздельные response/image_tool scopes; cache/cache-write
  partitions и reasoning subsets не добавляются к totals повторно. Payload/prompts,
  полные ответы, raw image, API keys и exception messages не сохраняются.
- Независимая versioned exact-model оценка в исходной валюте через BigDecimal.
  Неизвестные модель/схема/ставка/counters остаются unknown с NULL cost; stub отдельно.
  Цены пользователя и DTO не изменены.
- Метаданные сохраняются после domain/finance completion в REQUIRES_NEW транзакции.
  Максимум три metadata-only retries, атомарная idempotent запись snapshot и usage rows,
  метрики identity/save/loss. Ошибка аналитики не вызывает AI повторно и не откатывает debit.
- Уточнено прежнее обещание analysis provider: job correlation не гарантирует
  deduplication/ exactly-once обращения к провайдеру.

Формат, fetched official sources, подтверждённые/неизвестные категории,
правила ставок и ограничения: [billing-ai-cost.md](billing-ai-cost.md).

## Проверки

`JAVA_HOME=/home/malexey/.jdks/corretto-21.0.12 ./gradlew --offline test`:
**PASS**, 2m 44s, **272 tests: 271 passed, 1 прежний skipped, 0 failures/errors**.
`git diff --check`: PASS.

Добавлен 21 тест (8 parser/cost/retry unit, 5 PostgreSQL, 3 fake HTTP Responses,
5 worker/finance integration) плюс актуализированы две проверки context-aware overload.

Проверены: successful/error/HTTP429/timeout/free/stub, неверный image result с
сохранённым usage, paid generation/free entitlement/CPU branches, provider result
с failed storage, stale recovered attempt и failed version conflict; отсутствие
секретов и double count, unknown вместо нулей, exact/effective rate version,
отсутствие зависимости от customer prices, retry после rollback без partial rows,
три попытки save при analytics outage с единственным AI call и успешным capture.
Полный набор повторно прошёл на окончательной миграции с is_stub.

Использованы только MockRestServiceServer/fake fixtures, stub AI, in-memory storage
и изолированные PostgreSQL 18-alpine Testcontainers. Реальные AI calls, рабочая БД,
provider secrets и deployment configuration не использовались. Коммит/push/PR не выполнялись.

## Ограничения и передача этапам 06–07

- Tool usage fixture синтетический; actual image model/tool денежная схема не
  подтверждены доступными Responses примерами. Image tool cost остаётся unknown;
  оценка response scope не является полной стоимостью всего image обращения.
- Production provider rates намеренно отсутствуют; вручную добавлять только
  подтверждённые actual-model ставки отдельной новой версией в одной транзакции.
- Восстановление metadata ограничено живым worker. Crash до finally может потерять
  snapshot без loss metric; durable queue и invoice reconciliation вне этапа.
- Recovery способен вызвать AI повторно; новый invocation/attempt учитывается
  отдельно. Старый ответ сохраняет usage, но не меняет финансовое завершение.
- AI admission gate оставлен в прежнем состоянии по умолчанию (false).
  Технический учёт работает независимо от admission gate.

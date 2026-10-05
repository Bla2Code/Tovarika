# Генерация первой карточки товара

Читай при изменении запуска карточки, очереди, worker, состояний и выдачи результата.
Сборка текста запроса — [CARD_PROMPTS.md](CARD_PROMPTS.md),
AI transport и размеры — [IMAGE_GENERATION.md](IMAGE_GENERATION.md).

## 1. Поток и точки реализации

1. Загрузить исходное фото: `POST /api/v1/products` → `uploaded` ([PRODUCTS.md](PRODUCTS.md)).
2. Запустить `POST /api/v1/products/{productId}/analysis`, опрашивать job,
   получить/проверить description ([PRODUCT_ANALYSIS.md](PRODUCT_ANALYSIS.md)).
3. Создать проект для Product; это допустимо и до завершения анализа ([PROJECTS.md](PROJECTS.md)).
4. Выбрать шаблон либо написать пользовательский prompt ([TEMPLATES.md](TEMPLATES.md)).
5. `POST /api/v1/projects/{projectId}/cards` возвращает `202`, jobId, target Card
   и `pollAfterMs=1000`; HTTP-запрос не ждёт AI.
6. Опрашивать `GET /api/v1/jobs/{id}`; после completed читать Card через
   `GET /api/v1/projects/{projectId}/cards/{cardId}` или список карточек.

Код: `cards/CardsController`, `CardGenerationService`, `CardGenerationStore`,
`CardGenerationWorker`, `CardGenerationScheduler`; общий polling — `analyses/api/JobsController`.
Пути Java здесь относительно `src/main/java/com/tovarika/tech/`.
Контракт: generateCard/GenerateCardRequest/Card/Job в `../tovarika-api-contract/dist/openapi.yaml`.

## 2. Запуск и идемпотентность

- Identity — общий resolver: Bearer перед trial; cookie mutation требует разрешённый Origin.
  Чужой/отсутствующий проект даёт `404 PROJECT_NOT_FOUND`.
- Обязателен `Idempotency-Key`: `[A-Za-z0-9._:-]{8,128}`. Контракт требует ровно один
  templateId/prompt и явный aspectRatio: `1:1`, `3:4`, `4:5`, `16:9`.
- Совместимость контроллера со старым UI: при непустом templateId переданный вместе с ним
  prompt отбрасывается. Сервис получает ровно один источник оформления;
  не смешивай пользовательский текст с выбранным шаблоном.
- Транзакция блокирует владельца, затем Project/Product. Повтор ключа в owner_scope
  возвращает исходную job для того же проекта; другой проект — `409 IDEMPOTENCY_CONFLICT`.
  Сравнения payload при replay сейчас нет. Replay проверяется до лимита карточек и анализа.
- При card_count != 0 новый запуск даёт `409 CARD_LIMIT_EXCEEDED`.
  Нужен Product analysis_ready с непустым description и revision: иначе
  `409 RESULT_NOT_READY`, при analysis_failed — `409 ANALYSIS_FAILED`.
- Недоступный/отсутствующий шаблон — `404 TEMPLATE_NOT_FOUND`; в live без reference metadata —
  `422 TEMPLATE_NOT_READY`. Проверка metadata не гарантирует наличие объекта в MinIO.
- Одной транзакцией создаются Card (position=1, generating), project job (queued),
  snapshots prompt/recipe/reference asset ID/analysis revision; card_count становится 1.
  При шаблоне обновляется selected_template_id. Настройки проекта не подставляются
  автоматически вместо параметров запроса.

## 3. Worker и результат

| Этап | Job | Card |
| --- | --- | --- |
| Постановка | queued | generating |
| Claim | processing | generating |
| Успех | completed | ready |
| Ошибка | failed, GENERATION_FAILED | error, GENERATION_FAILED |

- Scheduler включён по умолчанию; `CARD_GENERATION_WORKER_ENABLED` управляет им,
  `tovarika.cards.poll-delay-ms` по умолчанию 1000.
- Claim: FOR UPDATE SKIP LOCKED, lease 300 секунд, увеличение attempt.
  Просроченный processing можно забрать снова; при attempt > 3 внешняя генерация не выполняется.
- После claim-транзакции worker читает оригинал Product и reference из MinIO.
  Исходное фото всегда первое; reference второе. Без reference в stub используется placeholder.
- Worker вызывает ImageGenerationService с provider chatgpt, сохраняет результат
  в `cards/{assetId}`. Complete-транзакция проверяет processing и тот же attempt,
  создаёт Asset card_image, связывает Card и завершает job; preview проекта
  заполняется только если ещё отсутствует.
- Устаревший attempt не меняет результат. Его объект удаляется; при исключении выполняется
  best-effort удаление, затем сохраняется только публичный код ошибки.
- Job progress: queued 0, processing 10, completed 100; это не реальный процент AI-работы.
  Job/Card GET используют Cache-Control: no-store; URL результата создаётся через AssetLinks.
- Метрики: `tovarika.card.generation.latency`/`tovarika.card.generation.operations`,
  только outcome (completed, failed, stale). Prompt, фото и provider body не логируются.

## 4. Границы MVP и правила изменения

- Реализована только первая карточка. Regenerate, region edit и PATCH Card сейчас возвращают
  `422 VALIDATION_ERROR`; контрактные возможности не означают готовую реализацию.
- CardCollection.limit=10 — поле контракта; фактический MVP допускает один запуск.
  Failed Card остаётся в проекте и занимает лимит; replay не перезапускает failed job.
- Списание trial generations и billing/quota пока отсутствует.
- Fencing защищает запись результата в БД, но не обеспечивает exactly-once вызов AI:
  после истечения lease возможен повторный запрос провайдеру.
- Для generated objects пока нет durable reservation/cleanup как у Product upload:
  падение после записи MinIO может оставить orphan. Best-effort не гарантирует очистку.
- Сохраняй snapshots, ownership, уникальность idempotency key/active job, fencing
  и сериализацию с PATCH/DELETE по [PROJECTS.md](PROJECTS.md).
  Изменение шаблона/анализа после enqueue не меняет уже собранный prompt.
- Проверяй компиляцию; при изменении AI boundary — проверки [OPENAI.md](OPENAI.md),
  блокировок проекта — ProjectMutationsIntegrationTest на PostgreSQL. Отдельного
  integration suite генерации карточек в текущем src/test нет.

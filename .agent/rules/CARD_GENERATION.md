# Генерация серии карточек товара

Читай при изменении запуска карточки, очереди, worker, состояний и выдачи результата.
Сборка текста запроса — [CARD_PROMPTS.md](CARD_PROMPTS.md),
AI transport и размеры — [IMAGE_GENERATION.md](IMAGE_GENERATION.md).

## 1. Поток и точки реализации

1. Загрузить исходное фото: `POST /api/v1/products` → `uploaded` ([PRODUCTS.md](PRODUCTS.md)).
2. Запустить `POST /api/v1/products/{productId}/analysis`, опрашивать job,
   получить/проверить description ([PRODUCT_ANALYSIS.md](PRODUCT_ANALYSIS.md)).
3. Создать проект для Product; это допустимо и до завершения анализа ([PROJECTS.md](PROJECTS.md)).
4. Прочитать `GET /api/v1/projects/{projectId}/cards/next-draft` и выбрать шаблон/идею либо написать пользовательский prompt ([TEMPLATES.md](TEMPLATES.md)).
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
- Для шаблона передаются variantId из next-draft и необязательная idea (до 2000 символов).
  Отсутствие идеи использует defaultIdea; явно пустая/пробельная идея отклоняется.
  templateId и технический prompt одновременно запрещены. На первой позиции сохранена
  совместимость с templateId без variantId; шаблон без сценариев допускает старую первую карточку.
- Транзакция блокирует владельца, затем Project/Product. Повтор ключа в owner_scope
  возвращает исходную job для того же проекта; другой проект — `409 IDEMPOTENCY_CONFLICT`.
  Сравнения payload при replay сейчас нет. Replay проверяется до лимита карточек и анализа.
- После replay активная project job даёт `409 CARD_BUSY`; при card_count >= 10 — `409 CARD_LIMIT_EXCEEDED`.
  Нужен Product analysis_ready с непустым description и revision: иначе
  `409 RESULT_NOT_READY`, при analysis_failed — `409 ANALYSIS_FAILED`.
- Недоступный/отсутствующий шаблон — `404 TEMPLATE_NOT_FOUND`; в live без reference metadata —
  `422 TEMPLATE_NOT_READY`. Проверка metadata не гарантирует наличие объекта в MinIO.
- Одной транзакцией создаются Card (position=card_count+1, generating), project job (queued),
  snapshots prompt/recipe/reference asset ID/analysis description/revision, вариант и окончательная idea; card_count увеличивается на 1.
  При шаблоне обновляются selected_template_id и snapshot серии; сохраняется выбранный ratio. Настройки проекта не подставляются
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

- Создание поддерживает позиции 1–10; произвольные Regenerate и PATCH Card возвращают
  `422 VALIDATION_ERROR`. Bitmap edit/region/Undo реализованы отдельно по [IMAGE_EDITING.md](IMAGE_EDITING.md).
- Failed Card остаётся на своей позиции. `POST /cards/{cardId}/retry` создаёт новую job
  для error Card с прежними snapshots, без увеличения card_count, включая лимит 10.
  Replay не перезапускает failed job; ready Card даёт `CARD_RETRY_NOT_ALLOWED`.
- Списание trial generations и billing/quota пока отсутствует.
- Fencing защищает запись результата в БД, но не обеспечивает exactly-once вызов AI:
  после истечения lease возможен повторный запрос провайдеру.
- Для generated objects пока нет durable reservation/cleanup как у Product upload:
  падение после записи MinIO может оставить orphan. Best-effort не гарантирует очистку.
- Сохраняй snapshots, ownership, уникальность idempotency key/active job, fencing
  и сериализацию с PATCH/DELETE по [PROJECTS.md](PROJECTS.md).
  Изменение шаблона/анализа после enqueue не меняет уже собранный prompt.
- Проверяй компиляцию; при изменении AI boundary — проверки [OPENAI.md](OPENAI.md),
  блокировок проекта — ProjectMutationsIntegrationTest на PostgreSQL. Дополнительный
  suite: `CardSeriesIntegrationTest`, обновление схемы: `CardSeriesMigrationTest`.

## 5. Сценарии серии и форма

[docs/card-series-tasks.md](../../docs/card-series-tasks.md) фиксирует согласованный
порядок задач: 10 сценариев текущего шаблона → хранение → наполнение → API/backend → UI.
Хранение — миграция 012; seed текущего шаблона — 013. UI находится в `/home/malexey/project/TovaricaUI`.

- 10 вариантов включают первую обложку; в проекте максимум 10 Card.
- Чтение следующей идеи и отмена формы не создают Card и не расходуют позицию.
- Следующую позицию выбирает backend под блокировкой; сохраняй replay до busy/лимита.
- Повтор failed generation должен создавать новую job для прежней Card, а не занимать
  следующую позицию. Replay старого ключа по-прежнему не означает повтор генерации.
- При enqueue сохраняй сценарий, окончательную идею и snapshots серии/анализа.
  Изменение каталога, идеи в UI или стиля после enqueue не меняет текущую job.
- Изменение выбранного стиля действует на новую и последующие карточки; готовые
  карточки не пересобираются. Для изменения готового bitmap — [IMAGE_EDITING.md](IMAGE_EDITING.md).

- GET next-draft только читает (repeatable read, ownership, no-store): state ready,
  limit_reached, variant_unavailable или template_required; поддерживаются все четыре ratio.
  Параметр templateId меняет стиль формы без PATCH. Recipe/prompt/storage key не выдаются.
- variantId проверяется по snapshot и текущей позиции: чужой ID — CARD_VARIANT_INVALID,
  прежняя/другая позиция — CARD_DRAFT_STALE с требованием обновить форму; отсутствие
  следующего сценария — CARD_VARIANT_UNAVAILABLE. Сценарии не выбираются циклически.
- Series snapshot фиксируется только при enqueue. У legacy-проекта общий recipe/reference
  берутся из первой Card; набор сценариев — из каталога. GET не записывает snapshot.
- UI получает draft, сохраняет отредактированную idea при смене стиля, создаёт Card только
  по кнопке «Создать карточку», показывает target сразу после 202 и опрашивает GET jobs.
  Сетевой повтор сохраняет ключ и payload; новая операция/failed retry используют новый ключ.

Приёмка UI на настоящем backend (внешние AI/storage — test boundaries):

```sh
TOVARIKA_UI_E2E=true TOVARIKA_UI_DIRECTORY=/path/to/TovaricaUI ./gradlew test --tests com.tovarika.tech.cards.CardSeriesBrowserIntegrationTest
```

Проверка запускает UI на localhost:5175, создаёт trial Product, анализ и первые две Card,
проверяет отмену, сохранение идеи/ratio, polling и reload. Без флага cross-repository тест
пропускается. Живое качество AI и применимость деталей проверяются отдельно по плану серии.

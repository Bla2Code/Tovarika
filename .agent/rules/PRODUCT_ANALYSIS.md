# Анализ товара и редактирование результата

Читай при изменении analyses, analysis jobs, description/idea или prompt анализа.
AI-конфигурация/secrets — [OPENAI.md](OPENAI.md), использование description карточкой —
[CARD_PROMPTS.md](CARD_PROMPTS.md).

## 1. Реализация и запуск

- API: AnalysesController, AnalysisPatchBodyAdvice, общий JobsController.
  Use cases: AnalysisService, AnalysisWorker, GenerationPromptBuilder; persistence:
  JdbcAnalysisStore; provider port: AnalysisProvider, adapter ChatGPTAnalysisAdapter.
  Код — `src/main/java/com/tovarika/tech/analyses/`.
- POST `/api/v1/products/{productId}/analysis` проверяет владельца Product и
  Idempotency-Key (`[A-Za-z0-9._:-]{8,128}`), блокирует владельца/Product, возвращает 202.
- Scope ключа — identity. Replay возвращает исходную job, даже после другого анализа;
  тот же ключ для другого Product — `409 IDEMPOTENCY_CONFLICT`.
  Новый запуск для pending Product — `409 ANALYSIS_ALREADY_RUNNING`.
- Лимит — 30 новых анализов в час на identity; replay его не расходует.
  В одной транзакции создаётся analysis_jobs, Product получает analysis_pending/analysisJobId.

## 2. Worker и результат AI

- PostgreSQL queue: FOR UPDATE SKIP LOCKED, lease 300 секунд, fencing по attempt.
  Просроченная lease позволяет повторный claim; при attempt > 3 операция завершается ошибкой.
  AI вызывается вне claim/complete-транзакций.
- Provider operation ID равен job ID и сохраняется при recovery. Это metadata,
  не гарантия exactly-once внешнего вызова. Старый attempt не может сохранить результат.
- Успех атомарно сохраняет текущий ProductAnalysis с revision 1 и analysis_ready.
  При повторном успешном анализе заменяются analysis id/createdAt и revision снова равна 1;
  это отличается от PATCH существующего результата.
  Ошибка — analysis_failed, terminal job и только стабильный ANALYSIS_FAILED.
  DB trigger запрещает возобновлять terminal job; повторный анализ создаёт новую job.
- Server-owned vision prompt: `src/main/resources/prompts/product-analysis-v1.txt`.
  Результат на русском: title до 200 символов или null, description 1–4000, idea 1–2000.
  Описываются видимые факты основного товара без выдуманных свойств, цен/скидок/рейтингов;
  idea задаёт визуальное оформление.
- Stub помечает анализ [STUB]. Live использует Structured Outputs с title/description/idea;
  это отдельный запрос от image generation. GenerationPromptBuilder сохраняет read-only
  generationPrompt анализа из title/description/idea.
- Фото, generation prompt и provider body не логируются и не входят в JobFailure.
  Метрики `tovarika.analysis.latency`/`tovarika.analysis.operations` имеют только ограниченный outcome.

## 3. GET/PATCH и ревизии

Identity — общая Bearer-first граница по [TRIAL.md](TRIAL.md); доступ наследуется от Product.

| Состояние Product при GET/PATCH анализа | Публичная ошибка |
| --- | --- |
| Отсутствует или чужой | 404 PRODUCT_NOT_FOUND |
| uploaded, результата нет | 404 ANALYSIS_NOT_FOUND |
| analysis_pending | 409 RESULT_NOT_READY |
| analysis_failed | 409 ANALYSIS_FAILED |

- PATCH принимает непустое подмножество title/description/idea. `generationPrompt`,
  неизвестные поля, null, неверный тип и превышение лимитов отклоняются до DTO binding:
  422 VALIDATION_ERROR; пустой/синтаксически неверный объект — 400 VALIDATION_ERROR.
- Cookie PATCH требует разрешённый Origin. Сервис блокирует владельца/Product,
  объединяет частичные изменения, всегда пересобирает generationPrompt и атомарным SQL update
  увеличивает revision на один. updatedAt растёт; analysis id/createdAt, owner и Asset сохраняются.
- Конкурентные PATCH сериализуются по Product и не теряют изменения разных полей.
  Уже поставленная Card использует сохранённые prompt/revision, а не новый текст анализа.
- `generationPrompt` анализа возвращается как read-only контрактное поле; UI не показывает/редактирует его.
  Prompt Card не возвращается. Сохраняй это различие.

## 4. Проверки

`./gradlew test --tests com.tovarika.tech.auth.AnalysisJobsIntegrationTest`
и `./gradlew test --tests com.tovarika.tech.auth.AnalysisEditingIntegrationTest` —
существующие PostgreSQL/Testcontainers suites; OpenAI boundary — [OPENAI.md](OPENAI.md).
ANALYSIS_WORKER_ENABLED управляет scheduler; Gradle выключает worker для integration tests.

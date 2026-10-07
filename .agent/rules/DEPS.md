# Зависимости модулей и граница с UI

Читай при изменении взаимодействия модулей или интеграции frontend/backend.
Общие требования к слоям находятся в [RULES.md](RULES.md).

## 1. Текущий поток карточки

```text
UI → products → analyses → project → cards
                              templates ↗  ↓
                                          images → OpenAiClient
products / cards → ProductStorage → MinIO
workspace API → WorkspaceIdentityResolver → auth / trial
GET jobs → JobsController → analyses / cards
```

Порядок клиентских операций — [CARD_GENERATION.md](CARD_GENERATION.md).
Проект можно создать до завершения анализа; готовый анализ обязателен при запуске карточки.

| Потребитель | Используемая граница |
| --- | --- |
| Workspace-контроллеры | `WorkspaceIdentityResolver`, затем явные userId/trialSessionId в сервис |
| `products` | `ProductStore`, `ProductStorage`, `ImageValidator`, общий rate limiter |
| `analyses` | `AnalysisStore`, `AnalysisProvider`, `ProductStorage`, `GenerationPromptBuilder` |
| `cards` | `CardGenerationStore`, `TemplateService`, `CardPromptCompiler`, `ImageGenerationService`, `ProductStorage` |
| `images` | `ImageGenerator`/`ImageEditor`, Spring registry и инфраструктурный `OpenAiClient` |
| HTTP assets и DTO | `AssetLinks`, доставка через backend; клиент не получает object key |

## 2. UI и фоновые операции

- UI использует публичный OpenAPI, backend реализует generated интерфейсы.
  Provider API предназначен для внешних webhook, а не для UI.
- Backend владеет техническим prompt, recipe и вызовом AI. UI передаёт контрактные
  поля, сохраняет jobId и опрашивает `GET /api/v1/jobs/{id}`.
- Асинхронный HTTP-запуск сохраняет состояние в PostgreSQL; worker вызывает провайдера
  после короткой claim-транзакции. Не удерживай транзакцию БД во время AI-запроса.

## 3. Правила развития и текущие исключения

- Новые application-сервисы используют узкие порты/facade, не JPA-репозитории другого модуля.
  Не переносить servlet/SecurityContext/generated DTO в application/domain.
- `project`, `templates`, `cards` сейчас имеют плоскую структуру и JDBC; `CardGenerationStore`
  читает Product/Analysis/Asset через SQL joins. Это описание существующего кода,
  а не разрешение добавлять произвольные межмодульные зависимости.
- `CardGenerationService` сейчас читает режим из `OpenAiProperties`; worker выбирает
  registry key `chatgpt`. Новый адаптер не требует изменения фабрики, но выбор провайдера
  для карточек нужно отдельно подключить в вызывающем сценарии.
- Не создавай вторую систему identity/session, очередь в JVM или обход
  `ImageGenerationService` прямым HTTP-запросом из контроллера.
- Для формы продолжения серии используй [план задач](../../docs/card-series-tasks.md):
  backend возвращает следующую пользовательскую идею, UI хранит несохранённый draft,
  а backend собирает технический prompt и назначает позицию при enqueue.
- Конвертация trial расширяет существующий `AuthenticationStore.convertTrial`;
  billing/quota не становятся claims JWT. См. [TRIAL.md](TRIAL.md), [SECURITY.md](SECURITY.md).

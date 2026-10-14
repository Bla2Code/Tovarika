# Отдельный учёт AI-расходов — этап 05

Реализация в `feature/billing`, 14 октября 2026. Учёт независим от кошелька,
резервирования и публичного прайса. Анализ не создаёт финансовую operation/reserve.
Gate AI admission не отключает технический журнал; grandfathered jobs имеют `funding=legacy`.

## Формат v1

`billing_ai_attempts` — одна строка на фактическое обращение к transport/stub boundary:

- `id` — новый UUID обращения, для live также `X-Client-Request-Id`. Один job может
  вызвать провайдера несколько раз; correlation ID не является ключом provider deduplication.
- `job_namespace`, `job_id`, `job_attempt` — непрозрачная корреляция; `operation_id`
  nullable, `funding`: wallet/free_entitlement/free_operation/legacy/unknown.
- `requested_main_model`, `requested_image_model`, requested size/quality — параметры
  запроса; не доказательство фактически использованной модели/качества.
- `response_id`, `actual_main_model`, provider created/completed timestamps — только
  поля ответа. При отсутствии модель не подставляется из конфигурации.
- `started_at`, `finished_at` — локальные timestamps, отдельно от Unix timestamps провайдера.
- `is_stub` — независимый флаг offline boundary, в том числе при ошибке stub.
- `provider_outcome`: successful/error/timeout/stub/unknown, HTTP status без error body.
- `job_outcome`: completed/failed/stale/unknown. Успех провайдера может закончиться
  failed из-за storage/validation или stale из-за другого attempt. Проверка текущего
  attempt/status отличает внутренний version conflict, завершивший job failed, от stale worker.

`billing_ai_usage` — компоненты обращения, `(attempt_id, component_index)`:

| Scope | Подтверждённое | Неизвестное / ограничения |
| --- | --- | --- |
| response | input/output/total tokens, cached/cache-write input, reasoning output subsets | Распределение mixed input на text/image обычно отсутствует; не считать весь input text-only |
| image_tool | ID/status и размер/качество, если они присутствуют | Usage/model расширения tool item сохраняются как `unverified_tool_usage`; денежная оценка запрещена |
| unknown | Нет usage или offline stub | Пустые counters и NULL amount, без фиктивных нулей |

Counters — плоская allowlist: `input_tokens`, `output_tokens`, `total_tokens`,
`input_cached_tokens`, `input_cache_write_tokens`, `input_text_tokens`,
`input_image_tokens`, `output_reasoning_tokens`, `output_text_tokens`, `output_image_tokens`.
Отсутствующие/отрицательные/дробные/переполненные значения не сохраняются как 0.
Status ограничен известными enum, model/IDs — ограниченными идентификаторами.
Ни prompts, revised_prompt, request metadata, text/image result, raw responses, error bodies,
API keys, названия товаров/проектов, byte arrays, ни exception messages не входят в журнал.
Таблицы не имеют FK к workspace/finance и сохраняются после удаления workspace.

## Проверка официальной схемы

Проверено 14 октября 2026 по полученным страницам официальной документации OpenAI:

- [Responses reference](https://developers.openai.com/api/reference/typescript/resources/responses/methods/create):
  root usage содержит totals, input cache details и reasoning subset. Эти вложенные
  счётчики не являются добавкой к агрегатам.
- [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching):
  обычный input определяется как input − cached − cache_write; cache_write выделяется отдельно.
- [Image generation tool](https://developers.openai.com/api/docs/guides/tools-image-generation):
  изображение приходит в image_generation_call.result; это payload результата, не usage.
- [Images API](https://developers.openai.com/api/reference/resources/images/methods/generate):
  описание Images usage нельзя автоматически переносить на Responses image tool.

В доступных fetched Responses примерах нет подтверждённого tool-level денежного
контракта или гарантированного actual image model. Поэтому fixture `response-usage.json`
содержит документированную root часть и явно синтетическое расширение tool usage для
проверки безопасного поведения. Это fake fixture, не снятый production ответ. Отсутствие
подтверждённой схемы не скрыто оценкой: image/text counters сохраняются по scopes,
image tool остаётся unknown. Даже повторённый tool ID внутри output не удваивает компонент.
Разные HTTP обращения с одинаковым response ID остаются разными попытками; это не
основание обещать отсутствие повторного provider charge.

## Независимые provider rates

`billing_provider_rates` — developer-managed exact-model/version/category справочник,
с currency, effective_from и source_url. UPDATE/DELETE запрещены; изменение ставки —
новая версия. Варианты одной версии добавляются согласованно в одной транзакции с
одинаковыми effective_from/currency; до полной вставки справочник не публиковать.
Production ставки намеренно не предзаполнены: конфигурация моделей и секреты deployment
не читались. Fake rates в тестах не являются рекомендацией цены OpenAI.

Оценка фиксируется в usage строке по actual response model и версии, действовавшей
на started_at обращения. Запрошенный model alias не подменяет actual model.
Применяется BigDecimal, без float, currency conversions и автоматического импорта.
Для response scope необходимы input/output/cached/cache-write counters и ставки всех
ненулевых категорий. Цена = сумма четырёх непересекающихся категорий / 1e6.
Total/reasoning counters второй раз не добавляются. Inconsistent cache partition → unknown.
`cost_state=estimated` означает оценку отдельного response scope, не invoice и не полную
стоимость обращения с image tool. Tool scope всегда unknown, даже если fixture содержит
actual model и counters; root/tool/total не суммируются. Stub имеет state=stub и NULL cost.
Цена пользователя остаётся в `billing_prices`, не зависит от provider rates.

## Ошибки и восстановление

Workers передают явный context через services/processor/adapters/OpenAiClient до
OpenAiResponsesClient. Context содержит только безопасный snapshot, не живой JsonNode.
Usage извлекается до декодирования result: некорректное изображение не теряет имеющиеся
counters. Ошибка parser accounting не превращается в повтор AI.

После финансового/domain завершения worker вызывает AiUsageService.finish в finally.
Каждый snapshot сохраняется в отдельной REQUIRES_NEW транзакции, максимум три попытки,
без повторного вызова AI/capture/release. Insert attempt + все usage rows атомарны;
повтор того же invocation UUID идемпотентен, включая неоднозначный commit acknowledgement.
Отдельные вызовы получают новые UUID, даже внутри одного job attempt.

Метрика `tovarika.billing.ai.usage` с фиксированным event:
`identity_lost`, `save_failure`, `metadata_lost`. В tags нет owner/job/model или секретов.
Сбой metrics также не распространяется на AI/финансовую операцию.

Восстановление ограничено тремя попытками в живом worker: durable queue/restart replay
не добавлены. При process crash до finally snapshot может потеряться без loss metric;
journal не обеспечивает полный invoice reconciliation или exactly-once provider execution.
Существующее job recovery может снова вызвать AI с новым attempt, оба завершившихся
обращения учитываются, stale ответ не меняет reserve/debit/free right.

CPU editing, Undo/Redo, upload/export не создают provider попытку. Публичные DTO,
кошелёк, история и фиксированный прайс не содержат technical usage/cost.
Реальные AI calls, ставки/FX import, dashboards и платежи вне этапа.

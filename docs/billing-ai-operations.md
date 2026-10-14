# AI admission и завершение операций

Реализация задания 04. Денежные значения — целые сотые Товарика.

## Включение

`tovarika.billing.ai-admission-enabled` по умолчанию `false`.
Соответствующая переменная Spring configuration: `TOVARIKA_BILLING_AI_ADMISSION_ENABLED`.
Флаг читается при запуске приложения. Включение только по отдельному запросу после проверок и rollout-инструкции [billing-recovery.md](billing-recovery.md).
Миграция 019 добавляет nullable `project_jobs.billing_operation_id` и `billing_fence`;
backfill нет. Выключение admission не выключает finalization уже принятых billing jobs.
Jobs без operation остаются grandfathered при claim/recovery/complete/fail независимо от флага;
reserve задним числом не создаётся.

## Классификация

| Команда | Серверный код | Funding |
| --- | --- | --- |
| Создание карточки | image_generation | wallet |
| Retry failed generation / entire image edit | image_entire_edit | один общий free_entitlement на стабильный Card ID, затем wallet по цене generation |
| Прямоугольная правка / legacy region endpoint | image_region_edit | wallet |
| Стирание кистью | image_erase | wallet |
| Удаление фона | image_remove_background | wallet |
| Resize outpaint с новыми полями | image_outpaint | wallet |
| Resize crop / fit_pad / outpaint без полей | cpu_only | free_operation, без debit |
| Analysis, Undo/Redo, draft, upload, export | без платного admission | без debit |

Цены читает существующее versioned financial core. Prompt не классифицирует намерение.
`ResizePlan` использует один и тот же scale/rounding в admission и raster worker:
исходные размеры — Asset immutable base version, целевые — серверный aspect ratio.
Сигнал `Result.aiCalled` позволяет worker завершить сохранённый результат без capture при
отсутствии AI. Такой результат освобождает денежный резерв и бесплатное право; история debit
не пополняется. AI/storage failure также освобождает reserve, даже если Card снова `ready`.

## Replay и result fencing

Auth и форма payload проверяются до replay. Generation command digest версии 1 — SHA256 JSON
списка intent/create + project/template/prompt/ratio/variant/idea, включая null значения.
Retry имеет отдельный intent + project/card; сохранённые generation параметры карточки не
меняются retry. Image edit сохраняет прежний полный digest command/project/card/base/revision/
payload (в том числе brush mask); legacy region intent остаётся отдельным.

При replay возвращается прежний job и связанный immutable billing snapshot без текущего
catalog/funds/init grant. Changed payload/intent с тем же key даёт IDEMPOTENCY_CONFLICT.
Старые generation receipts без digest нельзя достоверно сравнить: их прежняя replay policy
сохраняется без назначения billing operation. Новые receipts содержат digest даже при выключенном флаге.
Converted trial aliases и разрешение collision lineage этапа 03 сохраняются.

Для новой команды readiness/busy/version guards проверяются до reserve, reserve выполняется
до INSERT job/placeholder в одной transaction. Недостаток средств не создаёт domain records
и не вызывает AI. Lazy user initialization использует AuthenticationStore.initializeBilling
перед финансовыми guards, включая старые converted trial streams без нового grant.

Claim/reclaim увеличивает domain attempt и financial fence в одной transaction. Complete/fail
сначала проверяет current processing attempt; stale worker не меняет domain или finance.
Generation дополнительно проверяет last_image_job_id, base_version и image_revision;
editing — исходную version/revision и last_image_job_id. Внутренний version conflict сохраняет
failed outcome и release вместе. Успех требует нового Asset/version и terminal job в той же
transaction, затем capture/consume. Ошибка БД откатывает всё, worker fail в следующей transaction
освобождает reserve. AI и object storage вызываются вне transactions с финансовыми locks.

## Порядок блокировок

Admission: auth User/Trial rows → при необходимости exclusive topology/ownership conversion
этапа 03 → topology/owner guards → wallets финансовой группы в стабильном порядке → trial
reference / stable card entitlement → Product/Project → новая operation / domain job → Card.
Новая operation не имеет конкурентов; существующие operation locks всегда берутся до domain.
Replay заканчивается после auth и read-only ownership/receipt lookup.

Claim/finalization: candidate job ID без lock → topology/shared → wallets финансовой группы
→ entitlement (если free) → existing billing operation → Product/Project → domain job → Card/version.
Перечитывание status/attempt после locks обязательно. Candidate выбирается без `FOR UPDATE`
job до wallet, чтобы не инвертировать порядок. Проигравший claim возвращает empty; следующий
scheduler tick попробует снова, без перехода к другому wallet в той же transaction.
Это может уменьшить throughput при одновременном выборе одного candidate; domain parallelism
в этой задаче не расширяется. Финансовое core не зависит от project guard/CARD_BUSY.

Одна операция никогда не резервируется повторно при recovery. Terminal financial effects
учитывают operation fence и исходный wallet stream после ownership merge. Получение публичного
snapshot выполняется только после domain ownership validation в controller; ledger/usage/fence
в API не выдаются. У grandfathered jobs billing property отсутствует.

## Operational pause и recovery

`tovarika.billing.ai-launches-enabled=false` запрещает новые AI generation/retry/edits
с HTTP 503 `BILLING_AI_PAUSED`, сохраняя replay, pending finalization и CPU resize.
Это отдельный gate от accounting policy; выключение accounting само по себе не pause.
Lease/heartbeat, лимиты и reconciliation описаны в [billing-recovery.md](billing-recovery.md).

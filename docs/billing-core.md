# Billing core — задание 02

Реализация в `feature/billing`, 14 октября 2026. Нормативный источник — API contract 1.6.0,
`tovarika-api-contract/docs/billing/ai-contract.yaml`. AI admission, trial bootstrap и auth conversion
ещё не подключены к ядру: это задания 03/04. При чтении новым владельцем создаётся пустой wallet,
без демобаланса и без автоматического grant. Старые jobs завершаются по прежней политике.

## Данные и гарантии

Все таблицы начинаются с `billing_`: accounts, owners, wallets, price_versions, prices,
configuration, operations, reservations, entitlements, ledger, trial_grants, history_cursors.
У них нет FK на user/trial/workspace, карточку или domain job. Ownership — непрозрачный alias,
job — `(job_namespace, job_id)`, free-entire subject — стабильный opaque Card ID.
Удаление workspace не удаляет ledger, accepted receipts или использованное бесплатное право.

`BIGINT` хранит целые сотые Товарика, диапазон `[0, 9007199254740991]`. Начальные цены:
generation/entire/outpaint 400, region/erase/remove-background 200, analysis/CPU 0; trial 600.
Опубликованные версии и accepted snapshots защищены триггерами от изменения.
Ledger допускает только дополнительные положительные grant/debit/compensation postings;
UPDATE/DELETE запрещены. Финансового posting для reserve/release/free outcome нет.
CPU/no-AI outcome принятого платного плана завершать через `released`, сохраняя исходный receipt.

Wallet guard сериализует проверку available, reserve, capture, release, grant и compensation.
Capture обновляет total/used, снимает reserve и вставляет ровно один debit.
Повтор terminal effect безопасен; противоположный outcome и старый fence возвращают false.
Компенсация имеет уникальный reference и не может суммарно превысить исходный debit;
ограничение дополнительно проверяется PostgreSQL trigger. Она не возвращает free-entire право.
Consumed entitlement защищён от удаления/изменения. Failed reserve возвращает право.

Trial reference уникален глобально; grant receipt также уникален для wallet, включая allowance=0.
Смена configuration или появление нового ownership alias не выдаёт второй trial.
Это primitive для задания 03, а не изменение существующей auth/trial политики.

## Единый порядок locks для 03/04

Все вызовы используют одну Spring/JPA/JDBC transaction и один datasource/transaction manager.
Не запускать financial calls через REQUIRES_NEW. По нескольким ключам одного ранга —
лексикографический порядок, по wallet — сортировка ID.

1. При auth conversion — необходимые auth owner rows (`users`/`trial_sessions`) в стабильном порядке.
   Admission тоже проверяет/блокирует доступность owner на этом этапе; terminal worker не берёт auth rows.
2. Billing owner advisory guards: `hashtextextended(kind + ':' + subject, 7202)`.
   Для conversion заранее вызвать `prepareOwners` для всех involved aliases, до wallet locks.
3. `billing_wallets FOR UPDATE`. После получения lock читать свежие amounts/state.
4. Для grant/compensation — reference advisory guard, namespace seed 7203.
5. Для entire — `billing_entitlements FOR UPDATE`, затем financial operation/fence update.
6. Domain product, project, существующая domain job, Card, image-version/asset rows в этом порядке.
   Новый job при admission создаётся после financial admission в той же transaction.

Не брать billing wallet после захвата domain job/Card/project. Текущие legacy stores имеют
собственный порядок, например image-edit terminal берёт job перед Card, generation обновляет
project после job. В задании 04 нужно привести funded admission/claim/terminal к порядку выше,
а не вставить billing calls внутрь старого lockCurrent. До этого платные пути не включать.
Recovery сначала обнаруживает кандидата без domain row lock, затем получает wallet и fence;
после этого проверяет attempt/lease/current job под domain lock. AI/MinIO вне transaction/locks.

## Прикладной API

`BillingService` mutation methods требуют **существующую transaction** (`MANDATORY`).
`BillingStore` дополнительно отвергает mutation без активной transaction.

| Вызов | Назначение |
| --- | --- |
| `prepareOwners(List<Owner>)` | Guards всех aliases перед conversion и wallet locks |
| `wallet(owner)` | Find/create пустого wallet, согласованные total/available/used |
| `grantTrial(owner, originalIdentity)` | Единственный grant текущего allowance; reference исходной identity |
| `attachOwner(walletId, owner)` | Добавить alias к тому же account без изменений wallet/receipts/reserves |
| `replay(owner, command)` | Receipt/digest check до price, funds и domain busy/version validations |
| `admit(owner, command)` | Повторяет replay guard, фиксирует текущую цену, резервирует funds/right |
| `claim(operationId, expectedFence)` | Увеличивает financial fencing token при первом claim/reclaim |
| `finish(operationId, fence, captured/released)` | Fenced capture/consume либо release |
| `compensate(walletId, debitId, reference, units)` | Внутренняя ограниченная компенсация исходного debit |

Command включает namespace/key, текущую версию SHA256 digest и map digests для поддерживаемых
предыдущих версий, server code, стабильный entitlement subject только для entire и opaque job link.
При replay используется сохранённая digest version, а новый job ID из входной команды игнорируется.
В digest включать command namespace, targets и нормализованный payload/defaults;
не включать auth, меняющегося владельца, balance, price или случайный вновь сгенерированный job ID.

При admission: authorize/owner guard → replay → server classification/domain checks → admit →
создание job/placeholder. Все шаги с финансовыми/DB effects в одной transaction.
Исключение 402 содержит unit/requiredUnits/availableUnits/operationCode/priceVersion;
не ловить его с последующим commit частично принятой команды.

При finalization сначала захватить wallet/entitlement/operation guards до domain locks
(см. `CardJobBilling.lock`), затем проверить current processing attempt и version, сохранить
валидный результат и terminal job и вызвать `finish` в той же transaction. False financial fence
требует rollback всей transaction, включая domain result. Internal version conflict сохраняет
failed outcome и release вместе. Ошибка сохранения/commit откатывает result и finance;
последующая fenced failure transaction освобождает reserve, если успех ещё не был committed.
Replay terminal financial outcome true означает только идемпотентный финансовый эффект;
повторный domain finish не заменяет уже сохранённый результат. PostgreSQL-тест проверяет rollback
capture вместе с результатом. Provider HTTP success сам по себе не разрешает debit.
При unknown commit outcome сначала разрешить fenced terminal state под job lock; удалять
object только после проверки отсутствия ссылки в assets. Детали — [billing-recovery.md](billing-recovery.md).

`attachOwner` добавляет alias только когда целевой owner ещё не связан с другим account.
Попытка прикрепить owner другого существующего account отклоняется без переноса/начисления.
OAuth merge двух уже существующих financial accounts должен быть реализован в auth conversion
задания 03 с сохранением обоих ledger/receipt streams; этот helper нельзя использовать для
перезаписи alias и потери второго wallet. Старый alias сам по себе не авторизует expired/converted trial:
доступность identity проверяет `WorkspaceIdentityResolver` и auth lifecycle.

## Read API и история

GET `/api/v1/billing/credits/{wallet,summary,history}` принимает bearer или доступный trial cookie.
Bearer имеет приоритет; invalid bearer даёт 401 без fallback. Prices публичен.
Используются DTO и path constants из сгенерированного контракта; legacy BillingApi не реализован.
Все ответы имеют `Cache-Control: no-store`.

Summary читает wallet/net used под тем же wallet lock; `usedUnits` — lifetime debits минус
внутренние компенсации. History показывает только trial_grant/ai_debit, без внутренних ссылок,
резервов, refund, бесплатных результатов или товара. balanceAfter immutable и относится к total.
Скрытые postings исключаются до LIMIT. Сортировка — wallet ledger sequence; occurredAt монотонен,
ties сортируются sequence. Даты — `[from 00:00 Moscow, (to+1) 00:00 Moscow)`.

Cursor — случайный opaque ID серверной записи: wallet/filters/sort/limit/last/initial upper sequence.
Продолжение исключает новые postings. Токен действует 7 дней; invalid/expired/mismatched cursor — 400.
Операционный cleanup разработчика: `DELETE FROM billing_history_cursors WHERE expires_at < clock_timestamp();`.
Таблица cursor не финансовый ledger; её cleanup не затрагивает историю.

## Ручная смена configuration без restart

Работать через утверждённый доступ к PostgreSQL; credentials не помещать в репозиторий/документы.
Перед публикацией проверить полный набор восьми codes и суммы в сотых Т, reason и unique version ID.
Новая версия сначала draft. В одной transaction заполнить её, проверить, publish и activate:

```sql
BEGIN;
INSERT INTO billing_price_versions
    (id, trial_allowance_units, free_entire_successes, effective_from, change_reason)
VALUES ('billing-example-v2', 600, 1, clock_timestamp(), 'Reviewed price revision');
INSERT INTO billing_prices(version_id, operation_code, amount_units)
SELECT 'billing-example-v2', operation_code,
       CASE WHEN operation_code IN ('image_generation','image_entire_edit') THEN 500
            ELSE amount_units END
FROM billing_prices WHERE version_id = 'billing-2026-10-14-v1';
SELECT operation_code, amount_units FROM billing_prices
WHERE version_id = 'billing-example-v2' ORDER BY operation_code;
UPDATE billing_price_versions SET published_at = clock_timestamp()
WHERE id = 'billing-example-v2' AND published_at IS NULL;
UPDATE billing_configuration SET active_version = 'billing-example-v2' WHERE singleton = true;
COMMIT;
```

Это пример изменения generation/entire до 5 Т, не дополнительная миграция/реальный прайс.
Triggers проверяют completeness, equality entire/generation, free codes, safe range и published state.
Audit — immutable version, created_by, change_reason, effective_from, published_at.
После COMMIT проверить GET prices и новую test admission; replay принятой команды остаётся прежним.
Опубликованную версию не редактировать. Откат — атомарная активация предыдущей опубликованной версии,
без изменения операций и их accepted priceVersion. effective_from — метаданные; активирует pointer,
автоматического scheduler для будущего effective_from нет.

## Проверки

`JAVA_HOME=/home/malexey/.jdks/corretto-21.0.12 ./gradlew --offline test` — все backend tests.
`npm run check` в API contract — lint/bundle/examples/TypeScript/Orval.
Новые тесты используют изолированный PostgreSQL 18 Testcontainers и stub mode, без provider calls.
Покрыты reserve/replay/capture/refund/entitlement гонки, rollback и fences, integer scale/range,
immutable ledger/snapshot/price, price activation без restart, free/no-AI, alias и trial grant,
HTTP auth/DTO/validation, фильтры/cursor snapshot/Moscow boundaries/ties/net used.
Фактические результаты последнего прогона приведены в `billing-core-result.md`.

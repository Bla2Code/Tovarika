# Trial credits и ownership — задание 03

Реализация в `feature/billing`, 14 октября 2026. Контракт 1.6.0: обычные Товарики,
100 units = 1 Т. Trial allowance берётся из опубликованной PostgreSQL configuration;
начальное значение 600 units. Платный AI admission и новые auth экраны не включены.

## Bootstrap и совместимость

Создание trial identity, wallet и единственного grant — одна JPA/JDBC transaction.
Повтор POST или GET блокирует доступную `trial_sessions` row и возвращает тот же grant,
wallet и expiry. Unique reference `trial:trial:<id>` и проверка всей финансовой группы
сериализованы под wallet lock, включая нулевой allowance. `allowanceUnits` — сохранённый
snapshot grant, а `wallet` — текущий total/available. Оба поля всегда присутствуют в Trial API.

`active/exhausted` зависит от total, а не available: полный reserve ещё не делает trial
exhausted; release/compensation сохраняет или возвращает active. Analysis/CPU/free right
не требуют положительного остатка. Старые generation counters остаются required wire/DB
snapshots для текущих clients, не admission policy. Проверены main/test consumers backend и UI: в UI counters встречаются в существующих test fixtures,
а контракт сохраняет их required для generated DTO совместимости до отдельного breaking change.

Сохранены rate limit, Origin, TTL, cookie protection, bearer priority и запрет доступа по
converted/expired cookie. Очистка cookie создаёт другую identity при разрешённых abuse limits;
она не обеспечивает надёжное предотвращение повторного trial. Fingerprint не стал identity.

## Точная инициализация существующих accounts

**Ленивая, без массового backfill и без startup job**:

1. Новый User без cookie получает единственный configured grant в transaction регистрации/email
   или завершения OAuth. Registration bonus отсутствует.
2. Существующий ACTIVE User инициализируется при успешном email login, OAuth без trial cookie,
   либо первом GET credit wallet/summary/history. Prices не выдаёт grant. Пользователи без этих
   действий пока не получают новый wallet/grant. Blocked/deleted/unknown User не инициализируется.
3. Под lock User сначала обнаруживаются все ранее конвертированные trial identities. Их прежние
   wallet aliases привязываются/объединяются до попытки grant User. Уже выданный grant, в том числе
   нулевой, препятствует повторному начислению. Старый converted/expired cookie остаётся недоступным.
4. Доступная старая anonymous identity инициализируется при bootstrap/GET или conversion; counters
   не уменьшают её configured grant. При OAuth merge новое начисление не создаётся, если grant
   уже есть у одной из сторон. Два уже существующих grants сохраняются, не дублируются.
5. Liquibase 018 добавляет только links/order projections для существующих финансовых строк.
   DEMO/legacy balances и history не импортируются; auth/workspace данные не очищаются.
   Сумма фиксируется по configuration в момент первого grant, не ретроактивно при её смене.

Оплачиваемые jobs до задания 04 не создаются; существующие legacy jobs продолжают завершаться
по прежней политике. Billing operations им ретроактивно не назначаются. Новые funded paths
должны запускать эту инициализацию до admission и domain locks.

## Conversion и merge

`EmailAuthenticationService` и `YandexOAuthService` проверяют presented cookie; invalid/terminal
cookie даёт 401 и откатывает создание User/identity/session. Existing email conflict проверяется
раньше conversion. Cookie очищает существующий controller после успешного commit.

`JpaAuthenticationStore.convertTrial` flush-ит pending JPA user insert, блокирует User, затем
trial row и переносит financial ownership до `products` update. Всё использует один datasource
и transaction manager; ошибка workspace update откатывает alias/merge/grant/reserve вместе с auth.
Product/Project/Card/Asset IDs сохраняются, а jobs получают доступ через прежний Product.
Generation и image editing ищут существующие job/Undo/Redo receipts по текущему scope и
сохранённым converted trial aliases. Их owner_scope не переписывается. При прежних одинаковых
keys из разных accounts generation выбирает единственный project/card target, editing — digest;
ambiguity даёт conflict. Analysis аналогично выбирает единственный Product при нескольких receipts.
Повтор queued/processing/completed/failed команды возвращает прежнюю job, а terminal-failure retry
с новым key создаёт новую job и может снова зарезервировать освобождённое free-entire право.

У нового User без wallet сохраняется исходный wallet ID. При OAuth-входе в User с другим wallet
`billing_wallet_links` объединяет streams под существующий user wallet. Root хранит сумму total,
reserved и lifetime net used; старые balances становятся нулевыми техническими projections.
Ledger, grant references, operation/reservation wallet IDs, snapshots, fences, job links и
entitlement subjects не переписываются. Worker по старому operation ID находит root lock,
списывает/освобождает резерв в суммарном wallet и пишет debit/compensation в исходный ledger stream.
Таким образом компенсация прежнего debit и завершение pending jobs работают после merge.

`billing_ledger_order` — изменяемая проекция порядка history, не финансовый ledger. Merge объединяет
строки по occurredAt и стабильному порядку original stream; balanceAfter исходных posting неизменен.
Продолжение cursor, созданного до merge двух wallets, отклоняется 400: нужно запросить первую страницу.
Обычная conversion одного wallet сохраняет pagination snapshot. Новые cursors фиксируют объединённый
stream; внутренние события по-прежнему фильтруются до LIMIT.

Replay ищет receipt по всем сохранённым streams раньше funds/price. Разные ранее принятые keys
сохраняются; при одинаковом namespace/key выбирается единственный matching code/digest.
Если в старых независимых accounts были полностью одинаковые digests, нужна **доверенная lineage**:
optional `Command.originWalletId` из сохранённой связи command/target/job. Она не входит в digest,
не является публичным auth параметром и используется только для replay; чужой stream или отсутствие
receipt возвращает conflict. Не выводить lineage из случайного нового job ID и не выбирать первый
receipt при неоднозначности. Для задания 04: переносить trusted original wallet lineage вместе с
существующей domain idempotency scope. Старый восьмиаргументный Command constructor совместим.

## Порядок locks для задания 04

Уточняет порядок задания 02:

1. Auth owner rows: User, затем trial rows по ID; admission проверяет доступность owner здесь.
   Terminal worker auth rows не блокирует.
2. PostgreSQL topology advisory `(7204,1)`: **shared** при обычных financial transitions;
   **exclusive** при conversion/merge, до любого другого billing call в transaction.
   Shared → exclusive upgrade явно отклоняется, а не ожидает потенциального deadlock.
   Exclusive временно ждёт завершения текущих financial transitions и препятствует новой topology race.
   Не держать этот guard во время provider/MinIO вызова; это короткая DB transaction.
3. Billing owner advisory guards, все involved aliases в лексикографическом порядке.
4. Все retained wallet rows обеих групп по ID. Root amounts перечитываются после lock.
5. Grant/compensation reference, entitlement, operation/fence, затем domain product/project/job/Card.

Обычные операции используют shared topology guard и сохраняют параллельность между wallets.
Merge сериализует редкие ownership changes глобально и перестраивает history projection вовлечённых
кошельков в transaction; стоимость зависит от размера их истории. Не вызывать conversion после
admit/replay/wallet/grant и не использовать REQUIRES_NEW для financial effects.

`BillingService.convertOwner`, `grantTrial`, `trialAllowance` требуют caller transaction.
HTTP financial projections также охватывают identity availability и wallet read одной transaction.
Legacy admission нельзя просто дополнить financial вызовом после захвата domain lock.

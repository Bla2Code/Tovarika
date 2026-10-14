# Результат задания 03 — trial и ownership conversion

Выполнено 14 октября 2026 в `/home/malexey/project/tovarika/Tovarika`, ветка `feature/billing`.
Исходный diff задания 02 и контракт задания 01 сохранены. Коммит, push и PR не создавались.

## Изменения

- Trial bootstrap/GET атомарно создаёт/находит wallet и единственный configured grant;
  начально 600 units = 6 Т. Повторы и гонки не начисляют ещё один trial. Grant виден как trial_grant.
- Trial API всегда отдаёт allowanceUnits/wallet; active/exhausted вычисляется по total.
  Required legacy counters сохранены как совместимые snapshots, без billing quota.
- Email/Yandex conversion использует одну JPA/JDBC transaction: User/trial locks,
  financial alias/merge, JPA flush и перенос Product ownership. Invalid/terminal cookie
  откатывает регистрацию; workspace failure откатывает auth и financial effects.
- OAuth merge сохраняет два ранее существующих ledger/receipt streams и grants без нового mint,
  суммирует остаток/reserves/net used под root guard. Immutable operation/grant/ledger references
  не меняются. Старый worker завершает/компенсирует операцию в том же исходном financial stream.
- Domain generation/edit/history replay ищет original scopes через converted trial aliases;
  analysis/generation выбирают единственный target при прежних collision keys. Owner scopes
  не переписываются. Ambiguous финансовый replay требует trusted Command.originWalletId.
- Liquibase 018: billing_wallet_links, billing_ledger_order и индекс converted trial aliases.
  Прежние changesets не менялись. Порядок locks дополнен shared/exclusive topology guard;
  shared → exclusive upgrade явно отклоняется до потенциального deadlock.
- Добавлены 19 meaningful PostgreSQL integration tests: 14 auth/domain и 5 billing core.

## Инициализация старых пользователей

Выбрана **ленивая инициализация**: существующий ACTIVE User получает один trial при успешном
email login, OAuth без trial cookie или первом GET wallet/summary/history. Prices не выдаёт grant.
Сначала связываются уже конвертированные trial identities; существующий grant в финансовой группе,
в том числе нулевой, предотвращает второй. Новый User без trial получает тот же единственный grant,
без registration bonus. Доступная старая anonymous identity получает grant при bootstrap/GET/conversion.
Сумма фиксируется по опубликованной конфигурации в момент первого начисления.

Массового backfill/startup job нет. DEMO balance и legacy history не импортируются; реальные данные
не очищаются. Пользователи без перечисленных действий пока не инициализируются. Rate limit,
Origin/cookie/TTL защиты сохранены; удаление cookie не обещает защиту от нового anonymous trial.

## Проверки

`JAVA_HOME=/home/malexey/.jdks/corretto-21.0.12 ./gradlew --offline test` — **PASS**, 226 tests:
225 passed, 1 прежний skipped, 0 failures/errors, 2m 27s.
Auth integration: 53/53; billing core: 19/19; billing HTTP: 4/4. Прежние suites проходят.
Master changelog повторно применяется без duplicate grants/schema changes.

`npm run check` в API contract — PASS: public/provider lint и bundle, billing examples,
TypeScript/Orval generation. Backend git diff --check и whitespace новых файлов — PASS.
Generated DTOs и весь main/test source скомпилированы Gradle по актуальному контракту 1.6.0.

Покрыты concurrent bootstrap/grant/conversion, прежний configured allowance после смены configuration,
email/OAuth pending reserves, OAuth merge двух wallets без потери history/references,
worker/merge race и один debit, replay до/после conversion и conflicting key namespaces,
refund прежнего debit после merge, hidden history/pagination snapshots, rollback workspace transfer,
lazy existing-user grant, legacy converted identity и отказ invalid cookie без нового User.
Реальные generation/edit/analysis stores проверяют завершение job и replay после conversion,
technical failure и retry с возвратом free-entire права. Всё в изолированных PostgreSQL 18 Testcontainers,
без рабочей БД, provider calls, MinIO uploads и платежей; финансовые receipts для legacy jobs создаются
только test fixtures, не production backfill.

## Границы и передача заданию 04

**Платный AI admission не включён.** Production legacy jobs завершаются по прежней policy,
без ретроактивного назначения billing operation. Задание 04 подключает mutation primitives
к реальным admission/claim/terminal путям в одном transaction с domain outcome и fencing.
Обновлённый порядок locks, trusted replay lineage, объединённая история и точная процедура
инициализации описаны в [billing-trial.md](billing-trial.md). При merge двух wallets прежние history
cursors получают 400; новый запрос первой страницы создаёт корректный объединённый snapshot.
Обычная conversion одного wallet сохраняет курсоры. Оплата, новые auth экраны и регистрация сразу
после upload не реализовывались.

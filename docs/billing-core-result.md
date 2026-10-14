# Результат задания 02 — billing core

Выполнено 14 октября 2026 в `/home/malexey/project/tovarika/Tovarika`, ветка `feature/billing`.
До работы backend checkout был чистым. Исходные изменения задания 01 в API contract сохранены.
Коммит, push, PR и включение платных AI paths не выполнялись.

## Diff

- Самостоятельные `billing/domain`, `billing/application`, `billing/infrastructure`, `billing/api`.
- Liquibase 017: 12 новых `billing_*` таблиц, seed текущего прайса и trial configuration,
  constraints/indexes и PostgreSQL guards для ledger/snapshots/prices/entitlement/compensation.
  Все предыдущие migrations сохранены; в master добавлен один include.
- Wallet lock, денежные reserve/capture/release, SHA256 versioned receipts/replay,
  financial fencing, единственный trial grant, bounded immutable compensation,
  бесплатное право entire по opaque stable Card subject.
- Четыре additive GET routes, bearer/trial identity resolution, typed financial errors,
  net-used summary, server history filters/keyset snapshot cursors/Moscow dates.
  В существующих security и exception handler добавлено только необходимое для этих routes/errors.
- Два новых PostgreSQL test classes: 14 core tests и 4 HTTP integration tests.
- [Схема locks, прикладной API и ручная смена прайса](billing-core.md).

Цены в сотых Товарика: trial 600; generation/entire/outpaint 400;
region/erase/remove-background 200; analysis/CPU 0. Все amounts — целые Long/BIGINT,
safe range до 9007199254740991. Новые financial tables не связаны FK с workspace/auth/domain jobs.

## Проверки окончательного diff

| Проверка | Результат |
| --- | --- |
| `JAVA_HOME=/home/malexey/.jdks/corretto-21.0.12 ./gradlew --offline test` | PASS, 207 tests: 206 passed, 1 существующий skipped, 0 failures/errors; 2m 5s |
| Новые PostgreSQL tests | PASS, 18/18, PostgreSQL 18-alpine Testcontainers |
| Все прежние API/migration tests | PASS; прежние Liquibase changesets не изменены |
| Повторное применение master changelog | PASS, seed/version не дублируются |
| Java DTO/codegen и main/test compile | PASS в Gradle с текущим контрактом 1.6.0 |
| `npm run check` в API contract | PASS, public/provider lint/bundle, billing examples, TypeScript/Orval generation |
| Backend `git diff --check` и whitespace новых файлов | PASS |

Финансовые тесты проверяют конкурентные admission одного/разных ключей, reserve без overspend,
replay и conflict, ровно один debit, повтор release/capture, stale fence, rollback capture и
сохранённого результата вместе, unique grants/нулевой allowance/ownership aliases, компенсации
с одним и разными references и общий предел debit, entire contention в одном и разных wallets,
consume/release/paid fallback, safe range и 0,01 Т, отсутствие free/CPU postings, immutable ledger
и snapshots, активацию новой версии без restart и неизменность ранее принятой цены.
History tests проверяют скрытые события до LIMIT, net-used после compensation, binding cursor
к owner/filter/sort/limit, snapshot без новых postings, московские границы и timestamp ties.
HTTP tests проверяют generated DTOs, bearer priority, invalid bearer без trial fallback,
trial cookie, 400 query validation и отсутствие технических полей/нулевых optional values.

Тесты выполнялись в отдельных Testcontainers, без рабочей БД, оплаты или реальных provider calls.
Runtime dependencies и build.gradle не менялись.

## Передача заданиям 03/04

Mutation methods `BillingService` — MANDATORY: job/result/financial effect должны использовать
одну transaction. Порядок: auth owner rows (где нужны) → billing owner guards → wallets →
reference/entitlement guards → financial operation → product/project → domain job → Card/version.
AI/MinIO — за пределами locks. Точный порядок и rollback protocol описаны в `billing-core.md`.

Задание 03 подключает единственный grant к доступной trial identity и existing users,
email/OAuth conversion и merge разных уже существующих accounts. `attachOwner` сохраняет один
account целиком; чужой существующий account отклоняется, а не перезаписывается.
Задание 04 подключает classify/replay/admit/claim/finish к общим admission/finalization/recovery,
нормализует существующие domain locks и публикует immutable snapshot в job envelopes.
До этих этапов новые GET projections показывают пустой wallet без автоматического grant;
legacy trial quota и AI charging policy продолжают работать по старым правилам.

Ручной price mutation выполняется только developer SQL transaction с новой immutable version,
audit metadata и атомарной сменой active pointer. Административного API/UI нет.

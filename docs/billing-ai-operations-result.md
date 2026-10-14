# Результат задания 04 — биллинг существующих AI-операций

Выполнено 14 октября 2026 в `/home/malexey/project/tovarika/Tovarika`, ветка `feature/billing`.
Изменения этапов 02/03 и пользователя сохранены. Изменения API contract не требовались.

## Реализовано

- CardGenerationService.start/retry и CardImageEditingService.start: проверка auth/payload,
  полный command digest/replay, серверная классификация, financial admission до SQL-создания
  job/placeholder. Job и reserve коммитятся вместе. Недостаток available возвращает 402 без
  domain records и AI calls.
- Immutable price/funding snapshot в accepted response и GET job. Replay сохраняет старый
  job/operation/snapshot до текущего прайса, funds и initialization. Create/retry intent и все
  create параметры участвуют в digest; image edit сохраняет полный прежний digest с masks.
- Общий free_entire stable Card entitlement для failed-generation retry и entire edit:
  reserve → consume только вместе с новым успешным result; technical/no-AI failure → release.
  После consume применяется фиксированная цена generation. Region, erase, background removal
  и настоящий AI-outpaint имеют собственные фиксированные цены.
- Один ResizePlan для preflight и worker rounding. CPU crop/fit_pad/outpaint без новых полей —
  нулевой receipt без debit. Worker передаёт aiCalled в finalization; paid preflight с отсутствием
  AI также сохраняет result с release. Analysis, upload, Undo/Redo, draft и export не получают debit.
- Claim/recovery связывает attempt и financial fence. Complete/fail + capture/release — одна
  DB transaction в stores. Stale attempt ничего не меняет; internal version conflict завершает
  failed/released. Generation проверяет last job/base version/image revision. Card.ready не
  используется как доказательство нового успешного результата.
- Сохранены CARD_BUSY, лимит карточек и существующие version guards. Trial/user ownership
  aliases, conversion/merge и lazy grant этапа 03 сохранены, включая старые converted trials.
- Liquibase 019: domain operation link/fence + unique operation index, без backfill и financial FK
  к workspace. Admission gate `tovarika.billing.ai-admission-enabled=false` по умолчанию.
  Jobs без operation продолжают старое завершение даже после включения. Отключение gate не
  блокирует capture/release уже принятых billing jobs.

## Покрытые пути

24 новых PostgreSQL/HTTP integration tests в CardBillingOperationsIntegrationTest и одна
добавленная проверка default gate в прежней CardImageEditingIntegrationTest:

1. Гонка двух проектов одного владельца: один кошелёк, overspend невозможен, проигравший
   insufficient admission не создаёт job/Card и не вызывает AI.
2. Гонка одного key: одна operation/reservation/placeholder, одинаковый job.
3. Create replay после смены прайса и недоступного текущего баланса, прежний capture amount;
   changed prompt/project и create/retry intent → conflict.
4. Free entire replay после смены прайса/current version; changed edit payload → conflict.
5. Paid generation technical failure → release; retry → free consume; последующий entire → paid.
6. Failed free edit → release; следующий entire снова free; после success следующий paid.
7. Free/paid edit internal version conflict → release без новой version/debit/consume.
8. Stale edit/recovered attempt: stale complete/fail ничего не списывают и не возвращают чужой reserve.
9. Stale generation/recovered attempt + generation version conflict → release без debit.
10. Rollback сохранённого free edit result/consume и paid generation result/debit при DB failure,
    последующий fail освобождает reserve.
11. Storage read/write failures при paid generation/local edit и free retry → release;
    free entitlement сохраняется для следующей команды.
12. CPU crop/fit_pad/outpaint без margins: ни AI call, ни debit; Undo/Redo также бесплатны.
13. AI-outpaint: фиксированный paid reserve, AI вызван один раз, debit после success.
14. No-AI finalization после paid preflight → release; no-AI free result не расходует право.
15. Region/erase/remove_background: fixed local prices, prompt не меняет классификацию.
16. Несколько concurrent operations одного логического проекта напрямую в core без CARD_BUSY:
    wallet guard обеспечивает no overspend независимо от domain guard.
17. Grandfathered generation/edit, принятые без operation, завершаются без reserve/debit/backfill.
18. Default admission выключен; legacy editing paths продолжают работать.
19. HTTP accepted/poll/replay возвращают один immutable billing snapshot.
20. Anonymous trial получает ту же admission policy и единственный grant.
21. Новый user admission связывает legacy converted trial wallet без второго grant.

Предыдущие PostgreSQL core tests дополнительно проверяют concurrent free-entitlement admission
между wallets, финансовые callbacks/compensation и ownership conversion/worker races.

## Окончательные проверки

| Проверка | Результат |
| --- | --- |
| `JAVA_HOME=/home/malexey/.jdks/corretto-21.0.12 ./gradlew --offline test` | PASS, 2m 37s |
| Полный набор | 251 tests: 250 passed, 1 прежний skipped, 0 failures/errors |
| Новые AI billing PostgreSQL/HTTP tests | 24/24 PASS |
| Editing suite с новой проверкой gate | 13/13 PASS |
| Main/test compile и codegen из актуального API contract | PASS в полном Gradle прогоне |
| `git diff --check` | PASS |

Только изолированные PostgreSQL 18-alpine Testcontainers, stub AI, fake in-memory storage;
рабочая БД, реальные provider calls и платежи не использовались. Коммит/push/PR не выполнялись.

## Lock order и передача следующим этапам

Финансовые guards берутся раньше Product/Project/job/Card. Claim сначала читает candidate ID
без lock, затем topology → wallets финансовой группы → entitlement → operation → Product/Project
→ job → Card/version. Status/attempt перечитываются после locks. Проигравший claim возвращает
empty и повторяется следующим scheduler tick, не берёт второй wallet в той же transaction.
Это сохраняет корректность и может снизить throughput при выборе одного candidate несколькими
workers; domain parallelism по заданию не расширялся. Admission сначала блокирует auth owner,
при необходимости связывает legacy trials механизмом этапа 03, затем financial guards и domain.
AI/MinIO никогда не удерживают эти locks. Полный протокол: [billing-ai-operations.md](billing-ai-operations.md).

Gate остаётся выключенным до этапов 05–07. Usage/cost accounting не добавлялись: это этап 05.
Исторические generation jobs без command_digest сохраняют прежнюю replay policy, поскольку
исходный payload нельзя восстановить достоверно; billing задним числом им не назначается.
Новые generation jobs имеют digest независимо от gate.

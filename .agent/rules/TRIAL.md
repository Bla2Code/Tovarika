# Trial workspace и выбор владельца

Читай при изменении anonymous workspace, identity resolution, conversion или счётчиков trial.
Authentication/cookies/Origin — [SECURITY.md](SECURITY.md).

## 1. Bootstrap и восстановление

- Модуль trial: TrialController → TrialSessionService/TrialSessionStore → JdbcTrialSessionStore.
  Код — `src/main/java/com/tovarika/tech/trial/`.
- POST `/api/v1/trial-session` без cookie создаёт identity (201), устанавливает cookie;
  нужен allowlisted Origin даже при первом запросе. С действующей cookie — 200
  без новой cookie, продления срока и изменения counters.
- GET читает только trial cookie; Bearer её не заменяет. Ответы — Cache-Control: no-store.
- Cookie: Secure/HttpOnly/SameSite=Lax/Path=/ без Domain, централизованная настройка.
  Raw opaque token существует на HTTP boundary; в БД хранится hash.
- TTL по умолчанию 30 дней (TRIAL_SESSION_TTL). Отсутствующая на GET, пустая,
  невалидная, expired/converted cookie — 401 TRIAL_SESSION_NOT_FOUND.
  Недействующая cookie не создаёт новый лимит.
- Creation rate limit: 10 новых identity в час на transport IP по умолчанию
  (TRIAL_CREATION_MAX_ATTEMPTS/TRIAL_CREATION_WINDOW); restore не расходует лимит.
  Произвольный X-Forwarded-For не принимается; trusted proxy настраивается в deployment.

## 2. Общая identity boundary

- trial/api/WorkspaceIdentityResolver выбирает валидный Bearer user, иначе trial cookie.
  Контроллер передаёт userId/trialSessionId явными аргументами в use cases.
- Не создавай resolver на каждый модуль и не принимай owner ID от UI.
  Trial mutations проходят централизованную Origin policy.
- Product — корень ownership для связанных Analysis/Project/Card/Job.
  Standalone Asset ownership пока не реализован; signed URL — capability.

## 3. Conversion и генерации

- Регистрация использует транзакционный hook AuthenticationStore.convertTrial.
  Он помечает session converted и меняет owner Product; доступ к Analysis/Project/Card/Job
  следует за Product. Public IDs/counters сохраняются; cookie очищается после commit.
- Перед JDBC update нужен JPA flush, чтобы FK видел нового User в той же транзакции.
  Сохраняй блокировки против создания ресурсов старым trial owner.
- С Bearer trial resources не переносятся неявно. Новые независимые ownership-таблицы
  включай в тот же conversion flow, не во второй механизм.
- Bootstrap создаёт generation limit 3, used 0; исчерпание — exhausted.
  CardGenerationService пока не проверяет/не списывает эти counters.
  Поля quota в API не означают реализованный billing/лимит генераций.
- Conversion не переписывает owner_scope jobs. Analysis replay ищется по текущему владельцу
  Product, Card replay — по сохранённому scope identity. Поэтому доступ к прежней Card/job
  сохраняется, но replay ключа генерации после trial → User сейчас не гарантирован.

Проверки: AuthenticationContractIntegrationTest по [SECURITY.md](SECURITY.md).

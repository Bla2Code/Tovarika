# Trial workspace и выбор владельца

Читай при изменении anonymous workspace, identity resolution, conversion или trial credits.
Authentication/cookies/Origin — [SECURITY.md](SECURITY.md); кошелёк, grant, блокировки,
AI admission и история — [BILLING.md](BILLING.md). Trial — обычные Товарики, не три попытки.

## 1. Bootstrap и восстановление

- Модуль trial: TrialController → TrialSessionService/TrialSessionStore → JdbcTrialSessionStore.
  Код — `src/main/java/com/tovarika/tech/trial/`.
- POST `/api/v1/trial-session` без cookie создаёт identity (201), устанавливает cookie;
  нужен allowlisted Origin даже при первом запросе. С действующей cookie — 200
  без новой cookie, продления срока и повторного grant.
- GET читает только trial cookie; Bearer её не заменяет. Ответы — Cache-Control: no-store.
- Cookie: Secure/HttpOnly/SameSite=Lax/Path=/ без Domain, централизованная настройка.
  Raw opaque token существует на HTTP boundary; в БД хранится hash.
- TTL по умолчанию 30 дней (TRIAL_SESSION_TTL). Отсутствующая на GET, пустая,
  невалидная, expired/converted cookie — 401 TRIAL_SESSION_NOT_FOUND.
  Недействующая cookie не создаёт новую identity/allowance. После удаления cookie POST
  создаёт отдельную identity с прежними abuse limits; старый workspace не восстанавливается.
- Creation rate limit: 10 новых identity в час на transport IP по умолчанию
  (TRIAL_CREATION_MAX_ATTEMPTS/TRIAL_CREATION_WINDOW); restore не расходует лимит.
  Произвольный X-Forwarded-For не принимается; trusted proxy настраивается в deployment.

## 2. Общая identity boundary

- trial/api/WorkspaceIdentityResolver выбирает валидный Bearer user, иначе trial cookie.
  Контроллер передаёт userId/trialSessionId явными аргументами в use cases.
- Не создавай resolver на каждый модуль и не принимай owner ID от UI.
  Неверный Bearer даёт 401 без fallback к trial. Trial mutations проходят централизованную Origin policy.
- Product — корень ownership для связанных Analysis/Project/Card/Job.
  Standalone Asset ownership пока не реализован; signed URL — capability.
- Credit wallet/summary/history принимают ту же workspace identity, prices публичен.
  `/me`, favorites, список проектов и export требуют User; trial export/download → REGISTRATION_REQUIRED.

## 3. Allowance, кошелёк и совместимость

- `TrialSessionService.funded` в транзакции bootstrap/GET вызывает `grantTrial`, читает
  wallet и сохранённый allowance. Начально 1600 units = 16 Т из active PostgreSQL configuration.
  Reference `trial:trial:<id>` уникален; повтор/race, включая allowance=0, не начисляет второй раз.
- `allowanceUnits` — snapshot первоначального grant, не остаток; `wallet.totalUnits` и
  `wallet.availableUnits` — текущие деньги. Оба поля возвращаются в Trial API.
- `active/exhausted` определяется total: exhausted при total=0, а не available=0 из-за reserve.
  Release/compensation может вернуть active. Analysis/CPU и доступное free-entire право
  работают при нуле; платному действию нужны available >= price, иначе 402 WALLET_INSUFFICIENT_FUNDS.
  Legacy TRIAL_LIMIT_EXCEEDED не применяется для credit admission.
- `generationLimit=3`, `generationsUsed=0`, `remainingGenerations` сохраняются как deprecated
  wire/DB snapshots. Это не quota, не деньги и не основание кнопок/admission; их не уменьшают
  по jobs/debits. Удаление требует отдельного breaking contract change.
- User без trial получает один grant в регистрации/OAuth, существующий ACTIVE User — лениво
  при login, credit read или funded admission. `AuthenticationStore.initializeBilling` сначала
  присоединяет ранее converted trial wallets, затем проверяет grant. Registration bonus нет.
  Смена allowance не меняет уже выданный grant; demo/legacy balance/history не импортируются.

## 4. Conversion и replay

- Регистрация использует транзакционный hook AuthenticationStore.convertTrial.
  Он помечает session converted и меняет owner Product; доступ к Analysis/Project/Card/Job
  следует за Product. Email и Yandex OAuth переносят finance в той же транзакции;
  public IDs сохраняются, cookie очищается после commit.
- Перед JDBC update нужен JPA flush, чтобы FK видел нового User в той же транзакции.
  Сохраняй блокировки против создания ресурсов старым trial owner; общий порядок auth →
  topology → financial guards → domain и ограничения convertOwner описаны в [BILLING.md](BILLING.md).
- С Bearer trial resources не переносятся неявно. Новые независимые ownership-таблицы
  включай в тот же conversion flow, не во второй механизм.
- Без прежнего user wallet остаётся тот же финансовый account/wallet. Если OAuth User уже
  имеет wallet, merge сохраняет оба ledger streams/aliases, balances, reserves, pending operations,
  receipts и entitlement subjects под canonical user wallet. Нового grant нет; ранее выданные
  grants не дублируются. Ошибка откатывает auth/workspace/finance вместе.
- Conversion не переписывает owner_scope jobs. Generation/edit/Undo/Redo replay ищет текущий
  scope и converted trial aliases; analysis сохраняет доступ через Product. Прежний key/payload
  возвращает прежнюю job/цену, включая pending и terminal jobs; неоднозначные aliases → conflict.
- Existing email conflict проверяется до conversion: 409 EMAIL_ALREADY_REGISTERED.
  Presented invalid/expired/converted cookie → 401 TRIAL_SESSION_NOT_FOUND, без нового User.
  Cookie TTL закрывает anonymous доступ, но не удаляет финансовый audit. Перед retention cleanup
  активные operations должны завершиться/release по recovery, без потери reserve.

Проверки: AuthenticationContractIntegrationTest по [SECURITY.md](SECURITY.md),
финансовые гонки/merge/pending finalization — suites из [BILLING.md](BILLING.md).

## Регистрация после анализа и опрос

- Гость загружает фото и получает анализ. «Продолжить» в UI открывает полную регистрацию,
  сохраняя returnPath к исходному товару и шагу выбора стиля.
- CardsController требует User для генерации, retry и image edit/undo/redo;
  действующая trial cookie получает 403 REGISTRATION_REQUIRED.
- Миграция 023 публикует новую версию allowance; прежние grants не изменяются.
- Новый email/OAuth аккаунт получает pending-запись registration_onboarding в той же транзакции.
  GET /me/onboarding возвращает completed; PUT принимает role/frequency/difficulty,
  берёт email из аккаунта и атомарно сохраняет ответы только один раз.
- Старые аккаунты без записи не опрашиваются. Завершённый опрос не появляется
  при повторном входе, очистке localStorage или на другом устройстве.

- Успешный loginWithEmail с действующей trial cookie также переносит товар и кошелёк.
  Неверный пароль не конвертирует trial; истёкшая/converted cookie не блокирует вход.
  Успешный вход очищает trial cookie. Объединение сохраняет исходные grants без нового начисления.

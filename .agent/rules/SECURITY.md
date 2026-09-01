# Правила развития Authentication и Spring Security

Этот файл обязателен к прочтению перед любым изменением authentication, authorization, пользовательских
сессий, cookies, CORS/CSRF, OAuth, паролей и защищённых endpoint. Он дополняет `RULES.md`; при расхождении
с API приоритет имеет Authentication Contract.

## 1. Источники истины и неизменяемые инварианты

- Security semantics определены в `../tovarika-api-contract/docs/authentication/ai-contract.yaml`, а HTTP API —
  в собранном `../tovarika-api-contract/dist/openapi.yaml`.
- Access token остаётся short-lived JWT с TTL 10 минут и claims `sub`, `sid`, `iss`, `aud`, `iat`, `exp`, `jti`.
- Refresh token остаётся opaque random token: raw value существует только в браузере и кратковременно внутри
  application/API-слоя, в PostgreSQL хранится только криптографический hash.
- Refresh всегда выполняет rotation; повторное предъявление consumed token отзывает всю token family.
- Rotation и reuse detection обеспечиваются транзакцией и блокировкой/условным update в PostgreSQL. Нельзя
  заменять их `synchronized`, JVM-lock, in-memory state или тестировать эту семантику на H2.
- Authentication не использует `HttpSession`. Нельзя добавлять refresh JWT, собственный JWT filter/parser или
  параллельную login/session-систему.
- JWT доказывает identity и session, но не хранит актуальные тарифы, подписки, entitlements или иные права.
  Authorization truth читается из актуального состояния соответствующего модуля.
- `SessionUserJwtValidator` проверяет active user и session в PostgreSQL на каждом Bearer request. Не обходи и не
  дублируй эту проверку в контроллерах; кэш разрешён только отдельным архитектурным решением с сохранением
  немедленной блокировки пользователя и revoke session.

## 2. Карта существующей реализации

- `auth/api/AuthenticationController` и `UsersController` — thin controllers, реализующие generated interfaces.
- `auth/api/RequestAuthenticationContext` — единственная HTTP-адаптация текущего `JwtAuthenticationToken`; отдаёт
  только `userId = sub` и `sessionId = sid`, читает cookies и безопасные request metadata.
- `auth/api/AuthenticationCookieService` — централизованное создание и очистка refresh/OAuth cookies.
- `auth/application/EmailAuthenticationService`, `SessionService`, `YandexOAuthService`, `UserAccountService` —
  use cases и transaction boundaries.
- `auth/application/port` — порты persistence, cryptography, mail, rate limiting и Yandex.
- `auth/infrastructure/security/SecurityConfiguration` — Resource Server, JWT encoder/decoder, route policy,
  stateless sessions, CORS и handlers ошибок.
- `auth/infrastructure/security/AllowedOriginFilter` — Origin-защита cookie-authenticated mutations.
- `auth/infrastructure/security/SessionUserJwtValidator` — централизованная runtime-проверка user/session.
- `auth/infrastructure/persistence/JpaAuthenticationStore` — реализация `AuthenticationStore`, включая DB locking.
- `src/main/resources/db/changelog/changes/001-authentication.yaml` и последующие changesets — security schema.
- `src/test/java/com/tovarika/tech/auth/AuthenticationContractIntegrationTest.java` — основной executable пример
  security semantics и тестовой инфраструктуры.

Не создавай второй controller/service/repository для уже существующего flow. Сначала расширяй соответствующий
use case или порт из этой карты.

## 3. Как добавлять защищённые endpoint и новые сервисы

1. Сначала найди operation и security requirement в OpenAPI. Изменение API сначала вносится в contract repository.
2. Контроллер реализует generated interface. Для protected operation он получает пользователя через
   `RequestAuthenticationContext.principal()`, а не из request body/query/path и не через ручной разбор JWT.
3. Контроллер передаёт `userId` и при необходимости `sessionId` явными аргументами application-сервису.
   Application/domain-код не зависит от `SecurityContextHolder`, servlet API или generated DTO.
4. Новый application-сервис использует constructor injection и узкие порты. Он не обращается к JPA-репозиториям
   другого модуля напрямую. Если продуктовому модулю нужны user state или права, создай узкий facade/port вместо
   зависимости от `AuthenticationStore` или auth JPA entities.
5. Все новые маршруты по умолчанию защищены `.anyRequest().authenticated()`. Добавляй маршрут в `permitAll` только
   когда OpenAPI явно объявляет его публичным, и покрывай это HTTP-тестом.
6. Не добавляй роли, тарифы и entitlements в JWT как источник решения о доступе. Проверяй владельца ресурса и
   актуальные права внутри application use case.
7. Возвращай ошибки через `AuthException`/общую OpenAPI error schema. Не раскрывай причины signature, token lookup,
   account existence и provider failures.

## 4. Транзакции, tokens и persistence

- Граница security-транзакции находится на public application use case. Контроллер не помечается `@Transactional`.
- `SessionService.rotate` сохраняет family revocation даже при выброшенном `AuthException`; его
  `noRollbackFor` является частью reuse/expiry semantics. Не удаляй и не расширяй rollback policy без теста,
  подтверждающего итоговое состояние БД после ошибки.
- Для refresh сначала выполняется hash raw cookie, затем `AuthenticationStore.lockRefreshCredential`. Один token
  может породить только одного replacement. Absolute expiration family не продлевается rotation.
- Одноразовые verification/reset/OAuth attempt tokens consume-ятся атомарно. Изменение password отзывает sessions
  согласно контракту; reset отзывает все sessions.
- Raw refresh/verification/reset/trial token, OAuth code, PKCE verifier и provider tokens нельзя сохранять в БД,
  DTO, events или logs. Внутренний `SessionGrant.rawRefreshToken` разрешено использовать только для формирования
  `Set-Cookie`; он не должен попадать в JSON response.
- Схему усиливай `UNIQUE`, `CHECK`, FK и индексами. Любое изменение — новый Liquibase changeset; применённые файлы
  не переписываются. При добавлении auth-таблицы обновляй очистку integration test fixture.

## 5. Cookies, Origin, CORS и CSRF

- Cookie flags и имена меняются только через `AuthenticationProperties` и `AuthenticationCookieService`.
  Production defaults: `HttpOnly`, `Secure`, `SameSite`, `Path=/`, без broad `Domain`.
- Любой новый state-changing endpoint, использующий refresh/OAuth/trial cookie как credential, должен быть добавлен
  в централизованную Origin policy. Для него нужны тесты allowed/missing/unknown Origin.
- Credentialed CORS использует только явный `tovarika.security.cors.allowed-origins`; wildcard запрещён.
- CSRF отключён потому, что Bearer JWT не является ambient credential, а существующие cookie-authenticated
  mutations защищены strict Origin validation. Нельзя добавлять новый cookie-authenticated mutation, не расширив
  эту модель и не задокументировав решение.

## 6. Как писать security-тесты

Основной suite запускается так:

```bash
./gradlew test --tests com.tovarika.tech.auth.AuthenticationContractIntegrationTest
```

Требования к acceptance/integration test:

- Используй `@SpringBootTest`, настоящий `SecurityFilterChain`, `JwtDecoder`, Liquibase/JPA и PostgreSQL 18 через
  Testcontainers `@ServiceConnection`. Docker daemon должен быть доступен.
- Не ставь `@Transactional` на test class/method для concurrency и commit/rollback сценариев: отдельные потоки
  должны входить в реальные транзакции Spring proxy.
- Для HTTP security используй `MockMvc` с `springSecurity()`. Получай настоящий access token через production
  service flow и передавай `Authorization: Bearer ...`.
- Не используй `@WithMockUser`, mock `JwtDecoder`, mock `SecurityFilterChain`, mock `AuthenticationStore` или H2
  в acceptance-тестах: они обходят проверяемую security pipeline/DB semantics.
- Через `@TestConfiguration` + `@Primary` заменяй только внешние boundary: SMTP/breached-password HTTP/Yandex.
  Test double не должен ослаблять проверяемый внутренний invariant.
- Для invalid JWT создавай token с контролируемыми claims/key и проверяй production `JwtDecoder` и HTTP 401.
- Для cookie mutation проверяй cookie flags, отсутствие raw token в JSON, обязательный allowed `Origin`, rejected
  unknown/missing Origin и отсутствие credentialed wildcard CORS.
- Для concurrency используй два синхронизированных потока, один raw refresh token и реальные service calls.
  Проверяй не только «один success», но и reuse error и итоговый revoke всей family в PostgreSQL.
- Для enumeration-safe commands сравнивай HTTP status, public error code/body и не делай assertions, раскрывающих
  существование account.
- Для secrets проверяй одновременно response JSON, `Location`/authorization URL, `Set-Cookie` policy и captured
  application logs. Не выводи secrets в имя теста, assertion description или диагностический log.
- Каждый тест создаёт уникальный email/ID и очищает таблицы в `setUp`. Новые mutable auth-таблицы включай в
  `TRUNCATE ... CASCADE`, чтобы результат не зависел от порядка тестов.

Изменение считается покрытым только если тест проверяет публичный HTTP outcome и, когда важно, итоговое security
state в PostgreSQL. Unit-тест одного helper не заменяет acceptance test rotation, reuse, revoke, Origin или JWT.

## 7. Обязательная проверка перед завершением

Минимум для любого изменения Security:

```bash
./gradlew test --tests com.tovarika.tech.auth.AuthenticationContractIntegrationTest
./gradlew test
```

Если менялись миграции, locking, transaction boundaries или concurrency, тесты обязательно выполняются на
Testcontainers PostgreSQL. Если Docker/Testcontainers недоступны, явно сообщи, какие security semantics остались
непроверенными; не подменяй их H2 или mock-based тестом.

# Регистрация и вход через Яндекс ID: backend

Документ описывает текущую реализацию Yandex OAuth в модуле `auth`.
Читай при изменении старта OAuth, callback, создания Yandex-аккаунта,
конвертации trial и выдачи сессии после входа через Яндекс.
Общие требования безопасности — [SECURITY.md](SECURITY.md), перенос гостевых
данных — [TRIAL.md](TRIAL.md), финансовая модель — [BILLING.md](BILLING.md).
HTTP API определяется соседним `../tovarika-api-contract/openapi/paths/auth.yaml`.

## 1. Один сценарий для регистрации и входа

Backend не разделяет «зарегистрироваться через Яндекс» и «войти через Яндекс»
на разные операции. В обоих случаях выполняется один OAuth-поток:

1. Создать одноразовую попытку и вернуть URL Яндекса.
2. Принять callback и проверить принадлежность попытки этому браузеру.
3. Обменять код на токен Яндекса и получить профиль.
4. Найти аккаунт по Yandex identity либо создать новый.
5. При наличии trial перенести гостевые данные и финансы.
6. Создать обычную сессию Tovarika, установить refresh cookie и вернуть браузер в UI.

Вход через Яндекс не требует пароля Tovarika. Повторный вход в тот же Yandex
аккаунт использует прежнего User, но создаёт новую сессию Tovarika.

## 2. Старт: POST /api/v1/auth/oauth/yandex/authorizations

`AuthenticationController.startYandexOAuth` применяет rate limit `OAUTH_START`
и вызывает `YandexOAuthService.start` с необязательным `returnPath`.

- Проверяется наличие Client ID и Client Secret. Если интеграция не настроена,
  старт завершается HTTP 500 с `OAUTH_PROVIDER_ERROR`.
- Путь по умолчанию — `/editor`. Принимается только относительный путь,
  соответствующий разрешённому backend prefix: сам prefix, его дочерний путь
  или query. Проверяются длина до 1000 символов, допустимые символы и decoded path;
  внешние URL, `//`, `..`, backslash и закодированные обходы отклоняются.
- Генерируются независимые случайные `state`, correlation token и PKCE verifier.
  В `oauth_attempts` сохраняются hashes state/correlation, verifier, безопасный
  `returnPath`, время создания, срок действия и признак использования.
- URL авторизации содержит `response_type=code`, Client ID, backend Redirect URI,
  `scope=login:email login:info`, state и PKCE challenge с методом `S256`.
- Ответ HTTP 200 содержит `authorizationUrl` и `expiresAt`. Correlation token
  передаётся отдельно в HttpOnly cookie через `AuthenticationCookieService`.

Correlation cookie связывает браузер с серверной попыткой. Она не содержит
сам verifier или весь объект попытки. TTL попытки и cookie берётся из `attemptTtl`.

## 3. Callback: GET /api/v1/auth/oauth/yandex/callback

Это адрес возврата от Яндекса в backend. Параметры: `code`, `state`, `error`
и `error_description`. Последний не используется для сообщений пользователю.

Контроллер сначала применяет rate limit `OAUTH_CALLBACK`. Затем
`returnPathForAttempt` извлекает доверенный путь для возврата при ошибке;
`complete` повторно проверяет попытку внутри транзакции завершения входа.

Проверка попытки включает:

- наличие correlation cookie и state;
- поиск и блокировку попытки по hash correlation token;
- отсутствие `consumedAt` и неистёкший `expiresAt`;
- сравнение hash state через `MessageDigest.isEqual`;
- повторную проверку сохранённого return path.

Нарушение этих условий даёт `OAUTH_STATE_INVALID`. Ошибка провайдера либо
отсутствующий/пустой code после проверки попытки даёт `OAUTH_PROVIDER_ERROR`.

### Обмен кода и проверка профиля

`YandexOAuthRestClient` реализует порт `YandexOAuthClient`:

1. Отправляет серверный POST на token endpoint Яндекса. В форме передаются
   `grant_type=authorization_code`, code, backend Redirect URI и PKCE verifier.
   Client ID/Secret передаются через HTTP Basic authentication.
2. Полученным OAuth-токеном запрашивает профиль через заголовок
   `Authorization: OAuth …`. Токен не добавляется в query.
3. Проверяет, что `client_id` профиля соответствует приложению, присутствуют
   идентификатор пользователя и `default_email`.
4. Сервис проверяет непустой provider ID, формат и длину email, а при наличии
   имени — непустое значение после trim и длину до 100 символов.

Ошибки HTTP-клиента, отсутствующий токен и неполный профиль преобразуются
в `OAUTH_PROVIDER_ERROR` без передачи ответа Яндекса пользователю.
OAuth-токен Яндекса используется для чтения профиля; его сохранения как
постоянного credential аккаунта в текущем flow нет.

## 4. Как создаётся или выбирается аккаунт

После получения допустимого профиля выполняется атомарный `consumeOAuthAttempt`.
Повторное использование уже успешно завершённой попытки запрещено.

Поиск аккаунта идёт по паре `AuthProvider.YANDEX` + `profile.providerUserId`,
а затем по связанному `userId`.

- Если identity найдена, используется существующий User. Повторный вход
  не обновляет его email и имя данными профиля Яндекса.
- Если identity отсутствует, создаются User со статусом `ACTIVE`, нормализованным
  email, `emailVerified=true`, именем из профиля и пустым avatar URL, а также
  identity `YANDEX` без password hash.
- `JpaAuthenticationStore.createUserWithIdentity` одновременно создаёт pending-запись
  в `registration_onboarding`. Опрос нового OAuth-пользователя работает через
  общие `GET/PUT /api/v1/me/onboarding`.
- Неактивный аккаунт не получает сессию: результат — `FORBIDDEN`.

Совпадение email с другим аккаунтом не связывает identities. Существующий
email-аккаунт и новый Yandex-аккаунт могут быть разными User с одинаковым email.
Сценарий явной привязки Яндекса к существующему аккаунту здесь не реализован;
добавлять автоматическую привязку только по email нельзя.

## 5. Гостевые данные, баланс и транзакция

`YandexOAuthService.complete` выполняется в одной application-транзакции.
Consume попытки, создание User/identity/onboarding, перенос trial, финансовые
изменения и создание сессии должны завершиться вместе либо откатиться вместе.

- При предъявленной trial cookie вызывается `AuthenticationStore.convertTrial`.
  После JPA flush проверяется и блокируется trial, переносится финансовый owner,
  session помечается converted, а Product получает `owner_user_id`.
- Доступ к Analysis/Project/Card/Job следует за Product; публичные ID сохраняются.
  OAuth не запускает генерацию: UI после входа и опроса продолжает сохранённую команду.
- Если у User уже есть кошелёк, используется существующий billing merge с сохранением
  балансов, ledger/history, reserves, pending operations и receipts. Подробности
  и порядок блокировок находятся в [TRIAL.md](TRIAL.md) и [BILLING.md](BILLING.md).
- Без trial cookie вызывается `initializeBilling`: существующие converted wallets
  присоединяются, начальный grant выдаётся идемпотентно. Повторный вход и конвертация
  не создают дополнительный регистрационный бонус.
- Пустая, неизвестная, истёкшая или уже converted trial cookie в OAuth даёт
  `TRIAL_SESSION_NOT_FOUND`. Транзакция откатывается; контроллер очищает эту cookie,
  чтобы новая попытка входа могла пройти без недействующего trial.

При ошибке до commit откатывается и consume попытки. Очистка correlation cookie
в HTTP-ответе сама по себе не помечает запись `oauth_attempts` использованной.

## 6. Сессия и возврат браузера

`SessionService.create` создаёт обычные `auth_sessions` и `refresh_tokens`.
В БД хранится hash opaque refresh token. Сессия привязана к User, имеет token
family, сроки действия и безопасные metadata устройства/IP.

Контроллер после успешного завершения сервиса возвращает HTTP 303:

- `Location: /auth/yandex/callback?return=<сохранённый-путь>`;
- новую HttpOnly refresh cookie;
- очистку correlation и trial cookies;
- `Cache-Control: no-store` и `Referrer-Policy: no-referrer`.

Имена, сроки и flags cookies централизованы в `AuthenticationProperties`
и `AuthenticationCookieService`. Для рабочего HTTPS OAuth-потока требуются
Secure/HttpOnly, `Path=/`, один канонический host и SameSite, допускающий
верхнеуровневый возврат с Яндекса (`Lax`).

`/auth/yandex/callback` — маршрут UI, а `/api/v1/auth/oauth/yandex/callback` —
backend endpoint, указываемый в приложении Яндекса как Redirect URI.
Backend не передаёт access/refresh/provider token или code/state в Location.
UI вызывает `POST /api/v1/auth/refresh`, затем `GET /api/v1/me` и открывает return path.
Refresh выполняет обычную rotation; reuse detection и отзыв сессий описаны
в [SECURITY.md](SECURITY.md).

## 7. Ошибки callback

Ошибки, обработанные внутри `completeYandexOAuth`, возвращают HTTP 303 на тот же
UI callback с безопасным параметром `error`. Ошибки старта OAuth остаются обычными
API-ответами; валидация запроса и security filters также могут завершить запрос
до входа в метод контроллера.

| Код в UI callback | Причина |
| --- | --- |
| `access_denied` | Яндекс вернул отмену доступа после успешной проверки попытки |
| `OAUTH_STATE_INVALID` | Cookie/state отсутствуют или неверны; попытка истекла либо уже использована |
| `OAUTH_PROVIDER_ERROR` | Нет code, сбой обмена, ошибка Яндекса или недопустимый профиль |
| `TRIAL_SESSION_NOT_FOUND` | Предъявлен недействующий trial |
| `FORBIDDEN` | Аккаунт неактивен |
| `RATE_LIMITED` | Превышен лимит callback |
| `INTERNAL_ERROR` | Неожиданное исключение при завершении входа |

По умолчанию return path ошибки — `/editor`; доверенный путь исходного товара
сохраняется только после успешного `returnPathForAttempt`. Подставлять путь из
непроверенных параметров callback нельзя. Неверный state с `error=access_denied`
остаётся ошибкой state, а не подтверждённой отменой.

Correlation cookie очищается и при успехе, и при обработанной ошибке. При ошибке
новая refresh cookie не выдаётся, существующая не перезаписывается; trial cookie
сохраняется, кроме `TRIAL_SESSION_NOT_FOUND`. В UI не передаются `error_description`,
тексты исключений, code, state и credentials.

## 8. Хранение PKCE: расхождение с общими правилами

Текущий `YandexOAuthService.start` сохраняет PKCE verifier в поле серверной
`OAuthAttempt`, которое persistence записывает в `oauth_attempts`. Это необходимо
учитывать при описании фактического хранения: в БД сохраняется исходный verifier,
а не его hash; correlation cookie содержит отдельный случайный идентификатор.

При этом [SECURITY.md, раздел 4](SECURITY.md#4-транзакции-tokens-и-persistence)
запрещает сохранение raw PKCE verifier в БД. Это существующее расхождение между
реализацией и правилом; данный документ его фиксирует и не отменяет запрет.
Способ хранения/защиты verifier и соответствующие требования требуют отдельного
согласованного изменения. Не добавляй verifier в DTO, события, URL или логи.

## 9. Карта кода и проверки

Все следующие пути относятся к `src/main/java/com/tovarika/tech/`:

- `auth/api/AuthenticationController.java` — endpoints, cookies, HTTP redirects и безопасные ошибки.
- `auth/api/AuthenticationCookieService.java` — создание и очистка cookies.
- `auth/application/YandexOAuthService.java` — state/PKCE, return path, выбор User и orchestration транзакции.
- `auth/application/port/YandexOAuthClient.java` — порт провайдера.
- `auth/infrastructure/oauth/YandexOAuthRestClient.java` — обмен кода и чтение профиля.
- `auth/infrastructure/persistence/JpaAuthenticationStore.java` — attempts, identities, onboarding и trial conversion.
- `auth/application/SessionService.java` — создание сессии, refresh rotation и revoke.

Основные проверки — `AuthenticationContractIntegrationTest` и
`RegistrationOnboardingIntegrationTest`: новый/повторный вход, HTTP callback,
cookie flags, state/PKCE, отмена, expiry/replay, недействующий trial, защита return path,
отсутствие автоматической привязки по email, перенос финансов и pending operations.

```bash
./gradlew test --tests com.tovarika.tech.auth.AuthenticationContractIntegrationTest --tests com.tovarika.tech.auth.RegistrationOnboardingIntegrationTest
```

Используются PostgreSQL/Testcontainers и подмена внешнего Yandex boundary.
Они не подтверждают настройку реального OAuth-приложения. Для изменения security-кода
также обязательны общие проверки из [SECURITY.md](SECURITY.md).

## 10. Файловые secrets в Docker Compose

Client ID и Client Secret в `compose.yaml` и `deploy/docker-compose.prod.yml`
монтируются из отдельных файлов через Compose secrets. Значения credentials
не передаются через environment контейнера. Java-код не реализует переменные
`YANDEX_CLIENT_ID_FILE` / `YANDEX_CLIENT_SECRET_FILE`: файлы подключаются штатным
Spring Boot `SPRING_CONFIG_IMPORT=configtree:/run/secrets/yandex/`.

| Source-файл | Файл внутри контейнера / имя свойства Spring |
| --- | --- |
| `yandex_client_id` | `/run/secrets/yandex/tovarika.security.oauth.yandex.client-id` |
| `yandex_client_secret` | `/run/secrets/yandex/tovarika.security.oauth.yandex.client-secret` |

Локальные source-файлы по умолчанию находятся в `../.secrets/` за пределами
репозитория; production — в `./secrets/` относительно production Compose-файла
(при штатном deployment — `/home/deploy/tovarika-backend/secrets/`). Оператор
создаёт их рядом с исходным `openai_api_key`, по одному значению без кавычек и `=`.
Для другого каталога задаются только пути `YANDEX_CLIENT_ID_SOURCE` и
`YANDEX_CLIENT_SECRET_SOURCE`; это не значения credentials.

Secrets создаёт оператор. Агент не открывает `.env`, source-файлы secrets или
`/run/secrets`. Каталог должен быть закрыт от других пользователей, а файлы —
читаемы UID/GID процесса приложения внутри контейнера. Для файловых secrets Compose
использует bind mount: `uid`, `gid` и `mode` в Compose не исправляют права host-файла.
Не расширяйте права до world-readable для обхода ошибки доступа.

`YANDEX_REDIRECT_URI` содержит публичный адрес backend callback, в production
по умолчанию `https://tovarika.com/api/v1/auth/oauth/yandex/callback`. Он должен
точно совпадать с адресом в приложении Яндекса. Локальный Compose сохраняет свой
localhost default; для входа на публичном домене задайте соответствующий HTTPS URL.
Импорт configtree обязателен для Compose-запуска `app`; отсутствие файлов или доступа
к ним нужно исправить до запуска. Существующий secret OpenAI подключается отдельно
и не импортируется как дерево настроек Яндекса.

При запуске без Compose остаётся поддержка `YANDEX_CLIENT_ID` / `YANDEX_CLIENT_SECRET`
из существующей системы конфигурации; реальные значения нельзя включать в команды,
репозиторий, логи или UI. После изменения deployment-конфигурации приложение нужно
пересоздать. Перед проверкой Compose отключите загрузку `.env`, интерполяцию и
вывод конфигурации; не запускайте обычный `docker compose config`, печатающий secrets
из environment. Эти проверки не подтверждают реальные credentials или вход в Яндекс.

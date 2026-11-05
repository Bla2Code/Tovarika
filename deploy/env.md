# Конфигурация сервисов

Справочник переменных Tovarika: приложение `app`, PostgreSQL, MinIO, синхронизация
шаблонов, сборка и deployment. Описания сверены с исходниками; значения из `.env`
и файлов секретов здесь не используются. Примеры секретов заменены placeholders.

Правила создания, хранения, подключения и переноса секретов в CI secret store:
[SECRETS.md](../.agent/rules/SECRETS.md). Этот справочник описывает **текущее поведение**;
единый стандарт из правил ещё не реализован во всех конфигурациях. В частности,
`SECRETS_DIR` пока не поддерживается; порядок перехода и реестр целевых имён находятся
в правилах. Перенос в Git hosting предполагает защищённое хранилище Secrets,
без коммита открытых значений в репозиторий.

## Содержание

- [Как передавать настройки](#как-передавать-настройки)
- [Контейнер приложения и порты](#контейнер-приложения-и-порты)
  - [APP_UID](#app_uid)
  - [APP_GID](#app_gid)
  - [APP_PORT](#app_port)
- [PostgreSQL](#postgresql)
  - [POSTGRES_DB](#postgres_db)
  - [POSTGRES_USER](#postgres_user)
  - [POSTGRES_PASSWORD](#postgres_password)
  - [POSTGRES_PORT](#postgres_port)
  - [DB_URL](#db_url)
  - [DB_USERNAME](#db_username)
  - [DB_PASSWORD](#db_password)
- [MinIO и доставка файлов](#minio-и-доставка-файлов)
  - [MINIO_ENDPOINT](#minio_endpoint)
  - [MINIO_ACCESS_KEY](#minio_access_key)
  - [MINIO_SECRET_KEY](#minio_secret_key)
  - [MINIO_BUCKET](#minio_bucket)
  - [MINIO_INITIALIZE_BUCKET](#minio_initialize_bucket)
  - [MINIO_PORT](#minio_port)
  - [MINIO_CONSOLE_PORT](#minio_console_port)
  - [ASSET_PUBLIC_BASE_URL](#asset_public_base_url)
- [JWT, cookies и CORS](#jwt-cookies-и-cors)
  - [JWT_ISSUER](#jwt_issuer)
  - [JWT_AUDIENCE](#jwt_audience)
  - [JWT_SECRET_BASE64](#jwt_secret_base64)
  - [UI_ALLOWED_ORIGINS](#ui_allowed_origins)
  - [AUTH_COOKIE_SECURE](#auth_cookie_secure)
  - [AUTH_COOKIE_SAME_SITE](#auth_cookie_same_site)
  - [REFRESH_COOKIE_NAME](#refresh_cookie_name)
  - [OAUTH_COOKIE_NAME](#oauth_cookie_name)
  - [TRIAL_COOKIE_NAME](#trial_cookie_name)
  - [BREACHED_PASSWORD_CHECK_ENABLED](#breached_password_check_enabled)
  - [PWNED_PASSWORD_API_URL](#pwned_password_api_url)
- [Yandex OAuth](#yandex-oauth)
  - [YANDEX_CLIENT_ID](#yandex_client_id)
  - [YANDEX_CLIENT_SECRET](#yandex_client_secret)
  - [YANDEX_REDIRECT_URI](#yandex_redirect_uri)
- [Почта](#почта)
  - [SMTP_HOST](#smtp_host)
  - [SMTP_PORT](#smtp_port)
  - [SMTP_USERNAME](#smtp_username)
  - [SMTP_PASSWORD](#smtp_password)
  - [SMTP_AUTH](#smtp_auth)
  - [SMTP_STARTTLS](#smtp_starttls)
  - [AUTH_MAIL_FROM](#auth_mail_from)
  - [AUTH_UI_BASE_URI](#auth_ui_base_uri)
- [Пробные сессии](#пробные-сессии)
  - [TRIAL_SESSION_TTL](#trial_session_ttl)
  - [TRIAL_CREATION_MAX_ATTEMPTS](#trial_creation_max_attempts)
  - [TRIAL_CREATION_WINDOW](#trial_creation_window)
- [OpenAI](#openai)
  - [OPENAI_MODE](#openai_mode)
  - [OPENAI_BASE_URL](#openai_base_url)
  - [OPENAI_API_KEY_FILE](#openai_api_key_file)
  - [OPENAI_VISION_MODEL](#openai_vision_model)
  - [OPENAI_IMAGE_MODEL](#openai_image_model)
- [Воркеры и биллинг](#воркеры-и-биллинг)
  - [ANALYSIS_WORKER_ENABLED](#analysis_worker_enabled)
  - [CARD_GENERATION_WORKER_ENABLED](#card_generation_worker_enabled)
  - [TOVARIKA_BILLING_AI_ADMISSION_ENABLED](#tovarika_billing_ai_admission_enabled)
  - [TOVARIKA_BILLING_AI_LAUNCHES_ENABLED](#tovarika_billing_ai_launches_enabled)
- [Robokassa](#robokassa)
  - [ROBOKASSA_MODE](#robokassa_mode)
  - [ROBOKASSA_MERCHANT_LOGIN](#robokassa_merchant_login)
  - [ROBOKASSA_TEST_USER_IDS](#robokassa_test_user_ids)
- [Файловые секреты](#файловые-секреты)
  - [SPRING_CONFIG_IMPORT](#spring_config_import)
  - [OPENAI_API_KEY_SOURCE](#openai_api_key_source)
  - [YANDEX_CLIENT_ID_SOURCE](#yandex_client_id_source)
  - [YANDEX_CLIENT_SECRET_SOURCE](#yandex_client_secret_source)
  - [ROBOKASSA_TEST_PASSWORD1_SOURCE](#robokassa_test_password1_source)
  - [ROBOKASSA_TEST_PASSWORD2_SOURCE](#robokassa_test_password2_source)
  - [ROBOKASSA_LIVE_PASSWORD1_SOURCE](#robokassa_live_password1_source)
  - [ROBOKASSA_LIVE_PASSWORD2_SOURCE](#robokassa_live_password2_source)
  - [Соответствие secrets и свойств приложения](#соответствие-secrets-и-свойств-приложения)
- [template-assets-init](#template-assets-init)
- [Дополнительные свойства Spring](#дополнительные-свойства-spring)
- [Сборка и GitHub Actions](#сборка-и-github-actions)
  - [TOVARIKA_API_CONTRACT_DIR](#tovarika_api_contract_dir)
  - [IMAGE_NAME](#image_name)
  - [DEPLOY_DIR](#deploy_dir)
  - [DEPLOY_HOST](#deploy_host)
  - [DEPLOY_USER](#deploy_user)
  - [DEPLOY_SSH_KEY](#deploy_ssh_key)
  - [DEPLOY_KNOWN_HOSTS](#deploy_known_hosts)
  - [CONTRACTS_REPO_TOKEN](#contracts_repo_token)
- [Параметры служебных скриптов](#параметры-служебных-скриптов)
- [Источники](#источники)

## Как передавать настройки

В описаниях используются три варианта запуска:

| Вариант | Источник настроек |
| --- | --- |
| Приложение | Базовые значения `application.properties` и Java defaults без внешних переопределений. |
| Локальный Compose | Корневой `compose.yaml`; приложение запускается с профилем `full`. |
| Production Compose | `deploy/docker-compose.prod.yml`; приложение доступно по сети `tovarika-edge`, без публикации портов на хосте. |

Переменные подстановки Compose и окружение контейнера — разные уровни. Значение,
заданное для Compose, попадёт в `app` только при наличии соответствующей записи
в `services.app.environment`. Например, `POSTGRES_DB` используется для сборки `DB_URL`,
а `ROBOKASSA_MODE` передаётся как `TOVARIKA_PAYMENTS_ROBOKASSA_MODE`.
Простое добавление произвольной переменной в deployment `.env` не передаёт её в контейнер.

При `bootRun` Spring Boot также может поднимать локальную Compose-инфраструктуру
и автоматически настраивать подключение к её PostgreSQL; в таблицах ниже приведены
именно defaults исходников, а не фактические значения конкретного запуска.

Настройки приложения, для которых в Compose нет записи `environment`, можно передать
при прямом запуске либо явно добавить в окружение `app` в Compose override.
Изменение окружения требует пересоздания контейнера; настройки читаются при старте.
Список всех стандартных настроек Spring Boot/JVM в этот справочник не входит.

Тип `list` означает значения через запятую, `duration` — длительность с единицей,
например `10m`, `1h`, `30d`. Для периодов с суффиксом `-ms` используются миллисекунды.
В полях «По умолчанию» пустая строка обозначается `пусто`, отсутствие настройки — `не задано`.
Локальные значения учётных данных обозначены `[REDACTED]`; production должен задавать свои.

## Контейнер приложения и порты

#### **APP_UID**

- **Тип:** integer
- **В приложении:** пользователь Linux контейнера `app` (`services.app.user`).
- **По умолчанию:** локальный Compose — `1000`; production Compose — обязательно.
- **Пример:** `1000`
- **Описание:** UID процесса Java. `activate-backend-deploy.sh` самостоятельно экспортирует UID пользователя, выполняющего deployment, через `id -u`, чтобы приложение могло читать файловый secret OpenAI.

#### **APP_GID**

- **Тип:** integer
- **В приложении:** группа Linux контейнера `app` (`services.app.user`).
- **По умолчанию:** локальный Compose — `1000`; production Compose — обязательно.
- **Пример:** `1000`
- **Описание:** GID процесса Java. Скрипт активации deployment самостоятельно экспортирует значение `id -g`.

#### **APP_PORT**

- **Тип:** integer
- **В приложении:** публикация `${APP_PORT}:8080` в локальном Compose.
- **По умолчанию:** `8080`.
- **Пример:** `8082`
- **Описание:** Порт backend на хосте. Внутренний порт контейнера остаётся `8080`. Production Compose эту переменную не использует и host-port не публикует.

## PostgreSQL

#### **POSTGRES_DB**

- **Тип:** string
- **В приложении:** `POSTGRES_DB` контейнера `postgres`; имя БД в `DB_URL` контейнера `app`.
- **По умолчанию:** оба Compose — `tovarika`.
- **Пример:** `tovarika`
- **Описание:** База данных PostgreSQL для приложения. Изменение переменной само по себе не переименовывает базу в существующем volume.

#### **POSTGRES_USER**

- **Тип:** string
- **В приложении:** `POSTGRES_USER` контейнера `postgres` → `DB_USERNAME` контейнера `app`.
- **По умолчанию:** оба Compose — `[REDACTED]` (локальное техническое имя).
- **Пример:** `<DB_USER>`
- **Описание:** Пользователь PostgreSQL. Также используется в healthcheck `pg_isready`. Для существующего volume смена переменной не меняет уже созданную роль.

#### **POSTGRES_PASSWORD**

- **Тип:** string, секрет
- **В приложении:** `POSTGRES_PASSWORD` контейнера `postgres` → `DB_PASSWORD` контейнера `app`.
- **По умолчанию:** оба Compose — `[REDACTED]` (локальное значение).
- **Пример:** `<REDACTED_DB_PASSWORD>`
- **Описание:** Пароль пользователя БД. Для production задаётся явно. Изменение переменной не меняет пароль роли в уже инициализированном PostgreSQL.

#### **POSTGRES_PORT**

- **Тип:** integer
- **В приложении:** публикация `${POSTGRES_PORT}:5432` в локальном Compose.
- **По умолчанию:** `5432`.
- **Пример:** `5433`
- **Описание:** Порт PostgreSQL на хосте. Приложение внутри Compose подключается к `postgres:5432`; production Compose порт БД на хосте не публикует.

#### **DB_URL**

- **Тип:** string, JDBC URL
- **В приложении:** `spring.datasource.url`.
- **По умолчанию:** приложение — `jdbc:postgresql://localhost:5432/tovarika`; оба Compose — `jdbc:postgresql://postgres:5432/${POSTGRES_DB}`.
- **Пример:** `jdbc:postgresql://postgres:5432/tovarika`
- **Описание:** Адрес БД. В Compose формируется напрямую, подстановка внешнего `DB_URL` не предусмотрена. Пароль в URL не включается.

#### **DB_USERNAME**

- **Тип:** string
- **В приложении:** `spring.datasource.username`.
- **По умолчанию:** приложение — `[REDACTED]`; оба Compose — значение `POSTGRES_USER`.
- **Пример:** `<DB_USER>`
- **Описание:** Имя пользователя БД при прямом запуске. Для Compose используется `POSTGRES_USER`.

#### **DB_PASSWORD**

- **Тип:** string, секрет
- **В приложении:** `spring.datasource.password`.
- **По умолчанию:** приложение — `[REDACTED]` (локальное значение); оба Compose — значение `POSTGRES_PASSWORD`.
- **Пример:** `<REDACTED_DB_PASSWORD>`
- **Описание:** Пароль БД при прямом запуске. Для Compose используется `POSTGRES_PASSWORD`.

## MinIO и доставка файлов

#### **MINIO_ENDPOINT**

- **Тип:** string, URL
- **В приложении:** `tovarika.storage.minio.endpoint`; также окружение `template-assets-init`.
- **По умолчанию:** приложение — `http://localhost:9000`; оба Compose — `http://minio:9000`.
- **Пример:** `http://minio:9000`
- **Описание:** Внутренний S3 endpoint. В Compose задан напрямую. Для внешнего MinIO нужно изменить `environment` приложения и job; пользовательские ссылки формируются отдельно через `ASSET_PUBLIC_BASE_URL`.

#### **MINIO_ACCESS_KEY**

- **Тип:** string, учётные данные
- **В приложении:** `tovarika.storage.minio.access-key`; `MINIO_ROOT_USER` контейнера `minio`; окружение `template-assets-init`.
- **По умолчанию:** приложение и оба Compose — `[REDACTED]` (локальное значение).
- **Пример:** `<REDACTED_MINIO_ACCESS_KEY>`
- **Описание:** Access key S3. В текущих Compose одни учётные данные используются приложением, root-пользователем MinIO и синхронизацией шаблонов.

#### **MINIO_SECRET_KEY**

- **Тип:** string, секрет
- **В приложении:** `tovarika.storage.minio.secret-key`; `MINIO_ROOT_PASSWORD` контейнера `minio`; окружение `template-assets-init`.
- **По умолчанию:** приложение и оба Compose — `[REDACTED]` (локальное значение).
- **Пример:** `<REDACTED_MINIO_SECRET_KEY>`
- **Описание:** Secret key S3. В production требуется собственное значение, согласованное между MinIO, приложением и job.

#### **MINIO_BUCKET**

- **Тип:** string
- **В приложении:** `tovarika.storage.minio.bucket`; окружение `template-assets-init`.
- **По умолчанию:** приложение и оба Compose — `user-images`.
- **Пример:** `user-images`
- **Описание:** Бакет исходных фотографий, результатов, экспортов и reference-файлов шаблонов. Job загружает шаблоны в префикс `templates/` того же бакета.

#### **MINIO_INITIALIZE_BUCKET**

- **Тип:** boolean
- **В приложении:** `tovarika.storage.minio.initialize-bucket`.
- **По умолчанию:** `true`.
- **Пример:** `false`
- **Описание:** Проверять и создавать отсутствующий бакет при старте приложения. В обоих Compose переменная не передаётся. На создание бакета отдельным `template-assets-init` не влияет.

#### **MINIO_PORT**

- **Тип:** integer
- **В приложении:** публикация `${MINIO_PORT}:9000` в локальном Compose.
- **По умолчанию:** `9000`.
- **Пример:** `9002`
- **Описание:** Порт S3 API MinIO на хосте. В production Compose не используется.

#### **MINIO_CONSOLE_PORT**

- **Тип:** integer
- **В приложении:** публикация `${MINIO_CONSOLE_PORT}:9001` в локальном Compose.
- **По умолчанию:** `9001`.
- **Пример:** `9003`
- **Описание:** Порт web-консоли MinIO на хосте. В production Compose не используется.

#### **ASSET_PUBLIC_BASE_URL**

- **Тип:** string, URL
- **В приложении:** `tovarika.assets.public-base-url`.
- **По умолчанию:** приложение — `http://localhost:8080`; production Compose — `https://tovarika.com`.
- **Пример:** `https://tovarika.com`
- **Описание:** Внешний базовый URL подписанных ссылок на изображения, загрузку экспортов и placeholder шаблона. Reverse proxy должен направлять соответствующие `/media/*` маршруты в backend. В локальном Compose переменная не передаётся; при смене `APP_PORT` публичный URL нужно согласовать отдельно. Подписанные ссылки действуют до 15 минут; для экспорта срок также ограничен сроком самого экспорта.

## JWT, cookies и CORS

#### **JWT_ISSUER**

- **Тип:** string, URI
- **В приложении:** `tovarika.security.jwt.issuer`.
- **По умолчанию:** приложение и production Compose — `https://api.tovarika.com`; локальный Compose — `http://localhost:8080`.
- **Пример:** `https://api.tovarika.com`
- **Описание:** Значение claim `iss` выпускаемых JWT и ожидаемый issuer при проверке токена.

#### **JWT_AUDIENCE**

- **Тип:** string
- **В приложении:** `tovarika.security.jwt.audience`.
- **По умолчанию:** `tovarika-ui`.
- **Пример:** `tovarika-ui`
- **Описание:** Аудитория access token, записываемая в claim `aud`.

#### **JWT_SECRET_BASE64**

- **Тип:** string, секрет в Base64
- **В приложении:** `tovarika.security.jwt.secret-base64`.
- **По умолчанию:** не задано; обязателен для запуска приложения. Production Compose требует непустое значение.
- **Пример:** `<REDACTED_BASE64_SECRET>`
- **Описание:** Ключ HS256, после Base64-декодирования не менее 32 байт. Также используется для HMAC-подписи ссылок assets. Должен быть стабильным и одинаковым у экземпляров приложения; смена делает старые JWT и подписанные ссылки недействительными. В workflow берётся из GitHub Secret с тем же именем.

#### **UI_ALLOWED_ORIGINS**

- **Тип:** list, origins
- **В приложении:** `tovarika.security.cors.allowed-origins`.
- **По умолчанию:** приложение и локальный Compose — `http://localhost:3000,http://localhost:5173,http://localhost:8081`; production Compose — `https://tovarika.com`.
- **Пример:** `https://tovarika.com,https://stage.example.com`
- **Описание:** Разрешённые origins CORS и cookie-аутентифицированных изменяющих запросов. Указываются схема, хост и при необходимости порт, без пути. Credentialed CORS требует явного списка origins.

#### **AUTH_COOKIE_SECURE**

- **Тип:** boolean
- **В приложении:** `tovarika.security.cookie.secure`.
- **По умолчанию:** приложение — `true`; локальный Compose — `false`; production Compose — фиксированное `true`.
- **Пример:** `true`
- **Описание:** Атрибут `Secure` refresh, OAuth и trial cookies. Для локального HTTP одновременно нужны имена без префикса `__Host-`. В production Compose внешняя переменная не подставляется.

#### **AUTH_COOKIE_SAME_SITE**

- **Тип:** string
- **В приложении:** `tovarika.security.cookie.same-site`.
- **По умолчанию:** `Lax`.
- **Пример:** `Lax`
- **Описание:** Политика `SameSite` для cookies. Значения: `Lax`, `Strict`, `None`; `None` требует `Secure` в браузерах. В обоих Compose переменная не передаётся.

#### **REFRESH_COOKIE_NAME**

- **Тип:** string
- **В приложении:** `tovarika.security.cookie.refresh-name`.
- **По умолчанию:** приложение — `__Host-tovarika_refresh`; локальный Compose — `tovarika_refresh`.
- **Пример:** `__Host-tovarika_refresh`
- **Описание:** Имя cookie refresh token. В production Compose переменная не передаётся, используется default приложения с `Secure` и `Path=/`.

#### **OAUTH_COOKIE_NAME**

- **Тип:** string
- **В приложении:** `tovarika.security.cookie.oauth-name`.
- **По умолчанию:** приложение — `__Host-tovarika_oauth`; локальный Compose — `tovarika_oauth`.
- **Пример:** `__Host-tovarika_oauth`
- **Описание:** Имя cookie корреляции OAuth-попытки. В production Compose переменная не передаётся.

#### **TRIAL_COOKIE_NAME**

- **Тип:** string
- **В приложении:** `tovarika.security.cookie.trial-name`.
- **По умолчанию:** приложение — `__Host-tovarika_trial`; локальный Compose — `tovarika_trial`.
- **Пример:** `__Host-tovarika_trial`
- **Описание:** Имя cookie пробной сессии. В production Compose переменная не передаётся.

#### **BREACHED_PASSWORD_CHECK_ENABLED**

- **Тип:** boolean
- **В приложении:** `tovarika.security.password.breached-check-enabled`.
- **По умолчанию:** `false`.
- **Пример:** `true`
- **Описание:** Проверять пароль через внешний k-anonymity API известных утечек. При `false` сетевой запрос этой проверки не выполняется. В обоих Compose переменная не передаётся.

#### **PWNED_PASSWORD_API_URL**

- **Тип:** string, URL
- **В приложении:** `tovarika.security.password.pwned-api-url`.
- **По умолчанию:** `https://api.pwnedpasswords.com/range/`.
- **Пример:** `https://api.pwnedpasswords.com/range/`
- **Описание:** Базовый URL проверки утечек паролей; используется при `BREACHED_PASSWORD_CHECK_ENABLED=true`. Клиент добавляет первые пять символов SHA-1; нужен завершающий `/`. В обоих Compose переменная не передаётся.

## Yandex OAuth

#### **YANDEX_CLIENT_ID**

- **Тип:** string, идентификатор OAuth-клиента
- **В приложении:** `tovarika.security.oauth.yandex.client-id`.
- **По умолчанию:** приложение — пусто.
- **Пример:** `<REDACTED_YANDEX_CLIENT_ID>`
- **Описание:** Client ID приложения Яндекс ID при прямом запуске. В обоих Compose свойство загружается из файлового secret `yandex_client_id` через config tree; внешняя переменная `YANDEX_CLIENT_ID` в контейнер не передаётся.

#### **YANDEX_CLIENT_SECRET**

- **Тип:** string, секрет
- **В приложении:** `tovarika.security.oauth.yandex.client-secret`.
- **По умолчанию:** приложение — пусто.
- **Пример:** `<REDACTED_YANDEX_CLIENT_SECRET>`
- **Описание:** Секрет OAuth-клиента для обмена authorization code. В обоих Compose используется файловый secret `yandex_client_secret` через config tree.

#### **YANDEX_REDIRECT_URI**

- **Тип:** string, URI
- **В приложении:** `tovarika.security.oauth.yandex.redirect-uri`.
- **По умолчанию:** приложение — `https://api.tovarika.com/api/v1/auth/oauth/yandex/callback`; локальный Compose — `http://localhost:8080/api/v1/auth/oauth/yandex/callback`; production Compose — `https://tovarika.com/api/v1/auth/oauth/yandex/callback`.
- **Пример:** `https://tovarika.com/api/v1/auth/oauth/yandex/callback`
- **Описание:** Внешний callback backend, совпадающий с адресом в настройках OAuth-клиента Яндекса. Production использует домен UI с проксированием `/api/v1/*`.

## Почта

#### **SMTP_HOST**

- **Тип:** string
- **В приложении:** `spring.mail.host`.
- **По умолчанию:** приложение — `localhost`; локальный Compose — `host.docker.internal`; production Compose — пусто.
- **Пример:** `smtp.example.com`
- **Описание:** SMTP-сервер для писем аутентификации, включая сброс пароля. Для production нужен доступный сервер; локальный default Compose предполагает SMTP на хосте.

#### **SMTP_PORT**

- **Тип:** integer
- **В приложении:** `spring.mail.port`.
- **По умолчанию:** приложение — `25`; локальный Compose — `1025`; production Compose — `587`.
- **Пример:** `587`
- **Описание:** Порт SMTP-сервера. Согласуется с настройками аутентификации и STARTTLS.

#### **SMTP_USERNAME**

- **Тип:** string, учётные данные
- **В приложении:** `spring.mail.username`.
- **По умолчанию:** пусто.
- **Пример:** `<SMTP_USER>`
- **Описание:** Пользователь SMTP. Передаётся production Compose; в локальном Compose записи нет.

#### **SMTP_PASSWORD**

- **Тип:** string, секрет
- **В приложении:** `spring.mail.password`.
- **По умолчанию:** пусто.
- **Пример:** `<REDACTED_SMTP_PASSWORD>`
- **Описание:** Пароль SMTP. Передаётся production Compose; в локальном Compose записи нет.

#### **SMTP_AUTH**

- **Тип:** boolean
- **В приложении:** `spring.mail.properties.mail.smtp.auth`.
- **По умолчанию:** приложение — `false`; production Compose — `true`.
- **Пример:** `true`
- **Описание:** Использовать SMTP-аутентификацию. В локальном Compose переменная не передаётся.

#### **SMTP_STARTTLS**

- **Тип:** boolean
- **В приложении:** `spring.mail.properties.mail.smtp.starttls.enable`.
- **По умолчанию:** приложение и production Compose — `true`; локальный Compose — `false`.
- **Пример:** `true`
- **Описание:** Включить STARTTLS для SMTP-соединения.

#### **AUTH_MAIL_FROM**

- **Тип:** string, email
- **В приложении:** `tovarika.security.mail.from`.
- **По умолчанию:** `no-reply@tovarika.com`.
- **Пример:** `no-reply@example.com`
- **Описание:** Адрес отправителя писем. Передаётся production Compose; в локальном Compose записи нет.

#### **AUTH_UI_BASE_URI**

- **Тип:** string, URI
- **В приложении:** `tovarika.security.mail.ui-base-uri`.
- **По умолчанию:** приложение и production Compose — `https://tovarika.com`; локальный Compose — `http://localhost:8081`.
- **Пример:** `https://tovarika.com`
- **Описание:** Базовый адрес интерфейса для ссылок в письмах сброса пароля.

## Пробные сессии

#### **TRIAL_SESSION_TTL**

- **Тип:** duration
- **В приложении:** `tovarika.trial.ttl`.
- **По умолчанию:** `30d`.
- **Пример:** `30d`
- **Описание:** Срок действия пробной сессии и её cookie; должен быть положительным. Не определяет размер начисления Товариков. В обоих Compose переменная не передаётся.

#### **TRIAL_CREATION_MAX_ATTEMPTS**

- **Тип:** integer
- **В приложении:** `tovarika.trial.creation-max-attempts`.
- **По умолчанию:** `10`.
- **Пример:** `10`
- **Описание:** Максимум новых пробных сессий на адрес transport-клиента за `TRIAL_CREATION_WINDOW`; положительное число. Восстановление существующей сессии лимит не расходует. В обоих Compose переменная не передаётся.

#### **TRIAL_CREATION_WINDOW**

- **Тип:** duration
- **В приложении:** `tovarika.trial.creation-window`.
- **По умолчанию:** `1h`.
- **Пример:** `1h`
- **Описание:** Положительная длительность фиксированного окна ограничения создания пробных сессий. Произвольный `X-Forwarded-For` не используется; за reverse proxy без настройки доверенных адресов лимит может оказаться общим для клиентов proxy. В обоих Compose переменная не передаётся.

## OpenAI

#### **OPENAI_MODE**

- **Тип:** string
- **В приложении:** `tovarika.ai.openai.mode`.
- **По умолчанию:** приложение — `stub`; оба Compose — `live`.
- **Пример:** `live`
- **Описание:** Режим AI-клиента: `stub` возвращает офлайн-заглушки, `live` выполняет запросы к провайдеру. Для `live` нужны обе модели, base URL и доступный непустой файл ключа; иначе приложение завершает запуск с ошибкой.

#### **OPENAI_BASE_URL**

- **Тип:** string, URL
- **В приложении:** `tovarika.ai.openai.base-url`.
- **По умолчанию:** `https://api.openai.com/v1`.
- **Пример:** `https://api.openai.com/v1`
- **Описание:** Базовый URL API AI-провайдера. Клиент требует HTTPS; HTTP допускается только для loopback-адресов в локальных проверках. Этот адрес получает настроенный API key при runtime-аутентификации.

#### **OPENAI_API_KEY_FILE**

- **Тип:** string, путь к файлу
- **В приложении:** `tovarika.ai.openai.api-key-file`.
- **По умолчанию:** приложение — пусто; оба Compose — фиксированный `/run/secrets/openai_api_key`.
- **Пример:** `/run/secrets/openai_api_key`
- **Описание:** Путь к ключу внутри контейнера или процесса при прямом запуске. Значение API key не передаётся через environment. Для выбора исходного файла локальный Compose использует `OPENAI_API_KEY_SOURCE`; production использует фиксированный source.

#### **OPENAI_VISION_MODEL**

- **Тип:** string
- **В приложении:** `tovarika.ai.openai.vision-model`.
- **По умолчанию:** приложение — пусто; оба Compose — `gpt-6-luna`.
- **Пример:** `gpt-6-luna`
- **Описание:** Основная модель Responses API для анализа изображения и управления вызовом инструмента генерации. Обязательна в `live`. Имя отражает текущую конфигурацию репозитория, доступность у провайдера проверяется отдельно.

#### **OPENAI_IMAGE_MODEL**

- **Тип:** string
- **В приложении:** `tovarika.ai.openai.image-model`.
- **По умолчанию:** приложение — пусто; оба Compose — `gpt-image-2.5-sunburst`.
- **Пример:** `gpt-image-2.5-sunburst`
- **Описание:** Модель инструмента генерации и редактирования изображений. Обязательна в `live`. Default взят из Compose и не гарантирует доступность модели у провайдера.

## Воркеры и биллинг

#### **ANALYSIS_WORKER_ENABLED**

- **Тип:** boolean
- **В приложении:** `tovarika.analysis.worker-enabled`.
- **По умолчанию:** `true`.
- **Пример:** `false`
- **Описание:** Включить scheduler обработки очереди анализов. `false` отключает исполнение этим scheduler, но не является запретом постановки новых заданий. В обоих Compose переменная не передаётся.

#### **CARD_GENERATION_WORKER_ENABLED**

- **Тип:** boolean
- **В приложении:** `tovarika.cards.worker-enabled`.
- **По умолчанию:** `true`.
- **Пример:** `false`
- **Описание:** Включить общий scheduler генерации карточек и редактирования изображений. При `false` эти задания scheduler не исполняет. В обоих Compose переменная не передаётся.

#### **TOVARIKA_BILLING_AI_ADMISSION_ENABLED**

- **Тип:** boolean
- **В приложении:** `tovarika.billing.ai-admission-enabled`.
- **По умолчанию:** Java — `false`; оба Compose — `true`.
- **Пример:** `true`
- **Описание:** Создавать финансовые операции и резервы для новых оплачиваемых card jobs. При `false` новые задания идут без списания; ранее принятые задания завершают учёт по сохранённой политике. Не отключает trial-начисления, бесплатную историю и технический учёт AI usage.

#### **TOVARIKA_BILLING_AI_LAUNCHES_ENABLED**

- **Тип:** boolean
- **В приложении:** `tovarika.billing.ai-launches-enabled`.
- **По умолчанию:** Java и локальный Compose — `true`; production Compose переменную не передаёт, действует Java default.
- **Пример:** `false`
- **Описание:** Допуск новых AI-команд генерации, retry и редактирования. При `false` новые такие команды получают `503 BILLING_AI_PAUSED`; replay, завершение принятых заданий, CPU resize и бесплатный анализ сохраняются. Для production-паузы нужно явно добавить передачу переменной в `app`.

## Robokassa

#### **ROBOKASSA_MODE**

- **Тип:** string, enum
- **В приложении:** `TOVARIKA_PAYMENTS_ROBOKASSA_MODE` → `tovarika.payments.robokassa.mode`.
- **По умолчанию:** Java — `DISABLED`; локальный Compose — `test`; production Compose — `live`.
- **Пример:** `test`
- **Описание:** `DISABLED` отключает создание оплат, `TEST` разрешает тестовые оплаты при настроенных тестовых паролях и allowlist пользователей. `LIVE` разрешает реальные оплаты всем авторизованным пользователям при настроенных рабочих паролях и не переключается в test при их отсутствии. Подтверждения проверяются по сохранённому режиму заказа независимо от текущего режима новых оплат. Для прямого запуска используется полное имя environment в поле «В приложении».

#### **ROBOKASSA_MERCHANT_LOGIN**

- **Тип:** string
- **В приложении:** `TOVARIKA_PAYMENTS_ROBOKASSA_MERCHANTLOGIN` → `tovarika.payments.robokassa.merchant-login`.
- **По умолчанию:** Java — пусто; оба Compose — `tovarika`.
- **Пример:** `<MERCHANT_LOGIN>`
- **Описание:** Логин магазина. Проверяется по шаблону `[A-Za-z0-9_-]{1,100}`. `MERCHANTLOGIN` в имени environment пишется слитно, как в текущих Compose.

#### **ROBOKASSA_TEST_USER_IDS**

- **Тип:** list
- **В приложении:** `TOVARIKA_PAYMENTS_ROBOKASSA_TESTUSERIDS` → `tovarika.payments.robokassa.test-user-ids`.
- **По умолчанию:** Java — пустой список; в обоих Compose задан один ID тестового пользователя.
- **Пример:** `usr_example_one,usr_example_two`
- **Описание:** Список внутренних ID пользователей, которым разрешены тестовые оплаты. Для своего стенда список нужно переопределить. Включённый `TEST` без подходящего пользователя и двух тестовых паролей оплату не разрешает. В `LIVE` список не ограничивает доступ.

Адреса Result/Success/Fail URL настраиваются в кабинете магазина; порядок и текущие
URL описаны в [инструкции Robokassa](../docs/robokassa-testing.md).
Тестовые и рабочие пароли поступают через secrets ниже. Порядок ручного добавления
рабочих файлов — в [инструкции LIVE](../docs/robokassa-live.md).

## Файловые секреты

Сейчас используются разные механизмы: OpenAI читает отдельный файл, Yandex и Robokassa
импортируются через отдельные configtree, а JWT, PostgreSQL, MinIO и SMTP используют
environment. Единый каталог, правила создания и целевой способ подключения описаны
в [SECRETS.md](../.agent/rules/SECRETS.md). Таблицы ниже сохраняют действующие пути
и имена, чтобы ими можно было пользоваться до миграции.

`*_SOURCE` — пути на машине, запускающей Compose. Они используются при создании
mount, не передаются в приложение и не содержат значения секретов. Относительные
пути разрешаются относительно каталога Compose-файла. Исходные файлы заранее
подготавливает оператор; `activate-backend-deploy.sh` проверяет наличие OpenAI secret,
но не создаёт его. Файлы секретов не должны попадать в Git, документацию или логи.

#### **SPRING_CONFIG_IMPORT**

- **Тип:** list, locations
- **В приложении:** `spring.config.import`.
- **По умолчанию:** оба Compose явно задают `configtree:/run/secrets/yandex/,optional:configtree:/run/secrets/robokassa/`; без Compose не задано.
- **Пример:** `configtree:/run/secrets/yandex/,optional:configtree:/run/secrets/robokassa/`
- **Описание:** Импорт файлов, имена которых соответствуют Spring properties. Yandex config tree обязателен; Robokassa помечен `optional` для импорта Spring. При этом сами файлы Robokassa объявлены в `services.app.secrets` обоих Compose и нужны для создания контейнера: `optional` их монтирование необязательным не делает. Внешнее значение `SPRING_CONFIG_IMPORT` в Compose не подставляется.

#### **OPENAI_API_KEY_SOURCE**

- **Тип:** string, путь к secret-файлу
- **В приложении:** Compose secret `openai_api_key` → `OPENAI_API_KEY_FILE`.
- **По умолчанию:** локальный Compose — `../.secrets/openai_api_key`.
- **Пример:** `/secure/secrets/openai_api_key`
- **Описание:** Путь source только для локального Compose. В production переменная не используется: source фиксирован как `./secrets/openai_api_key` относительно production Compose.

#### **YANDEX_CLIENT_ID_SOURCE**

- **Тип:** string, путь к secret-файлу
- **В приложении:** secret `yandex_client_id` → `tovarika.security.oauth.yandex.client-id`.
- **По умолчанию:** локальный Compose — `../.secrets/yandex_client_id`; production Compose — `./secrets/yandex_client_id`.
- **Пример:** `/secure/secrets/yandex_client_id`
- **Описание:** Файл идентификатора OAuth-клиента. Spring читает содержимое через Yandex config tree.

#### **YANDEX_CLIENT_SECRET_SOURCE**

- **Тип:** string, путь к secret-файлу
- **В приложении:** secret `yandex_client_secret` → `tovarika.security.oauth.yandex.client-secret`.
- **По умолчанию:** локальный Compose — `../.secrets/yandex_client_secret`; production Compose — `./secrets/yandex_client_secret`.
- **Пример:** `/secure/secrets/yandex_client_secret`
- **Описание:** Файл секрета OAuth-клиента; монтируется отдельно от client ID.

#### **ROBOKASSA_TEST_PASSWORD1_SOURCE**

- **Тип:** string, путь к secret-файлу
- **В приложении:** secret `robokassa_test_password1` → `tovarika.payments.robokassa.test-password1`.
- **По умолчанию:** локальный Compose — `../.secrets/test_robokassa_pass1`; production Compose — `secrets/test_robokassa_pass1`.
- **Пример:** `/secure/secrets/test_robokassa_pass1`
- **Описание:** Файл тестового пароля № 1 для подписи запроса создания оплаты. Возврат браузера в текущей реализации только перенаправляет на UI и не подтверждает платёж. Без config tree Java default свойства — пусто.

#### **ROBOKASSA_TEST_PASSWORD2_SOURCE**

- **Тип:** string, путь к secret-файлу
- **В приложении:** secret `robokassa_test_password2` → `tovarika.payments.robokassa.test-password2`.
- **По умолчанию:** локальный Compose — `../.secrets/test_robokassa_pass2`; production Compose — `secrets/test_robokassa_pass2`.
- **Пример:** `/secure/secrets/test_robokassa_pass2`
- **Описание:** Файл тестового пароля № 2 для проверки подписи серверного Result URL. Без config tree Java default свойства — пусто.

#### **ROBOKASSA_LIVE_PASSWORD1_SOURCE**

- **Тип:** string, путь к secret-файлу
- **В приложении:** secret `robokassa_live_password1` → `tovarika.payments.robokassa.live-password1`.
- **По умолчанию:** локальный Compose — `../.secrets/live_robokassa_pass1`; production Compose — `secrets/live_robokassa_pass1`.
- **Пример:** `/secure/secrets/live_robokassa_pass1`
- **Описание:** Файл рабочего пароля № 1 для подписи запроса реальной оплаты. Java default — пусто. Добавляется вручную на хосте, значение должно отличаться от тестового пароля № 1.

#### **ROBOKASSA_LIVE_PASSWORD2_SOURCE**

- **Тип:** string, путь к secret-файлу
- **В приложении:** secret `robokassa_live_password2` → `tovarika.payments.robokassa.live-password2`.
- **По умолчанию:** локальный Compose — `../.secrets/live_robokassa_pass2`; production Compose — `secrets/live_robokassa_pass2`.
- **Пример:** `/secure/secrets/live_robokassa_pass2`
- **Описание:** Файл рабочего пароля № 2 для проверки серверного подтверждения реальной оплаты. Java default — пусто. Добавляется вручную на хосте, значение должно отличаться от тестового пароля № 2.

### Соответствие secrets и свойств приложения

Все значения secret-файлов имеют тип `string`; в примерах содержимого допускается
только `<REDACTED>`. Ниже приведены имена и mount paths, без содержимого файлов.

| Compose secret | Путь в контейнере `app` | Потребитель |
| --- | --- | --- |
| `openai_api_key` | `/run/secrets/openai_api_key` | `OPENAI_API_KEY_FILE`; API key читает AI-клиент. |
| `yandex_client_id` | `/run/secrets/yandex/tovarika.security.oauth.yandex.client-id` | `AuthenticationProperties`, client ID. |
| `yandex_client_secret` | `/run/secrets/yandex/tovarika.security.oauth.yandex.client-secret` | `AuthenticationProperties`, client secret. |
| `robokassa_test_password1` | `/run/secrets/robokassa/tovarika.payments.robokassa.test-password1` | `RobokassaSettings`, тестовый пароль № 1. |
| `robokassa_test_password2` | `/run/secrets/robokassa/tovarika.payments.robokassa.test-password2` | `RobokassaSettings`, тестовый пароль № 2. |
| `robokassa_live_password1` | `/run/secrets/robokassa/tovarika.payments.robokassa.live-password1` | `RobokassaSettings`, рабочий пароль № 1. |
| `robokassa_live_password2` | `/run/secrets/robokassa/tovarika.payments.robokassa.live-password2` | `RobokassaSettings`, рабочий пароль № 2. |

`JWT_SECRET_BASE64`, `DB_PASSWORD` / `POSTGRES_PASSWORD`, `MINIO_SECRET_KEY` и
`SMTP_PASSWORD` сейчас передаются через environment, а не через перечисленные
Compose secrets. Для прямого запуска Robokassa также связывает environment
`TOVARIKA_PAYMENTS_ROBOKASSA_TESTPASSWORD1`, `TOVARIKA_PAYMENTS_ROBOKASSA_TESTPASSWORD2`,
`TOVARIKA_PAYMENTS_ROBOKASSA_LIVEPASSWORD1` и `TOVARIKA_PAYMENTS_ROBOKASSA_LIVEPASSWORD2`
с соответствующими свойствами; defaults пустые, тип — `string`, пример — `<REDACTED>`.
В Compose эти environment-переменные не передаются: используются файлы.

## template-assets-init

Одноразовый сервис production Compose с профилем `template-sync`. Скрипт
`activate-backend-deploy.sh` сначала поднимает MinIO, запускает job, затем запускает
приложение, чтобы reference-файлы были доступны до Liquibase-миграций каталога.

| ENV в job | Источник | Назначение |
| --- | --- | --- |
| `MINIO_ENDPOINT` | Фиксированный `http://minio:9000` | S3 endpoint. |
| `MINIO_ACCESS_KEY` | Переменная Compose с тем же именем | Access key. |
| `MINIO_SECRET_KEY` | Переменная Compose с тем же именем | Secret key. |
| `MINIO_BUCKET` | Переменная Compose с тем же именем | Общий бакет приложения и шаблонов. |

Все четыре переменные обязательны для самого `sync-template-assets.sh`;
Compose подставляет значения по правилам выше. WebP-файлы из `/template-assets/*/*.webp`
публикуются в `templates/<templateId>/<file>.webp`. Существующий объект проверяется
по SHA-256; при несовпадении job завершается с ошибкой. Перезаписи и удаления
опубликованных reference нет. Отдельных переменных cron, retry или overwrite у job нет.

## Дополнительные свойства Spring

Ниже перечислены настройки из исходников, у которых нет коротких ENV-псевдонимов
в `application.properties` или подстановок в Compose. Их можно задать внешним
properties-файлом или аргументом JAR `--<property>=<value>`, например
`--tovarika.exports.worker-enabled=false`. Для передачи в контейнер потребуется
отдельная настройка Compose; существующий deployment `.env` автоматически их не применит.

### Scheduler и очистка

Интервалы `fixedDelay` отсчитываются после завершения предыдущего вызова.

| Свойство | Тип | По умолчанию | Описание |
| --- | --- | --- | --- |
| `tovarika.analysis.poll-delay-ms` | integer, ms | `1000` | Пауза между вызовами analysis worker. |
| `tovarika.cards.poll-delay-ms` | integer, ms | `1000` | Пауза между проходами генерации карточек и редактирования изображений. |
| `tovarika.exports.worker-enabled` | boolean | `true` | Включить scheduler экспорта, включая его периодическую очистку. |
| `tovarika.exports.poll-delay-ms` | integer, ms | `1000` | Пауза между проходами export worker. |
| `tovarika.exports.cleanup-delay-ms` | integer, ms | `60000` | Пауза между очистками экспортов. |
| `tovarika.products.cleanup-delay-ms` | integer, ms | `60000` | Пауза между уборками незавершённых загрузок товаров старше часа. |
| `tovarika.billing.reconciliation-delay-ms` | integer, ms | `60000` | Пауза между сверками финансовых резервов для отсутствующих или terminal jobs. |
| `tovarika.billing.credit-expiry-delay-ms` | integer, ms | `60000` | Пауза между обработками истёкших остатков купленных пакетов. |

### Аутентификация

Имена ниже начинаются с `tovarika.security.`; в таблице указан оставшийся суффикс.

| Свойство после `tovarika.security.` | Тип | По умолчанию | Описание |
| --- | --- | --- | --- |
| `jwt.access-token-ttl` | duration | `10m` | Срок действия access token. |
| `refresh.absolute-ttl` | duration | `30d` | Абсолютный срок refresh family. |
| `refresh.inactivity-ttl` | duration | `14d` | Допустимое бездействие refresh family. |
| `token.password-reset-ttl` | duration | `20m` | Срок одноразового токена сброса пароля. |
| `cookie.path` | string | `/` | Path всех auth/trial cookies; `/` нужен для имён `__Host-`. |
| `oauth.yandex.authorization-uri` | string, URI | `https://oauth.yandex.ru/authorize` | Endpoint начала авторизации. |
| `oauth.yandex.token-uri` | string, URI | `https://oauth.yandex.ru/token` | Endpoint обмена authorization code. |
| `oauth.yandex.user-info-uri` | string, URI | `https://login.yandex.ru/info?format=json` | Endpoint получения профиля пользователя. |
| `oauth.yandex.attempt-ttl` | duration | `10m` | Срок OAuth-попытки и корреляционной cookie. |
| `oauth.yandex.allowed-return-path-prefixes` | list | `/editor,/projects,/account` | Разрешённые префиксы локального return path после OAuth. |
| `rate-limit.login.max-attempts` | integer | `10` | Лимит входа за окно. |
| `rate-limit.login.window` | duration | `1m` | Окно лимита входа. |
| `rate-limit.register.max-attempts` | integer | `5` | Лимит регистрации за окно. |
| `rate-limit.register.window` | duration | `1h` | Окно лимита регистрации. |
| `rate-limit.password-reset.max-attempts` | integer | `5` | Лимит запросов сброса пароля за окно. |
| `rate-limit.password-reset.window` | duration | `1h` | Окно лимита сброса пароля. |
| `rate-limit.oauth-start.max-attempts` | integer | `20` | Лимит начала OAuth за окно. |
| `rate-limit.oauth-start.window` | duration | `1m` | Окно лимита начала OAuth. |
| `rate-limit.oauth-callback.max-attempts` | integer | `30` | Лимит callback OAuth за окно. |
| `rate-limit.oauth-callback.window` | duration | `1m` | Окно лимита callback OAuth. |
| `rate-limit.refresh.max-attempts` | integer | `60` | Лимит refresh за окно. |
| `rate-limit.refresh.window` | duration | `1m` | Окно лимита refresh. |

### Загрузка файлов и служебные настройки

| Свойство | Тип | По умолчанию | Описание |
| --- | --- | --- | --- |
| `spring.servlet.multipart.max-file-size` | data size | `10MB` | Максимальный размер одного multipart-файла. |
| `spring.servlet.multipart.max-request-size` | data size | `11MB` | Максимальный размер всего multipart-запроса. |
| `spring.servlet.multipart.file-size-threshold` | data size | `0` | Порог записи multipart на диск. |
| `spring.servlet.multipart.resolve-lazily` | boolean | `true` | Отложенный разбор multipart. |
| `spring.application.name` | string | `tovarika` | Имя приложения. |
| `spring.jpa.hibernate.ddl-auto` | string | `validate` | Проверка соответствия JPA схеме; изменения схемы выполняет Liquibase. |
| `spring.jpa.open-in-view` | boolean | `false` | Запрет держать JPA persistence context на весь web-запрос. |
| `spring.liquibase.change-log` | string | `classpath:db/changelog/db.changelog-master.yaml` | Основной changelog миграций. |
| `springdoc.swagger-ui.path` | string | `/swagger-ui.html` | Путь Swagger UI. |
| `springdoc.swagger-ui.url` | string | `/openapi/tovarika-openapi.yaml` | Публикуемая спецификация API. |
| `springdoc.swagger-ui.disable-swagger-default-url` | boolean | `true` | Отключить демонстрационную спецификацию Swagger. |

`MAX_IMAGE_SIZE` упоминается в карте проекта, но текущие Java-код, properties и Compose
его не читают. Для multipart действуют свойства выше; ограничение количества пикселей
проверяется кодом валидатора изображений.

## Сборка и GitHub Actions

Переменные этого раздела относятся к Gradle и deployment workflow, в runtime
окружение `app` не передаются. В текущем `.github/workflows/deploy.yml` автоматический
запуск по push убран, а job дополнительно отключён через `if: false`.
Наличие настроек ниже само по себе deployment не включает.

#### **TOVARIKA_API_CONTRACT_DIR**

- **Тип:** string, путь к каталогу
- **В приложении:** Gradle provider `apiContractDirectory`.
- **По умолчанию:** `../tovarika-api-contract` относительно корня проекта.
- **Пример:** `/workspace/tovarika-api-contract`
- **Описание:** Репозиторий контракта с подготовленными `dist/openapi.yaml` и `dist/provider-openapi.yaml`. Gradle-параметр `-PapiContractDir=/path` имеет приоритет над этой переменной. Dockerfile передаёт свой `-PapiContractDir`.

#### **IMAGE_NAME**

- **Тип:** string
- **В приложении:** верхнеуровневый `env` GitHub Actions.
- **По умолчанию:** `tovarika-backend`.
- **Пример:** `tovarika-backend`
- **Описание:** Имя Docker-образа и префикс deployment-архивов workflow. Это настройка workflow в YAML; production Compose и скрипт активации используют собственные фиксированные имена, поэтому изменение одного `IMAGE_NAME` требует их согласования.

#### **DEPLOY_DIR**

- **Тип:** string, путь к каталогу
- **В приложении:** верхнеуровневый `env` GitHub Actions.
- **По умолчанию:** `/home/deploy/tovarika-backend`.
- **Пример:** `/home/deploy/tovarika-backend`
- **Описание:** Каталог загрузки файлов на сервер в workflow. Финальный SSH-шаг также содержит этот путь напрямую. В `activate-backend-deploy.sh` одноимённая переменная вычисляется из расположения скрипта, внешним environment не переопределяется.

#### **DEPLOY_HOST**

- **Тип:** string
- **В приложении:** GitHub Secret `DEPLOY_HOST`, environment SSH/SCP-шагов workflow.
- **По умолчанию:** не задано, обязателен для deployment.
- **Пример:** `deploy.example.com`
- **Описание:** Адрес сервера. Используется для SSH, SCP и проверки заранее заданного known_hosts.

#### **DEPLOY_USER**

- **Тип:** string
- **В приложении:** GitHub Secret `DEPLOY_USER`, environment SSH/SCP-шагов workflow.
- **По умолчанию:** не задано, обязателен для deployment.
- **Пример:** `<DEPLOY_USER>`
- **Описание:** SSH-пользователь сервера с доступом к каталогу deployment и Docker.

#### **DEPLOY_SSH_KEY**

- **Тип:** string, секрет
- **В приложении:** GitHub Secret `DEPLOY_SSH_KEY` → input `ssh-private-key` шага `webfactory/ssh-agent`.
- **По умолчанию:** не задано.
- **Пример:** `<REDACTED_PRIVATE_SSH_KEY>`
- **Описание:** Приватный SSH-ключ для deployment. В workflow используется как input action, не как environment приложения.

#### **DEPLOY_KNOWN_HOSTS**

- **Тип:** string, строки known_hosts
- **В приложении:** GitHub Secret `DEPLOY_KNOWN_HOSTS`, environment шага настройки SSH.
- **По умолчанию:** не задано, обязателен для deployment.
- **Пример:** `<VERIFIED_KNOWN_HOSTS_ENTRY>`
- **Описание:** Заранее проверенные публичные host keys сервера. Workflow записывает их в `known_hosts`, проверяет наличие `DEPLOY_HOST` и использует `StrictHostKeyChecking=yes`.

#### **CONTRACTS_REPO_TOKEN**

- **Тип:** string, секрет
- **В приложении:** GitHub Secret `CONTRACTS_REPO_TOKEN` → input `token` checkout API-контракта.
- **По умолчанию:** используется `github.token`, если отдельный secret не задан.
- **Пример:** `<REDACTED_REPOSITORY_TOKEN>`
- **Описание:** Токен для чтения соседнего репозитория `Bla2Code/tovarika-api-contract`, если прав встроенного токена недостаточно. В контейнер и собранное приложение не передаётся.

Workflow также использует `JWT_SECRET_BASE64`, описанный выше: проверяет его наличие
и подготавливает deployment env-файл. Файловые secrets OpenAI, Yandex и Robokassa
должны быть подготовлены на сервере отдельно.

## Параметры служебных скриптов

Это параметры командной строки, не environment приложения. Полные команды и
условия выполнения приведены в [scripts/README.md](../scripts/README.md).

| Скрипт | Параметр | Тип | Пример / default | Описание |
| --- | --- | --- | --- | --- |
| `grant-credits.sql`, `debit-credits.sql` | `email` (`psql -v`) | string | `client@example.com` | Обязательный email существующего активного пользователя. |
| `grant-credits.sql`, `debit-credits.sql` | `amount` (`psql -v`) | decimal | `25.50` | Обязательная положительная сумма в Товариках, до двух знаков после точки; не внутренние units. |
| `grant-credits.sql` | `reason` (`psql -v`) | string | `Manual credit grant` | Необязательная причина начисления, 1–210 символов. |
| `debit-credits.sql` | `reason` (`psql -v`) | string | `Manual credit debit` | Необязательная причина списания, 1–200 символов. |
| `seed-card-variants.py` | `--source` | path | `assets/templates/tpl_fashion_hero/card-variants.json` | Исходный JSON сценариев. |
| `seed-card-variants.py` | `--output` | path | Не задан; пример имени: `NNN-template-variants.yaml` | Обязателен без `--check`. Новый файл в `src/main/resources/db/changelog/changes/`, имя начинается с трёх цифр свободного номера миграции; существующий файл не перезаписывается. |
| `seed-card-variants.py` | `--check` | flag | Выключен | Только проверка JSON без записи миграции. |

`<DB_HOST>`, `<DB_PORT>`, `<DB_USER>`, `<DB_NAME>` и `<POSTGRES_CONTAINER>` в примерах
скриптов — placeholders параметров подключения, не дополнительные переменные
конфигурации backend. Каждый успешный запуск финансового скрипта выполняет новую
операцию; автоматической защиты от повторного запуска нет.

## Источники

- [application.properties](../src/main/resources/application.properties) — ENV-псевдонимы, defaults приложения и настройки Spring.
- [compose.yaml](../compose.yaml) — локальная инфраструктура и профиль `full`.
- [docker-compose.prod.yml](docker-compose.prod.yml) — production-стек и файловые secrets.
- [activate-backend-deploy.sh](activate-backend-deploy.sh), [sync-template-assets.sh](sync-template-assets.sh) — активация deployment и синхронизация reference.
- [deploy.yml](../.github/workflows/deploy.yml), [build.gradle](../build.gradle) — workflow и сборка контракта.
- [AuthenticationProperties](../src/main/java/com/tovarika/tech/auth/application/AuthenticationProperties.java), [TrialProperties](../src/main/java/com/tovarika/tech/trial/application/TrialProperties.java) — типы настроек аутентификации и пробных сессий.
- [MinioStorageProperties](../src/main/java/com/tovarika/tech/infrastructure/storage/MinioStorageProperties.java), [AssetLinks](../src/main/java/com/tovarika/tech/products/application/AssetLinks.java) — хранилище и публичные ссылки.
- [OpenAiProperties](../src/main/java/com/tovarika/tech/images/infrastructure/OpenAiProperties.java), [OpenAiResponsesClient](../src/main/java/com/tovarika/tech/images/infrastructure/OpenAiResponsesClient.java) — конфигурация и startup-проверки AI.
- [CardJobBilling](../src/main/java/com/tovarika/tech/cards/CardJobBilling.java), [RobokassaSettings](../src/main/java/com/tovarika/tech/billing/payments/RobokassaSettings.java) — биллинг и платёжные настройки.

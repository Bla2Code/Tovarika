# Бэкенд Tovarika

Бэкенд на Spring Boot с REST API, построенным по схеме contract-first, PostgreSQL, Liquibase и MinIO.

## Требования

- JDK 21;
- Docker с Docker Compose;
- Node.js 22.18+ и npm 10+ только при пересборке артефактов API-контракта;
- репозиторий `tovarika-api-contract`, расположенный рядом с этим репозиторием.

По умолчанию контракт читается из `../tovarika-api-contract`. Путь можно переопределить через
`-PapiContractDir=/absolute/path` или `TOVARIKA_API_CONTRACT_DIR`.

## Запуск

Authentication требует 256-битный signing key без значения по умолчанию в репозитории. Для локального
запуска создайте временный ключ в окружении (после перезапуска ранее выданные access token станут
недействительными):

```bash
export JWT_SECRET_BASE64="$(openssl rand -base64 32)"
```

Запускайте приложение локально, а PostgreSQL и MinIO будут управляться средствами Docker
Compose в Spring Boot:

```bash
./gradlew bootRun
```

Перед первым запуском или после изменения контракта подготовьте его артефакты в репозитории
контракта:

```bash
(cd ../tovarika-api-contract && npm ci && npm run build)
```

Затем запустите весь стек, включая приложение, в Docker:

```bash
docker compose --profile full up --build --wait
```

Сервис приложения вынесен в профиль `full`, чтобы `bootRun` не запускал его рекурсивно.
Контекст сборки Docker — родительский каталог, потому что сборка использует и этот
репозиторий, и соседний репозиторий `tovarika-api-contract`. `Dockerfile.dockerignore`
ограничивает набор файлов, передаваемых демону Docker.

Остановить стек без удаления данных PostgreSQL и MinIO:

```bash
docker compose --profile full down
```

При старте приложение создаёт настроенный бакет MinIO. Полезные локальные адреса:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Консоль MinIO: <http://localhost:9001>
- S3 endpoint MinIO: <http://localhost:9000>

Если параллельно запускаете UI из соседнего репозитория в Docker, он будет доступен
на <http://localhost:8081/>, чтобы не конфликтовать с backend `8080`.

Учётные данные по умолчанию для разработки: `tovarika` / `tovarika-secret` для MinIO и
`tovarika` / `tovarika` для PostgreSQL. Вне локальной разработки их следует переопределять
через переменные окружения, указанные в `compose.yaml` и `application.properties`.

## Authentication и security

Authentication реализован по `docs/authentication/ai-contract.yaml` соседнего contract-репозитория:

- регистрация по email сразу создаёт активный аккаунт; если browser уже несёт trial cookie, она привязывается при регистрации;
- access token — HS256 JWT с TTL 10 минут и claims `sub`, `sid`, `iss`, `aud`, `iat`, `exp`, `jti`;
- refresh token — opaque 256-bit value только в `Secure; HttpOnly; SameSite` cookie, в PostgreSQL
  хранится только SHA-256 hash;
- каждый refresh атомарно consume-ит предыдущий token под PostgreSQL row lock и создаёт следующий;
  reuse отзывает всю token family;
- refresh family ограничена 30 днями absolute и 14 днями inactivity;
- email password хранится только в `AuthIdentity` через Argon2id; common/breached passwords
  проверяются через k-anonymity API;
- cookie-authenticated mutations требуют allowlisted `Origin`; credentialed CORS не использует `*`;
- Yandex ID использует Authorization Code, PKCE S256, state и server-side correlation attempt.

Resource Server валидирует active user/session централизованно через PostgreSQL на каждом Bearer request.
Это осознанный trade-off: JWT transport остаётся stateless и не использует `HttpSession`, но блокировка user
или revoke session действует сразу, а не только после истечения 10-минутного access token. Кэширование этой
проверки не включено, чтобы не ослаблять revoke semantics.

Production обязательно задаёт стабильные `JWT_SECRET_BASE64`, `UI_ALLOWED_ORIGINS`, SMTP и Yandex OAuth
параметры. Локальный HTTP допускает `AUTH_COOKIE_SECURE=false`, но тогда одновременно используйте cookie
names без префикса `__Host-`, как уже настроено в `compose.yaml`. Signing key, SMTP/Yandex secrets и токены
не должны попадать в Git или logs.

## Развертывание

Продакшн-развертывание выполняется через [.github/workflows/deploy.yml](.github/workflows/deploy.yml)
и использует [deploy/docker-compose.prod.yml](deploy/docker-compose.prod.yml) в качестве файла стека.

Workflow извлекает этот репозиторий в `Tovarika/`, рядом извлекает
`Bla2Code/tovarika-api-contract`, сначала собирает API-контракт, затем собирает
`tovarika-backend:latest`, загружает tarball с образом и Compose-файл в
`/home/deploy/tovarika-backend`, создаёт общую сеть `tovarika-edge` при необходимости и
перезапускает стек через Docker Compose.

Секреты репозитория GitHub:

- `DEPLOY_HOST` - адрес сервера;
- `DEPLOY_USER` - SSH-пользователь;
- `DEPLOY_SSH_KEY` - приватный SSH-ключ для сервера;
- `CONTRACTS_REPO_TOKEN` - необязательный токен, если `tovarika-api-contract` приватный.

Локально бэкенд доступен на порту `8080`. В production он не публикует host-port:
UI Caddy обслуживает фронтенд и проксирует `/api/v1/*` в backend по общей сети
`tovarika-edge`. PostgreSQL и MinIO остаются внутри сети Compose, если вы не измените
production Compose-файл.

## API-контракт

Репозиторий API-контракта отвечает за валидацию, сборку и генерацию для фронтенда. Бэкенд
использует его подготовленные артефакты `dist/*.yaml`, поэтому Docker-образ бэкенда не
содержит Node.js и не запускает его. `generatePublicApi` и `generateProviderApi` создают
Spring API-интерфейсы и DTO в `build/generated/openapi`. Сгенерированные исходники никогда
не редактируются и не коммитятся.

```bash
./gradlew generatePublicApi generateProviderApi
```

`buildApiContract` — это отдельная служебная задача для обновления `dist` через инструментарий Node.js
репозитория контракта. Она не входит в `build`, `bootRun` и сборку Docker-образа
бэкенда.

Контроллеры реализуют интерфейсы из `com.tovarika.api.publicapi` или
`com.tovarika.api.provider`; прикладную и доменную логику нельзя добавлять в сгенерированный код.

## Тесты

```bash
./gradlew test
```

Authentication contract suite запускает PostgreSQL 18 через Testcontainers и проверяет Liquibase/JPA,
rotation/reuse/concurrency, one-time reset tokens, JWT validators, Origin/CORS, Yandex
state/PKCE/safe redirects и exactly-once trial conversion. H2 для security concurrency semantics не
используется.

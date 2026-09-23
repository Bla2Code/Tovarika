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
не должны попадать в Git или logs. Для деплоя `JWT_SECRET_BASE64` хранится в GitHub Secret и workflow
записывает его в `/home/deploy/tovarika-backend/.env` перед `docker compose up`.

## Развертывание

Продакшн-развертывание выполняется через [.github/workflows/deploy.yml](.github/workflows/deploy.yml)
и использует [deploy/docker-compose.prod.yml](deploy/docker-compose.prod.yml) в качестве файла стека.

Workflow извлекает этот репозиторий в `Tovarika/`, рядом извлекает
`Bla2Code/tovarika-api-contract`, сначала собирает API-контракт, затем собирает
`tovarika-backend:latest`, загружает tarball с образом, Compose-файл и `.env` в
`/home/deploy/tovarika-backend`, создаёт общую сеть `tovarika-edge` при необходимости и
перезапускает стек через Docker Compose.

Секреты репозитория GitHub:

- `DEPLOY_HOST` - адрес сервера;
- `DEPLOY_USER` - SSH-пользователь;
- `DEPLOY_SSH_KEY` - приватный SSH-ключ для сервера;
- `CONTRACTS_REPO_TOKEN` - необязательный токен, если `tovarika-api-contract` приватный;
- `JWT_SECRET_BASE64` - обязательный 256-битный signing key для production deploy.

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

## Изменение и удаление проектов

`PATCH /api/v1/projects/{projectId}` частично обновляет `name`, `selectedTemplateId` и
`defaultAspectRatio`, сохраняя остальные поля. Пустой объект, неизвестные поля, явный `null`
и нарушения схемы возвращают `400 VALIDATION_ERROR`. Шаблон должен существовать в каталоге
`templates` и иметь `available = true`; иначе возвращается `422 TEMPLATE_NOT_FOUND`.
Таблица хранит минимальную информацию о доступности общего каталога; наполнением каталога
занимается модуль шаблонов. Недоступность шаблона не снимает уже сохранённый выбор проекта.

PATCH и DELETE принимают Bearer JWT либо действующую непривязанную trial-session cookie.
Bearer имеет приоритет. Для запросов через trial cookie требуется разрешённый `Origin`;
отсутствующий или посторонний Origin даёт `403 FORBIDDEN`. Чужой и отсутствующий проект
одинаково возвращают `404 PROJECT_NOT_FOUND`.

`DELETE /api/v1/projects/{projectId}` возвращает `204` и атомарно удаляет проект, его карточки
и завершённые/неуспешные project jobs через внешние ключи `ON DELETE CASCADE`.
**Product сохраняется**, включая владельца и ссылку на исходное изображение: для него можно
создать новый проект. Метаданные assets и бинарные объекты MinIO сохраняются; сборка мусора
хранилища — отдельная задача. Повторное удаление проекта возвращает `404`.

Обе операции возвращают `409 CARD_BUSY`, если в `project_jobs` есть job со статусом `queued`
или `processing` (генерация, регенерация, редактирование области либо экспорт). Отмена job
не выполняется. Jobs анализа Product не принадлежат проекту и не блокируют эти операции:
сам Product при удалении проекта сохраняется.

Мутации блокируют строки проекта и его Product в транзакции PostgreSQL; создание project job
через DB trigger блокирует ту же строку проекта до проверки FK. Поэтому создание job и
изменение/удаление проекта сериализуются. Существующая job не может менять `project_id`
или возвращаться из `completed`/`failed` в активное состояние: повторная попытка создаёт
новую job. Переходы статуса существующей job не берут блокировку проекта, чтобы не создавать
обратный порядок блокировок при каскадном удалении. При конкурентном завершении job
возможен консервативный `409`, после которого запрос можно повторить.

HTTP-проверки мутаций, доступов, Origin, ошибок, каскадного удаления и конкурентного запуска
job выполняются на PostgreSQL 18 с настоящими JWT:

```bash
./gradlew test --tests com.tovarika.tech.project.ProjectMutationsIntegrationTest
```

## Анонимный workspace (trial)

`POST /api/v1/trial-session` без cookie создаёт identity (`201`) и устанавливает
`__Host-tovarika_trial` с `Secure; HttpOnly; SameSite=Lax; Path=/`, без Domain.
Для POST требуется allowlisted `Origin`, в том числе при первом bootstrap.
Повторный POST с действующей cookie возвращает `200` без новой cookie, продления срока
или изменения счётчиков. `GET /api/v1/trial-session` читает только cookie: Bearer её не заменяет.
Оба ответа используют `Cache-Control: no-store`.

Лимит по контракту равен 3, использовано изначально 0; при исчерпании возвращается
`exhausted`. Списание генераций сюда не входит. Срок по умолчанию — 30 дней
(`TRIAL_SESSION_TTL`). Отсутствующая cookie на GET, пустая/невалидная, expired или converted
cookie дают `401 TRIAL_SESSION_NOT_FOUND`. Новый лимит вместо недействующего не создаётся.

Создание ограничено в PostgreSQL: по умолчанию 10 новых сессий на адрес клиента за часовое
фиксированное окно (`TRIAL_CREATION_MAX_ATTEMPTS`, `TRIAL_CREATION_WINDOW`), далее `429`.
Восстановление существующей сессии не расходует этот лимит. Адрес берётся из servlet transport,
произвольный `X-Forwarded-For` не используется. При reverse proxy без доверенной настройки
передачи адреса лимит будет общим для клиентов этого proxy; настройку доверенных proxy
следует выполнить на уровне deployment. Очистка cookie не сбрасывает серверный rate limit.
В БД хранится только SHA-256 hash криптографически случайного 256-bit opaque token.

`WorkspaceIdentityResolver` выбирает Bearer user первым, иначе проверяет trial cookie;
проекты используют этот общий resolver. Регистрация использует существующий транзакционный
hook `AuthenticationStore.convertTrial`: переносит Product и доступ к связанным Project/Card,
сохраняет счётчики, помечает session converted и очищает cookie. Если регистрация пришла с
Bearer, trial-владелец не переносится неявно. ProductAnalysis и самостоятельный ownership
Asset ещё не реализованы в backend; их будущая конвертация должна расширять этот же hook.

Проверки bootstrap, expiry, rate limit, identity isolation, conversion и отсутствия raw token
в логах входят в `AuthenticationContractIntegrationTest` и выполняются на PostgreSQL 18.

## Исходные товары

`POST /api/v1/products` принимает multipart `image` и необязательное `name`; `GET /api/v1/products/{id}`
доступен только владельцу через Bearer или trial cookie. Cookie-загрузка требует разрешённого Origin.
Контрактный purpose исходного Asset — `source_image`. Product создаётся в `uploaded`, без запуска анализа.
Поддерживаются JPEG, PNG и WEBP: сигнатура и декодирование проверяются независимо от присланного MIME;
исходные байты сохраняются без преобразований. WEBP декодируется TwelveMonkeys ImageIO 3.13.1.
Лимит файла 10 MiB применяется servlet multipart parser во время чтения; память не используется для
накопления всего multipart. Общий multipart ограничен 11 MiB с учётом служебных полей; изображения
свыше 25 миллионов пикселей отклоняются до декодирования. Rate limit — 30 загрузок в час на transport IP.

Metadata Asset/Product сохраняются одной транзакцией. Durable `product_uploads` reservation создаётся
до записи MinIO; ошибка компенсируется удалением объекта, неудачная компенсация и сбой процесса
восстанавливаются scheduled cleanup. Активная загрузка удерживает reservation row lock; уборщик
использует SKIP LOCKED и удаляет только незавершённые объекты старше часа, не принадлежащие Asset.

URL оригинала — 15-минутная HMAC capability на `/media/assets/{assetId}`; storage key и адрес MinIO
в API не возвращаются. `ASSET_PUBLIC_BASE_URL` задаёт внешний origin backend; reverse proxy должен
направлять `/media/assets/*` в backend наряду с `/api/v1/*`. Новый GET Product выдаёт свежий URL.
Подпись отделена purpose-префиксом от JWT и использует настроенный signing secret; его смена отзывает
старые URL. URL предоставляет доступ обладателю до expiry, поэтому query string нельзя писать в proxy logs.

Проверка: `./gradlew test --tests com.tovarika.tech.auth.ProductUploadIntegrationTest`.

## Анализ товара и AI-адаптеры

`POST /api/v1/products/{productId}/analysis` быстро создаёт PostgreSQL job и возвращает
`202`; Product получает `analysis_pending` и `analysisJobId` в той же транзакции.
`Idempotency-Key` уникален в пределах владельца: повтор возвращает исходную job, даже
если позже была запущена другая, а использование ключа для другого Product возвращает
`409 IDEMPOTENCY_CONFLICT`. Одновременный новый запуск для pending Product возвращает
`409 ANALYSIS_ALREADY_RUNNING`. Лимит составляет 30 новых анализов в час на identity;
idempotent replay лимит не расходует.

Worker забирает задания через `FOR UPDATE SKIP LOCKED`, переводит их в `processing` и
ставит пятиминутную lease. После падения процесса lease позволяет другому worker повторно
забрать операцию с тем же provider operation ID. Номер attempt ограждает результат старого
worker, а terminal jobs защищены DB trigger. Успех атомарно сохраняет ProductAnalysis
revision 1 и `analysis_ready`; ошибка сохраняет только стабильный публичный код
`ANALYSIS_FAILED`. Текст провайдера, изображение и generation prompt не логируются и не
попадают в JobFailure. Метрики `tovarika.analysis.latency` и
`tovarika.analysis.operations` имеют только ограниченный tag `outcome`.

Интеграция AI разделена на интерфейсы. `AnalysisProvider` отвечает за vision-анализ.
`ImageGenerator.generate(prompt, width, height)` и необязательный `ImageEditor` отвечают
за создание и редактирование изображений. `ChatGPTAdapter` реализует оба интерфейса,
а `ImageGeneratorFactory` использует Spring registry: новый адаптер достаточно объявить
bean с новым именем, код фабрики и клиентского `ImageGenerationService` менять не нужно.

Сейчас `OPENAI_MODE=stub` обязателен: `StubOpenAiClient` не выполняет сетевых запросов,
анализ помечается `[STUB]`, а генерация возвращает placeholder PNG. Подготовлены переменные:

- `OPENAI_API_KEY` — server-side API key, не передавать в UI и логи;
- `OPENAI_BASE_URL` — по умолчанию `https://api.openai.com/v1`;
- `OPENAI_VISION_MODEL` — модель Responses API для анализа исходного изображения;
- `OPENAI_IMAGE_MODEL` — GPT Image model для generation/edit;
- `OPENAI_MODE` — пока только `stub`; включать live до регистрации реального
  `OpenAiClient` запрещено fail-fast проверкой.

Реальный transport должен отправлять изображение анализа как `input_image` в Responses
API и требовать структурированный результат title/description/idea. Генерация использует
`POST /v1/images/generations`, редактирование — multipart `POST /v1/images/edits`.
Размеры приложения нужно явно сопоставлять поддерживаемым API размерам, а base64-ответ
валидировать тем же `ImageValidator`, что и пользовательские изображения. Актуальные
форматы и модели проверяйте по официальной документации OpenAI:
<https://developers.openai.com/api/docs/guides/images-vision> и
<https://developers.openai.com/api/docs/guides/image-generation>.

Проверка: `./gradlew test --tests com.tovarika.tech.auth.AnalysisJobsIntegrationTest`.

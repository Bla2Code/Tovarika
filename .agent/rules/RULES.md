# Общие правила разработки `Tovarika`

`Tovarika` — backend платформы карточек товаров для маркетплейсов. Проект развивается
как модульный монолит с contract-first API. Наличие generated endpoint не означает
готовую реализацию: проверяй ручной код и ограничения соответствующего модуля.

## 1. Как читать правила

Здесь находятся общие требования и навигация. Перед изменением отдельной части
прочитай документ из таблицы; соседние открывай только при затрагивании их ответственности.
Подробную бизнес-логику храни в профильном документе, не возвращай её в `RULES.md`.

| Область задачи | Конкретный документ |
| --- | --- |
| Редактирование готовой карточки, версии и Undo | [IMAGE_EDITING.md](IMAGE_EDITING.md) |
| Генерация карточки: запуск, состояния, worker, результат и ограничения MVP | [CARD_GENERATION.md](CARD_GENERATION.md) |
| План реализации серии из 10 карточек, сценарии шаблона и новая форма UI | [docs/card-series-tasks.md](../../docs/card-series-tasks.md) |
| Сборка prompt карточки, факты товара, текст и палитра reference | [CARD_PROMPTS.md](CARD_PROMPTS.md) |
| Каталог, recipe и reference шаблонов | [TEMPLATES.md](TEMPLATES.md) |
| AI-адаптеры, входные изображения и размеры результата | [IMAGE_GENERATION.md](IMAGE_GENERATION.md) |
| Запуск, получение и редактирование анализа товара | [PRODUCT_ANALYSIS.md](PRODUCT_ANALYSIS.md) |
| Загрузка Product, валидация, хранение и подписанные ссылки assets | [PRODUCTS.md](PRODUCTS.md) |
| Проекты, настройки, блокировки и удаление | [PROJECTS.md](PROJECTS.md) |
| Trial workspace, выбор identity и конвертация владельца | [TRIAL.md](TRIAL.md) |
| Межмодульные зависимости и граница с UI | [DEPS.md](DEPS.md) |
| Authentication, authorization, cookies, CORS/CSRF, OAuth и защищённые endpoint | [SECURITY.md](SECURITY.md) |
| OpenAI-клиент, модели, конфигурация, secrets и диагностика deployment | [OPENAI.md](OPENAI.md) |
| Создание и изменение Liquibase-миграций | [MIGRATIONS.md](MIGRATIONS.md) |

[overview.json](overview.json) — дополнительная карта кода и команд; для точечной задачи
не нужно читать её целиком. HTTP API определяется соседним репозиторием контракта,
а документы модулей описывают текущую реализацию и правила её изменения.

## 2. Технологии и архитектура

- Java 21, Spring Boot 4, Gradle Wrapper; Spring MVC, Validation, Spring Data JPA/JDBC.
- Spring Security OAuth2 Resource Server; PostgreSQL/Liquibase; MinIO; OpenAPI Generator;
  springdoc/Swagger UI; Docker/Compose.
- Новый бизнес-код группируй по функциональным модулям. Внутри разделяй `api`
  (HTTP/DTO/валидация), `application` (сценарии/транзакции/порты), `domain` (бизнес-модель)
  и `infrastructure` (persistence/внешние клиенты).
- Зависимости: `api` → `application` → `domain`; инфраструктура реализует порты.
  Контроллеры не обращаются напрямую к БД, MinIO или внешним API. Domain не зависит
  от Spring, JPA и transport DTO. Общий код выноси только при использовании несколькими модулями.
- Библиотеки добавляй при реальной необходимости; версии обновляй отдельной задачей,
  если обновление не требуется для самой функциональности.

## 3. API-контракт

- Источник истины — `../tovarika-api-contract`: `dist/openapi.yaml` и `dist/provider-openapi.yaml`.
- Endpoint, DTO, статусы и ошибки сначала меняй в контракте. Контроллер реализует интерфейс
  из `com.tovarika.api.publicapi` или `com.tovarika.api.provider`.
- `build/generated/openapi` не редактируется и не коммитится. Generated DTO используются
  только на границе API; JPA-сущности и доменные модели через REST не возвращаются.
- После изменения контракта собери его: `(cd ../tovarika-api-contract && npm ci && npm run build)`,
  затем выполни `./gradlew generatePublicApi generateProviderApi`.

## 4. Данные, интеграции и безопасность

- Схему меняй только новым changeset по [MIGRATIONS.md](MIGRATIONS.md); опубликованные файлы
  неизменяемы. Сохраняй `spring.jpa.hibernate.ddl-auto=validate`.
- Границы транзакций находятся в application-сервисах. Не связывай domain с ленивой загрузкой JPA.
- Бинарные файлы хранятся в MinIO, метаданные и серверный object key — в PostgreSQL.
  Пользовательский путь не становится object key без нормализации.
- Внешние сервисы подключаются через интерфейсы и отдельные адаптеры. Ошибки провайдера
  преобразуются в ошибки приложения без раскрытия внутренних деталей. Платёжные webhook
  проверяют подлинность и обрабатываются идемпотентно.
- Настройки группируй в типизированных `@ConfigurationProperties`, не размазывай по `@Value`.
  Локальные defaults допустимы в `application.properties`; production-секреты передаются
  настроенным каналом deployment. Для OpenAI применяется файловый Compose secret по [OPENAI.md](OPENAI.md).
- Соблюдай корневой [AGENTS.md](../../AGENTS.md): не читай и не передавай `.env`, `.env.*`,
  credentials, ключи, токены и источники Compose secrets. Не коммить и не логируй секреты,
  cookies, платёжные данные и содержимое пользовательских файлов. Примеры используют placeholders.
- Вход проверяй в API; критичные ограничения и ownership повторно защищай в application/domain.
  Для изменений Security обязательно применяется [SECURITY.md](SECURITY.md).

## 5. Стиль и проверка изменений

- Constructor injection; небольшие классы с одной ответственностью; явные предметные имена.
  Для неизменяемых value objects/configuration используй `record`, когда уместно.
- Не используй `null` как неявное состояние. Не перехватывай `Exception` без осмысленной
  обработки. Комментарии объясняют причины. Не выполняй попутный массовый рефакторинг.
- Существующие тесты не удаляй и не переписывай без прямой задачи. Security и критичные
  регрессии проверяй на существующем JUnit/Testcontainers по [SECURITY.md](SECURITY.md).
  H2 не проверяет PostgreSQL locking/concurrency; новую тестовую инфраструктуру не вводи
  без отдельного решения команды или прямой задачи.
- Минимальная проверка: `./gradlew classes`; проверка исполняемого JAR: `./gradlew bootJar -x test`.
  Требуется готовый контракт в `../tovarika-api-contract/dist` либо `TOVARIKA_API_CONTRACT_DIR`.
  Специализированные обязательные проверки указаны в документах областей.

## 6. Запуск и порядок работы

- `./gradlew bootRun` запускает приложение и Compose-инфраструктуру; `docker compose up --wait`
  запускает PostgreSQL/MinIO; `docker compose --profile full up --build --wait` — весь стек.
- Сохраняй профиль `full` у `app`, чтобы `bootRun` не запускал приложение рекурсивно.
  Docker build context включает `Tovarika` и `tovarika-api-contract` на одном уровне.
- Перед реализацией найди endpoint/модели контракта, прочитай профильные правила и существующую
  точку расширения. При изменении схемы сначала добавь миграцию, затем persistence/use case.
- Вноси минимальный связный diff, выполняй проверки и сообщай о непроверенных частях.
  При изменении поведения обновляй документ модуля; при изменении структуры/команд —
  `overview.json`, ссылки здесь и при необходимости `README.md`.

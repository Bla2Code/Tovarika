# Правила создания и подключения секретов

Обязательный документ перед добавлением или изменением секрета, Compose secrets,
конфигурации credentials или их доставки при deployment. Дополняет корневой
[AGENTS.md](../../AGENTS.md). Справочник действующих переменных —
[deploy/env.md](../../deploy/env.md).

**Статус:** единый стандарт для новых изменений. Существующие конфигурации ещё
не приведены к нему; расхождения и порядок перехода перечислены ниже. Этот документ
сам по себе не меняет Compose, приложение, workflow или секреты на сервере.

## 1. Что обнаружено в текущих конфигурациях

Проверены `compose.yaml`, `deploy/docker-compose.prod.yml`, `application.properties`,
backend deploy-скрипты, `.github/workflows/deploy.yml` и профильные правила.
Содержимое `.env` и файлов секретов не проверяется.

| Группа | Текущий способ | Расхождение со стандартом |
| --- | --- | --- |
| OpenAI | Compose secret → `/run/secrets/openai_api_key` → `OPENAI_API_KEY_FILE`; файл читает Java-клиент | Отдельный механизм чтения в приложении; `OPENAI_API_KEY_SOURCE` поддерживается только локально |
| Yandex | Compose secrets → `/run/secrets/yandex/<Spring property>` → configtree | Отдельный каталог импорта и переменные `*_SOURCE` |
| Robokassa | Compose secrets → `/run/secrets/robokassa/<Spring property>` → configtree | Файлы называются `test_robokassa_pass1/2`, а secrets — `robokassa_test_password1/2`; отдельный каталог импорта |
| JWT | `JWT_SECRET_BASE64` в environment `app` | Workflow записывает значение GitHub Secret в deployment `.env` |
| PostgreSQL | `POSTGRES_PASSWORD` в environment БД, то же значение как `DB_PASSWORD` в `app` | Файлового secret нет; имеются defaults credentials в конфигурации |
| MinIO | Access/secret key в environment MinIO, `app` и `template-assets-init` | Файловых secrets нет; имеются defaults credentials |
| SMTP | `SMTP_USERNAME` / `SMTP_PASSWORD` в production environment; при прямом запуске — Spring configuration | Файловых secrets нет; локальный Compose эти переменные не передаёт |
| Deployment | Файловые secrets оператор готовит отдельно; activation проверяет и меняет права только OpenAI | Нет общей подготовки и проверки всех требуемых файлов |

Локальные файловые sources по умолчанию находятся в `../.secrets/` относительно
корня backend, production — в `./secrets/` относительно production Compose.
Сейчас отдельные `*_SOURCE` позволяют разнести файлы по разным каталогам.
Robokassa configtree помечен `optional`, но оба secret-файла всё равно объявлены
в `services.app.secrets`: для создания контейнера они требуются даже при отключённых
платежах. GitHub deployment сейчас отключён через `if: false`; описанная запись
JWT в `.env` относится к существующему, но не выполняющемуся job.

Раньше общего правила не было: [OPENAI.md](OPENAI.md) описывал создание API key,
[YANDEX_OAUTH.md](YANDEX_OAUTH.md) — подключение OAuth credentials,
[RULES.md](RULES.md) — общие запреты. Эти документы остаются описанием действующей
реализации до её миграции; общий стандарт задаётся здесь.

## 2. Единый стандарт

1. **Один каталог на окружение:** обязательная несекретная переменная `SECRETS_DIR`
   содержит абсолютный путь вне Git checkout и Docker build context. Все runtime
   secrets этого окружения лежат непосредственно в нём. Local, staging и production
   используют разные каталоги и значения, но одинаковые имена и правила подключения.
   Например, local — `$HOME/.local/share/tovarika/secrets/local`, production —
   `/home/deploy/tovarika-secrets/production`. Это целевые примеры, не текущие defaults.
2. **Один файл — одно значение.** Имя файла равно Compose secret ID: `snake_case`,
   без расширения и имени окружения. Содержимое — UTF-8 значение без `NAME=`,
   кавычек и лишних переводов строк. Кодировка Base64 применяется только когда её
   требует потребитель, например JWT; она не является шифрованием.
3. **Один источник в Compose:** `file: ${SECRETS_DIR:?SECRETS_DIR is required}/<id>`.
   Этот шаблон одинаков в local и production. Новые индивидуальные `*_SOURCE`,
   secrets в `.env`, `env_file`, `environment`, build args и Dockerfile не добавляются.
   Несекретные режимы, URL, имена БД и пути остаются обычной конфигурацией.
4. **Один способ доставки:** Compose secrets, только сервисам-потребителям,
   read-only в `/run/secrets/<id>`, без переименования source-файла. Spring-приложение
   получает все значения через единый `SPRING_CONFIG_IMPORT=configtree:/run/secrets/`.
   В `application.properties` свойства из реестра ссылаются на ID дерева, например
   `spring.datasource.password=${postgres_password}`. Каталоги отдельных провайдеров
   и ручное чтение API key в Java после миграции не используются.
5. PostgreSQL и MinIO получают те же source-файлы через штатные файловые параметры
   их entrypoint. Путь `/run/secrets/<id>` передаётся в `*_FILE`,
   само значение в environment не передаётся. Поддержку файловых параметров нужно
   проверить на используемом образе до изменения конфигурации. `template-assets-init`
   также получает файлы и читает их только при runtime-аутентификации в MinIO.
6. **Нет секретных defaults.** Обязательный для включённой интеграции отсутствующий,
   пустой или недоступный secret останавливает подготовку/запуск с сообщением только
   об ID. Отключённая интеграция не должна требовать фиктивный ключ: согласованно
   исключаются её mounts и проверка обязательности. `optional:configtree:` сам по
   себе не делает Compose secret необязательным.
7. Значения создаёт оператор или доверенный CI/secret manager. Агент редактирует
   только конфигурации, правила и примеры с `<REDACTED>`; не открывает, не копирует
   и не переносит существующие файлы секретов. Тесты используют только временные
   фиктивные credentials и не обращаются к каталогам окружений.

`SECRETS_DIR` пока не поддерживается текущими Compose. Единый configtree для OpenAI
требует изменения `OpenAiProperties` и клиента: сейчас есть только `api-key-file`,
а целевого свойства `api-key` ещё нет. До миграции применяются действующие пути
из справочника конфигурации.

## 3. Реестр runtime secrets для перехода

Идентификаторы стабильны при смене CI или Git hosting. Source каждого файла —
`${SECRETS_DIR}/<id>`. Во всех контейнерах путь равен `/run/secrets/<id>`.
Configtree-свойство `<id>` связывается с указанным свойством Spring явным placeholder.
Имена в колонке CI — целевой контракт доставки, а не список уже настроенных Secrets.

| ID / имя source-файла | Свойство Spring | Имя в CI secret store | Потребители |
| --- | --- | --- | --- |
| `jwt_secret_base64` | `tovarika.security.jwt.secret-base64` | `JWT_SECRET_BASE64` | `app` |
| `postgres_password` | `spring.datasource.password` | `POSTGRES_PASSWORD` | `postgres`, `app` |
| `minio_access_key` | `tovarika.storage.minio.access-key` | `MINIO_ACCESS_KEY` | `minio`, `app`, `template-assets-init` |
| `minio_secret_key` | `tovarika.storage.minio.secret-key` | `MINIO_SECRET_KEY` | `minio`, `app`, `template-assets-init` |
| `smtp_username` | `spring.mail.username` | `SMTP_USERNAME` | `app`, если SMTP требует credentials |
| `smtp_password` | `spring.mail.password` | `SMTP_PASSWORD` | `app`, если SMTP требует credentials |
| `openai_api_key` | `tovarika.ai.openai.api-key` **(нужно реализовать)** | `OPENAI_API_KEY` | `app` в OpenAI live mode |
| `yandex_client_id` | `tovarika.security.oauth.yandex.client-id` | `YANDEX_CLIENT_ID` | `app` при включённом OAuth |
| `yandex_client_secret` | `tovarika.security.oauth.yandex.client-secret` | `YANDEX_CLIENT_SECRET` | `app` при включённом OAuth |
| `robokassa_test_password1` | `tovarika.payments.robokassa.test-password1` | `ROBOKASSA_TEST_PASSWORD1` | `app` в Robokassa TEST |
| `robokassa_test_password2` | `tovarika.payments.robokassa.test-password2` | `ROBOKASSA_TEST_PASSWORD2` | `app` в Robokassa TEST |

DB username остаётся несекретной настройкой. Yandex client ID не является приватным
ключом, но хранится вместе с client secret для единого подключения. MinIO access key
и SMTP username считаются частью credentials. Live-пароли Robokassa не добавляются
заранее: режим LIVE сейчас не реализован. Новый secret сначала добавляется в реестр
с потребителем, форматом, условием обязательности и способом ротации.

Пример **целевой**, ещё не действующей конфигурации для одного секрета:

```yaml
services:
  app:
    environment:
      SPRING_CONFIG_IMPORT: "configtree:/run/secrets/"
    secrets:
      - jwt_secret_base64

secrets:
  jwt_secret_base64:
    file: ${SECRETS_DIR:?SECRETS_DIR is required}/jwt_secret_base64
```

Соответствующая целевая запись в `application.properties`:

```properties
tovarika.security.jwt.secret-base64=${jwt_secret_base64}
```

## 4. Как оператор создаёт secret

1. Выбрать окружение и задать абсолютный `SECRETS_DIR`. Создать каталог с mode `0700`;
   отдельные файлы создавать с `umask 077` и mode `0600`. Владелец — runtime/deployment
   user. При файловых Compose secrets права задаются на host: `uid/gid/mode` в Compose
   не исправляют права bind-mounted source. Доступ UID/GID PostgreSQL, MinIO, job и
   `app` согласовывается отдельно через минимальные ACL/группы; `0644` и `0777`
   для обхода ошибки чтения запрещены.
2. Для внешнего сервиса получить credentials в его кабинете/secret manager.
   Не генерировать случайную строку вместо выданного API key или OAuth secret.
   Собственные пароли генерировать CSPRNG с учётом ограничений сервиса; JWT —
   минимум 32 случайных байта, затем Base64. JWT сохраняется между deployment
   и используется всеми экземплярами приложения одного окружения.
3. Сохранить значение под ID из реестра через защищённый ввод/secret manager.
   Не вставлять значение в shell history, аргументы команд, чат, issue или редактор
   tracked-файла. Для интерактивного Bash допустим следующий шаблон, выполняемый
   **оператором вручную**, с заранее выбранным `SECRETS_DIR`:

   ```bash
   (
     set +x
     set -euo pipefail
     : "${SECRETS_DIR:?Set an absolute secrets directory}"
     [[ "$SECRETS_DIR" = /* ]]
     install -d -m 700 -- "$SECRETS_DIR"
     umask 077
     set -o noclobber
     read -r -s -p 'Yandex client secret: ' secret_value
     printf '\n'
     [[ -n "$secret_value" ]]
     printf '%s' "$secret_value" > "$SECRETS_DIR/yandex_client_secret"
     unset secret_value
   )
   ```

   Шаблон не перезаписывает существующий файл. Для другого секрета меняются только
   prompt и ID. Подготовленный каталог не размещают внутри checkout/build context.
4. Проверять только наличие, непустоту, владельца, права и доступность потребителю.
   Не выводить содержимое, hash или часть значения. Не запускать обычные
   `docker compose config`, полный `docker inspect`, `printenv`, `env` или shell
   tracing в окружении с credentials. Проверки конфигурации выполняются без загрузки
   `.env`, без интерполяции секретов и без печати разрешённого окружения.
5. После изменения файла пересоздать затронутые контейнеры: конфигурация читается
   при старте, bind mount может сохранять прежний inode после атомарной замены файла.
   Для ротации сначала согласовать новое значение у провайдера/в сервисе, подготовить
   файл, пересоздать потребителей, проверить работоспособность и отозвать старое.
   Смена PostgreSQL-файла не меняет пароль роли в существующей БД; JWT-ротация
   делает прежние access tokens и подписанные asset-ссылки недействительными.

## 5. Перенос в GitHub или другой Git hosting

В Git переносятся код, реестр имён и правила доставки. **Открытые значения секретов
не коммитятся даже в приватный репозиторий.** Целевое хранилище значений — защищённые
Secrets выбранной CI-платформы либо внешний secret manager. Если отдельно потребуется
версионирование зашифрованных секретов в Git, до этого выбираются инструмент
шифрования, владельцы ключей и процедура восстановления; ключ дешифрования хранится
вне репозитория. Сейчас такая схема не внедрена.

Контракт runtime не зависит от CI: защищённое хранилище → подготовка файлов из
реестра → один `SECRETS_DIR` окружения → Compose secrets → потребители. Миграция
Git hosting меняет только получение/доставку значений, имена файлов и конфигурация
приложения сохраняются.

- При переходе оператор отдельно импортирует значения в новое хранилище, задаёт
  доступ для deployment окружения и проверяет наличие всех требуемых имён. Агент
  не выгружает существующие значения для такого переноса.
- Доверенный CI создаёт файлы с `umask 077` в закрытом временном каталоге, передаёт
  их по проверенному SSH/SCP в server-side `SECRETS_DIR`, устанавливает host-права
  до запуска и очищает временные файлы при успехе и ошибке. Секреты не входят в
  image, архив backend/template assets, CI artifacts, cache или logs. Секретные CI
  inputs передаются штатным механизмом платформы, без вставки значений в shell-код.
- `DEPLOY_SSH_KEY` и `CONTRACTS_REPO_TOKEN` — credentials самого CI, а не приложения.
  Они включаются в учёт секретов, хранятся в защищённом CI store и передаются только
  SSH/checkout-инструментам; в runtime `SECRETS_DIR` и контейнеры их не доставляют.
  Если инструменту нужен файл, CI создаёт его в своём закрытом временном каталоге
  и удаляет при завершении. `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_KNOWN_HOSTS` —
  параметры доступа и проверенные публичные host keys, значения secrets приложения
  в них не включаются.
- Deployment не должен перезаписывать остальные настройки сервера файлом только
  с JWT, как делает текущий workflow. Несекретная конфигурация и доставка файлов
  секретов обновляются отдельно; автоматическая регенерация credentials запрещена.

## 6. Порядок приведения реализации к стандарту

Эти пункты **ещё не выполнены**; переход требует согласованного изменения runtime
и deployment, а не только правки путей в документации.

1. Подготовить единый каталог на каждое окружение по реестру. Сохранить существующие
   значения, включая JWT и пароли уже созданной БД; source-файлы переносит оператор.
   Переименовать файлы Robokassa в `robokassa_test_password1/2`.
2. Одновременно изменить оба Compose: обязательный `SECRETS_DIR`, secrets по реестру,
   единый `/run/secrets/`, configtree `app` и файловые inputs PostgreSQL/MinIO.
   Убрать environment со значениями secrets и индивидуальные `*_SOURCE`.
3. Изменить OpenAI на получение `tovarika.ai.openai.api-key` из configtree, сохранить
   fail-fast и маскирование credentials в диагностике. Удалить ручное чтение
   `OPENAI_API_KEY_FILE` после перехода всех способов запуска. При прямом `bootRun`
   импортировать тот же каталог через `SPRING_CONFIG_IMPORT=configtree:<SECRETS_DIR>/`
   с подстановкой абсолютного пути и завершающим `/`; копии файлов и отдельное дерево
   для запуска без контейнера не создавать. В shell передаётся только путь.
4. Удалить секретные defaults и старые ENV aliases, способные перекрыть файловое
   значение. Для отключённых OpenAI/SMTP/Robokassa и необязательного OAuth определить
   состав mounts и проверки запуска; текущего переключения режима недостаточно.
5. Обновить activation, `sync-template-assets.sh` и GitHub workflow: общий preflight
   файлов без чтения содержимого, права для всех потребителей, доставка по реестру,
   очистка CI staging и отсутствие JWT в `.env`. Согласовать внешний локальный
   UI deploy-скрипт, если он используется: в этом backend-репозитории его нет.
6. Проверить на временных фиктивных secrets: local/full и production Compose,
   `bootRun`, обязательный отсутствующий/пустой/недоступный файл, отключённые
   интеграции, MinIO template sync и пересоздание после замены файла. Проверить,
   что credentials отсутствуют в logs и конфигурации environment контейнеров,
   без вывода самих значений. Не использовать реальные secrets в тестах.
7. Обновить справочник, README, профильные правила и `overview.json` по фактическому
   результату. Удалять прежние sources оператор может после успешного перехода
   и подготовки rollback; не удалять их автоматически при первом deployment.

# Настройка OpenAI API

Этот файл обязателен к прочтению перед изменением OpenAI-клиента, AI-конфигурации,
prompt анализа товара, Compose secrets или deployment flow.

## 1. Текущая реализация

- `OPENAI_MODE=stub` использует `StubOpenAiClient` и не выполняет сетевых запросов.
- `OPENAI_MODE=live` использует `OpenAiResponsesClient` и отправляет изображение в
  `POST /v1/responses` как `input_image` с `detail=high`.
- Prompt анализа хранится в
  `src/main/resources/prompts/product-analysis-v1.txt`.
- Ответ ограничен Structured Outputs-схемой с полями `title`, `description` и `idea`.
- Запрос выполняется с `store=false`; ошибки провайдера санитизируются.
- Live-генерация и редактирование изображений пока не реализованы.
  `OPENAI_IMAGE_MODEL` зарезервирован для следующего этапа.

Официальная документация:

- API и аутентификация: <https://developers.openai.com/api/reference/overview>;
- Responses API: <https://developers.openai.com/api/reference/resources/responses/methods/create>;
- vision: <https://developers.openai.com/api/docs/guides/images-vision>;
- Structured Outputs: <https://developers.openai.com/api/docs/guides/structured-outputs>.

## 2. Обязательные правила безопасности

- Никогда не читай, не печатай, не копируй и не передавай содержимое `.env`,
  `openai_api_key`, `/run/secrets/*` или пути из `OPENAI_API_KEY_SOURCE`.
- Не проси пользователя вставлять API key в чат, issue, тест, log или командную строку.
- Не добавляй ключ в `compose.yaml`, `application.properties`, Dockerfile, Git или UI.
- Не выводи `.Config.Env`, полный `docker inspect`, request headers или тело ошибки
  OpenAI: рядом могут находиться другие секреты.
- Допустимо проверять только несекретные признаки: выбран ли режим `live`, задана ли
  модель, совпадает ли путь `OPENAI_API_KEY_FILE`, присутствует ли mount destination.
- Ключ отправляется только backend-приложением целевому OpenAI API как Bearer credential.

OpenAI рекомендует хранить API key только на сервере в environment/key-management
системе и никогда не раскрывать его в браузерном коде.

## 3. Конфигурация

| Переменная | Назначение | Значение Compose по умолчанию |
| --- | --- | --- |
| `OPENAI_MODE` | `stub` или `live` | `stub` |
| `OPENAI_BASE_URL` | базовый URL API | `https://api.openai.com/v1` |
| `OPENAI_API_KEY_FILE` | путь внутри контейнера | `/run/secrets/openai_api_key` |
| `OPENAI_API_KEY_SOURCE` | локальный source-файл Compose secret | `../.secrets/openai_api_key` |
| `OPENAI_VISION_MODEL` | модель Responses API для анализа | `gpt-6-luna` |
| `OPENAI_IMAGE_MODEL` | будущая generation/edit модель | `gpt-image-2.5-sunburst` |

`OPENAI_API_KEY_FILE` задаётся Compose напрямую. Значение ключа не является
environment-переменной контейнера.

## 4. Локальная настройка оператором

Агент не создаёт и не читает secret. Пользователь выполняет следующие команды вручную
в обычном терминале:

```bash
install -d -m 700 /home/malexey/project/tovarika/.secrets
umask 077
read -rsp 'OpenAI API key: ' OPENAI_KEY
echo
printf '%s' "$OPENAI_KEY" > /home/malexey/project/tovarika/.secrets/openai_api_key
unset OPENAI_KEY
chmod 600 /home/malexey/project/tovarika/.secrets/openai_api_key
```

Включить live mode можно для одного запуска без `.env`:

```bash
cd /home/malexey/project/tovarika/Tovarika
OPENAI_MODE=live docker compose --profile full up -d --build --force-recreate app
```

Либо пользователь вручную создаёт игнорируемый Git файл `.env`, содержащий только
несекретную настройку:

```dotenv
OPENAI_MODE=live
```

API key в `.env` не добавляется. После изменения Compose или режима контейнер приложения
нужно пересоздать с `--force-recreate`.

## 5. Production deployment

Production secret заранее создаётся оператором на сервере:

```text
/home/deploy/tovarika-backend/secrets/openai_api_key
```

Каталог должен иметь mode `700`, файл — `600`, владелец — deployment user. Production
`.env` содержит `OPENAI_MODE=live` и названия моделей, но не API key.

Локальный deploy-скрипт находится в:

```text
/home/malexey/project/TovaricaUI/scripts/deploy-local.sh
```

Если используется текущий backend checkout, оператор задаёт в UI deployment config:

```dotenv
BACKEND_DIR=/home/malexey/project/tovarika/Tovarika
```

Deploy-скрипт проверяет наличие непустого server-side secret, но не читает и не
пересылает его.

## 6. Безопасная диагностика

Если UI возвращает текст с `[STUB]`, это означает одно из следующего:

1. `OPENAI_MODE` не равен `live`;
2. контейнер не был пересоздан после изменения конфигурации;
3. запущен образ или Compose-файл из другого checkout.

Сначала проверь Compose labels контейнера и только несекретные флаги. Не читай `.env`
и secret-файл. В live mode отсутствие/пустой/нечитаемый secret или модели приводит к
fail-fast ошибке старта приложения.

Сохранённый `[STUB]`-анализ не пересчитывается автоматически после переключения режима.
Для smoke-теста загрузи новое изображение или явно запусти повторный анализ с новым
idempotency key.

## 7. Проверка изменений

Минимальные тесты OpenAI boundary:

```bash
./gradlew test --tests com.tovarika.tech.images.infrastructure.OpenAiResponsesClientTest
./gradlew test
```

Тесты используют только фиктивный ключ во временном файле и mock HTTP boundary. Реальный
API key и пользовательские изображения в fixtures не добавляются.

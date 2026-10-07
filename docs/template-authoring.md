# Подготовка шаблонов карточек

В MVP шаблон состоит из одного компактного reference-изображения и одного JSON recipe — структурированного описания оформления. Один и тот же файл используется для превью в каталоге и как визуальный образец при генерации. Отдельных версий и дополнительных размеров изображений нет. Разработчик загружает файл в MinIO вручную, а все метаданные и связь с шаблоном добавляет скрипт миграции Liquibase.

Для будущей серии подготовлен [набор из 10 сценариев tpl_fashion_hero](../assets/templates/tpl_fashion_hero/card-variants.json).
Его структура и задачи подключения описаны в [плане серии карточек](card-series-tasks.md).
Сценарии пока не участвуют в runtime и не заменяют JSON recipe schemaVersion=1 ниже.

Готовый пример для категории «Одежда и обувь»: [изображение и инструкция подключения](../assets/templates/tpl_fashion_hero/README.md). Миграция `011-fashion-template-example` заполняет один существующий шаблон `tpl_fashion_hero`; изображение нужно вручную загрузить в MinIO до её применения.

## 1. Подготовить изображение

- Ориентир — вертикальный WebP размером `384×512` (`3:4`). Размер `382×512`, как в примере конкурента, тоже подходит: небольшое отклонение от пропорции допустимо.
- Рекомендуемый бюджет файла — до `100 КиБ`, если при этом сохраняется читаемость текста. Это ориентир подготовки, а не текущий лимит API. После сжатия проверить изображение в размере карточки каталога; большой исходник сам по себе не нужен для превью и увеличивает объём загрузки страницы.
- Размер reference не определяет разрешение готовой карточки: оно задаётся отдельно при генерации.
- Шаблон должен выглядеть как реальная карточка товара: демонстрационный товар, крупный заголовок, подзаголовки, характеристики, плашки и другие элементы оформления.
- Текст убирать не нужно. Например, в карточке с костюмом остаются заголовок «КОСТЮМ», плашка «ДЕТСКИЙ», характеристики и блок цветов. Они показывают расположение и оформление будущего текста, а не факты о товаре пользователя.
- Композиция, фон и свет должны быть пригодны для товаров разной формы внутри выбранной категории.
- При генерации модель заменяет демонстрационный товар товаром с исходного изображения пользователя, а надписи — достоверным текстом о нём. Названия, бренды, характеристики, цены и цвета из шаблона не должны автоматически переходить в результат.

## 2. Подготовить recipe JSON

Правила runtime-сборки prompt и переноса текста/палитры описаны в
[CARD_PROMPTS.md](../.agent/rules/CARD_PROMPTS.md); каталог и snapshots — в
[TEMPLATES.md](../.agent/rules/TEMPLATES.md). Ниже — процедура подготовки authoring-данных.

Recipe готовит автор шаблона или разработчик один раз при добавлении шаблона. Это обычный JSON, который сохраняется в `templates.recipe_json` через миграцию. Приложение сейчас не извлекает его из изображения автоматически; отдельного API для получения recipe нет.

Можно заполнить JSON вручную по схеме ниже либо получить черновик с помощью модели, умеющей анализировать изображения:

1. Открыть чат с поддержкой загрузки и анализа изображений.
2. Прикрепить подготовленный файл шаблона и отправить служебный запрос ниже.
3. Скопировать JSON из ответа и проверить его по изображению. Для примера с костюмом заполненный recipe приведён в миграции в разделе 3.
4. Поместить проверенный JSON в поле `recipe_json` скрипта миграции. Отдельно загружать JSON в MinIO не нужно.

Служебный запрос для подготовки recipe:

```text
Проанализируй приложенное изображение шаблона карточки товара. Подготовь описание оформления для создания карточки другого товара по его исходному фото и описанию пользователя.

Сохрани в описании композицию, фон, свет, палитру и структуру текстовых блоков: заголовок, подзаголовки, характеристики, плашки. Укажи расположение и визуальную иерархию текста, не переписывая демонстрационные надписи и сведения о товаре из шаблона. Текстовые блоки должны заполняться данными нового товара, а не исчезать из-за общего запрета на текст.

Верни только валидный JSON без Markdown строго по схеме:
{
  "schemaVersion": 1,
  "scene": "короткое описание окружения или фона на английском",
  "composition": "расположение товара и текстовых блоков на английском",
  "lighting": "свет и тени на английском",
  "style": "визуальный стиль, типографика и плашки на английском",
  "palette": ["#RRGGBB", "#RRGGBB"],
  "avoid": ["нежелательный элемент на английском"]
}

Ограничения: scene/composition/lighting/style — не более 160 символов каждое; palette — 2–5 цветов; avoid — 3–8 пунктов. В avoid запрети дословный перенос текста и бренда шаблона, выдуманные характеристики/цены/варианты товара, добавление посторонних товаров и изменение бренда исходного товара. Не добавляй общий запрет на текст, заголовки, характеристики или плашки.
```

Проверить синтаксис JSON, `schemaVersion: 1`, наличие всех полей, ограничения длины и отсутствие общего запрета `"text"` в `avoid`. Recipe описывает оформление; конкретные сведения для надписей берутся из описания товара пользователя и его исходного изображения при каждой генерации.

### Требование к итоговому prompt генерации

Служебный запрос выше нужен только для подготовки recipe. Итоговый prompt карточки собирает приложение из recipe и описания товара; вместе с ним модель получает исходное фото товара и reference шаблона.

В итоговом prompt необходимо явно задать следующие правила:

```text
Image 1 is the user's source product. Preserve its identity, geometry, colors, branding and visible product labels.
Image 2 is a layout and style reference. Replace its demonstration product with the source product.
Preserve the layout and visual hierarchy of headings, feature text and badges, adapting them to the requested output ratio.
Rewrite every reference overlay text block using the user's product description, consistent with the source image. Use the language of that description.
Do not copy the reference product name, brand, claims, prices or color variants. Do not invent missing facts. Omit a block if there is no supported content for it.
The reference palette applies to the background and decoration, not to recoloring the source product or inventing its available colors.
```

Например, заголовок «КОСТЮМ» заменяется названием товара пользователя. Фраза «с начёсом», указание возраста и блок вариантов цветов появляются только при наличии соответствующих данных о новом товаре. Надписи и логотип на самом исходном товаре сохраняются; заменяется наложенный текст карточки.

`CardPromptCompiler` включает эти правила в prompt генерации. Миграция `011-fashion-template-example` записывает recipe с текстовыми блоками для `tpl_fashion_hero`. В стартовой миграции `010` у остальных recipe может оставаться общий запрет `"text"`; при подготовке других шаблонов с текстом нужно заменить его проверенным recipe в новой миграции.

## 3. Подготовить миграцию всех метаданных

Для нового шаблона добавить отдельный файл со следующим свободным номером в `src/main/resources/db/changelog/changes/` и подключить его в конец `db.changelog-master.yaml`. Уже применённые миграции не редактировать.

Скрипт должен в одной транзакции:

1. Добавить категорию, если её ещё нет: `id`, `name`, уникальный `sort_order`, `available`. Для существующей категории использовать её ID.
2. Создать `assets`: `id`, `purpose='template_reference'`, фактические `media_type`, `size_bytes`, `width`, `height`, согласованный `storage_key`, `url`, `expires_at`, `created_at`.
3. Создать или обновить `templates`: `id`, `name`, `category_id`, `sort_order`, `available`, `reference_asset_id`, полный проверенный `recipe_json`, `created_at`, `updated_at`. При обновлении сохранить исходный `created_at`.

`storage_key` — ключ объекта внутри bucket, например `templates/tpl_fashion_text/reference.webp`. В `url` записывается пустая строка, как и для других хранимых в MinIO файлов: ссылку для клиента формирует приложение. `expires_at` — `NULL`.

Пример файла `012-fashion-text-template.yaml` для существующей категории `cat_fashion`. Перед использованием выбрать свободный номер миграции и заменить примерные ID, имя, порядок и метаданные на реальные. Здесь `49152` — **условный размер файла в байтах**, а `382×512` — размер изображения из примера; после перекодирования или изменения размера нужно указать параметры окончательного файла.

```yaml
databaseChangeLog:
  - changeSet:
      id: 012-fashion-text-template
      author: tovarika
      changes:
        - sql:
            splitStatements: false
            sql: |
              INSERT INTO assets (
                  id, purpose, media_type, size_bytes, width, height,
                  storage_key, url, expires_at, created_at
              ) VALUES (
                  'asset_tpl_fashion_text', 'template_reference', 'image/webp',
                  49152, 382, 512,
                  'templates/tpl_fashion_text/reference.webp', '', NULL, CURRENT_TIMESTAMP
              );

              INSERT INTO templates (
                  id, name, category_id, sort_order, available,
                  reference_asset_id, recipe_json, created_at, updated_at
              ) VALUES (
                  'tpl_fashion_text', 'Крупный заголовок и характеристики', 'cat_fashion', 40, true,
                  'asset_tpl_fashion_text',
                  '{
                    "schemaVersion": 1,
                    "scene": "Warm beige background with rounded cream panels",
                    "composition": "Large headline across the top; source product prominent on the left; stacked feature text and rounded badges on the right",
                    "lighting": "Soft even studio light with gentle product shadows",
                    "style": "Marketplace infographic with bold condensed headings, readable feature text and rounded badges; rewrite overlays using user product facts",
                    "palette": ["#D6C3A0", "#F5F2EB", "#8E7959", "#66DADC"],
                    "avoid": [
                      "verbatim reference text or reference branding",
                      "invented specifications, prices or color variants",
                      "unrelated products or unsupported product views",
                      "changes to source product identity, colors or branding"
                    ]
                  }'::jsonb,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
              )
              ON CONFLICT (id) DO UPDATE SET
                  name = EXCLUDED.name,
                  category_id = EXCLUDED.category_id,
                  sort_order = EXCLUDED.sort_order,
                  available = EXCLUDED.available,
                  reference_asset_id = EXCLUDED.reference_asset_id,
                  recipe_json = EXCLUDED.recipe_json,
                  updated_at = CURRENT_TIMESTAMP;
```

Подключение в конец списка `databaseChangeLog` в `src/main/resources/db/changelog/db.changelog-master.yaml`:

```yaml
  - include:
      file: db/changelog/changes/012-fashion-text-template.yaml
```

Миграция записывает только метаданные в БД. Она не загружает изображение, не скачивает его по внешней ссылке и не создаёт bucket.

## 4. Вручную загрузить изображение и проверить шаблон

1. Разработчик вручную загружает окончательный файл в bucket приложения по точному `storage_key` из подготовленной миграции. Для WebP указать Content-Type `image/webp`.
2. Сверить формат, размер в байтах, ширину и высоту файла с метаданными миграции.
3. Применить миграцию через штатный запуск приложения с Liquibase. Файл должен быть загружен **до применения миграции, делающей шаблон доступным**.
4. Через `GET /api/v1/templates` найти шаблон и открыть его превью. Проверить читаемость текста и объём загружаемого файла.
5. Выполнить тестовую генерацию для каждого поддерживаемого соотношения сторон (`1:1`, `3:4`, `4:5`, `16:9`). Проверить замену товара и демонстрационных надписей, соответствие описанию пользователя, сохранение деталей исходного товара и отсутствие выдуманных характеристик.

Общая заглушка отображается, пока у шаблона нет `reference_asset_id`; в `live`-режиме генерация без настроенного reference отклоняется. Наличие метаданных не подтверждает наличие объекта в MinIO: если связать asset до загрузки файла, превью и генерация могут завершиться ошибкой.

Для замены изображения использовать новый `storage_key` и новую запись `assets`, затем переключить `reference_asset_id` отдельной миграцией. Это сохраняет reference для уже созданных заданий. В карточке сохраняется снимок recipe; изменение шаблона применяется к новым заданиям, а внутренний собранный prompt не выдаётся через публичный API.

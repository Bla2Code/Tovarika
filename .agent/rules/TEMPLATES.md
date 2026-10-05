# Каталог и подготовка шаблонов

Читай при изменении templates, recipe/reference, preview или избранного.
Prompt — [CARD_PROMPTS.md](CARD_PROMPTS.md); подготовка файлов/metadata —
[docs/template-authoring.md](../../docs/template-authoring.md).

## 1. Каталог

- TemplatesController/TemplateService в `src/main/java/com/tovarika/tech/templates/`:
  публичные categories/list, поиск, cursor pagination, authenticated favorites.
  Каталог показывает available templates в available categories;
  категории без доступных шаблонов не возвращаются.
- Фильтры: categoryId, search (1–200 символов после strip), favoriteOnly; limit 1–100,
  default 20. Cursor сортирует по category.sort_order/template.sort_order/template.id.
- FavoriteOnly и изменение favorite требуют Bearer User, trial недостаточно.
  Favorite хранится в template_favorites(user_id, template_id), изменение идемпотентно.
- Один reference используется для thumbnail/генерации; без него каталог отдаёт placeholder.
  Recipe и технический prompt не входят в публичный Template DTO.

## 2. Reference и recipe

- templates.recipe_json — server-owned JSON с schemaVersion=1 и scene/composition/
  lighting/style/palette/avoid. Готовится и проверяется заранее, не извлекается приложением из фото.
- Reference: Asset purpose template_reference, key templates/{templateId}/…;
  image загружается вручную в MinIO, metadata/связь/recipe — новой Liquibase-миграцией.
  Загрузка должна предшествовать миграции, которая делает шаблон доступным.
- Не переписывай опубликованные миграции по [MIGRATIONS.md](MIGRATIONS.md).
  При замене reference создай новый key/Asset, затем переключи ссылку новой миграцией;
  старый объект сохраняется для jobs.
- Card сохраняет recipe snapshot, reference asset ID, prompt и analysis revision.
  Worker использует snapshot, а не обновлённый recipe каталога.
- В live нужны reference ID/storage key/mediaType, иначе 422 TEMPLATE_NOT_READY.
  Это проверка metadata: отсутствие binary обнаруживается worker как generation failure.
  В stub без reference разрешён placeholder.
- Проверяй текст, замену демонстрационных сведений и четыре ratio. Authoring limits
  проверяются при подготовке; compiler проверяет лишь object/version и длину prompt.
  Не выдавай authoring-инструкцию за полную runtime-валидацию.

Пример: [tpl_fashion_hero](../../assets/templates/tpl_fashion_hero/README.md).
Правила переноса текста/палитры и достоверности товара — [CARD_PROMPTS.md](CARD_PROMPTS.md).

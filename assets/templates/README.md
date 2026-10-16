# Добавление шаблона карточки

Рабочий образец — [tpl_fashion_hero](tpl_fashion_hero/README.md); второй пример с UUID-именем изображения и десятью сценариями — [tpl_fashion_accent](tpl_fashion_accent/README.md). Подробные правила: [подготовка шаблонов](../../docs/template-authoring.md), [каталог и snapshots](../../.agent/rules/TEMPLATES.md), [сборка prompt](../../.agent/rules/CARD_PROMPTS.md), [миграции](../../.agent/rules/MIGRATIONS.md).

Задача при подготовке очередного макета — оставить в репозитории изображение, описание, десять сценариев и Liquibase-миграции. **Не загружать объект в MinIO вручную и не применять миграции к БД.** При backend-деплое одноразовый Compose job синхронизирует reference-файлы с MinIO до запуска приложения и Liquibase.

## Порядок работы

1. **Выбрать шаблон.** Найти его `templateId`, категорию и порядок в миграции каталога `010`. Для нового шаблона назначить уникальный ID и свободный `sort_order`. Не менять уже опубликованные миграции.
2. **Подготовить изображение.** Поместить один готовый reference в `assets/templates/<templateId>/<uuid>.webp`. Ориентир — `384×512`, до `100 КиБ`. Убрать случайные призывы к действию и интерфейсные элементы. Reference используется и как превью, и как второй вход генерации. Не хранить исходный тяжёлый файл в каталоге шаблона.
3. **Описать общий recipe.** Составить JSON `schemaVersion: 1` с полями `scene`, `composition`, `lighting`, `style`, `palette`, `avoid`. Описывать композицию и оформление, а не свойства демонстрационного товара. Текстовые поля — до 160 символов, палитра — 2–5 цветов, `avoid` — 3–8 пунктов. Не запрещать весь текст: надписи reference заменяются подтверждёнными сведениями пользователя.
4. **Описать десять сценариев.** Создать `card-variants.json` по примеру рядом с reference: позиции 1–10, стабильные ID `<templateId>_v01`…`_v10`, русские `title` и `defaultIdea`, `generationRecipe` с `framingMode`, `composition`, `conditions`, `fallbackComposition`. Для деталей предусмотреть показ товара целиком, если участок исходного фото неразличим. `referenceFile` должен совпадать с именем WebP.
5. **Создать миграции.** Следующим свободным номером добавить Liquibase changeSet с записью `assets` (`purpose='template_reference'`, фактические MIME, байты, ширина, высота и ключ `templates/<templateId>/<uuid>.webp`) и обновлением/созданием `templates` с recipe и `reference_asset_id`. Создать отдельную миграцию вариантов:

   ```sh
   python3 scripts/seed-card-variants.py --source assets/templates/<templateId>/card-variants.json --output src/main/resources/db/changelog/changes/NNN-template-variants.yaml
   ```

   Подключить оба файла в конце `src/main/resources/db/changelog/db.changelog-master.yaml`. Скрипт не загружает данные в БД: это делает Liquibase при запуске приложения.
6. **Передать результат пользователю.** Сообщить `templateId`, путь к WebP, точный ключ `templates/<templateId>/<uuid>.webp`, номера миграций и путь к `card-variants.json`. `deploy-local.sh backend` передаёт WebP на сервер и запускает синхронизацию с Content-Type `image/webp` **до применения миграций**. В ключ объекта не входят `assets/` и имя bucket.

Если опубликованный reference нужно заменить, создать новый UUID/key и новую запись asset в новой миграции, затем переключить `reference_asset_id`. Старый объект сохранить для уже созданных заданий.

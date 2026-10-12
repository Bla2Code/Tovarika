# Сценарии карточек

Источник данных — `assets/templates/tpl_fashion_hero/card-variants.json`.
Проверка без записи:

```sh
python3 scripts/seed-card-variants.py --check
```

Для нового набора изменений выберите следующий свободный номер миграции:

```sh
python3 scripts/seed-card-variants.py --output src/main/resources/db/changelog/changes/NNN-fashion-card-variants.yaml
```

Скрипт требует новый файл; существующий файл, в том числе опубликованный в HEAD,
не перезаписывается. Подключите созданный файл в конце master changelog, затем
выполните `.agent/rules/check-liquibase-migrations.sh` и `./gradlew test`.
Liquibase загружает данные атомарно при штатном запуске. Скрипт не подключается
к БД и не читает credentials. `013` — первоначальный seed ровно десяти сценариев;
обновление JSON требует отдельной новой data migration. Reference и общий recipe
сохраняются; уже начатые серии и поставленные в очередь Card используют snapshots.

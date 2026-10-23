# Шаблон «Летний минимализм»

Reference для категории **«Одежда и обувь»** (`cat_fashion`). Шаблон `tpl_fashion_summer`, порядок `80`. Общая миграция `078-fashion-footwear-templates` добавляет asset, шаблон и recipe; `079-fashion-footwear-variants` — десять сценариев.

![Превью шаблона](e57a8c89-f20e-5842-80ff-251396ea6062.webp)

| Параметр | Значение |
| --- | --- |
| Template ID | `tpl_fashion_summer` |
| Asset ID | `asset_tpl_fashion_summer_ref` |
| Файл | `assets/templates/tpl_fashion_summer/e57a8c89-f20e-5842-80ff-251396ea6062.webp` |
| Ключ объекта | `templates/tpl_fashion_summer/e57a8c89-f20e-5842-80ff-251396ea6062.webp` |
| Content-Type | `image/webp` |
| Размер | `384×512`, `22516` байт (около `22 КиБ`) |
| Recipe | [recipe.json](recipe.json) |
| Сценарии | [card-variants.json](card-variants.json) |
| Миграции | [078](../../../src/main/resources/db/changelog/changes/078-fashion-footwear-templates.yaml), [079](../../../src/main/resources/db/changelog/changes/079-fashion-footwear-variants.yaml) |

Предоставленный JPEG `c3f55ee3a7d97bafc322e945bcedf8ff.jpg` детерминированно уменьшен Pillow с фильтром Lanczos до `384×512` и перекодирован в WebP (`quality=90`, `method=6`). Исходный макет, демонстрационный товар, типографика и текст сохранены; новое изображение не генерировалось. Тяжёлый исходник в каталоге не хранится.

Демонстрационный бренд, размерный ряд и утверждения о нескольжении и лёгком мытье не являются данными нового товара. При генерации наложенные надписи заменяются подтверждёнными сведениями пользователя, блоки без данных опускаются. Ноги, руки и модель используются только при наличии на исходном фото; иначе показывается сам товар. Для крупных планов предусмотрен запасной общий вид при недостаточной детализации.

## Сценарии серии

| Позиция | Сценарий | Кадрирование |
| --- | --- | --- |
| 1 | Обложка | `full_product` |
| 2 | Один важный факт | `full_product` |
| 3 | Коротко о товаре | `full_product` |
| 4 | Видимая фактура | `detail` |
| 5 | Деталь конструкции | `detail` |
| 6 | Силуэт | `full_product` |
| 7 | Цвет и отделка | `full_product` |
| 8 | Принт или маркировка | `detail` |
| 9 | Размеры и параметры | `full_product` |
| 10 | Итоговая карточка | `full_product` |

## Подключение и проверка

Backend-деплой синхронизирует WebP с MinIO по точному ключу до применения Liquibase. Ручная загрузка, применение миграций и деплой при подготовке не выполнялись. В ключ не входят имя bucket и префикс `assets/`.

```sh
python3 scripts/seed-card-variants.py --check --source assets/templates/tpl_fashion_summer/card-variants.json
```

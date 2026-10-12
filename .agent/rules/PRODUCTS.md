# Исходный товар, загрузка и доставка assets

Читай при изменении products, исходных фото, MinIO compensation или подписанных ссылок.
Identity — [TRIAL.md](TRIAL.md), следующий этап — [PRODUCT_ANALYSIS.md](PRODUCT_ANALYSIS.md).

## 1. Product upload

- POST `/api/v1/products` принимает multipart image и необязательное name;
  GET `/api/v1/products/{id}` доступен владельцу. Bearer перед trial, cookie upload требует
  разрешённый Origin. Чужой/отсутствующий Product — 404 PRODUCT_NOT_FOUND.
- Product создаётся в uploaded, анализ автоматически не запускается.
  Asset purpose — source_image; сервер генерирует key products/{UUID}.
- ImageValidator проверяет сигнатуру JPEG/PNG/WEBP, размеры и декодирование независимо
  от присланного MIME. WEBP — TwelveMonkeys ImageIO. Оригинальные bytes не преобразуются.
- Лимит image — 10 MiB, multipart — 11 MiB; servlet parser пишет на диск
  (file-size-threshold=0). Сервис затем читает ограниченный image в bytes.
  Изображения более 25 млн пикселей отклоняются до полного декодирования.
- Rate limit — 30 загрузок в час на transport IP. Имя очищается/проверяется,
  но никогда не становится готовым object key.

## 2. Транзакции и компенсация

ProductService использует ProductStore/ProductStorage; реализации — JdbcProductStore/MinioProductStorage.
Код — `src/main/java/com/tovarika/tech/products/`.

1. До записи MinIO отдельной транзакцией создаётся durable product_uploads reservation.
2. Рабочая транзакция блокирует reservation, сохраняет оригинал в MinIO и Asset/Product
   в БД, затем удаляет reservation. Trial owner перепроверяется под блокировкой,
   чтобы conversion не оставила ресурс на старом владельце.
3. При ошибке удаляется объект, ещё не принадлежащий Asset; при неудачной компенсации
   reservation остаётся для повторной очистки.
4. Scheduled cleanup забирает reservations старше часа через SKIP LOCKED.
   Активная загрузка защищена row lock; существующий Asset не удаляется.

Сохраняй durable recovery при изменении storage; обычный catch/delete его не заменяет.
У Card другая, best-effort компенсация — [CARD_GENERATION.md](CARD_GENERATION.md).

## 3. Подписанная доставка файлов

- AssetLinks выдаёт HMAC capability на `/media/assets/{assetId}` сроком 15 минут.
  API не раскрывает storage key/адрес MinIO; новый GET создаёт свежий URL.
- ASSET_PUBLIC_BASE_URL задаёт внешний origin backend. Proxy маршрутизирует
  /media/assets/* вместе с /api/v1/*; query signature не записывается в proxy logs.
- Подпись использует purpose-prefix asset-download с настроенным signing secret;
  смена secret отзывает старые ссылки. Доступ разрешён обладателю URL до expiry.
- Binary — MinIO, metadata — assets. Signed URL — не отдельный ownership:
  защищённые API проверяют владельца связанного Product/Project/Card.

Проверки: `./gradlew test --tests com.tovarika.tech.auth.ProductUploadIntegrationTest`
на PostgreSQL/Testcontainers; authorization — [SECURITY.md](SECURITY.md).

# Редактирование готового bitmap, Undo и Redo

Контракт: `../tovarika-api-contract/openapi/schemas/image-edits.yaml`,
`paths/cards.yaml`; описание сценария — `../tovarika-api-contract/docs/image-editing.md`.

- `CardsController.editCardImage` принимает discriminator `entire`, `region`,
  `remove_background`, `resize`, `erase`. `ImageEditBodyAdvice` защищает exact JSON shape,
  application/domain повторно проверяют семантику. Generated code не редактируется.
- `CardImageEditingService` зависит от `ImageEditingStore`; PostgreSQL adapter использует
  существующие owner/project locks. Перед busy/version проверкой выполняется replay.
  `baseVersionId` и `expectedImageRevision` обязательны; revision защищает ABA после Undo.
- Миграция 014 создаёт `card_image_versions`, pointer/revision Card, Job payload/result,
  undo receipts и общий индекс активной image operation проекта. Готовые legacy Card
  получают original version. Существующие миграции неизменяемы.
- Каждый успешный edit создаёт immutable Asset и версию с predecessor. Card.image,
  current version, ratio/revision и Job.result переключаются атомарно. Ошибка сохраняет
  прежнее изображение и ready Card; диагноз читается из Job, не Card.error.
- Undo синхронно возвращает предыдущий Asset без ИИ, Job и пересчёта картинки. Увеличивает
  revision, записывает receipt. Replay возвращает текущую Card без второго шага.
  Undo запрещён при active project operation и без predecessor; отменённая версия попадает
  в серверный стек Redo. Redo снимает верхнюю версию, восстанавливает Asset/ratio и увеличивает
  revision без ИИ/Job. Replay Undo/Redo не делает второй шаг, command входит в digest.
  Миграция 015 добавляет card_image_redo_stack и переименовывает receipts в
  card_image_history_receipts, сохраняя старые receipts. После успешного нового edit стек
  очищается, immutable версии/Assets остаются. Failed/stale edit сохраняет стек.
  canUndo/canRedo=false при active project operation. Endpoint списка истории пока нет.
- `CardImageEditingWorker` использует существующие Jobs, scheduler и ProductStorage.
  Claim/complete/fail — короткие транзакции; AI/storage обработка вне транзакции.
  Lease 300 секунд, attempt fencing, максимум 3 попытки recovery. Commit проверяет
  base/revision/lastImageJobId, устаревший результат не публикуется. Не добавляй Project
  lock из update project_jobs: это нарушит существующий порядок блокировок.
- `CardImageProcessor` строит alpha mask по normalized rectangle floor/ceil и копирует
  только выделенные RGBA pixels из AI-result. Маска провайдера — guidance, не гарантия.
- erase принимает mask.kind=brush: 1–64 штриха, radius .002–.25 относительно меньшей
  стороны bitmap, 1–1024 normalized points на штрих, максимум 8192 суммарно. JSON body
  ограничен 1 MiB. BrushMaskRasterizer строит binary alpha mask round caps/joins без
  antialiasing; пустое pixel выделение отклоняется до ИИ. Prompt удаления/восстановления
  фона формирует CardImageProcessor; decoded RGBA вне маски копируются из исходника.
  Использует существующие Jobs/lease/version guards и Undo/Redo; новая схема БД не нужна.
  Локальная история штрихов не вызывает /undo или /redo. Legacy uploaded masks всё ещё
  недоступны: erase не принимает AssetId/URL/PNG от клиента.
- remove_background требует явного foreground и возвращает PNG с actual alpha; отсутствие
  прозрачности — failure. Resize требует mode: fit_pad/crop без ИИ, outpaint только поля.
  Pixel presets совпадают с генерацией. Не вводи неявные продуктовые defaults.
- `editCardRegion` — legacy facade для rectangle, guard-пара обязательна только вместе;
  без неё pin current при accept. Uploaded masks возвращают IMAGE_EDIT_UNAVAILABLE.
- Счётчик/позиция Card, generation prompt/snapshots и первое превью Project не меняются.
  Существующие signed AssetLinks доставляют текущий Asset. URL не является version id.
- Bearer/trial: WorkspaceIdentityResolver и project ownership обязательны. Новые POST
  внесены в SecurityConfiguration и строгую card Origin policy; permitAll на уровне
  filter chain разрешает trial identity, а не анонимное изменение ресурса.
- Accounting/quota, exports, arbitrary regeneration, PATCH Card, durable orphan cleanup
  и cross-identity replay после trial conversion не добавлены этим flow.

Проверки: CardImageProcessorTest (pixels/mask/alpha/resize),
CardImageEditingIntegrationTest (PostgreSQL, workflow/Undo/idempotency/concurrency/
stale attempts/ownership/trial Origin), OpenAiResponsesClientTest (mock HTTP).
Обязательны migrations checker, `./gradlew test`, `./gradlew classes` и `bootJar -x test`.
Реальные запросы к ИИ требуют отдельной пользовательской авторизации.

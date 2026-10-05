# AI-граница генерации изображений

Читай при изменении images, входных фото, registry или размеров результата.
Secrets/startup/deployment — [OPENAI.md](OPENAI.md), worker — [CARD_GENERATION.md](CARD_GENERATION.md).

## 1. Интерфейсы и адаптеры

- ImageGenerator.generate(GenerationRequest) получает prompt, список фото и целевой размер.
  GenerationRequest.InputImage содержит bytes/mediaType/role; bytes копируются.
- ImageEditor — отдельная необязательная capability; generation-only adapter не обязан поддерживать edit.
- ImageGeneratorFactory получает `Map<String, ImageGenerator>` из Spring. Bean name — registry key;
  новый adapter регистрируется bean, без switch по провайдерам в фабрике.
- ImageGenerationService зависит от интерфейсов/фабрики. ChatGPTAdapter имеет key chatgpt,
  transport скрыт за OpenAiClient. Анализ использует отдельный AnalysisProvider;
  не добавляй его в ImageGenerator.

## 2. Вход и транспорт

- ChatGPTAdapter требует 1–2 фото JPEG/PNG/WEBP, каждое непустое и до 10 MiB;
  prompt непустой, до 8000 символов. Первое фото — Product, второе — reference.
  Порядок важен для CardPromptCompiler.
- Adapter допускает положительные стороны до 4096, не более 8 388 608 пикселей;
  live transport дополнительно ограничивает каждую сторону 3840.
- Stub не вызывает сеть, выдаёт тестовый PNG; проверяет flow, не качество генерации.
- Live OpenAiResponsesClient.generate вызывает /responses: основная модель — OPENAI_VISION_MODEL,
  image tool model — OPENAI_IMAGE_MODEL; фото — Base64 data URL, detail=high, store=false.
  Tool image_generation использует action=edit для создания карточки по входным фото;
  это не реализация публичного region edit.
- Transport принимает completed response с Base64 image_generation_call.result,
  декодирует через ImageIO и нормализует в PNG. Ошибка не содержит provider body.
- Отдельный OpenAiClient.edit в live бросает UnsupportedOperationException.
  Не обещай edit только потому, что adapter реализует ImageEditor.

## 3. Размеры и нормализация

| Aspect ratio | Итоговые пиксели | Размер image tool |
| --- | --- | --- |
| 1:1 | 1024 × 1024 | 1024x1024 |
| 3:4 | 1152 × 1536 | 1024x1536 |
| 4:5 | 1024 × 1280 | 1024x1536 |
| 16:9 | 1536 × 864 | 1536x1024 |

Tool size выбирается по ориентации. Live-result масштабируется bicubic до покрытия
целевого размера, обрезается по центру и сохраняется в RGB PNG. Края могут обрезаться;
проверяй текст/товар на четырёх ratio при изменении reference/prompt.

Нормализация использует ImageIO, а не products/ImageValidator; это не полная проверка
лимитов пользовательской загрузки. HTTP boundary проверяет OpenAiResponsesClientTest по [OPENAI.md](OPENAI.md).

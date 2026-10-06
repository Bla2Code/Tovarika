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

| Aspect ratio | Итоговые пиксели | Размер image tool для GPT Image 2/2.5 | Fallback для остальных моделей |
| --- | --- | --- | --- |
| 1:1 | 1024 × 1024 | 1024x1024 | 1024x1024 |
| 3:4 | 1152 × 1536 | 1152x1536 | 1024x1536 |
| 4:5 | 1024 × 1280 | 1024x1280 | 1024x1536 |
| 16:9 | 1536 × 864 | 1536x864 | 1536x1024 |

`gpt-image-2`, `gpt-image-2.5-sunburst`, `gpt-image-2.5-flare` и их датированные snapshots
получают точный размер, если стороны кратны 16, ratio в пределах 1:3–3:1,
а число пикселей — от 655 360 до 8 294 400. Для остальных моделей или размеров
используется стандартный tool size по ориентации. Ограничения:
[официальная документация OpenAI](https://developers.openai.com/api/docs/guides/image-generation).

Transport добавляет `instructions` с размером tool canvas и итоговыми пикселями,
адаптацией layout, выравниванием, полями минимум 5% для товара и текста,
переносом длинных надписей и уменьшением шрифта без обрезки.
Live-result целиком вписывается bicubic с сохранением пропорций в целевой canvas,
центрируется и сохраняется в RGB PNG. При несовпадении ratio свободное место
и прозрачность заполняются белым. Crop и растягивание запрещены.

Нормализация гарантирует размер и сохранение содержимого ответа, но не восстанавливает
текст/товар, уже обрезанные моделью. Проверяй текст/товар на четырёх ratio при изменении
reference/prompt. HTTP boundary tests проверяют точные размеры запроса, fallback,
сохранение четырёх углов, центрирование и прозрачность.

Нормализация использует ImageIO, а не products/ImageValidator; это не полная проверка
лимитов пользовательской загрузки. HTTP boundary проверяет OpenAiResponsesClientTest по [OPENAI.md](OPENAI.md).

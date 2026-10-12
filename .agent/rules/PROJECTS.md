# Проекты: настройки, владение и блокировки

Читай при изменении project или взаимодействия project jobs с PATCH/DELETE.
Запуск job — [CARD_GENERATION.md](CARD_GENERATION.md), identity — [TRIAL.md](TRIAL.md).

## 1. API и данные

- ProjectsController/ProjectService в `src/main/java/com/tovarika/tech/project/`
  реализуют создание, чтение, список, PATCH/DELETE. Владение наследуется от Product.
- Проект создаётся для своего Product даже до готовности анализа. Defaults:
  name из Product, defaultAspectRatio=3:4; максимум один текущий проект на Product.
- Операции отдельного проекта принимают Bearer/trial; список требует Bearer.
  Чужой/отсутствующий проект одинаково даёт 404 PROJECT_NOT_FOUND.
- PATCH частично обновляет name/selectedTemplateId/defaultAspectRatio. Пустой объект,
  неизвестные поля, null или нарушение схемы — 400 VALIDATION_ERROR.
  Шаблон должен существовать и быть available, иначе 422 TEMPLATE_NOT_FOUND.
  Последующая недоступность не стирает сохранённый выбор.
- До готовой Card preview использует source Asset; после первой успешной генерации
  preview_asset_id заполняется результатом, если ещё не задан.

## 2. Сериализация с jobs

- PATCH/DELETE блокируют Project/Product в application-транзакции PostgreSQL.
  Любая project job queued/processing запрещает мутацию: 409 CARD_BUSY, без отмены job.
- Analysis jobs принадлежат Product и не блокируют PATCH/DELETE проекта.
- DB trigger project_jobs_lock_project берёт ту же Project row lock перед INSERT job.
  Это сериализует создание job с мутацией, включая writers вне application service.
- Job не меняет project_id и не возвращается из completed/failed в активное состояние.
  Retry создаёт новую запись; ограничения MVP Card — отдельное правило.
- UPDATE статуса job не берёт Project lock: не добавляй обратный порядок блокировок
  при каскадном удалении. Конкурентное завершение может дать консервативный 409,
  после которого клиент повторяет PATCH/DELETE.

## 3. Удаление и сохранённые ресурсы

- DELETE возвращает 204 и атомарно удаляет Project/Card/завершённые project jobs
  через ON DELETE CASCADE; повторное удаление — 404.
- Product, его владелец, ProductAnalysis, metadata assets и binary сохраняются.
  Можно создать новый проект; storage garbage collection — отдельная задача.
- Trial mutation требует разрешённый Origin. Сохраняй одинаковую ошибку для чужого
  и отсутствующего проекта и общую identity/security policy.

Проверки: `./gradlew test --tests com.tovarika.tech.project.ProjectsContractIntegrationTest`
и `./gradlew test --tests com.tovarika.tech.project.ProjectMutationsIntegrationTest`
с настоящими JWT/PostgreSQL 18. H2 не проверяет locking/concurrency.

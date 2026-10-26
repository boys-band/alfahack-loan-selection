# Схемы БД сервисов

Начальные миграции PostgreSQL 16 для шести отдельных БД. Каждая миграция самодостаточна: таблицы, локальные FK, CHECK, UNIQUE, индексы и триггеры. Межсервисные ссылки представлены UUID и версиями; межбазовых FK нет. HTTP-модели заданы в [контрактах v2](../contracts/README.md).

**Визуальная ER-модель:** [общая карта и шесть подробных схем](diagrams/README.md). Доступны SVG для увеличения, PNG и исходники Graphviz; схемы получены из примененных SQL-миграций.

| Сервис | База / пользователь приложения | Миграция |
| --- | --- | --- |
| applications | `alfa_applications` / `applications_app` | [001_initial.sql](../services/applications/migrations/001_initial.sql) |
| catalog | `alfa_catalog` / `catalog_app` | [001_initial.sql](../services/catalog/migrations/001_initial.sql) |
| verification | `alfa_verification` / `verification_app` | [001_initial.sql](../services/verification/migrations/001_initial.sql) |
| decision | `alfa_decision` / `decision_app` | [001_initial.sql](../services/decision/migrations/001_initial.sql) |
| agreements | `alfa_agreements` / `agreements_app` | [001_initial.sql](../services/agreements/migrations/001_initial.sql) |
| disbursement | `alfa_disbursement` / `disbursement_app` | [001_initial.sql](../services/disbursement/migrations/001_initial.sql) |

## Запуск и проверка

Из корня репозитория:

```bash
docker compose -p alfa-db -f database/compose.yaml up -d --wait
```

Профиль запускает БД с постоянным volume и применяет миграции/демоданные при первом старте пустого volume. Порты на хост не публикуются. Полный запуск backend/frontend будет добавлен при реализации сервисов.

Просмотр таблиц конкретного сервиса:

```bash
docker compose -p alfa-db -f database/compose.yaml exec postgres psql -U applications_app -d alfa_applications -c '\dt'
```

Автономная проверка всех схем, положительных/отрицательных операций и прав:

```bash
python3 scripts/validate_database.py
```

Проверка требует только Python 3 и Docker. Она создает отдельный контейнер со случайным именем, без сети и с БД в tmpfs, затем удаляет его. Данные запущенного проекта не используются. Проверяемый образ зафиксирован digest в compose и проверяющем скрипте; фактически использован PostgreSQL 16.15.

`bootstrap.sh` создает шесть владельцев `*_owner` без LOGIN и шесть пользователей `*_app` с доступом только к собственной БД. Пользователь приложения имеет DML, но не владеет таблицами, не меняет схему и не отключает триггеры. `schema_migrations` хранит версию, SHA-256 SQL-файла и время сидирования; runtime-пользователь не изменяет эту таблицу.

Параметры паролей: `POSTGRES_PASSWORD` и `<SERVICE>_DB_PASSWORD`, например `APPLICATIONS_DB_PASSWORD`. Compose содержит отдельные демопароли `demo-<service>-only`; они переопределяются окружением. Имя узла внутри сети этого compose — `postgres`, порт 5432. Например: `postgresql://applications_app:demo-applications-only@postgres:5432/alfa_applications`.

## Формат хранения

- Имена SQL — snake_case, API — camelCase. UUID создаются сервисом; время — timestamptz, timezone БД UTC.
- Деньги — bigint в рублях, ставки/отношения — целые базисные пункты. Диапазоны соответствуют схемам v2.
- Основные ключи, состояния, версии, суммы и индексируемые поля типизированы. Внешние снимки, составные факты и вход HTTP хранятся в jsonb.
- Сервис валидирует JSON Schema до записи. SQL дополнительно проверяет ключевые типы, версии, связи и инварианты; он не заменяет полный валидатор HTTP-контракта.
- Неизменяемые снимки и результаты защищены от UPDATE/DELETE. Для повторного создания использовать `INSERT ... ON CONFLICT DO NOTHING`, затем прочитать запись и сравнить исходный запрос; неизменяемый результат не обновляется через UPSERT.
- Каждая миграция применяется один раз к пустой БД в транзакции. Дальнейшие изменения оформляются следующей миграцией. Повторный старт непустого volume не применяет новые файлы автоматически.

Синтетические seed-файлы находятся в [seeds](seeds), соответствуют `contracts/examples/seed.json` и допускают повторный запуск: компании/принадлежность, две версии продуктов по разным productId, три мок-источника и демосчета. Заявки, решения и выдачи создаются через бизнес-процесс. Для decision и agreements seed пока пустой.

## Applications

```mermaid
erDiagram
    COMPANIES ||--o{ CUSTOMER_COMPANIES : company_id
    CUSTOMER_COMPANIES ||--o{ APPLICATIONS : customer_company
    APPLICATIONS ||--o{ APPLICATION_DATA_VERSIONS : snapshots
    APPLICATION_DATA_VERSIONS ||--o{ INFORMATION_REQUESTS : requests
    APPLICATION_DATA_VERSIONS ||--o{ OFFER_ACCEPTANCES : accepted_terms
    APPLICATION_DATA_VERSIONS ||--o{ PAYOUT_INSTRUCTIONS : payout_versions
    APPLICATION_DATA_VERSIONS ||--o{ WORKFLOW_JOBS : processing
    WORKFLOW_JOBS ||--o{ WORKFLOW_ATTEMPTS : attempts
    APPLICATIONS ||--o{ APPLICATION_TIMELINE : history
```

`applications.input_data` — текущие входные данные: компания/продукт, параметры, финансы, дополнения, согласия и аттестация сотрудника. До отправки допускаются null-поля. `company_id`, сумма, срок и финансовые показатели — generated columns: сервис пишет input_data, не эти столбцы.

После отправки input_data совпадает с неизменяемым `application_data_versions.payload`. dataVersion=0 существует только в черновике/его отмене; первая отправка создает версию 1. FK на текущую версию отложен до конца транзакции, чтобы агрегат и снимок можно было записать вместе. Изменение отправленных данных требует следующей версии и сброса прежних результатов.

Триггер проверяет переходы status/reviewStage по бизнес-модели, вход в рассмотрение с ENRICHMENT, version+1 и запрет открытия конечного состояния. Отложенный триггер требует событие истории для каждого сохраненного изменения агрегата с теми же version, dataVersion, status/reviewStage. Одной смены вложенного статуса тоже достаточно для обязательной новой записи истории.

Текущие указатели на дозапрос, принятие, поручение и job имеют составные FK с applicationId/dataVersion. В одной заявке допускается один открытый дозапрос, одно активное принятие и одно активное бизнес-задание. Очистка старых ресурсов использует jobs с is_cleanup=true и может идти отдельно.

`availableActions`, nextActionHint и ETag вычисляются сервисом. Срок команды, текущие полномочия, актуальность предложения и ответы соседних API проверяет applications в транзакции с блокировкой агрегата. SQL проверяет сохраненные связи/состояния, но не выполняет межсервисных HTTP-проверок.

Для выполнения задания: выбрать PENDING через `FOR UPDATE SKIP LOCKED`, записать RUNNING/lease_token/lease_until и увеличить счетчики. Выполнять HTTP вне транзакции. Завершать с `WHERE id = ... AND state = 'RUNNING' AND lease_token = ...`, затем под блокировкой агрегата проверять dataVersion, status/reviewStage, current_job_id и payoutVersion. Поздний результат не применяется.

Истекший RUNNING lease возвращается в PENDING. Повтор PAUSED сбрасывает attempt_count, сохраняет total_attempt_count, запрос и ключ. Исход каждого запуска записывается в workflow_attempts; завершенная попытка неизменяема. Перед созданием следующего бизнес-job завершить предыдущий в той же транзакции, чтобы соблюсти частичный UNIQUE.

До SIGNED реквизиты DTO берутся из профиля компании; в SIGNED создается первая payout_instruction. Правка счета создает следующую запись payoutVersion. Единственное обновление поручения — однократная привязка operationId при переходе в DISBURSING. После этого реквизиты неизменяемы.

## Catalog

```mermaid
erDiagram
    PRODUCT_VERSIONS ||--o| CURRENT_PRODUCTS : current_version
    PRODUCT_VERSIONS {
        text product_id PK
        text version PK
        bigint min_amount_rub
        bigint max_amount_rub
        int annual_rate_bps
    }
```

product_versions неизменяема. current_products хранит текущую версию и active; FK запрещает указатель на отсутствующую версию. Ограничения суммы/срока проверяют также порядок min/max. Поля API берутся из конкретной строки версии; старые версии остаются доступны. Изменение ставки оформляется новой версией продукта.

## Verification

```mermaid
erDiagram
    SOURCE_FIXTURES {
        uuid company_id PK
        text source PK
        jsonb payload
    }
    VERIFICATION_RESULTS {
        uuid id PK
        uuid application_id UK
        bigint data_version UK
        text outcome
        jsonb raw_facts
        jsonb facts
    }
```

Уникальность результата — составная `(application_id, data_version)`. Связи результатов с fixture через FK нет: результат сохраняет исходные факты даже при последующем изменении моков. COMPLETE требует confirmed=true и facts; NEEDS_INFO требует непустого перечня сведений. MANUAL_REVIEW и NEEDS_INFO не подтверждают факты. Сохранены точный запрос и его хеш для сравнения повторов.

## Decision

```mermaid
erDiagram
    ASSESSMENTS ||--|{ EVALUATED_TERMS : candidates
    ASSESSMENTS o|--o{ DECISIONS : assessment
    EVALUATED_TERMS o|--o{ DECISIONS : selected_terms
    DECISIONS ||--o| OFFERS : approved_offer
    ASSESSMENTS o|--o{ MANUAL_REVIEW_CASES : scored_case
    MANUAL_REVIEW_CASES ||--o| MANUAL_REVIEW_ACTIONS : resolution
    MANUAL_REVIEW_ACTIONS o|--o| DECISIONS : manual_result
```

Assessment ссылается на подтвержденный снимок verification текущей версии данных. Все кандидаты привязаны к assessment; FK не позволяет выбрать условия чужой оценки. Сумма факторов и grade согласуются с risk_score. Один AUTO и один MANUAL на версию хранятся отдельно. Выбранный risk и поля предложения собираются join с evaluated_terms; их копии не расходятся с первичным расчетом.

Для APPROVED обязателен offer, а offer ссылается именно на выбранного кандидата и решение. Две стороны связи отложены до COMMIT: решение и предложение создаются в одной транзакции. Триггер одобрения требует eligible=true и порог 600 для AUTO / 500 для MANUAL.

Дело без assessment не разрешает APPROVE. Ручное действие блокирует OPEN-дело, сохраняет сотрудника/причину и закрывает его как RESOLVED в той же транзакции. Одно дело имеет одно разрешающее действие; дозапрос/правка переходят к новой версии данных и новому делу при необходимости. Ручное решение связано с действием отложенными FK и проверяет соответствие сотрудника, причины, заявки/версии и исхода.

Правила расчета, набор стоп-факторов и достоверность снимка внешней проверки остаются в decision. БД проверяет сохраненную оценку, связи и допустимость выбора кандидата.

## Agreements

```mermaid
erDiagram
    AGREEMENTS ||--o| SIGNATURES : signed_document
    AGREEMENTS {
        uuid id PK
        uuid application_id UK
        bigint data_version UK
        text status
        bigint document_version
        text document_sha256
        timestamptz sign_by
    }
```

Уникальность договора — `(application_id, data_version)`. Текст/условия неизменяемы, SHA-256 точных UTF-8 байт вычисляется BEFORE INSERT в БД. Подпись связывается с конкретными клиентом, documentVersion и SHA-256 через составной FK.

Подписание одной транзакцией: заблокировать READY-договор; INSERT signatures; UPDATE agreements в SIGNED с signature_id; сохранить идемпотентный ответ. Взаимные отложенные FK запрещают COMMIT подписи без SIGNED и SIGNED без подписи. Триггер вставки подписи сам блокирует договор, проверяет READY и signBy; обновление статуса повторно проверяет срок.

Close меняет только READY → CLOSED с причиной и временем. Sign и close конкурируют за одну строку. После SIGNED/CLOSED запись неизменяема; повторную команду сервис обслуживает чтением прежнего результата. Если создание документа и запись ответа не завершились, вся транзакция откатывается.

## Disbursement

```mermaid
erDiagram
    PAYOUT_CHECKS ||--o| DISBURSEMENTS : ready_instruction
    DISBURSEMENTS ||--|| CREDIT_ENTRIES : atomic_credit
    ACCOUNT_FIXTURES {
        uuid id PK
        uuid company_id
        text status
    }
```

AccountFixture проверяется для READY через локальный триггер. Для NEEDS_CORRECTION сохраняются и неизвестные/чужие реквизиты, поэтому FK от всех payout_checks к account_fixtures отсутствует. Проверки поручения уникальны по `(application_id, data_version, payout_version)`.

Выдача имеет составной FK на READY-проверку с теми же компанией, счетом, договором и версиями. Снимок договора обязан быть SIGNED и соответствовать сумме/компании/заявке. Получение и проверку подлинного договора по HTTP выполняет сервис до локальной транзакции.

Отдельные UNIQUE по application_id, agreement_id, operation_id запрещают вторую выдачу даже с новым ключом идемпотентности. Двусторонний отложенный FK связывает disbursement с ровно одной credit_entry и проверяет счет/сумму/валюту. Создать обе записи и успешный ответ повтора нужно одной транзакцией; без записи зачисления COMMIT невозможен.

GET by-operation читает UNIQUE operation_id. При неизвестном исходе нельзя выделять новый operationId: оркестратор разыскивает прежний и повторяет тот же запрос. Сохраненные зачисления и выдачи защищены от изменения/удаления.

## Общая идемпотентность

В пяти изменяющих сервисах есть одинаковая по форме локальная idempotency_records; catalog ее не использует. PK: caller/method/path/key. Хранятся только успешные зафиксированные ответы, request_hash и заголовки/JSON. В таблицу не записываются токены.

Сначала проверить ключ и совпадение отпечатка, выполнить локальное бизнес-изменение, записать ответ и сделать COMMIT. Гонка вставки ключа откатывает проигравшую транзакцию; затем читается сохраненный ответ. Для одновременных повторов сервис также может использовать транзакционную advisory-блокировку ключа. Таймаут ожидания отображается в IDEMPOTENCY_IN_PROGRESS по контракту.

Успешные записи не обновляются. DELETE разрешен только после expires_at; null означает хранение без автоматического удаления для внутренних операций. Бизнес-уникальность и request_hash у самих ресурсов остаются после очистки публичного ключа. Политику хранения для архива всей заявки определяет последующая реализация.

# Applications

Java-сервис заявок и оркестратор кредитного процесса v2. Реализованы все 23 операции [OpenAPI](../../contracts/openapi/applications.yaml): компании и счета, черновики, неизменяемые версии данных, команды клиента, очередь и действия сотрудника, история, договор и health. Только applications меняет глобальное состояние заявки.

Стек: Java 21 / JVM 21, Spring Boot 3.5.16, Spring JDBC, HikariCP 6.3.3, PostgreSQL JDBC 42.7.8, Jackson и networknt JSON Schema Validator. Gradle Wrapper закрепляет Gradle 8.14.3; JDK/JRE 21 в Dockerfile закреплены digest. Версии транзитивных зависимостей закреплены BOM и [gradle.lockfile](gradle.lockfile), лицензии — в [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).

## Устройство

- `Api`: HTTP, демотокены, роли, строгая JSON-валидация, `X-Request-Id`, Problem.
- `Service`: публичные команды, доступ, ETag, идемпотентность, страницы списков и представления.
- `Store`: транзакционное хранение агрегата, снимков, истории, заданий и поручений.
- `Worker`: первичная проверка, HTTP-этапы, таймеры, lease, повторы и восстановление.
- `Dependencies`: HTTP-клиент пяти соседей; connect timeout 2 с, полный timeout одного вызова 10 с.

API и worker — отдельные процессы одного JAR/образа с общей собственной БД. Worker можно запускать в нескольких экземплярах. PostgreSQL выбирает задания через `FOR UPDATE SKIP LOCKED`; lease длится 45 с. Перед HTTP уже сохранены точное тело, ключ, requestId, ожидаемые статус/этап/версии и ID задания. Сеть выполняется вне транзакции. Результат применяется только действующим владельцем lease и к актуальному заданию.

Первичная проверка отдельно сохраняет `VALIDATION`, затем `IN_REVIEW/ENRICHMENT` и `SCORING`. Проверки источников, скоринг, ручные решения, документ, подпись и зачисление выполняют соседи через HTTP. При смене данных сохраняется новая версия и сбрасываются текущие результаты; прежние ответы остаются в append-only `workflow_results`. Устаревшие дела и поздно созданные договоры закрываются долговечными cleanup-заданиями.

Создание заявки, сохранение черновика и первая отправка требуют текущую активную версию catalog. PATCH проверяет данные по актуальным ограничениям и при успехе обновляет снимок программы; ошибка не меняет черновик. Submit старой версии возвращает `409 PRODUCT_NOT_CURRENT`, предлагая проверить новые условия и сохранить черновик. Уже отправленные заявки, уточнение сведений и правка сотрудника используют точную сохраненную версию через `?version=`. Исторические снимки и успешные идемпотентные ответы остаются неизменными.

В подписи/отмене/истечении источник истины — состояние agreements. Подтвержденная подпись продолжает процесс, закрытие фиксирует фактическую причину. Прежний срок предложения после принятия не ограничивает подпись. В `SIGNED` можно исправить поручение без изменения договора. Перед `DISBURSING` закрепляется постоянный `operationId`; каждая попытка выдачи начинается с GET by-operation, включая восстановление после потери ответа.

## Конфигурация

[.env.example](.env.example) содержит только демозначения. Настройки передаются окружением:

| Переменная | Значение по умолчанию |
| --- | --- |
| `DATABASE_URL` | `postgresql://applications_app:demo-applications-only@localhost:5432/alfa_applications` |
| `PORT` | `8001` |
| `INTERNAL_API_TOKEN` | `demo-internal-only` |
| `DEMO_CUSTOMER_TOKEN` / `DEMO_CUSTOMER_ID` | `demo-customer-only` / `00000000-0000-4000-8000-000000000001` |
| `DEMO_STAFF_TOKEN` / `DEMO_STAFF_ID` | `demo-staff-only` / `00000000-0000-4000-8000-000000000002` |
| `CATALOG_URL` | `http://catalog:8002` |
| `VERIFICATION_URL` | `http://verification:8003` |
| `DECISION_URL` | `http://decision:8004` |
| `AGREEMENTS_URL` | `http://agreements:8005` |
| `DISBURSEMENT_URL` | `http://disbursement:8006` |

`DATABASE_URL` — PostgreSQL URI с логином и паролем; runtime допускает только `applications_app` и `alfa_applications`. API слушает `0.0.0.0:$PORT`. Все три токена должны различаться. Для запуска JAR на хосте задайте доступные адреса соседей вместо Docker-имен.

Liveness проверяет процесс. Readiness проверяет собственные таблицы, миграцию истории результатов, runtime-роль и пять ожидаемых seed-компаний. Соседи и закрытая для runtime `schema_migrations` в readiness не опрашиваются.

## БД и миграции

Команды ниже выполняются из корня репозитория. Общий DB-профиль создает шесть БД, NOLOGIN-владельцев, DML-роли и применяет принятую `001_initial.sql` с seed:

```bash
docker compose -p alfa-db -f database/compose.yaml up -d --wait
```

Дополнительную миграцию applications применяет администратор через `SET LOCAL ROLE applications_owner`. Runtime миграции не выполняет. Для DB-профиля без опубликованного порта используйте клиент в той же сети:

```bash
docker run --rm --network alfa-db_database \
  -v "$PWD/services/applications:/service:ro" \
  -e MIGRATION_DATABASE_URL=postgresql://postgres:demo-admin-only@postgres:5432/alfa_applications \
  postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea \
  bash /service/scripts/migrate.sh
```

При доступной извне PostgreSQL и установленном `psql`:

```bash
MIGRATION_DATABASE_URL=postgresql://postgres:demo-admin-only@localhost:5432/alfa_applications \
  services/applications/scripts/migrate.sh
```

Скрипт атомарно применяет `002_result_history.sql`, записывает checksum в `schema_migrations`, сериализует миграции advisory lock и безопасно повторяется. Несовпавший checksum останавливает запуск. Владелец и runtime сохраняют разные полномочия; триггеры принятой базы работают без обходов.

## Сборка и запуск

Нужен JDK 21 (`JAVA_HOME` указывает на JDK 21). Makefile использует Gradle Wrapper 8.14.3; отдельная установка Gradle не требуется. Версии зависимостей закреплены в `gradle.lockfile`. `build` проверяет стиль и выполняет unit-тесты; интеграционные тесты запускаются отдельно.

```bash
make -C services/applications build
cp services/applications/.env.example services/applications/.env
set -a
source services/applications/.env
set +a
java -jar services/applications/build/libs/applications.jar
```

В другом терминале с тем же окружением:

```bash
java -jar services/applications/build/libs/applications.jar worker
```

Сборка и запуск контейнеров после подготовки БД:

```bash
make -C services/applications image
docker run --rm --name applications-api --network alfa-db_database -p 8001:8001 \
  --env-file services/applications/.env \
  -e DATABASE_URL=postgresql://applications_app:demo-applications-only@postgres:5432/alfa_applications \
  alfa-applications:2.0.0
```

Worker в другом терминале:

```bash
docker run --rm --name applications-worker --network alfa-db_database \
  --env-file services/applications/.env \
  -e DATABASE_URL=postgresql://applications_app:demo-applications-only@postgres:5432/alfa_applications \
  alfa-applications:2.0.0 worker
```

Оба процесса используют непривилегированного пользователя контейнера. Для продолжения процесса нужны доступные HTTP-сервисы по заданным URL. Общий gateway/Compose и соседние сервисы в этой ветке не реализуются.

## HTTP-проверка

После запуска API:

```bash
curl -fsS http://localhost:8001/health/live
curl -fsS http://localhost:8001/health/ready
curl -fsS -H 'Authorization: Bearer demo-customer-only' http://localhost:8001/v2/companies
```

При доступном catalog создать заполненный черновик:

```bash
curl -i http://localhost:8001/v2/applications \
  -H 'Authorization: Bearer demo-customer-only' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: readme-create-001' \
  -d '{"companyId":"00000000-0000-4000-8000-000000000101","productId":"working-capital","productVersion":"1","amountRub":1200000,"termMonths":12,"purpose":"WORKING_CAPITAL","financialData":{"monthlyRevenueRub":800000,"monthlyExpensesRub":500000,"existingMonthlyDebtPaymentsRub":20000}}'
```

Сохранить `id` и точный заголовок `ETag` из ответа. Отправить заявку, подставив их вместо `APPLICATION_ID` и `ETAG_FROM_RESPONSE`:

```bash
curl -i http://localhost:8001/v2/applications/APPLICATION_ID/submit \
  -H 'Authorization: Bearer demo-customer-only' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: readme-submit-001' \
  -H 'If-Match: ETAG_FROM_RESPONSE' \
  -d '{"personalDataConsent":true,"creditBureauConsent":true}'
curl -fsS -H 'Authorization: Bearer demo-customer-only' \
  http://localhost:8001/v2/applications/APPLICATION_ID
```

Кавычки ETag сохраняются. Ответ `202` подтверждает сохранение намерения; worker продолжает процесс. Для следующей команды перечитывайте заявку и используйте новый ETag. Полный автоматический сценарий до выдачи с HTTP-стабами выполняет `test-integration`.

## Проверки

```bash
make -C services/applications test
make -C services/applications test-integration
make -C services/applications build
```

`test` проверяет JSON Schema, отсутствие/null, строгие поля и JSON, совпадение канонического ETag с опубликованными примерами и актуальность копий контрактов. `test-integration` требует Docker: каждый сценарий создает отдельный случайно названный PostgreSQL 16-контейнер, применяет исходную миграцию/seed и owner-скрипт второй миграции, запускает настоящий Spring HTTP API и пять контрактных HTTP-зависимостей. После сценария удаляется только его контейнер. Существующий DB-профиль и данные разработчика не используются.

Покрыты полный путь до `DISBURSED`, три источника дозапроса и история версий, отказ/альтернатива/смена продукта, четыре действия сотрудника и запрет одобрения без оценки; роли, доступ и пагинация; `412/428`, replay со старым ETag и конкурентные ключи; отмены до подписи и поздние документы/дела; три срока и точная граница, гонки sign/cancel и sign/expiry; пять попыток, Retry-After, PAUSED/retry, перезапуск и потерянный lease; исправление поручения, поздняя проверка, обрыв/таймаут после зачисления, GET by-operation и повтор после `404` с прежними телом/ключом/операцией.

Результаты приемки до перехода на Gradle (24.10.2026, Temurin 21.0.12.1+1):

| Команда | Результат |
| --- | --- |
| `make -C services/applications test` | 7 тестов, успешно |
| `make -C services/applications test-integration` | 7 unit + 22 HTTP/PostgreSQL, включая смену текущей версии catalog |
| `make -C services/applications build` | исполняемый JAR успешно собран |
| `docker build --network=host -t alfa-applications:review services/applications` | образ на JVM 21; API и worker запускаются под UID 10001 |

Отчеты JUnit: `build/reports/tests/test/` и `build/reports/tests/integrationTest/`. Проверка образа: liveness `200`, readiness без БД `503`, отдельный worker работает. Для сборки в этой среде потребовалась сеть хоста из-за временных DNS-сбоев Docker/Maven Central; эквивалентная команда Make — `make -C services/applications image DOCKER_BUILD_ARGS=--network=host`.

Исправление сообщений о лимитах, 25.10.2026: выход суммы или срока за границы выбранного продукта возвращает `422 VALIDATION_ERROR` с русским объяснением и фактическим диапазоном из снимка продукта, например «Сумма кредита должна быть от 100 000 до 5 000 000 ₽.». Объяснения присутствуют в `detail` и `errors[].message`; `errors[].path` сохраняется для привязки к полям формы. Протокол и правила проверки границ не меняются. Регрессионный HTTP/PostgreSQL-тест проверяет обе границы суммы/срока, несколько ошибок сразу, отсутствие записи при ошибочном POST/PATCH, допустимость точных границ и пустых полей черновика. После интеграции с актуальным `main` на JVM 21 через Gradle успешно выполнены `make test-integration` (7 unit + 24 HTTP/PostgreSQL) и `make build`, включая Spotless, из каталога сервиса. Для сетевого доступа Gradle в этой среде использован `-Djava.net.preferIPv4Stack=true`.

## Эксплуатационные допущения

Проверка актуальности черновика, 26.10.2026: отправка старой версии продукта теперь возвращает `409 PRODUCT_NOT_CURRENT`. PATCH читает текущий активный продукт и атомарно применяет его вместе с исправленными данными; прежние сумма, срок и цель проверяются по новым ограничениям. При 422, 404 catalog или техническом сбое черновик и его ETag сохраняются. Новый снимок данных создаётся только после успешного submit. Проверка не затрагивает опубликованные версии данных, уточнение сведений и действия сотрудника; повтор ранее успешной команды обрабатывается до обращения в catalog.

Проверки этого изменения: на Temurin JVM 21.0.12.1 выполнено `./gradlew spotlessApply test integrationTest build --no-daemon -Djava.net.preferIPv4Stack=true` — 7 unit-тестов и 29 HTTP/PostgreSQL-тестов (25 ApplicationIT и 4 StaffRegistryIT), Spotless и сборка успешны. JDK 21 получен из локального официального образа `eclipse-temurin:21-jdk-jammy`; каждый интеграционный тест использовал свой временный PostgreSQL 16 и HTTP-стабы зависимостей. Регрессия проверяет 409 для устаревшей отправки, 422 по новым границам/целям без изменений в БД, переход программы 1 → 2 после исправления, неизменность успешных повторов PATCH/submit после публикации новой версии, снимок данных и запрет сохранения/отправки при 404/503 catalog. Полный сквозной стенд с настоящими соседними сервисами в этой проверке не запускался.

Публичные успешные ответы хранятся как минимум 48 часов; текущая реализация не удаляет их автоматически. Тела, ключи и история внутренних заданий сохраняются весь срок хранения заявки. Сетевые ошибки, `408/429/5xx` и `409 IDEMPOTENCY_IN_PROGRESS` повторяются до пяти попыток с 1/2/4/8 с и jitter; после этого бизнес-статус сохраняется, этап становится PAUSED. Ручной повтор сбрасывает счетчик запуска, сохраняя общий журнал. Cleanup после отмены/смены версии повторяет ограниченные серии с паузой не менее минуты между сериями: терминальная заявка не требует клиентского retry для закрытия удаленного ресурса.

Стабы находятся только в тестах. Они используют согласованные примеры как синтетические ответы, проверяют формы запросов и моделируют сбои; они не доказывают корректность расчетов соседнего decision. Сквозной прогон с реальными пятью реализациями требуется после общей интеграции. Несовместимых изменений общего контракта эта ветка не вносит.

## Автоформатирование

Из каталога сервиса: `./gradlew spotlessApply` исправляет Java-исходники и тесты,
`./gradlew spotlessCheck` проверяет стиль. Версии: Spotless 8.0.0,
google-java-format 1.28.0 (Google). `./gradlew build` включает проверку стиля.
Для автоматического форматирования перед коммитом один раз выполнить `npm ci`
в корне репозитория; настройки и общие команды — в корневом README.

## Проверка перехода на Gradle (24.10.2026)

На JDK 21 выполнены `./gradlew test assemble`, `make test-integration` и корневая
`./gradlew build`: 7 unit-тестов и 22 HTTP/PostgreSQL-тестов успешно.
Разрешенные версии прежнего Maven-дерева сравнены с `gradle.lockfile`: версии
прикладных зависимостей сохранены. Docker-образ собирается независимо из каталога
сервиса через `docker build --network=host`; корневые файлы сборки ему не нужны.

## Сквозная проверка локального окружения

При соединении настоящих applications и agreements обнаружено расхождение точности `acceptedAt`: Java Clock возвращает наносекунды, а PostgreSQL `timestamptz` сохраняет микросекунды. Worker отвергал созданный договор с `SNAPSHOT_MISMATCH`. Время принятия теперь приводится к микросекундам **до** записи принятия и неизменяемого HTTP-задания; схема и протокол не меняются.

Добавлен регрессионный сценарий с Clock `2030-01-01T00:00:00.123456789Z`: время HTTP-договора совпадает с сохраненным принятием и процесс проходит до DISBURSED. После исправления: 7 unit + 23 HTTP/PostgreSQL интеграционных теста успешно, JAR собран на JDK 21. Исторический прогон выполнен через Maven до перехода на Gradle; актуальные команды: `./gradlew test integrationTest build` на JDK 21.

На 24.10.2026 дополнительно прошли реальные сквозные HTTP-сценарии через общий gateway и шесть API: `python3 scripts/smoke_local.py`. В браузере проверен путь от каталога и создания черновика до подтвержденной выдачи с `MOCK_API=false`. Общая инструкция: [локальный Docker-запуск](../../docs/local-docker.md).

## Общий реестр сотрудника

`GET /v2/staff/applications` возвращает `ApplicationPage` всех клиентов и статусов, включая DRAFT. Только StaffBearer; customer/admin/internal получают 403, неизвестный bearer — 401. `DEMO_ADMIN_TOKEN` по умолчанию `demo-product-admin-only`, все четыре токена должны различаться. Список использует сохраненную проекцию заявки без обращения в catalog; GET не изменяет аудит/версии.

Фильтры `status` и `approvalType=AUTO|MANUAL` комбинируются. Тип одобрения определяется только APPROVED-решением текущей dataVersion и сохраняется после принятия/подписи/выдачи. Сортировка created_at DESC,id DESC, limit 1–100 (20 по умолчанию), limit+1, keyset. Новый staff-cursor связан с фильтрами; испорченный/несовместимый cursor дает 422. Существующие клиентские курсоры не меняются. Между страницами нет snapshot; новые заявки сверху видны после обновления первой страницы.

Миграция `003_staff_registry_index.sql` добавляет составной индекс. Обновление существующей БД: после общего bootstrap выполнить `MIGRATION_DATABASE_URL=<owner-capable URL> bash services/applications/scripts/migrate.sh`. Runner проверяет checksum и повторно безопасно применяет 002/003. Runtime applications_app остается DML-only. В объединенном origin/main общий infra/migrate.sh уже обнаруживает новые миграции автоматически, включая applications/003; профиль frontend/e2e-real/compose.staff-registry.yaml использует его без отдельного шага. Service runner остается доступен для самостоятельного запуска.

Результат проверки реестра (2026-10-25): Java 21 / Gradle Wrapper 8.14.3, `integrationTest build` — PASS (28 HTTP/PostgreSQL +7 unit), общая backend-сборка — PASS; SQL-валидатор — 61 PASS. Новые AUTO/MANUAL real E2E прошли; общая браузерная регрессия имеет отдельный тайминг-лимит, подробно в отчете.

После интеграции origin/main 451f5ab: composite build — PASS; StaffRegistryIT — 4/4 PASS. Общий мигратор проверил applications/003, совместные admin/staff real E2E — 4/4 PASS, включая архивную программу в реестре.

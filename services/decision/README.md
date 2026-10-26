# Decision — Java / Gradle

Сервис decision: оценка риска `demo-policy-v2`, отдельные AUTO/MANUAL решения, пересчитанные альтернативы, предложения и ручные дела. Реализованы все девять операций `contracts/openapi/decision.yaml`, включая два health endpoint. Глобальные статусы заявок остаются в applications. Обращений к соседним сервисам и их БД нет: сервис принимает полные снимки.

## Стек и структура

Java 21 / JVM 21, Gradle Wrapper 8.14.3, встроенный JDK HTTP server, Jackson 2.19.2, PostgreSQL JDBC 42.7.5, JUnit 5.11.4. Зависимости, включая транзитивные, закреплены в `gradle.lockfile`. Версии образов закреплены в Dockerfile; лицензии перечислены в `THIRD_PARTY_NOTICES`.

- `Policy` — чистые вычисления через BigDecimal произвольной точности без округления промежуточных величин; деньги не вычисляются через float/double. Аннуитет вычисляется в алгебраически эквивалентной форме с целочисленно масштабированными степенями, затем применяется точный ceiling до рубля.
- `Store` — PostgreSQL, транзакции, блокировки, хранение снимков и ответов.
- `HttpApi` — InternalBearer, маршруты `/v2`, request ID и Problem.
- `Contract` — проверка словаря JSON Schema, используемого согласованными DTO. Снимок `src/main/resources/schemas.json` соответствует общему YAML; тест проверяет его равенство и все успешные decision-примеры. Неизвестные поля, null/отсутствие, условные ограничения действий и форматы проверяются. Неизвестный ключ словаря схемы приводит к ошибке запуска.

## Конфигурация

Обязательны `DATABASE_URL` и `INTERNAL_API_TOKEN`. `PORT` по умолчанию 8004; адрес прослушивания `0.0.0.0`. Примеры без реальных секретов — `.env.example`. Значения файла не загружаются автоматически: их нужно передать окружением.

`DATABASE_URL` принимает `postgresql://decision_app:password@host:5432/alfa_decision` или JDBC URL с `user`/`password`. Соединение с БД ограничено 2 секундами, запрос — 10 секундами, ожидание блокировки — 5 секундами. При ожидании конкурентного запроса возвращается `409 IDEMPOTENCY_IN_PROGRESS` и `Retry-After: 1`. Финансовые данные и токены не попадают в журнал: только requestId, HTTP status и код ошибки.

## База и миграции

Из корня репозитория поднять общий DB-профиль:

```sh
docker compose -p alfa-db -f database/compose.yaml up -d --wait
```

Он применяет принятую `001_initial.sql` и пустой decision seed. Общий bootstrap пока применяет только 001, поэтому миграцию **002 необходимо применить отдельно** до запуска сервиса. Она добавляет неизменяемые JSON-проекции решений/ответов действий для точного чтения и бизнес-повтора. Исходная 001 не изменена.

Для выполнения из каталога `services/decision` через доступное администратору соединение:

```sh
export DECISION_ADMIN_URL='postgresql://postgres:demo-admin-only@localhost:5432/alfa_decision'
checksum=$(sha256sum migrations/002_response_documents.sql | cut -d ' ' -f 1)
psql "$DECISION_ADMIN_URL" -v migration_checksum="$checksum" -f scripts/apply-002.sql
```

Порт общего DB-профиля на хост не опубликован. Вместо публикации порта можно выполнить внутри контейнера из корня репозитория:

```sh
docker compose -p alfa-db -f database/compose.yaml exec postgres sh -c 'cd /schema/services/decision && psql -U postgres -d alfa_decision -v migration_checksum="$(sha256sum migrations/002_response_documents.sql | cut -d " " -f 1)" -f scripts/apply-002.sql'
```

`apply-002.sql` выполняет DDL через `SET ROLE decision_owner`, выдает только DML на новые таблицы и записывает checksum в закрытую `schema_migrations`. Применять однократно. Runtime работает исключительно как `decision_app`; не создавать таблицы и не выполнять миграции от его имени. Для новой автономной БД сначала создать владельца NOLOGIN, БД `alfa_decision`, runtime-роль с LOGIN/DML и применить 001 по порядку из `database/bootstrap.sh`.

`/health/live` проверяет процесс; `/health/ready` читает ожидаемые таблицы/столбцы своей БД, включая 002. У decision seed нет обязательных строк, поэтому бизнес-таблицы могут быть пусты. `schema_migrations` runtime-пользователь не читает. Готовность соседей не проверяется.

## Сборка и запуск

В каталоге `services/decision`, при установленном JDK 21:

```sh
./gradlew test
./gradlew build
./gradlew installDist
export DATABASE_URL='postgresql://decision_app:demo-decision-only@localhost:5432/alfa_decision'
export INTERNAL_API_TOKEN='demo-internal-token-change-me'
export PORT=8004
build/install/decision/bin/decision
```

Windows: использовать `gradlew.bat`, переменные `$env:DATABASE_URL`, `$env:INTERNAL_API_TOKEN`, `$env:PORT` и `build\install\decision\bin\decision.bat`. `JAVA_HOME` должен указывать на JDK 21. Wrapper при первом запуске загружает Gradle 8.14.3; первая сборка загружает зависимости из Maven Central.

Docker-контекст — сам каталог сервиса:

```sh
docker build -t alfa-decision:local .
docker run --rm --network alfa-db_database -p 127.0.0.1:8004:8004 \
  -e DATABASE_URL='postgresql://decision_app:demo-decision-only@postgres:5432/alfa_decision' \
  -e INTERNAL_API_TOKEN='demo-internal-token-change-me' alfa-decision:local
```

Контейнер выполняется без root, сервер слушает `0.0.0.0:$PORT`. Корневой Compose и gateway не изменены.

## Проверка HTTP

После запуска с примененной 002:

```sh
curl -i http://localhost:8004/health/ready
curl -i http://localhost:8004/v2/decisions \
  -H 'Authorization: Bearer demo-internal-token-change-me' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-decision-001' \
  --data-binary @src/test/resources/approved-request.json
```

Первый запрос решения возвращает 201. Точный повтор с тем же ключом сохраняет исходные 201/тело/UUID/срок предложения; другой ключ и тот же бизнес-ресурс — 200. Измененный вход — 409. Полученное решение читается через `GET /v2/decisions/{id}` с внутренним токеном. Во всех ответах есть X-Request-Id; ошибки имеют `application/problem+json`. Входной X-Request-Id воспроизводится, при отсутствии создается сервером.

Все изменения и успешный ответ фиксируются одной транзакцией. Блокировки PostgreSQL сериализуют одинаковые ключи, одну версию заявки и разрешение дела; внутренние ключи не истекают. Авторизация выполняется до воспроизведения ответа. Ошибки не сохраняются как успешный результат.

## Риск, решение, ручное обоснование

Контрольный пример: 1 000 000 рублей, 12 месяцев, 2400 б.п.; платеж **94 560 рублей**, свободный поток **500 000**, текущие платежи **20 000**, нагрузка **2292 б.п.** Факторы: BASE 300 + CREDIT_HISTORY 250 + BUSINESS_AGE 150 + DEBT_BURDEN 200 = **900, LOW**. Отдельный результат — APPROVED; предложение REQUESTED, версия 1, срок 168 часов от серверного времени.

Негативные полные факты дают предметный REJECTED. NEEDS_INFO при нулевых расходах без объяснения возвращается до расчета. Альтернативы оцениваются в порядке workflow с дедупликацией и проверкой минимума. Все рассмотренные условия сохраняются, включая отклоненные; автоматический допустимый вариант имеет приоритет перед ручным.

Ручное дело создается из спорной проверки без оценки или из сохраненного AUTO/MANUAL_REVIEW результата той же версии и тех же снимков. `allowedActions` исключает APPROVE без завершенного скоринга. APPROVE принимает только свой evaluated termsId и повторно вычисляет риск/ограничения; REJECT допускается без оценки. REQUEST_INFO требует конкретных requirements, AMEND_DATA — фактических changes и/или factsResolution с verificationId своего дела. Эти действия сохраняют аудит, а новую версию и повторные проверки создает applications. Старые факты/AUTO не изменяются. Одно действие разрешает дело; закрытие сохраняет исходную причину и не создает кредитного решения.

## Тесты и фактическая проверка

```sh
make test
make test-integration
make build
```

Это цели `./gradlew test`, `./gradlew integrationTest`, `./gradlew build`. `make docker-build` отдельно собирает образ.

`integrationTest` по умолчанию создает отдельный контейнер PostgreSQL 16.15 с закрепленным digest из общего DB-профиля, применяет 001, 002 и seed, проверяет через настоящий HTTP runtime-пользователя и удаляет только свой контейнер. Подмена PostgreSQL на SQLite или память не используется. Работающие соседние сервисы/стабы не нужны: все входы — контрактные снимки.

Альтернатива при установленном PostgreSQL 16 без Docker:

```powershell
.\gradlew.bat integrationTest '-PpgBin=C:/Program Files/PostgreSQL/16/bin'
```

Создается отдельный временный кластер в `build/integration/decision-it-<uuid>` на свободном loopback-порту. Он останавливается и удаляется после тестов, существующие БД не используются. Удаление проверяет границу тестового каталога.

После ревью 24.10.2026 проверено на Linux: Temurin 21.0.12.1+1, Gradle Wrapper 8.14.3, настоящий PostgreSQL 16 в отдельном Docker-контейнере. `make test` (104 проверки), `make test-integration` (14 HTTP/PostgreSQL-сценариев) и `make build` прошли без ошибок и пропусков. Использован обычный Maven Central; lock-файл включает BOM Jackson и JUnit.

Дополнительно проверены дробные деньги без округления через double, версии вне диапазона bigint и повторы с математически одинаковыми целыми числами (`1` / `1.0`). Дробные и непредставимые в БД значения дают 422 до записи. Вход нормализуется перед вычислением отпечатка и сохранением снимка. Серверные часы в тестах фиксированы через Java Clock; API не принимает часы или тестовые исходы.

## Выявленные границы общего контракта

В `PositiveRubles` и SQL `evaluated_terms.monthly_payment_rub` верхняя граница платежа — 1 000 000 000. Однако снимок продукта со сроком 1 месяц, ставкой 10000 б.п. и суммой 1 000 000 000 проходит входную схему, а аннуитетный платеж равен 1 083 333 334. Согласованные тестовые продукты этого сочетания не допускают. Для такого произвольного снимка текущая схема не позволяет сохранить/вернуть математически верный результат; транзакция откатывается с технической ошибкой, кредитный отказ не подставляется. Изменение общего DTO/001 вне области этого сервиса. PositiveVersion не ограничен сверху в JSON Schema; сервис явно возвращает 422 для значений выше 9223372036854775807, не усекая их до long.

## Автоформатирование

Из каталога сервиса: `./gradlew spotlessApply` исправляет Java-исходники и тесты,
`./gradlew spotlessCheck` проверяет стиль. Версии: Spotless 8.0.0,
google-java-format 1.28.0 (Google). `./gradlew build` включает проверку стиля.
Для автоматического форматирования перед коммитом один раз выполнить `npm ci`
в корне репозитория; настройки и общие команды — в корневом README.

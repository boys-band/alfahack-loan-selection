# Catalog

Реализован сервис каталога продуктов на Java 21 / JVM 21: JDK HttpServer, Jackson, JDBC и HikariCP. PostgreSQL — единственная зависимость. Production-код находится в `src/main/java`.

| Метод и путь | Поведение |
| --- | --- |
| `GET /v2/products` | Активные текущие версии, стабильный порядок по `id`, объект `{"items":[...]}` |
| `GET /v2/products/{productId}` | Только активная текущая версия |
| `GET /v2/products/{productId}?version=1` | Точная сохраненная версия, включая архивную и деактивированную |
| `GET /v2/admin/products` | Текущие продукты, включая неактивные; `Cache-Control: no-store` |
| `GET /v2/admin/products/{productId}` | Текущая версия, история версий и ETag текущего состояния |
| `POST /v2/admin/products` | Создание активной программы v1 с аудитом, причиной и идемпотентным ответом |
| `POST /v2/admin/products/{productId}/versions` | Атомарная публикация новой неизменяемой версии, указателя и аудита; требует `If-Match` и `Idempotency-Key` |
| `POST /v2/admin/products/{productId}/archive` | Архивирование без удаления версий; требует `If-Match`, причину и `Idempotency-Key` |
| `GET /health/live` | `{"status":"ok"}` независимо от БД |
| `GET /health/ready` | Проверяет доступность колонок обеих таблиц, указателей и двух seed-версий `1`; при отсутствии — `503` |

Все ответы содержат `X-Request-Id`: входной canonical lowercase UUID либо новый UUID. Ошибки имеют контрактную схему `Problem`, `application/problem+json`, `requestId` и `errors[].path`. Неверные параметры дают `422 VALIDATION_ERROR`, отсутствующий продукт/версия — `404 NOT_FOUND`, недоступная БД/схема — `503 DEPENDENCY_UNAVAILABLE` с `Retry-After: 3`. Тексты SQL-ошибок и секреты не попадают в ответ или журнал.

## Конфигурация

| Переменная | Значение |
| --- | --- |
| `DATABASE_URL` | Обязательна: `postgresql://catalog_app:password@host:5432/alfa_catalog` |
| `PORT` | `8002`, слушает `0.0.0.0` |
| `DEMO_CUSTOMER_TOKEN` | Обязательный клиентский демотокен |
| `DEMO_ADMIN_TOKEN` | Обязательный отдельный токен администратора |
| `DEMO_ADMIN_ID` | UUID автора публикации в аудите |
| `INTERNAL_API_TOKEN` | Обязательный внутренний секрет, отличается от клиентского |

Пример окружения — `.env.example`, содержит только демозначения. Пароль в URL должен быть percent-encoded при наличии специальных символов. Поддержаны query-параметры подключения `sslmode` и `sslrootcert`; пользователь и база фиксированы на `catalog_app` / `alfa_catalog`. Пул: максимум 8 соединений, ожидание 2,5 с, connect timeout 2 с, socket/statement timeout 3 с. Runtime имеет только DML на таблицах каталога, без DDL и доступа к чужим БД. Процесс стартует при недоступной БД и восстанавливает соединения после ее возвращения.

Bearer клиента и InternalBearer дают доступ к чтению публичного каталога. Отдельный admin Bearer допускается только к `/v2/admin/products`; клиентский, staff и внутренний токены туда не допускаются. Отсутствующий или неверный токен возвращает `401 UNAUTHENTICATED` / `WWW-Authenticate: Bearer`. Сервис не загружает `DEMO_STAFF_TOKEN`. Health не требует токен. Аутентификация API выполняется до проверки параметров и чтения БД.

Публикация принимает только поля `Product` и причину изменения, не меняет кредитную политику и сроки workflow. ETag — SHA-256 канонического JSON полного `AdminProduct` (текущий `product`, `active` и массив `versions`); смена этого состояния делает старый `If-Match` непригодным (`412`). Отсутствующий `If-Match` даёт `428`. Успех возвращает `201` и новый ETag. Повтор того же ключа с тем же телом возвращает сохранённый `201` и тот же ETag даже после следующих публикаций; то же имя ключа с другим телом даёт `409 IDEMPOTENCY_CONFLICT`. Изменение, указатель, аудит и сохранённый ответ коммитятся в одной транзакции. Старые версии и снимки заявок остаются доступными.

## Независимый запуск через Docker

Из корня репозитория, установлена только Docker / Compose:

```bash
docker compose -p alfa-catalog -f services/catalog/compose.yaml up --build -d --wait
curl --fail http://localhost:8002/health/ready
```

На проверочной машине Docker bridge давал TLS-таймауты при загрузке зависимостей. Рабочая команда сборки и запуска в этом окружении:

```bash
docker build --network=host -t alfa-catalog:2.0.0 services/catalog
docker compose -p alfa-catalog -f services/catalog/compose.yaml up --no-build -d --wait
```

Этот профиль создает только catalog и его PostgreSQL с собственным постоянным volume. Порт БД не публикуется, HTTP опубликован на `127.0.0.1:8002`. Для другого HTTP-порта задать `CATALOG_HTTP_PORT`. Демотокены по умолчанию: `demo-customer-only`, `demo-internal-only`, `demo-product-admin-only`. При смене `CATALOG_DB_PASSWORD` задать соответствующий `DATABASE_URL`; Compose не кодирует пароль в URL автоматически. Изменение пароля после первого старта требует отдельной смены пароля в существующей БД.

```bash
curl --fail -H 'Authorization: Bearer demo-customer-only' \
  -H 'X-Request-Id: 00000000-0000-4000-8000-000000000001' \
  http://localhost:8002/v2/products
curl --fail -H 'Authorization: Bearer demo-internal-only' \
  'http://localhost:8002/v2/products/working-capital?version=1'
docker compose -p alfa-catalog -f services/catalog/compose.yaml restart catalog
docker compose -p alfa-catalog -f services/catalog/compose.yaml down
```

`down` сохраняет данные. `tests/bootstrap.sh` применяется только к пустому volume: создает NOLOGIN-владельца `catalog_owner`, DML-пользователя `catalog_app`, применяет неизмененную `001_initial.sql`, затем `002_admin_publication.sql`, `003_program_lifecycle.sql` и общий `database/seeds/catalog.sql` от имени владельца. В общем Compose номерные миграции применяет `infra/migrate.sh` после базовой и seed. `schema_migrations` закрыта runtime-роли. Изменение существующей версии запрещено триггером, включая операции владельца.

## Запуск с общим DB-профилем

Из корня:

```bash
docker compose -p alfa-db -f database/compose.yaml up -d --wait
docker build -t alfa-catalog:2.0.0 services/catalog
docker run --rm --name alfa-catalog --network alfa-db_database \
  --env-file services/catalog/.env.example -p 127.0.0.1:8002:8002 alfa-catalog:2.0.0
```

DB-профиль применяет только `001_initial.sql` и seed. Перед запуском admin API в нём нужно применить 002 и затем 003 от владельца схемы; корневой Compose делает это автоматически. Runtime не выполняет DDL и не обращается к `schema_migrations`. Для DB-профиля:

```bash
(printf 'SET ROLE catalog_owner;\n'; cat services/catalog/migrations/002_admin_publication.sql services/catalog/migrations/003_program_lifecycle.sql; \
 printf 'GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO catalog_app;\n') | \
  docker compose -p alfa-db -f database/compose.yaml exec -T postgres \
    psql -X -v ON_ERROR_STOP=1 -U postgres -d alfa_catalog -f /dev/stdin
```

Изменения условий после миграции публикуются через admin API. Следующий SQL — пример проверки неизменности старой версии на отдельной базе, а не штатный способ редактирования:

```bash
docker compose -p alfa-catalog -f services/catalog/compose.yaml exec -T postgres \
  psql -X -v ON_ERROR_STOP=1 -U postgres -d alfa_catalog <<'SQL'
SET ROLE catalog_owner;
INSERT INTO product_versions SELECT product_id, '2', name, description, purpose_codes,
  min_amount_rub, max_amount_rub, min_term_months, max_term_months, 2100,
  min_business_age_months, max_debt_service_ratio_bps, clock_timestamp()
  FROM product_versions WHERE product_id='working-capital' AND version='1';
UPDATE current_products SET version='2' WHERE product_id='working-capital';
SQL
```

После этого чтение без `version` возвращает `2`, явная `version=1` сохраняет исходную ставку `2400`. `UPDATE current_products SET active=false ...` скрывает продукт из списка и текущего чтения, сохраняя обе конкретные версии. Readiness при этом остается успешной.

## Сборка и тестирование

Для локальных команд нужны JDK 21 (`JAVA_HOME` указывает на JDK 21), `make` и доступ к Maven Central/Gradle Plugin Portal; для интеграционных тестов — работающий Docker с доступным CLI. Gradle Wrapper фиксирует Gradle 8.14.3 и SHA-256 дистрибутива. `build.gradle` и `gradle.lockfile` фиксируют зависимости. Исполняемый JAR собирается плагином Shadow 8.3.6 с объединением service descriptors.
```bash
make -C services/catalog test
make -C services/catalog test-integration
make -C services/catalog build
```

`test` выполняет unit-тесты конфигурации. `test-integration` выполняет unit-тесты и отдельную Gradle-задачу `integrationTest` с JUnit HTTP-тестами на настоящей PostgreSQL 16.15 с закрепленным digest. Создается контейнер `catalog-it-<uuid>` с tmpfs и случайным loopback-портом. После завершения удаляется только этот контейнер; данные проекта не используются. Входные seed и JSON Schema читаются из репозитория. Соседние сервисы и стабы не нужны. При принудительном уничтожении JVM оставшийся контейнер можно найти по метке `alfa.test=catalog`.

Сборка создает `build/libs/catalog.jar`. Для запуска вне Docker указать окружение из таблицы и выполнить `java -jar services/catalog/build/libs/catalog.jar`. В Docker обработчик SIGTERM прекращает прием запросов, дает текущим до 5 секунд и закрывает пул.

Повторное получение дерева зависимостей:

```bash
cd services/catalog
./gradlew dependencies --write-locks
```

## Проверки и допущения

Результаты приемки до перехода на Gradle (24.10.2026, Temurin **21.0.12.1+1**, PostgreSQL **16.15**):

- `make test` — 3 unit-теста, успешно.
- `make test-integration` — те же 3 unit-теста и 11 HTTP/PostgreSQL-тестов, успешно.
- `make build` — исполняемый `build/libs/catalog.jar`, успешно.
- `docker build --network=host -t alfa-catalog:2.0.0 services/catalog` — успешно; unit-тесты выполняются и внутри сборки.
- Compose с пустой собственной БД — оба контейнера `healthy`. Через опубликованный HTTP проверены live, ready, клиентский список, внутренняя конкретная версия и `401` без токена. В runtime-контейнере подтверждена JVM 21. После проверки временный профиль и его volume удалены.

Покрыты точное совпадение обоих продуктов с общим seed, оба типа токенов и отказ staff/неверному/отсутствующему токену, `X-Request-Id`, ошибки параметров и схемы всех JSON-ответов. Проверены смена `1 → 2`, деактивация вплоть до пустого списка, старая версия без текущего указателя, длинные версии, границы сумм/ставок, конкурентное чтение, сохранность после перезапуска сервиса, отсутствие DDL/доступа к миграциям/чужой БД у runtime-роли, неизменяемость версий и внешний ключ указателя. Приостановка и возобновление реального PostgreSQL проверяют `503`, независимую liveness и восстановление пула. Отсутствующая схема и отсутствующий seed дают отрицательную readiness.

Версия — строка положительного десятичного числа без ведущих нулей. Она не преобразуется в ограниченный Java `int`: неизвестная, но синтаксически корректная длинная версия дает `404`. Повторяющийся `version`, неизвестные query-параметры и GET с телом дают `422`; неподдерживаемый метод — `405` с `Allow: GET`. Для отсутствующего или неверного staff-токена используется `401`: каталог не имеет конфигурации личности сотрудника.

Уточнение SQL/API: `purpose_codes` в принятой миграции допускает дубликаты, тогда как Product требует `uniqueItems`. При чтении сервис сохраняет порядок и убирает повторные коды; миграция и исходная запись остаются прежними. Остальные поля берутся из конкретной строки версии без пересчета.

Readiness проверяет наличие исходных seed-версий и указателей, независимо от `active` и номера текущей версии. Пустой активный каталог допустим. Это позволяет штатно деактивировать оба продукта, сохранив готовность чтения истории.

## Автоформатирование

Из каталога сервиса: `./gradlew spotlessApply` исправляет Java-исходники и тесты,
`./gradlew spotlessCheck` проверяет стиль. Версии: Spotless 8.0.0,
google-java-format 1.28.0 (Google). `./gradlew build` включает проверку стиля.
Для автоматического форматирования перед коммитом один раз выполнить `npm ci`
в корне репозитория; настройки и общие команды — в корневом README.

## Проверка перехода на Gradle (24.10.2026)

На JDK 21 выполнены `./gradlew test assemble`, `make test-integration` и корневая
`./gradlew build`: 3 unit-тестов и 11 HTTP/PostgreSQL-тестов успешно.
Разрешенные версии прежнего Maven-дерева сравнены с `gradle.lockfile`: версии
прикладных зависимостей сохранены. Docker-образ собирается независимо из каталога
сервиса через `docker build --network=host`; корневые файлы сборки ему не нужны.

## Проверка административной публикации (25.10.2026)

В изолированном worktree на JDK 21 выполнено `./gradlew integrationTest build --no-daemon`: 3 unit-теста и 12 HTTP/PostgreSQL-тестов, включая публикацию, повтор исходного ключа, 412, 409 при том же ключе с другим даже невалидным телом, точный `Content-Type`, отдельные права, одну запись аудита и один сохранённый ответ. `spotlessCheck` и сборка прошли. На хосте JDK 21 отсутствовал, поэтому Gradle запускался в локальном Docker-образе Temurin 21 с Docker CLI; интеграционный тест поднимал отдельный PostgreSQL 16. Проверка общей базы через `python3 scripts/validate_database.py` прошла: 61 проверка схем, seed и прав. В изолированном общем Compose миграция `002_admin_publication` записана в `schema_migrations`, таблицами владеет `catalog_owner`, роль `catalog_app` имеет DML и не имеет `TRUNCATE`.

## Проверка создания и архивирования (25.10.2026)

Команда на JDK 21: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew -p services/catalog spotlessApply integrationTest build --no-daemon`. Фактически пройдены 3 unit и 14 HTTP/PostgreSQL-тестов, `spotlessCheck` и сборка. Тесты используют временный PostgreSQL 16 с реальным HTTP и проверяют создание v1, чтение клиентом, занятый ID, сохраненный ответ, аудит, запрет архивной публикации, сохранность точной версии, конкурентное создание и гонку публикации с архивом. После устранения гонки чтения тестовый набор повторён трижды без падений.

Новая `003_program_lifecycle.sql` добавляет неизменяемые события `CREATED`/`ARCHIVED`; первая публикация v1 также остаётся в `product_publications`. `current_products.active=false` скрывает программу только из текущего клиентского каталога. Временная БД с 001/002 обновлена общим `infra/migrate.sh` до 003, повтор мигратора завершился без повторного применения; с нуля тот же мигратор прошёл в отдельном Compose `alfa-admin-e2e`. Проверка `python3 scripts/validate_database.py` прошла 61 проверку. Сквозной browser real E2E через gateway прошёл 2/2: старая заявка дошла до предложения после архива, подписанный договор сохранил текст, хеш и подпись, выдача завершилась, новый выбор и смена на архивную программу дали `409 PRODUCT_NOT_CURRENT`. Права runtime остались DML; физического удаления нет.

Дополнительные HTTP/PostgreSQL проверки подтвердили: два одновременных запроса с одинаковым `If-Match` и разными ключами дают ровно один `201`, один `412`, одну новую версию и одну запись аудита; пустое или состоящее только из пробелов JSON-тело даёт `400 MALFORMED_JSON`; пять emoji в причине дают `422` по числу Unicode-символов, десять emoji допустимы. Проверены пустые текстовые поля, слишком длинная причина, дубли целей и границы числовых полей.

После полной real-регрессии (`47 passed, 2 skipped`) в отдельном Compose-проекте оба seed-продукта архивированы по HTTP. Клиентский список стал пустым, обе точные версии `1` остались читаемы, `GET /health/ready` catalog вернул `200`.

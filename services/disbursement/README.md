# Disbursement

Сервис проверки синтетических реквизитов и однократного моделируемого зачисления.
Реализует восемь операций `contracts/openapi/disbursement.yaml` на Java 21,
Spring Boot 3.5.7 и Gradle 8.14.3. Хранилище — PostgreSQL 16+, JDBC/HikariCP.
Реальных банковских операций, денег и электронной подписи здесь нет.

## Поведение

- `GET /v2/company-accounts/{companyId}` возвращает только счета указанной компании;
  для неизвестной компании список пустой: у сервиса нет реестра компаний.
- `POST /v2/payout-checks` сохраняет неизменяемый результат на
  `(applicationId,dataVersion,payoutVersion)`. ACTIVE-счёт своей компании даёт READY,
  INVALID — ACCOUNT_INVALID, чужой или неизвестный — ACCOUNT_NOT_OWNED.
  Исправление требует новой payoutVersion. `GET /v2/payout-checks/{id}` читает результат.
- `POST /v2/disbursements` проверяет точный READY payoutCheckId и все связи,
  повторно проверяет принадлежность/пригодность счёта, получает актуальный договор
  через `GET /v2/agreements/{agreementId}`. Требуются SIGNED, согласованные ID/версии,
  подпись того же клиента и документа, правильный SHA-256 содержимого и подпись до signBy.
  Прошедший signBy не отменяет ранее подписанный договор. Сумма берётся из offer
  подписанного договора; валюта RUB закреплена контрактом. Вход не принимает сумму.
- Выдача, credit_entries и успешный ответ идемпотентности фиксируются одной транзакцией.
  Ответ EXECUTED выдаётся после commit, включая проверку deferred FK. Используются
  принятые unique applicationId/agreementId/operationId и matching FK суммы/счёта.
- `GET /v2/disbursements/{id}` и `GET /v2/disbursements/by-operation/{operationId}`
  читают только выдачу с соответствующим сохранённым зачислением.
- `GET /health/live` проверяет процесс. `GET /health/ready` проверяет свою схему и
  шесть ожидаемых счетов seed, включая их компанию и статус. Agreements на readiness
  не влияет. Runtime не читает schema_migrations.

Все предметные endpoint требуют `Authorization: Bearer $INTERNAL_API_TOKEN`.
Health открыт. Ответ всегда содержит X-Request-Id; корректный входной UUID сохраняется
и передаётся agreements. Отсутствующий UUID генерируется; некорректный даёт 400.
JSON проверяется по встроенной неизменённой копии общей схемы, с точным разбором чисел,
запретом дублей ключей, дополнительных полей и нескольких JSON-корней.
Ошибки имеют `application/problem+json`, code, requestId и errors.

## Повторы и восстановление

Идемпотентность: `(caller=applications,POST,конкретный path,key)`, каноническое тело
с сортировкой ключей, нормализацией целых чисел (`1000` и `1e3`) и SHA-256 метода/пути/тела. Внутренние ключи хранятся бессрочно.
Общий внутренний токен соответствует одному доверенному caller; отдельные клиентские
и staff-токены здесь не принимаются.

Аутентификация предшествует воспроизведению. Повтор ключа/входа возвращает прежние
статус и тело; X-Request-Id относится к текущему HTTP-запросу. Другой вход с тем же
ключом даёт 409 IDEMPOTENCY_CONFLICT. Новый ключ с тем же бизнес-ресурсом/входом —
200 и прежний ресурс. Несовпадающий бизнес-вход — 409 RESOURCE_CONFLICT.
Повторы сохранённых результатов не требуют доступности agreements.

Транзакционные advisory locks PostgreSQL защищают ключ идемпотентности и
бизнес-ключи; они действуют между экземплярами сервиса. Пересекающаяся незавершённая
операция возвращает 409 IDEMPOTENCY_IN_PROGRESS с `Retry-After: 1`. После завершения
нужно повторить исходный запрос. Нет процесса очистки, снимающего защиту старых ключей.

При таймауте клиента сначала читайте by-operation. 404 означает лишь отсутствие
видимого committed результата сейчас. Повторяйте **те же operationId, ключ и тело**;
новый operationId не является способом восстановления. После перезапуска данные
восстанавливаются из PostgreSQL. Сбой БД, timeout, невалидный JSON/контракт или
недоступность agreements дают технический 503 без успешной записи идемпотентности.
READY/CLOSED/404 договора и несвязанные снимки дают 409, без зачисления.

## Конфигурация

| Переменная | Назначение |
| --- | --- |
| DATABASE_URL | Обязательная JDBC PostgreSQL URL либо postgres:// / postgresql:// URL с credentials |
| PORT | HTTP-порт, по умолчанию 8006; bind 0.0.0.0 |
| INTERNAL_API_TOKEN | Обязательный непустой внутренний токен |
| AGREEMENTS_URL | Обязательная HTTP(S) base URL agreements, без credentials/query/fragment |
| AGREEMENTS_TIMEOUT_MS | Таймаут подключения/запроса, 2000 по умолчанию, диапазон 1–30000 |
| JAVA_HOME | JDK 21 для Gradle и локального запуска |

В `.env.example` только демозначения. Файл не подхватывается приложением автоматически.
Для запуска из shell экспортируйте переменные. Пул БД — до 16 соединений,
connection timeout 2 секунды, JDBC socket timeout 5 секунд; транзакция — до 35 секунд.
Токены и полные финансовые снимки не логируются. SQL/HTTP body debug logging не включён.

## Сборка и тесты

Из корня репозитория:

```bash
# На macOS с Homebrew, если JDK 21 не зарегистрирован в java_home:
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
# Иначе укажите ваш JDK 21.
make -C services/disbursement test
make -C services/disbursement test-integration
make -C services/disbursement build
```

Wrapper входит в репозиторий и проверяет официальный SHA-256 Gradle.
Версии зависимостей закреплены BOM и `gradle.lockfile`; лицензии перечислены в
THIRD_PARTY_NOTICES. При намеренном обновлении зависимостей: `./gradlew dependencies --write-locks`.
Проверка равенства встроенной схемы общему контракту входит в unit-тесты.

`test-integration` требует Docker, создаёт только собственный временный контейнер
PostgreSQL 16.15 с закреплённым digest, отдельную БД и DML-пользователя
disbursement_app. Миграция/seed применяются владельцем NOLOGIN; schema_migrations
закрыта для runtime. Контейнер с его данными удаляется через trap даже при падении
тестов. Скрипт не использует существующие рабочие БД/volumes. Agreements заменён
локальным HTTP-стабом в tests. SQLite и встроенных замен БД нет.

Результат сборки: `services/disbursement/build/libs/disbursement.jar`.
Отчёты: `build/reports/tests/test/` и `build/reports/tests/integrationTest/`.

Проверено 24 октября 2026 года:

| Проверка | Фактический результат |
| --- | --- |
| `make -C services/disbursement test` | 6 тестов, 0 ошибок/пропусков |
| `make -C services/disbursement test-integration` | 37 HTTP/PostgreSQL тестов, 0 ошибок/пропусков |
| `make -C services/disbursement build` | Исполняемый JAR собран |
| `docker build -t alfa-disbursement:local services/disbursement` | Образ собран из service-only контекста |
| `tests/smoke.py` против контейнера, два запуска | Все восемь endpoint; ровно 1 выдача и 1 credit entry, UID 10001 |

Покрыты принадлежность/INVALID/неизвестный счёт, сохранность старого payoutVersion,
неподписанный/закрытый/отсутствующий договор, все связи payout-check, подмена суммы,
несвязанный offer/подпись/хеш документа, повтор после перезапуска, новые и прежние
ключи, конкурентные operationId, Retry-After, ошибка между двумя INSERT,
восстановление тем же ключом после 503/таймаута, недоступная БД и readiness без
соседей, отсутствие DDL/доступа к schema_migrations, строгий JSON и соответствие
каждого HTTP-ответа JSON Schema. Независимое ревью выполнено; найденные дефекты
числовых границ/точности и строгого разбора upstream JSON исправлены с RED→GREEN тестами.

## Запуск с общим DB-профилем

```bash
# Из корня: общий bootstrap сам применяет 001 и seed от владельцев схем.
docker compose -p alfa-db -f database/compose.yaml up -d --wait
docker build -t alfa-disbursement:local services/disbursement

# В отдельном терминале: демонстрационная HTTP-зависимость.
export INTERNAL_API_TOKEN=demo-internal-only
python3 services/disbursement/tests/agreements_stub.py --port 8005

# Docker Desktop: соглашения доступны по host.docker.internal.
docker run --rm --name alfa-disbursement -p 8006:8006 \
  --network alfa-db_database \
  -e 'DATABASE_URL=jdbc:postgresql://postgres:5432/alfa_disbursement?user=disbursement_app&password=demo-disbursement-only' \
  -e PORT=8006 -e INTERNAL_API_TOKEN=demo-internal-only \
  -e AGREEMENTS_URL=http://host.docker.internal:8005 \
  alfa-disbursement:local

# В другом терминале — весь проверочный HTTP-сценарий:
export INTERNAL_API_TOKEN=demo-internal-only
python3 services/disbursement/tests/smoke.py
```

Для Docker Engine Linux добавьте к docker run
`--add-host=host.docker.internal:host-gateway`, либо поместите настоящий agreements
в ту же сеть и укажите его URL. Образ запускается от UID/GID 10001.
Root Compose сервисы не изменены.

Чтобы запустить JAR без Docker, используйте PostgreSQL, доступную с хоста, и
экспортируйте DATABASE_URL/INTERNAL_API_TOKEN/AGREEMENTS_URL, затем:

```bash
java -jar services/disbursement/build/libs/disbursement.jar
```

Для создания собственной отдельной пустой БД администратором (демопароль только
для разработки) пример service-only bootstrap находится в tests/bootstrap.sql.
Он создаёт владельца NOLOGIN и ограниченного app-пользователя. В отдельном
административном соединении с созданной БД выполните `SET ROLE disbursement_owner`,
затем `migrations/001_initial.sql`, `../../database/seeds/disbursement.sql` и
выдайте app-пользователю USAGE схемы и DML таблиц как в database/bootstrap.sh.
001 применяется один раз; новые изменения схемы требуют следующей миграции.
Сервис не запускает DDL и не получает прав владельца.

Стандартный smoke использует ID синтетического подписанного договора из approved.json.
Он повторяем с постоянными ключами: плохой счёт → payoutVersion=2 → выдача →
поиск по operationId → повтор. Для других тестовых состояний stub можно запустить
с `--state READY|CLOSED|404|503` или `--delay-ms`; это CLI тестового инструмента,
в production API переключателей нет. Стаб не реализует создание/подпись договора.

## Ограничения и замечание к общему контракту

JSON Schema PositiveVersion задаёт только нижнюю границу, а принятая SQL-схема
хранит версии в bigint. Например `dataVersion=18446744073709551617` соответствует
формальной схеме JSON, но не представим в БД. Сервис возвращает 422 до записи,
вместо усечения значения до 1. Поддерживаемая верхняя граница — 9223372036854775807.
Общая схема и 001 не изменены; это согласование границы типов требует общего решения.

Production-работа с реальным agreements/applications проверяется при общей интеграции.
Локальные тесты проверяют настоящий HTTP и PostgreSQL с контрактным стабом agreements.
Бухгалтерское зачисление здесь — только append-only credit_entries в своей БД.

## Автоформатирование

Из каталога сервиса: `./gradlew spotlessApply` исправляет Java-исходники и тесты,
`./gradlew spotlessCheck` проверяет стиль. Версии: Spotless 8.0.0,
google-java-format 1.28.0 (Google). `./gradlew build` включает проверку стиля.
Для автоматического форматирования перед коммитом один раз выполнить `npm ci`
в корне репозитория; настройки и общие команды — в корневом README.

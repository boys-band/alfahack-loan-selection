# Agreements — Java 21 / Gradle

Самостоятельный сервис договоров, моделируемой подписи и закрытия. Реализованы все шесть операций `contracts/openapi/agreements.yaml`: create, GET, sign, close, liveness и readiness. Изменяется только собственная БД `alfa_agreements`; статус заявки остается в applications. Другие сервисы и их базы не требуются.

## Сборка

Java 21, Gradle Wrapper 8.14.3, встроенный JDK HTTP server, Jackson 2.19.2, PostgreSQL JDBC 42.7.5. Точные версии, включая транзитивные, закреплены в `gradle.lockfile`; лицензии — `THIRD_PARTY_NOTICES`. Production-код полностью на Java. Текст договора генерирует принадлежащий сервису шаблон `demo-agreement-v1`; внешние шаблоны во время запроса не загружаются.

Из каталога `services/agreements`:

```sh
./gradlew test
./gradlew build
./gradlew installDist
```

Windows: `gradlew.bat test`, `gradlew.bat build`, `gradlew.bat installDist`. `JAVA_HOME` должен указывать на JDK 21. При первом запуске Wrapper загружает Gradle 8.14.3, а сборка — зависимости из Maven Central.

Цели Makefile:

```sh
make test
make test-integration
make build
make docker-build
```

Первые три вызывают `./gradlew test`, `./gradlew integrationTest`, `./gradlew build`; последняя — сборку Docker-образа. Make не требуется для прямого запуска Gradle Wrapper.

## База и запуск

Из корня репозитория:

```sh
docker compose -p alfa-db -f database/compose.yaml up -d --wait
```

Общий bootstrap создает `agreements_owner` с NOLOGIN, отдельную БД и `agreements_app` с DML-правами, применяет принятую `services/agreements/migrations/001_initial.sql` и `database/seeds/agreements.sql`. **001 не изменена, дополнительных миграций для этой реализации не требуется.** В пустой автономной БД порядок создания ролей/БД и применения миграции с `SET ROLE agreements_owner` приведен в `database/bootstrap.sh`. Runtime не применяет DDL и не получает доступ к `schema_migrations`.

Обязательные переменные: `DATABASE_URL`, `INTERNAL_API_TOKEN`; `PORT` по умолчанию 8005. Демообразец — `.env.example`. Он не загружается автоматически. Поддерживаются PostgreSQL URI и JDBC URL с параметрами пользователя/пароля.

Для локального PostgreSQL с доступным портом:

```sh
export DATABASE_URL='postgresql://agreements_app:demo-agreements-only@localhost:5432/alfa_agreements'
export INTERNAL_API_TOKEN='demo-internal-token-change-me'
export PORT=8005
build/install/agreements/bin/agreements
```

PowerShell:

```powershell
$env:DATABASE_URL='postgresql://agreements_app:demo-agreements-only@localhost:5432/alfa_agreements'
$env:INTERNAL_API_TOKEN='demo-internal-token-change-me'
$env:PORT='8005'
.\build\install\agreements\bin\agreements.bat
```

Общий DB-профиль не публикует PostgreSQL на хост. Для него сервис можно запустить в той же Docker-сети, не меняя корневой Compose:

```sh
docker build -t alfa-agreements:local .
docker run --rm --network alfa-db_database -p 127.0.0.1:8005:8005 \
  -e DATABASE_URL='postgresql://agreements_app:demo-agreements-only@postgres:5432/alfa_agreements' \
  -e INTERNAL_API_TOKEN='demo-internal-token-change-me' alfa-agreements:local
```

Dockerfile имеет контекст самого сервиса, собирает на JDK 21 и запускает на JVM 21 без root. Сервер слушает `0.0.0.0:$PORT`. Версии образов зафиксированы тегами и SHA-256 digest; сборка использует Gradle Wrapper с проверкой SHA-256 дистрибутива.

## HTTP-проверка

В каталоге сервиса, после запуска:

```sh
curl -i http://localhost:8005/health/ready
curl -s http://localhost:8005/v2/agreements \
  -H 'Authorization: Bearer demo-internal-token-change-me' \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-agreement-001' \
  --data-binary @src/test/resources/agreement-request.json
```

Фикстура содержит синтетические даты принятия/истечения из контракта за октябрь 2026 года. `acceptedAt` должен быть не позже серверного времени и строго раньше `offer.expiresAt`; после истечения уже принятого предложения создавать и подписывать договор разрешено до собственного signBy.

Первый create — 201. Из его ответа взять `id`, а затем выполнить `GET /v2/agreements/{id}` с внутренним токеном. Для подписи передать:

```json
{
  "applicationId": "<applicationId из ответа>",
  "dataVersion": 1,
  "offerId": "<offer.id из ответа>",
  "offerVersion": 1,
  "customerId": "<customerId из ответа>",
  "documentVersion": 1,
  "documentSha256": "<document.sha256 из ответа>",
  "consent": true
}
```

Тело нужно отправить `POST /v2/agreements/{id}/sign` с `Content-Type: application/json`, внутренним Bearer и новым `Idempotency-Key`. Успех — 200, status SIGNED и одна signature с методом DEMO_CONSENT. GET возвращает тот же подписанный документ для disbursement. Для неподписанного договора close принимает `{"reason":"CANCELLED"}`, `EXPIRED` или `SUPERSEDED`; EXPIRED доступен начиная с signBy.

## Гарантии

Входы строго проверяются по снимку общих DTO в `src/main/resources/schemas.json`: неизвестные поля запрещены, отсутствующее поле отличается от null, UUID lowercase, UTC-время с Z, согласие только true. Собственный валидатор поддерживает используемый общими DTO словарь JSON Schema; незнакомый ключ словаря приводит к ошибке запуска. Тест сверяет снимок с общим YAML и проверяет все семь agreements-примеров. Ожидаемые ошибки — `application/problem+json`, у каждого ответа есть X-Request-Id. При отсутствии ID он генерируется, корректный входной ID воспроизводится. В журнал попадают только requestId, статус и код ошибки, без токенов/снимков/документов.

Создание проверяет applicationId/dataVersion предложения, своевременность acceptedAt и отсутствие будущего принятия. Customer/company принадлежат переданному applications снимку: других источников для независимой проверки владельца/компании контракт не содержит. Сервис не изобретает внешний lookup и не читает чужие БД. Сохраняются исходный запрос, offer и точные условия. Один договор на `(applicationId,dataVersion)`.

documentVersion всегда 1. SHA-256 считается от точных UTF-8 байтов content и независимо вычисляется SQL-триггером. Расхождение прерывает транзакцию. Документ содержит клиента/компанию, принятую версию предложения/продукта, сумму, срок, ставку и платеж; реквизиты зачисления исключены. Изменение settlementAccountId не меняет текст или hash. Срок signBy — ровно 72 часа от серверного createdAt. Старый срок оффера не ограничивает эту подпись.

В транзакции сначала берется блокировка ключа `(applications, POST, конкретный path, key)`. Команды sign/close далее блокируют одну строку договора `FOR UPDATE`. Подписант проверяется до воспроизведения сохраненного ответа. Для sign также проверяются ссылки заявки/предложения, версии, hash, READY и `now < signBy`. SQL дополнительно защищает сроки и неизменяемость. Вставка signature, переход SIGNED и ответ идемпотентности коммитятся вместе с отложенными взаимными FK. При проигрыше гонки ничего частичного не сохраняется.

Повтор того же ключа возвращает исходные статус/тело, включая прежний READY-ответ создания после последующей подписи. Новый ключ с тем же бизнес-входом возвращает 200 и текущий договор; другой вход — конфликт. Повтор sign не создает вторую подпись и работает после deadline, если прежняя подпись уже сохранена. Новый sign с несовпадающими клиентом/документом не воспроизводит чужую подпись. SIGNED нельзя закрыть (`AGREEMENT_ALREADY_SIGNED`), CLOSED нельзя подписать (`AGREEMENT_CLOSED`), при deadline — `DEADLINE_EXPIRED`. Повтор close возвращает сохраненную первоначальную closureReason, даже если новый ключ просит другую причину. GET не переводит READY в CLOSED после срока.

Проверка времени выполняется после получения блокировки строки. Production использует UTC Clock, тесты подставляют часы через Java-зависимость; публичных переключателей времени/исхода нет. Соединение с БД ограничено 2 секундами, SQL — 10, ожидание блокировки — 5. Конкурентная операция может дождаться предыдущей либо получить `409 IDEMPOTENCY_IN_PROGRESS` с `Retry-After: 1`. Недоступность БД — 503; техническая ошибка не подставляет кредитный исход.

Readiness проверяет собственные таблицы/столбцы и защитные триггеры договора/подписи. Seed пустой, обязательных бизнес-строк нет. `schema_migrations` не читается и не открывается runtime-роли. Liveness не требует доступной БД или соседей.

## Проверки и ограничения

`./gradlew integrationTest` создает свой временный PostgreSQL 16.15 в Docker с закрепленным digest из общего DB-профиля. Применяются настоящие 001 и seed через владельца NOLOGIN; HTTP работает через ограниченного `agreements_app`. После тестов удаляется только созданный контейнер.

Без Docker можно использовать установленный PostgreSQL 16:

```powershell
.\gradlew.bat integrationTest '-PpgBin=C:/Program Files/PostgreSQL/16/bin'
```

Тесты создают отдельный кластер в `build/integration/agreements-it-<uuid>` на свободном loopback-порту, останавливают и удаляют его с проверкой границ пути. Существующие кластеры и данные не используются. SQLite/память не подменяют БД, соседи и HTTP-стабы не требуются.

Проверены строгие DTO и примеры, текст/UTF-8 hash, неизменяемость условий, независимый срок договора, граница now == signBy, все причины close, неверные подписанты/версии/ссылки/хеш/consent, auth до replay, повтор после перезапуска и потери ответа, конкурентные create/create и sign/sign, sign/cancel и sign/expiry. Гонки воспроизводятся двумя параллельными HTTP-вызовами с барьером и разными транзакциями PostgreSQL; проверяется ровно один исход и согласованное число signatures. Дополнительно проверены запреты DDL, отключения триггеров и чтения schema_migrations для runtime-роли.

При ревью выполнены `make test`, `make test-integration`, `make build` на Java 21.0.12.1+1 и Gradle Wrapper 8.14.3: 21 unit-проверка и 27 HTTP/PostgreSQL-сценариев без ошибок и пропусков. Использован временный Docker-контейнер PostgreSQL 16.15. Собран Docker-образ; проверены JVM 21, пользователь 65532, liveness 200, readiness без БД 503 и отказ без токена 401. Дополнительные сценарии проверяют точную дробную сумму, переполнение bigint и эквивалентные целые версии `1`/`1.0` при повторе с прежним и новым ключом. Подпись является моделируемым согласием; реальная ЭП и внешний провайдер не входят в архитектуру.

Как и в согласованном SQL, времена сохраняются с микросекундной точностью PostgreSQL; createdAt/signBy/signedAt формируются сервером с той же точностью. PositiveVersion в общем DTO не имеет верхней границы, тогда как SQL использует bigint: значения свыше 9223372036854775807 отвергаются с 422 до записи. Дробная часть проверяется без округления; эквивалентные целые JSON-числа нормализуются до вычисления отпечатка идемпотентности. Общие DTO и 001 не изменялись.

## Автоформатирование

Из каталога сервиса: `./gradlew spotlessApply` исправляет Java-исходники и тесты,
`./gradlew spotlessCheck` проверяет стиль. Версии: Spotless 8.0.0,
google-java-format 1.28.0 (Google). `./gradlew build` включает проверку стиля.
Для автоматического форматирования перед коммитом один раз выполнить `npm ci`
в корне репозитория; настройки и общие команды — в корневом README.

# Verification

Сервис обогащения версии кредитной заявки. Java 21 / JVM 21, Gradle Wrapper
8.14.3, JDK HTTP Server, JDBC PostgreSQL, Jackson и JSON Schema 2020-12.

Сервис получает полный снимок через внутренний API, читает три синтетических
источника из своей БД и сохраняет неизменяемый результат. Соседние сервисы
для запуска не нужны. Скоринг, кредитное решение и статус заявки принадлежат
другим сервисам.

## API

- `POST /v2/verifications`: `201` при создании; повтор ключа возвращает прежний
  статус/JSON; другой ключ с тем же входом и `(applicationId,dataVersion)` — `200`.
- `GET /v2/verifications/{verificationId}`: сохраненный результат или `404`.
- `GET /health/live`: liveness процесса, без обращения в БД.
- `GET /health/ready`: доступность своей схемы, включая миграцию 002, и 15
  ожидаемых записей seed. Таблица `schema_migrations` runtime-пользователю закрыта.

Предметные endpoint требуют `Authorization: Bearer <INTERNAL_API_TOKEN>`.
POST требует `Idempotency-Key` длиной 8–128 символов `[A-Za-z0-9:_.-]` и
`Content-Type: application/json`. `X-Request-Id` — lowercase UUID;
при отсутствии генерируется. Он возвращается на каждом ответе, включая ошибки.
Для нового HTTP-вызова, воспроизводящего старый бизнес-ответ, используется
request ID текущего вызова. Ошибки имеют формат `application/problem+json`.

DTO, nullable, enum и запрет неизвестных полей проверяются по копии
[`contracts/schemas.yaml`](../../contracts/schemas.yaml) в `src/main/resources/`.
Числа проверяются без преобразования в double; поддерживаемый диапазон версии —
положительный PostgreSQL bigint. Отсутствующие обязательные поля отличаются от
null. Повторяющиеся JSON-ключи и несколько JSON-документов дают `400`.
Лимит тела — 1 MiB; одновременно обрабатывается до 128 запросов.

## Запуск в Docker

Нужны Docker Engine и Compose v2; установленная Java не требуется.
Из корня репозитория:

```bash
docker compose -p alfa-verification -f services/verification/compose.yaml up --build -d --wait
curl -fsS http://localhost:8003/health/live
curl -fsS http://localhost:8003/health/ready
```

Service Compose запускает только verification и PostgreSQL 16.15. HTTP
публикуется на `127.0.0.1:8003`; БД не публикуется. Миграции и seed применяются
при первом запуске пустого service volume. Данные сохраняются между перезапусками.
Корневые Compose и файлы соседей не изменены.

Остановка с сохранением данных:

```bash
docker compose -p alfa-verification -f services/verification/compose.yaml down
```

Демонстрационные значения заданы в [.env.example](.env.example). Для их замены
скопируйте файл в `services/verification/.env` и укажите
`--env-file services/verification/.env` перед `-f`. Пароль в URL должен быть
URL-encoded. Пароли существующего volume автоматически не изменяются.
Dockerfile использует зафиксированные по digest JDK 21 и JRE 21, запускает
unit-тесты при сборке и выполняет приложение от UID/GID 10001.

## Конфигурация и запуск локально

| Переменная | Назначение |
| --- | --- |
| `DATABASE_URL` | Обязательный `postgresql://verification_app:password@host:5432/alfa_verification`; также поддерживается JDBC URL |
| `INTERNAL_API_TOKEN` | Обязательный общий секрет внутренних вызовов |
| `PORT` | Порт HTTP, по умолчанию 8003; bind `0.0.0.0` |
| `POSTGRES_PASSWORD` | Пароль администратора только для service Compose/bootstrap |
| `VERIFICATION_DB_PASSWORD` | Пароль `verification_app` только для service Compose/bootstrap |

Для Gradle требуется установленный JDK 21. Например, на macOS с Homebrew:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export DATABASE_URL=postgresql://verification_app:demo-verification-only@localhost:5432/alfa_verification
export INTERNAL_API_TOKEN=demo-internal-verification-only
make -C services/verification run
```

Локальному процессу нужна доступная по TCP БД с примененными миграциями/seed.
В общей Docker-сети DB-профиля используйте hostname `postgres`, а не localhost.
Команды `./gradlew installDist` и `build/install/verification/bin/verification`
внутри каталога сервиса создают и запускают автономный JVM-дистрибутив.

## Миграции и права

`migrations/001_initial.sql` сохранена без изменений. Следующая миграция
`002_source_snapshots.sql` добавляет исходные payload трех источников и точный
JSON результата. Имеющиеся результаты 001 читаются через прежние столбцы;
у новых записей заполнен `result_payload`.

Владелец объектов — `verification_owner NOLOGIN`. Runtime `verification_app`
имеет DML и USAGE, не является владельцем, не может выполнять DDL или
отключать триггеры. Результаты и успешные идемпотентные ответы защищены
исходными триггерами append-only. Runtime не применяет миграции.

Если используется общий DB-профиль, он применяет только 001. Примените 002
от администратора с переходом в роль владельца (из корня репозитория):

```bash
docker compose -p alfa-db -f database/compose.yaml exec -T postgres \
  psql -X -v ON_ERROR_STOP=1 -U postgres -d alfa_verification \
  -c 'SET ROLE verification_owner' \
  -f /schema/services/verification/migrations/002_source_snapshots.sql
```

Миграция выполняется один раз. Зафиксируйте ее checksum в `schema_migrations`
своим deployment runner от владельца. Для нового автономного service volume
[database/init.sh](database/init.sh) выполняет создание ролей, обе миграции,
checksum ledger, seed и grants автоматически. Непустой volume не мигрирует
автоматически при повторном `up`.

## Моки и бизнес-логика

| Источник | Смысл |
| --- | --- |
| REGISTRY | Статус/дата регистрации; синтетические признаки потребности в описании и спорных фактов |
| BKI | Кредитная история 0–1000 и наличие текущей просрочки |
| FNS | Налоговый долг в целых рублях |

Данные взяты из общего seed без изменений; локальная копия — `tests/seed.sql`.
Компании с суффиксами UUID `101` — полный положительный набор, `102` —
просрочка, `103` — требуется `/additionalInfo/businessDescription`, `104` —
спорные факты, `105` — молодой бизнес с БКИ 620. Неактивная компания, просрочка
и налоговый долг при полном наборе фактов возвращаются как `COMPLETE` для decision.

Нехватка описания дает `NEEDS_INFO`; после его передачи в новой версии —
`COMPLETE`. Признак спорных фактов или расхождение даты регистрации с исходным
снимком дают `MANUAL_REVIEW`. Если одновременно не хватает описания, сначала
возвращается `NEEDS_INFO`; предупреждения о споре сохраняются в findings.
Неполный/некорректный источник или отсутствие fixture — `503
DEPENDENCY_UNAVAILABLE`, без успешной записи и без блокировки будущего повтора.

Возраст: разница лет × 12 + разница месяцев − 1, если UTC-день `asOf` меньше
дня регистрации; минимум 0. Проверены месячная граница и 29 февраля.
Регистрация после даты снимка не принимается как достоверный факт.

`staffAttestation` допускается только при `dataVersion > 1`, с обоснованием
не короче 10 символов, корректными UUID и `recordedAt <= asOf`. Подтвержденные
факты получают заново рассчитанный возраст. Первоначальные rawFacts и полные
`source_payloads` остаются неизменными; автор, action ID и причина сохраняются
в `request_payload`. Уточнение сотрудника не устраняет требование описания.

## Повторы и восстановление

Хеш включает HTTP-метод, конкретный путь и канонический JSON запроса с
сортировкой ключей и точными числами. В одной PostgreSQL-транзакции берутся
advisory locks ключа и бизнес-ресурса, сохраняются результат, вход, источники и
успешный ответ. Уникальные ограничения 001 дополняют сериализацию запросов.
Внутренние ключи не истекают. Конкурентный запрос ждет до 3 секунд;
таймаут дает `409 IDEMPOTENCY_IN_PROGRESS` с `Retry-After: 1`.

Авторизация выполняется до чтения повтора. Повтор не обращается к измененным
источникам, не пересчитывает время и не выполняет заново временные guards.
Измененный вход с прежним ключом или `(applicationId,dataVersion)` дает
`409 IDEMPOTENCY_CONFLICT`. Сбой до commit откатывает и результат, и ответ.
Токены, строки подключения и полные финансовые сведения не логируются.

## Проверочный HTTP-сценарий

Из корня репозитория подготовьте полный запрос из согласованного примера:

```bash
python3 services/verification/tests/example_request.py > /tmp/verification-request.json
curl -sS -i http://localhost:8003/v2/verifications \
  -H 'Authorization: Bearer demo-internal-verification-only' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo:verification:001' \
  --data-binary @/tmp/verification-request.json
```

Ожидается `201`, `outcome=COMPLETE`, `confirmed=true`. Тот же запрос/ключ
возвращает прежний `201` и тот же JSON. Новый ключ для того же тела — `200`.
GET по `id` из ответа возвращает сохраненный результат. Без InternalBearer —
`401`. Для независимого нового ресурса измените `applicationId`.

## Проверки

```bash
make -C services/verification test
make -C services/verification test-integration
make -C services/verification build
```

Интеграционный runner требует Python 3 и Docker. Он создает отдельный
PostgreSQL-контейнер со случайным именем, tmpfs и случайным loopback-портом,
применяет рабочий bootstrap/миграции/seed, запускает JVM HTTP-сервер на
случайном порту и удаляет только свой контейнер в `finally`. Runtime тестируется
от `verification_app`; административное соединение используется только тестами.
Нет SQLite, публичных test-флагов или зависимости от соседей.

Результат проверки 24.10.2026: 7 unit-тестов, 14 HTTP/PostgreSQL-тестов,
`make build` проходят. Проверены HTTP-контракт/nullable/ошибки/трассировка,
факты и уточнения, целые числа без потери точности, временные границы,
конкурентные одинаковые и разные ключи, таймаут, rollback/retry, изменение seed,
перезапуск сервера, запрет DDL и изменения неизменяемых результатов.
Отчеты: `build/reports/tests/test/` и `build/reports/tests/integrationTest/`.
Также проверены Docker-сборка, автономный Compose с пустым volume, HTTP
создание/чтение и воспроизведение после перезапуска контейнера на JVM 21.0.9.
Независимое code review не выявило Critical/Important замечаний.

## Расхождения исходных документов

- План называет `asOf` серверным; OpenAPI требует `VerificationRequest.asOf`.
  Приоритет отдан HTTP-контракту: это время доверенного внутреннего снимка от
  applications, оно сохраняется и используется для возраста. Будущее время
  отвергается. `completedAt` устанавливается часами verification.
- `rawFacts` в JSON Schema — нормализованный полный `VerificationFacts`,
  а не произвольный ответ адаптера. Полные исходные ответы, включая флаги
  сценария, дополнительно сохраняются в `source_payloads` миграции 002.
- В `staffAttestation` нет `verificationId` или `applicationId` дела; есть
  `caseActionId`, `staffId`, `reason`, `recordedAt`, `confirmedFacts`. Проверка
  связи с делом/действием остается у доверенного applications/decision.
  Новые поля и обращения к чужой БД не добавлены. Сервис проверяет структуру,
  новую версию, время и сохраняет полный аудит поступившей аттестации.
- В некоторых примерах версии 2 время ответа осталось от версии 1. Сервис
  использует реальное `asOf` запроса и собственное `completedAt`.
- Seed не содержит ИНН/названия/финансовых оборотов источников. Поэтому из
  личности сопоставляется дата регистрации; кредитные финансы не выдумываются
  и остаются исходным снимком для decision. Это ограничение синтетических моков.

## Автоформатирование

Из каталога сервиса: `./gradlew spotlessApply` исправляет Java-исходники и тесты,
`./gradlew spotlessCheck` проверяет стиль. Версии: Spotless 8.0.0,
google-java-format 1.28.0 (Google). `./gradlew build` включает проверку стиля.
Для автоматического форматирования перед коммитом один раз выполнить `npm ci`
в корне репозитория; настройки и общие команды — в корневом README.

# Ревью codex/applications

Дата: 24.10.2026. Исходная реализация: `32dcc0f9e412f40aeb63641eb82eeed26761ff91`. Проверены HTTP/DTO, доступ, идемпотентность, версии данных, SQL/миграции, worker, повторы и гонки, интеграционные тесты и поставка.

## Исправления перед слиянием

- **P1 — обновление catalog блокировало существующие заявки.** Все изменения запрашивали текущий активный продукт и сравнивали его с сохраненной версией. После публикации v2 заявки на v1 получали `409 PRODUCT_NOT_CURRENT` при PATCH, submit, provide-info, change-terms без смены продукта и AMEND_DATA. Теперь текущая активная версия проверяется при создании и фактической смене продукта; остальные команды запрашивают точную сохраненную версию. Два новых HTTP/PostgreSQL-теста сначала воспроизвели ошибку, затем прошли с исправлением. Создание новой заявки на устаревший продукт по-прежнему запрещено.
- **P2 — Docker использовал Java 25 вместо обязательной Java 21.** Сборка переведена на JDK 21, запуск — на JRE 21; оба образа закреплены digest. Полный набор тестов выполнен на Java 21.
- **P2 — отсутствовал обязательный Wrapper.** Добавлен Maven Wrapper 3.3.4 с Maven 3.9.16; Makefile и Dockerfile используют `./mvnw`. Документация и сведения о зависимостях обновлены.

Изменения: [выбор версии продукта](../../services/applications/src/main/java/ru/alfa/applications/Service.java), [регрессионные сценарии](../../services/applications/src/test/java/ru/alfa/applications/ApplicationIT.java), [Dockerfile](../../services/applications/Dockerfile), [запуск и проверки](../../services/applications/README.md).

## Проверка

- Temurin 21.0.12.1+1; Maven 3.9.16 через Wrapper.
- `make -C services/applications test`: 7 тестов, без ошибок и пропусков.
- `make -C services/applications test-integration`: 7 unit + 22 HTTP/PostgreSQL, без ошибок и пропусков. Каждый сценарий использует отдельную PostgreSQL, исходную миграцию/seed, дополнительную owner-миграцию, настоящий Spring API и пять HTTP-стабов.
- `make -C services/applications build`: исполняемый JAR собран.
- `docker build --network=host -t alfa-applications:review services/applications`: образ собран с JDK 21 и Wrapper. Проверены JRE 21, UID 10001, API liveness `200`, readiness без БД `503` и отдельный процесс worker.
- Контракты: шесть OpenAPI, 54 операции, 81 HTTP-пример, восемь отрицательных примеров. Общие контракты и принятая миграция `001_initial.sql` не менялись.

После исправлений блокирующих замечаний не осталось. Сквозной прогон с пятью настоящими соседними сервисами требуется после общей интеграции. Контрактные стабы не проверяют production-реализации соседей.

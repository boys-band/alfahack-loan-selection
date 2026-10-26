# Приемка компонентов

На 24.10.2026 в main приняты frontend и все шесть backend-сервисов. Код PR №1–5 проверен и слит, включая локальную реализацию catalog. Ошибки, найденные при ревью, исправлены до слияния.

| PR | Сервис | Unit | HTTP/PostgreSQL | Отчет |
| --- | --- | ---: | ---: | --- |
| 1 | decision | 104 | 14 | [decision](decision.md) |
| 2 | disbursement | 6 | 37 | [disbursement](disbursement.md) |
| 3 | verification | 7 | 14 | [verification](verification.md) |
| 4 | catalog | 3 | 11 | [catalog](catalog.md) |
| 5 | agreements | 21 | 27 | [agreements](agreements.md) |

Все 244 теста прошли без ошибок и пропусков на Java 21. Собраны и запущены пять Docker-образов; проверены JVM 21, непривилегированный пользователь, health и отказ без токена. Проверка общих контрактов прошла: 6 OpenAPI, 54 операции, 81 HTTP-пример, 8 отклоненных невалидных примеров. GitHub API/CI-метаданные недоступны подключению; результаты локальные. Ранее принятые applications и frontend описаны в [applications](applications.md) и [frontend](frontend.md).

## Сквозная интеграция

Раздел H [распределения работ](../service-assignments.md) выполнен:

1. Корневой `compose.yaml` поднимает шесть сервисов, worker, frontend, PostgreSQL с шестью БД и gateway; наружу открыт только порт 8080. Команды и демосценарии — в [локальном Docker-запуске](../local-docker.md).
2. Полный путь frontend → applications → catalog/verification/decision/agreements/disbursement с выключенными моками проверяется браузерными тестами [e2e-real](../../frontend/e2e-real/README.md) и `scripts/smoke_local.py`.
3. Дозапросы, ручное рассмотрение, пересмотр условий, отказы, отмена и гонка с подписью, потерянные ответы и восстановление покрыты там же; матрица покрытия — в README e2e-real.

Открытые вопросы:

- CI запускает frontend E2E только с контрактными моками; набор на реальном стеке запускается вручную.
- Не согласован отмеченный в [decision](decision.md) крайний случай общего DTO платежа для произвольных синтетических продуктов. После появления админ-публикации продуктов он может проявиться на практике.

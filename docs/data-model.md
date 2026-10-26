# Модель данных v2

Формы JSON находятся в `contracts/schemas.yaml`, переходы — в [workflow](workflow.md). Каждый сервис владеет своей БД; UUID между сервисами не являются межбазовыми FK.

Физические SQL-схемы, ER-диаграммы и порядок применения: [database/README.md](../database/README.md). Каждый сервис получил свою `services/<service>/migrations/001_initial.sql`; ниже описаны предметные сущности и общие инварианты.

| Владелец | Сущность | Данные и уникальность |
| --- | --- | --- |
| applications | Company / CustomerCompany | Профиль компании, счет по умолчанию, связь с клиентом; уникальная пара клиент/компания |
| applications | Application | Текущая версия агрегата, status/reviewStage, ссылки на актуальные снимки/результаты, deadline, technicalError, processing и поручение |
| applications | ApplicationDataVersion | Неизменяемый снимок, компания, продукт, параметры, финансы, дополнения, согласия, автор/причина; unique `(applicationId, dataVersion)` |
| applications | InformationRequest | Версия данных, источник, список полей и причин, createdAt/deadline, OPEN/ANSWERED/CLOSED; не более одного открытого на заявку |
| applications | OfferAcceptance | Точные offerId/offerVersion/dataVersion, условия, acceptedAt; одна актуальная запись на заявку |
| applications | PayoutInstruction | Отдельные реквизиты, payoutVersion и стабильный operationId; после DISBURSING неизменяемы |
| applications | WorkflowJob | Версия данных, шаг, operationId, сохраненное тело/ключ, попытки, состояние, nextAttemptAt, lease/token; уникальность логического запуска этапа |
| applications | TimelineEntry | sequence, from/to status и reviewStage, dataVersion, время, инициатор, причина; unique `(applicationId, sequence)` |
| catalog | ProductVersion / CurrentProduct | Неизменяемые версии; unique `(productId, version)`; указатель текущей активной версии |
| verification | SourceFixture / VerificationResult | Фикстуры по компании/источнику; исходные и подтвержденные факты, запрос/аттестация; unique `(applicationId, dataVersion)` |
| decision | Assessment | Версия данных, факты, policyVersion, оцененные кандидаты и факторы риска; уникальная автоматическая оценка версии |
| decision | Decision / Offer | Неизменяемое автоматическое или ручное решение; unique `(applicationId, dataVersion, kind)`; offerId и offerVersion |
| decision | ManualReviewCase | Версия данных, причина входа, проверка, необязательная оценка, состояние OPEN/RESOLVED/CLOSED; unique `(applicationId, dataVersion)` |
| decision | ManualReviewAction | Сотрудник, время, обоснование, действие, выбранный кандидат/дозапрос/поправка; unique `(caseId, actionId)`; один разрешающий результат дела |
| agreements | Agreement | Версия данных, клиент/компания, принятое предложение, documentVersion, текст/хеш, signBy, READY/SIGNED/CLOSED; unique `(applicationId, dataVersion)` |
| agreements | Signature | Клиент, agreementId/documentVersion/SHA-256, время; unique agreementId; атомарна с SIGNED |
| disbursement | AccountFixture / PayoutCheck | Принадлежность/готовность демосчета; проверка unique `(applicationId, dataVersion, payoutVersion)` |
| disbursement | Disbursement / CreditEntry | Операция, договор, счет, сумма/время; отдельные unique applicationId, agreementId, operationId; одна credit entry на выдачу |
| Все изменяющие сервисы | IdempotencyRecord | Caller, метод, путь, ключ, requestHash, успешный ответ; unique `(caller, method, path, key)` |

Результаты старых версий не удаляются и не помечаются новыми. Актуальность предложения/дела для клиентской команды определяется агрегатом applications, а не наличием ресурса в соседней БД. Старые открытые дела/неподписанные договоры закрываются отдельными идемпотентными заданиями; ошибка такой очистки не возобновляет отмененную заявку.

## Инварианты

- Черновик: dataVersion=0; первая отправка создает снимок 1. Любая корректировка после отправки создает новый снимок, без правок старого.
- reviewStage заполнен только при IN_REVIEW. Вход начинается с ENRICHMENT, переход в SCORING записывается в историю отдельно.
- Скоринг и проверки соответствуют текущей dataVersion; без подтвержденных фактов ручное одобрение запрещено.
- Сотрудник не задается браузером. Любое действие сохраняет его ID, время, обоснование и версию данных.
- Информация дополняется до deadline; на границе now == deadline действие запрещено. Принятие/истечение/новая версия сериализуются в агрегате.
- Подпись относится к точному договору/версии/хешу. Из READY возможен только один победивший переход SIGNED или CLOSED.
- Реквизиты поручения не входят в подписанные кредитные условия. Их изменение в SIGNED увеличивает payoutVersion и требует новой проверки, но не нового скоринга.
- В DISBURSING поручение и operationId заморожены. Неизвестный исход сначала разыскивается; повтор не создает вторую credit entry.
- Ошибка и retry сохраняют бизнес-статус/вложенный статус, dataVersion и ключ. Результаты старого задания, версии или отмененной заявки не применяются.
- Завершение worker проверяет lease/token и ожидаемое состояние; переход, история и следующее задание атомарны.
- Успешный ответ идемпотентности фиксируется в той же транзакции, что бизнес-ресурс. После потерянного ответа повтор возвращает прежний результат.

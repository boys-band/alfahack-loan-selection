import type {
  AdditionalInfo,
  DecisionResult,
  RiskAssessment,
  SubmittedData,
  VerificationFacts,
  VerificationResult,
} from '../api/generated';
import { Fragment } from 'react';
import { dateTime, money } from '../core/state';

export function AdditionalInformation({ info }: { info: AdditionalInfo }) {
  if (!Object.keys(info).length) return null;
  return (
    <div>
      <h3>Дополнительные сведения</h3>
      <dl className="facts">
        {info.businessDescription && (
          <>
            <dt>Описание бизнеса</dt>
            <dd>{info.businessDescription}</dd>
          </>
        )}
        {info.revenueExplanation && (
          <>
            <dt>Пояснение выручки и расходов</dt>
            <dd>{info.revenueExplanation}</dd>
          </>
        )}
        {info.refinancedDebtRub !== undefined && (
          <>
            <dt>Рефинансируемый долг</dt>
            <dd>{money(info.refinancedDebtRub)}</dd>
          </>
        )}
      </dl>
    </div>
  );
}

const factFields: {
  key: keyof VerificationFacts;
  label: string;
  format: (facts: VerificationFacts) => string;
}[] = [
  {
    key: 'registryStatus',
    label: 'Статус компании',
    format: (facts) => (facts.registryStatus === 'ACTIVE' ? 'Действующая' : 'Недействующая'),
  },
  {
    key: 'registryRegisteredAt',
    label: 'Дата регистрации',
    format: (facts) =>
      new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC' }).format(
        new Date(facts.registryRegisteredAt),
      ),
  },
  {
    key: 'businessAgeMonths',
    label: 'Возраст бизнеса',
    format: (facts) => `${facts.businessAgeMonths} мес.`,
  },
  {
    key: 'creditHistoryScore',
    label: 'Балл кредитной истории',
    format: (facts) => String(facts.creditHistoryScore),
  },
  {
    key: 'hasCurrentOverdue',
    label: 'Текущая просрочка',
    format: (facts) => (facts.hasCurrentOverdue ? 'Есть' : 'Нет'),
  },
  { key: 'taxDebtRub', label: 'Налоговый долг', format: (facts) => money(facts.taxDebtRub) },
];

export function VerifiedFacts({ facts }: { facts: VerificationFacts | null }) {
  if (!facts) return <p>Сведения пока не получены.</p>;
  return (
    <dl className="facts">
      {factFields.map((field) => (
        <Fragment key={field.key}>
          <dt>{field.label}</dt>
          <dd>{field.format(facts)}</dd>
        </Fragment>
      ))}
    </dl>
  );
}

export function VerificationDetails({
  verification,
  staff = false,
}: {
  verification: VerificationResult;
  staff?: boolean;
}) {
  return (
    <div>
      <h3>Подтверждённые сведения и расхождения</h3>
      <p>{verification.confirmed ? 'Сведения подтверждены.' : 'Сведения требуют уточнения.'}</p>
      {staff && (
        <div className="table-scroll">
          <table className="staff-data-table">
            <caption>Сведения источников и подтверждённые факты</caption>
            <thead>
              <tr>
                <th scope="col">Показатель</th>
                <th scope="col">Сведения источников</th>
                <th scope="col">Подтверждённые сведения</th>
              </tr>
            </thead>
            <tbody>
              {factFields.map((field) => {
                const changed =
                  verification.rawFacts &&
                  verification.facts &&
                  verification.rawFacts[field.key] !== verification.facts[field.key];
                return (
                  <tr key={field.key}>
                    <th scope="row">{field.label}</th>
                    <td>
                      {verification.rawFacts ? field.format(verification.rawFacts) : 'Нет сведений'}
                    </td>
                    <td>
                      {verification.facts ? field.format(verification.facts) : 'Нет сведений'}
                      {changed && <span className="fact-change">Уточнено</span>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {staff && verification.findings.length > 0 && (
        <ul>
          {verification.findings.map((finding, index) => (
            <li key={index}>{finding.message}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function SavedDataDetails({
  data,
  staff = false,
}: {
  data: SubmittedData;
  staff?: boolean;
}) {
  return (
    <>
      <AdditionalInformation info={data.additionalInfo} />
      <h3>Согласия</h3>
      <p>Дано согласие на обработку персональных данных.</p>
      <p>Дано согласие на запрос кредитной истории.</p>
      <p>Согласия приняты {dateTime(data.consents.acceptedAt)}.</p>
      {staff && data.staffAttestation && (
        <>
          <h3>Уточнение сотрудника</h3>
          <p>{data.staffAttestation.reason}</p>
          <p>{dateTime(data.staffAttestation.recordedAt)}</p>
          <VerifiedFacts facts={data.staffAttestation.confirmedFacts} />
        </>
      )}
    </>
  );
}

export function EvaluatedConditions({ decision }: { decision: DecisionResult }) {
  return (
    <div>
      <h3>Оценённые условия</h3>
      {decision.evaluatedTerms.length === 0 ? (
        <p>Оценка условий пока не завершена.</p>
      ) : (
        <div className="table-scroll">
          <table className="staff-data-table">
            <caption>Сравнение оценённых условий</caption>
            <thead>
              <tr>
                <th scope="col">Вариант</th>
                <th scope="col">Условия</th>
                <th scope="col">Платёж в месяц</th>
                <th scope="col">Оценка риска</th>
                <th scope="col">Долговая нагрузка</th>
                <th scope="col">Свободный поток</th>
                <th scope="col">Результат</th>
              </tr>
            </thead>
            <tbody>
              {decision.evaluatedTerms.map((terms, index) => (
                <tr key={terms.id}>
                  <th scope="row">
                    {index + 1}
                    {terms.id === decision.selectedTermsId && (
                      <span className="candidate-selected">Выбран</span>
                    )}
                  </th>
                  <td>
                    <strong>{money(terms.amountRub)}</strong>
                    <small>{terms.termMonths} мес.</small>
                    <small>{terms.annualRateBps / 100}% годовых</small>
                  </td>
                  <td>{money(terms.monthlyPaymentRub)}</td>
                  <td>{terms.risk.score} баллов</td>
                  <td>
                    {terms.risk.debtServiceRatioBps === null
                      ? 'Не рассчитана'
                      : `${terms.risk.debtServiceRatioBps / 100}%`}
                  </td>
                  <td>{money(terms.risk.freeCashFlowRub)}</td>
                  <td>
                    <strong>{terms.eligible ? 'Допустим' : 'Не подходит'}</strong>
                    {terms.reasons.map((reason, i) => (
                      <small key={i}>{reason.message}</small>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export function RiskOverview({ risk }: { risk: RiskAssessment }) {
  const maximum = Math.max(1, ...risk.factors.map((factor) => factor.points));
  return (
    <div className="risk">
      <h3>Факторы оценки</h3>
      <p>
        Оценка риска: {risk.score} баллов ·{' '}
        {{ LOW: 'низкий', MEDIUM: 'средний', HIGH: 'высокий' }[risk.grade]} риск
      </p>
      <figure className="risk-chart" aria-label="Вклад факторов в оценку риска">
        <figcaption>Вклад факторов в баллах</figcaption>
        <ul>
          {risk.factors.map((factor) => (
            <li key={factor.code}>
              <div className="risk-factor-label">
                <span>{factor.description}</span>
                <strong>{factor.points} баллов</strong>
              </div>
              <meter min={0} max={maximum} value={factor.points} aria-label={factor.description} />
            </li>
          ))}
        </ul>
      </figure>
      <p className="muted">Баллы риска не являются вероятностью одобрения.</p>
    </div>
  );
}

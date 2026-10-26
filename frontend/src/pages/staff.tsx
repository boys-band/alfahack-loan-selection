import { ValidatedForm, Input, Select, Textarea } from '../components/validated-form';
import { useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { ApplicationCard } from './application';
import { useSession } from '../core/session';
import { useResource } from '../core/hooks';
import type {
  Application,
  ManualActionType,
  ManualReviewCase,
  StaffReviewActionRequest,
  VerificationFacts,
} from '../api/generated';
import { fieldsOf, changesOf, fieldLabels } from '../core/forms';
import { DataFields, useDirty } from '../components/fields';
import { useMutation } from '../components/commands';
import { ErrorBox, Loading } from '../components/common';
import { EvaluatedConditions } from '../components/application-data';
import { money } from '../core/state';
const names: Record<ManualActionType, string> = {
  APPROVE: 'Одобрить',
  REJECT: 'Отказать',
  REQUEST_INFO: 'Запросить сведения',
  AMEND_DATA: 'Уточнить данные',
};
export function StaffPage() {
  const { id = '' } = useParams();
  return (
    <ApplicationCard
      key={id}
      id={id}
      staffChildren={(a, etag, refresh, stale) =>
        a.status === 'MANUAL_REVIEW' &&
        a.manualReview?.status === 'OPEN' &&
        a.manualReview.dataVersion === a.dataVersion ? (
          <StaffCase app={a} etag={etag} refresh={refresh} stale={stale} />
        ) : null
      }
    />
  );
}
function StaffCase({
  app: a,
  etag,
  refresh,
  stale,
}: {
  app: Application;
  etag: string | null;
  refresh: () => Promise<void>;
  stale: boolean;
}) {
  const { api } = useSession();
  const r = useResource(
    async (signal) =>
      (await api!.call('getStaffManualReview', { applicationId: a.id }, undefined, signal)).data,
    [api, a.id, a.dataVersion, a.manualReview?.id, a.manualReview?.status],
  );
  const c = r.data;
  return (
    <section className="panel">
      <span className="eyebrow">БАНКОВСКОЕ РЕШЕНИЕ</span>
      <h2>Ручное дело</h2>
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : (
        c &&
        c.status === 'OPEN' &&
        c.dataVersion === a.dataVersion && (
          <>
            <p>{c.reason}</p>
            <p className="muted small">Дело открыто · версия данных {c.dataVersion}</p>
            {a.decision && <EvaluatedConditions decision={a.decision} />}
            <StaffForm
              key={c.id + ':' + c.dataVersion}
              app={a}
              review={c}
              etag={etag}
              refresh={refresh}
              stale={stale}
            />
          </>
        )
      )}
    </section>
  );
}
function StaffForm({
  app: a,
  review: c,
  etag,
  refresh,
  stale,
}: {
  app: Application;
  review: ManualReviewCase;
  etag: string | null;
  refresh: () => Promise<void>;
  stale: boolean;
}) {
  const { commands } = useSession();
  const [action, setAction] = useState<ManualActionType | ' '>(' '),
    [reason, setReason] = useState(''),
    [confirmedReject, setConfirmedReject] = useState(false),
    [terms, setTerms] = useState(''),
    [fields, setFields] = useState(() => fieldsOf(a)),
    [requirements, setRequirements] = useState([
      { path: '/additionalInfo/businessDescription', message: '' },
    ]),
    [resolveFacts, setResolveFacts] = useState(false),
    [facts, setFacts] = useState<VerificationFacts>(
      () =>
        a.verification?.facts ||
        a.verification?.rawFacts || {
          registryStatus: 'ACTIVE',
          registryRegisteredAt: a.company.registeredAt,
          businessAgeMonths: 0,
          creditHistoryScore: 0,
          hasCurrentOverdue: false,
          taxDebtRub: 0,
        },
    );
  const mutation = useMutation(a.id, refresh);
  useDirty(!!reason);
  const current =
    a.status === 'MANUAL_REVIEW' &&
    c.status === 'OPEN' &&
    c.id === a.manualReview?.id &&
    c.dataVersion === a.dataVersion;
  const allowed = c.allowedActions
    .filter((x) => a.manualReview?.allowedActions.includes(x))
    .filter(
      (x) =>
        x !== 'APPROVE' ||
        (!!c.assessmentId &&
          !!a.decision?.assessmentId &&
          a.verification?.confirmed === true &&
          a.verification.dataVersion === a.dataVersion &&
          a.decision.dataVersion === a.dataVersion),
    );
  const disabled =
    stale ||
    !etag ||
    !current ||
    !!commands!.get(a.id) ||
    mutation.busy ||
    !allowed.includes(action as ManualActionType);
  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      if (disabled) throw new Error('Дело изменилось. Обновите данные перед решением.');
      const body: StaffReviewActionRequest = {
        action: action as ManualActionType,
        reason,
        termsId: null,
        requirements: [],
        changes: null,
        factsResolution: null,
      };
      if (action === 'APPROVE') body.termsId = terms;
      if (action === 'REQUEST_INFO') body.requirements = requirements;
      if (action === 'REJECT' && !confirmedReject) throw new Error('Подтвердите кредитный отказ');
      if (action === 'AMEND_DATA') {
        const changes = changesOf(fields, a);
        body.changes = Object.keys(changes).length ? changes : null;
        if (resolveFacts)
          body.factsResolution = { verificationId: c.verificationId, confirmedFacts: facts };
      }
      const r = await mutation.run(
        'submitStaffReviewAction',
        { applicationId: a.id },
        body,
        etag || undefined,
      );
      if (r) {
        setReason('');
        setAction(' ');
      }
    } catch (e) {
      mutation.setError(e);
    }
  }
  return (
    <ValidatedForm onSubmit={submit}>
      {!current && (
        <p className="alert">
          Это дело закрыто или относится к прежней версии. Решение недоступно.
        </p>
      )}
      <div className="field">
        <label htmlFor="staff-action">Действие сотрудника</label>
        <Select
          id="staff-action"
          value={action}
          disabled={!current}
          onChange={(e) => {
            setAction(e.target.value as ManualActionType);
            setConfirmedReject(false);
          }}
        >
          <option value=" ">Выберите действие</option>
          {allowed.map((x) => (
            <option key={x} value={x}>
              {names[x]}
            </option>
          ))}
        </Select>
      </div>
      {action === 'APPROVE' && (
        <div className="field">
          <label htmlFor="terms">Оценённые допустимые условия</label>
          <Select id="terms" required value={terms} onChange={(e) => setTerms(e.target.value)}>
            <option value="">Выберите кандидата</option>
            {a.decision?.evaluatedTerms
              .filter((t) => t.eligible)
              .map((t) => (
                <option key={t.id} value={t.id}>
                  {money(t.amountRub)} · {t.termMonths} мес. · {t.annualRateBps / 100}% · платёж{' '}
                  {money(t.monthlyPaymentRub)}
                </option>
              ))}
          </Select>
        </div>
      )}
      {action === 'REQUEST_INFO' && (
        <div className="requirements-form">
          <h3>Что необходимо уточнить</h3>
          {requirements.map((r, i) => (
            <div className="form-grid" key={i}>
              <div className="field">
                <label htmlFor={'req-path-' + i}>Поле требования {i + 1}</label>
                <Select
                  id={'req-path-' + i}
                  value={r.path}
                  onChange={(e) =>
                    setRequirements(
                      requirements.map((x, n) => (n === i ? { ...x, path: e.target.value } : x)),
                    )
                  }
                >
                  {Object.entries(fieldLabels).map(([p, label]) => (
                    <option key={p} value={p}>
                      {label}
                    </option>
                  ))}
                </Select>
              </div>
              <div className="field">
                <label htmlFor={'req-message-' + i}>Сообщение {i + 1}</label>
                <Input
                  id={'req-message-' + i}
                  required
                  value={r.message}
                  onChange={(e) =>
                    setRequirements(
                      requirements.map((x, n) => (n === i ? { ...x, message: e.target.value } : x)),
                    )
                  }
                />
              </div>
              {requirements.length > 1 && (
                <button
                  type="button"
                  className="text-button"
                  onClick={() => setRequirements(requirements.filter((_, n) => n !== i))}
                >
                  Удалить требование {i + 1}
                </button>
              )}
            </div>
          ))}
          <button
            className="secondary"
            type="button"
            onClick={() =>
              setRequirements([
                ...requirements,
                { path: '/additionalInfo/revenueExplanation', message: '' },
              ])
            }
          >
            ＋ Добавить требование
          </button>
        </div>
      )}
      {action === 'AMEND_DATA' && (
        <>
          <p className="alert">
            Уточнение создаст новую версию данных и запустит повторные проверки. Исходные факты
            сохранятся в истории.
          </p>
          <DataFields
            purposeCodes={a.product.purposeCodes}
            fields={fields}
            setFields={setFields}
            requiredPaths={Object.keys(fieldLabels).filter(
              (path) => !path.startsWith('/additionalInfo/'),
            )}
          />
          <label className="check">
            <Input
              type="checkbox"
              checked={resolveFacts}
              onChange={(e) => setResolveFacts(e.target.checked)}
            />
            Подтвердить уточнённые факты по текущему делу
          </label>
          {resolveFacts && (
            <div className="form-grid">
              <div className="field">
                <label htmlFor="registry">Статус регистрации</label>
                <Select
                  id="registry"
                  value={facts.registryStatus}
                  onChange={(e) =>
                    setFacts({
                      ...facts,
                      registryStatus: e.target.value as VerificationFacts['registryStatus'],
                    })
                  }
                >
                  <option value="ACTIVE">Активна</option>
                  <option value="INACTIVE">Неактивна</option>
                </Select>
              </div>
              <div className="field">
                <label htmlFor="registered">Подтверждённая дата регистрации</label>
                <Input
                  id="registered"
                  required
                  type="date"
                  value={facts.registryRegisteredAt}
                  onChange={(e) => setFacts({ ...facts, registryRegisteredAt: e.target.value })}
                />
              </div>
              {(
                [
                  ['businessAgeMonths', 'Возраст бизнеса, мес.'],
                  ['creditHistoryScore', 'Балл кредитной истории'],
                  ['taxDebtRub', 'Налоговый долг, ₽'],
                ] as const
              ).map(([key, label]) => (
                <div className="field" key={key}>
                  <label htmlFor={key}>{label}</label>
                  <Input
                    id={key}
                    type="number"
                    required
                    min={0}
                    max={key === 'creditHistoryScore' ? 1000 : 1000000000000}
                    step={1}
                    value={facts[key]}
                    onChange={(e) =>
                      setFacts({
                        ...facts,
                        [key]: e.target.value === '' ? NaN : Number(e.target.value),
                      })
                    }
                  />
                </div>
              ))}
              <label className="check">
                <Input
                  type="checkbox"
                  checked={facts.hasCurrentOverdue}
                  onChange={(e) => setFacts({ ...facts, hasCurrentOverdue: e.target.checked })}
                />
                Есть текущая просрочка
              </label>
            </div>
          )}
        </>
      )}
      {action !== ' ' && (
        <>
          <div className="field">
            <label htmlFor="reason">Обоснование решения (10–2000 символов)</label>
            <Textarea
              id="reason"
              required
              minLength={10}
              maxLength={2000}
              rows={4}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
          {action === 'REJECT' && (
            <label className="check">
              <Input
                type="checkbox"
                required
                checked={confirmedReject}
                onChange={(e) => setConfirmedReject(e.target.checked)}
              />
              Подтверждаю кредитный отказ с указанным обоснованием
            </label>
          )}
          <ErrorBox error={mutation.error} />
          <button
            className="primary"
            disabled={
              disabled || reason.trim().length < 10 || (action === 'REJECT' && !confirmedReject)
            }
          >
            Сохранить решение сотрудника
          </button>
        </>
      )}
    </ValidatedForm>
  );
}

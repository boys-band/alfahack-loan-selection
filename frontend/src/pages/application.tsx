import { ValidatedForm, Input, Select, Textarea } from '../components/validated-form';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { Application, Agreement } from '../api/generated';
import { useSession } from '../core/session';
import { useApplication, useClock, useResource, useUnauthorized } from '../core/hooks';
import {
  money,
  dateTime,
  deadline,
  purposeLabels,
  statusLabels,
  workflowText,
} from '../core/state';
import { fieldsOf, changesOf, draftValues, fieldLabels, type Fields } from '../core/forms';
import { DataFields, useDirty } from '../components/fields';
import { CommandBanner, useMutation } from '../components/commands';
import { Deadline, ErrorBox, Loading, Modal, Status, errorsForFields } from '../components/common';
import {
  AdditionalInformation,
  VerificationDetails,
  RiskOverview,
} from '../components/application-data';
import { ApiError } from '../api/client';
import { approvalLabel, currentDecision, useStaffNavigation } from '../core/staff-navigation';
export function ApplicationPage() {
  const { id = '' } = useParams();
  return <ApplicationCard key={id} id={id} />;
}
export function ApplicationCard({
  id,
  agreementPage = false,
  staffChildren,
}: {
  id: string;
  agreementPage?: boolean;
  staffChildren?: (
    app: Application,
    etag: string | null,
    refresh: () => Promise<void>,
    stale: boolean,
  ) => React.ReactNode;
}) {
  const { data, error, refreshing, refresh } = useApplication(id);
  const staffNavigation = useStaffNavigation(id);
  const { session, commands } = useSession();
  const { now, approximate } = useClock(data?.date);
  const at = data ? deadline(data.data) : null;
  const lastExpired = useRef<string>('');
  useEffect(() => {
    if (at && now >= Date.parse(at) && lastExpired.current !== at) {
      lastExpired.current = at;
      void refresh();
    }
  }, [at, now, refresh]);
  if (!data)
    return (
      <>
        <ErrorBox error={error} retry={() => void refresh()} />
        {!error && <Loading />}
      </>
    );
  const a = data.data,
    staff = session!.role === 'staff';
  const expired = !!at && now >= Date.parse(at);
  const blocked = !!error || !!commands!.get(id) || !data.etag;
  return (
    <>
      <Link className="back" to={staff ? staffNavigation.backTo : '/applications'}>
        ←{' '}
        {staff
          ? staffNavigation.backTo.startsWith('/staff/manual-reviews')
            ? 'Очередь рассмотрения'
            : 'Все заявки'
          : 'Мои заявки'}
      </Link>
      <div className="page-title">
        <div>
          <span className="eyebrow">
            ЗАЯВКА № {id.slice(-8)} · ВЕРСИЯ ДАННЫХ {a.dataVersion}
          </span>
          <h1>{a.company.legalName}</h1>
          <p>
            {a.product.name} · версия {a.product.version}{' '}
            <span className="muted">· ИНН {a.company.inn}</span>
          </p>
        </div>
        <Status app={a} />
      </div>
      <div className="card-links">
        <Link
          to={
            staff
              ? staffNavigation.base + '/history' + staffNavigation.suffix
              : '/applications/' + id + '/history'
          }
        >
          История и версии ↗
        </Link>
        {a.agreement && (
          <Link
            to={
              staff
                ? staffNavigation.base + '/agreement' + staffNavigation.suffix
                : '/applications/' + id + '/agreement'
            }
          >
            Документ договора ↗
          </Link>
        )}
        <button className="text-button" onClick={() => void refresh()} disabled={refreshing}>
          ↻ {refreshing ? 'Обновляем…' : 'Обновить'}
        </button>
      </div>
      <ErrorBox error={error} retry={() => void refresh()} />
      {!!error && (
        <p className="stale">
          Показаны последние полученные данные. Действия приостановлены до обновления.
        </p>
      )}
      <CommandBanner scope={id} refresh={refresh} />
      <div className="application-grid">
        <div className="main-column">
          <div className="panel application-summary">
            <div className="section-heading">
              <h2>{statusLabels[a.status]}</h2>
              <span className="muted small">Обновлено {dateTime(a.updatedAt)}</span>
            </div>
            <p>
              {!staff && a.status === 'REJECTED'
                ? 'По результатам рассмотрения кредит не одобрен.'
                : workflowText(a.nextActionHint)}
            </p>
            <div className="metric-row">
              <div>
                <span>Запрошенная сумма</span>
                <strong>{money(a.amountRub)}</strong>
              </div>
              <div>
                <span>Срок</span>
                <strong>
                  {a.termMonths ?? '—'} <small>мес.</small>
                </strong>
              </div>
              <div>
                <span>Цель</span>
                <strong className="metric-text">
                  {a.purpose ? purposeLabels[a.purpose] : 'Не указана'}
                </strong>
              </div>
            </div>
            {a.processing && (
              <p className="processing">
                <span className={a.processing.state === 'PAUSED' ? '' : 'spinner'} />{' '}
                {a.processing.state === 'PAUSED'
                  ? 'Обработка приостановлена'
                  : 'Выполняется обработка'}
              </p>
            )}
            {a.technicalError && (
              <div className="alert">
                <strong>Техническое ожидание</strong>
                <p>Не удалось завершить обработку. Повторите её позже.</p>
              </div>
            )}
            {a.closureReason && (
              <p className="muted">
                Причина завершения:{' '}
                {
                  {
                    INFO_TIMEOUT: 'Истёк срок ответа на дозапрос',
                    OFFER_TIMEOUT: 'Истёк срок принятия предложения',
                    SIGNATURE_TIMEOUT: 'Истёк срок подписи',
                    CUSTOMER_CANCELLED: 'Отменено по запросу клиента',
                    CREDIT_REJECTED: 'Кредитное решение — отказ',
                  }[a.closureReason]
                }
              </p>
            )}
          </div>
          {!staff && a.status === 'DRAFT' && (
            <DraftForm key={id} app={a} etag={data.etag} blocked={blocked} refresh={refresh} />
          )}
          {a.informationRequest && a.status === 'NEEDS_INFO' && (
            <section className="panel">
              <h2>Запрос дополнительных сведений</h2>
              <p className="muted">
                Запрошены уточнения для версии данных {a.informationRequest.dataVersion}
              </p>
              <ul className="requirements">
                {a.informationRequest.requirements.map((r, i) => (
                  <li key={i}>
                    <strong>{fieldLabels[r.path] || 'Дополнительные сведения'}</strong>
                    <p>{r.message}</p>
                    {!fieldLabels[r.path] && (
                      <p>Непокрытое требование — требуется уточнение сотрудника.</p>
                    )}
                  </li>
                ))}
              </ul>
              {!staff && a.availableActions.includes('PROVIDE_INFO') && (
                <ChangesForm
                  key={a.informationRequest.id}
                  mode="info"
                  app={a}
                  etag={data.etag}
                  refresh={refresh}
                  blocked={blocked || expired}
                />
              )}
            </section>
          )}
          {staff && (
            <section className="panel">
              <h2>Текущее решение</h2>
              <p>{approvalLabel(a)}</p>
              {a.acceptedOffer && (
                <p>Предложение принято {dateTime(a.acceptedOffer.acceptedAt)}.</p>
              )}
            </section>
          )}
          {currentDecision(a) && <Decision app={a} staff={staff} />}
          {a.agreement && (agreementPage || a.status === 'AWAITING_SIGNATURE') && (
            <AgreementView
              app={a}
              etag={data.etag}
              blocked={blocked || expired || staff}
              refresh={refresh}
            />
          )}
          {a.disbursement && (
            <section className="panel success-panel">
              <span className="eyebrow">✓ ВЫДАЧА ПОДТВЕРЖДЕНА</span>
              <h2>{money(a.disbursement.amountRub)} зачислено</h2>
              <dl className="facts">
                <dt>Дата</dt>
                <dd>{dateTime(a.disbursement.executedAt)}</dd>
                <dt>Счет</dt>
                <dd>{a.disbursement.settlementAccountId}</dd>
                <dt>Операция</dt>
                <dd>{a.disbursement.operationId}</dd>
              </dl>
            </section>
          )}
          {a.payoutCheck?.outcome === 'NEEDS_CORRECTION' && (
            <section className="panel">
              <h2>Нужно исправить реквизиты</h2>
              <p>
                {a.payoutCheck.reasonCodes
                  .map((c) =>
                    c === 'ACCOUNT_INVALID'
                      ? 'Счет не прошёл проверку'
                      : 'Счет не принадлежит компании',
                  )
                  .join('. ')}
              </p>
              {!staff && a.availableActions.includes('UPDATE_PAYOUT_ACCOUNT') && (
                <PayoutForm app={a} etag={data.etag} blocked={blocked} refresh={refresh} />
              )}
            </section>
          )}
          {staffChildren?.(a, data.etag, refresh, !!error)}
          <section className="panel">
            <h2>Сведения заявки</h2>
            <dl className="facts">
              <dt>Месячная выручка</dt>
              <dd>{money(a.financialData?.monthlyRevenueRub)}</dd>
              <dt>Месячные расходы</dt>
              <dd>{money(a.financialData?.monthlyExpensesRub)}</dd>
              <dt>Текущие платежи</dt>
              <dd>{money(a.financialData?.existingMonthlyDebtPaymentsRub)}</dd>
            </dl>
            {a.additionalInfo.businessDescription && <p>{a.additionalInfo.businessDescription}</p>}
            {a.additionalInfo.revenueExplanation && <p>{a.additionalInfo.revenueExplanation}</p>}
            {a.additionalInfo.refinancedDebtRub !== undefined && (
              <p>Рефинансируемый долг: {money(a.additionalInfo.refinancedDebtRub)}</p>
            )}
            {staff && a.verification && <VerificationDetails verification={a.verification} staff />}
          </section>
        </div>
        <aside className="side-column">
          {at && (
            <section className="panel">
              <Deadline at={at} now={now} approximate={approximate} />
            </section>
          )}
          {!staff && (
            <ActionPanel
              app={a}
              etag={data.etag}
              blocked={blocked}
              canInterrupt={
                !error &&
                !!data.etag &&
                commands!.get(id)?.state === 'accepted' &&
                !commands!.busy(id)
              }
              expired={expired}
              refresh={refresh}
            />
          )}
          <div className="panel help-panel">
            <span aria-hidden>◎</span>
            <h3>Всё под контролем</h3>
            <p>
              Статус и доступные действия обновляются автоматически. Вы можете вернуться к заявке
              позже.
            </p>
            <small>Подпись и зачисление моделируются.</small>
          </div>
        </aside>
      </div>
    </>
  );
}
function DraftForm({
  app: a,
  etag,
  blocked,
  refresh,
}: {
  app: Application;
  etag: string | null;
  blocked: boolean;
  refresh: () => Promise<void>;
}) {
  const { api } = useSession();
  const unauthorized = useUnauthorized();
  const currentProduct = useResource(
    async (signal) =>
      (await api!.call('getProduct', { productId: a.product.id }, undefined, signal)).data,
    [api, a.product.id, a.product.version],
  );
  const [checkingProduct, setCheckingProduct] = useState(false);
  const [fields, setFields] = useState(() => fieldsOf(a)),
    [dirty, setDirty] = useState(false),
    [saved, setSaved] = useState(false),
    [personal, setPersonal] = useState(false),
    [bureau, setBureau] = useState(false),
    [confirm, setConfirm] = useState(false),
    [confirmedEtag, setConfirmedEtag] = useState<string | null>(null);
  useDirty(dirty);
  const mutation = useMutation(a.id, refresh);
  useEffect(() => {
    if (!dirty) setFields(fieldsOf(a));
  }, [a.version, dirty]);
  const fieldErrors = errorsForFields(mutation.error);
  const productChanged = !!currentProduct.data && currentProduct.data.version !== a.product.version;
  const rules = productChanged ? currentProduct.data! : a.product;
  const productReady = !!currentProduct.data && !currentProduct.error && !checkingProduct;
  const unsupportedPurpose = !!a.purpose && !rules.purposeCodes.includes(a.purpose);
  useEffect(() => {
    if (
      mutation.error instanceof ApiError &&
      mutation.error.problem?.code === 'PRODUCT_NOT_CURRENT'
    ) {
      setConfirm(false);
      currentProduct.reload();
    }
  }, [mutation.error]);
  async function review() {
    if (checkingProduct) return;
    setCheckingProduct(true);
    try {
      const latest = await api!.call('getProduct', { productId: a.product.id }, undefined);
      if (latest.data.version !== a.product.version) {
        currentProduct.reload();
        return;
      }
      setConfirmedEtag(etag);
      setConfirm(true);
    } catch (failure) {
      mutation.setError(failure);
      currentProduct.reload();
      unauthorized(failure);
    } finally {
      setCheckingProduct(false);
    }
  }
  async function save(e: FormEvent) {
    e.preventDefault();
    try {
      const r = await mutation.run(
        'updateDraft',
        { applicationId: a.id },
        draftValues(fields),
        etag || undefined,
      );
      if (r) {
        setDirty(false);
        setSaved(true);
      }
    } catch (e) {
      mutation.setError(e);
    }
  }
  async function submit() {
    if (unsupportedPurpose) {
      mutation.setError(new Error('Выберите доступную цель и сохраните черновик.'));
      return;
    }
    if (confirmedEtag !== etag) {
      mutation.setError(
        new Error('Заявка изменилась. Проверьте сведения и откройте подтверждение заново.'),
      );
      return;
    }
    if (dirty) {
      mutation.setError(new Error('Сначала сохраните изменения черновика.'));
      return;
    }
    const r = await mutation.run(
      'submitApplication',
      { applicationId: a.id },
      { personalDataConsent: true, creditBureauConsent: true },
      etag || undefined,
    );
    if (r) setConfirm(false);
  }
  return (
    <section className="panel">
      <h2>Параметры заявки</h2>
      <ErrorBox error={currentProduct.error} retry={currentProduct.reload} />
      {productChanged && (
        <div className="alert" role="status">
          <strong>
            Условия программы изменились: версия {a.product.version} → {rules.version}.
          </strong>
          <p>Проверьте данные и сохраните черновик по новым условиям перед отправкой.</p>
          <p>
            Сумма: {money(rules.minAmountRub)} — {money(rules.maxAmountRub)}. Срок:{' '}
            {rules.minTermMonths}–{rules.maxTermMonths} мес. Ставка: {rules.annualRateBps / 100}%
            годовых.
          </p>
          <p>Цели: {rules.purposeCodes.map((code) => purposeLabels[code]).join(', ')}.</p>
        </div>
      )}
      <ValidatedForm onSubmit={save}>
        <DataFields
          purposeCodes={rules.purposeCodes}
          productLimits={rules}
          fields={fields}
          setFields={(f) => {
            setFields(f);
            setDirty(true);
            setSaved(false);
          }}
          disabled={mutation.busy || checkingProduct}
          errors={fieldErrors}
          errorKey={mutation.error}
        />
        {unsupportedPurpose && (
          <p className="alert">
            Сохранённая цель недоступна для продукта. Выберите другую и сохраните черновик.
          </p>
        )}
        <div className="form-footer">
          <small className="muted" role="status">
            {dirty
              ? '● Есть несохранённые изменения'
              : saved
                ? '✓ Черновик сохранён'
                : 'Все изменения сохранены'}
          </small>
          {a.availableActions.includes('EDIT') && (
            <button className="secondary" disabled={!productReady || blocked || mutation.busy}>
              Сохранить черновик
            </button>
          )}
        </div>
      </ValidatedForm>
      <ErrorBox error={mutation.error} />
      {a.availableActions.includes('SUBMIT') && (
        <div className="submit-section">
          <h3>Перед отправкой</h3>
          <p className="muted">Проверьте сведения и дайте два отдельных согласия.</p>
          <label className="check">
            <Input
              type="checkbox"
              checked={personal}
              onChange={(e) => setPersonal(e.target.checked)}
            />
            Согласие на обработку персональных данных
          </label>
          <label className="check">
            <Input type="checkbox" checked={bureau} onChange={(e) => setBureau(e.target.checked)} />
            Согласие на запрос кредитной истории
          </label>
          <button
            className="primary"
            disabled={
              !productReady ||
              productChanged ||
              unsupportedPurpose ||
              !personal ||
              !bureau ||
              dirty ||
              blocked ||
              mutation.busy
            }
            onClick={() => void review()}
          >
            Проверить и отправить →
          </button>
        </div>
      )}
      {confirm && (
        <Modal title="Проверьте заявку перед отправкой" close={() => setConfirm(false)}>
          <p>
            {a.company.legalName} · {a.product.name}
          </p>
          <dl className="facts">
            <dt>Сумма</dt>
            <dd>{money(a.amountRub)}</dd>
            <dt>Срок</dt>
            <dd>{a.termMonths} мес.</dd>
            <dt>Выручка / расходы</dt>
            <dd>
              {money(a.financialData?.monthlyRevenueRub)} /{' '}
              {money(a.financialData?.monthlyExpensesRub)}
            </dd>
            <dt>Текущие платежи</dt>
            <dd>{money(a.financialData?.existingMonthlyDebtPaymentsRub)}</dd>
            <dt>Цель</dt>
            <dd>{a.purpose ? purposeLabels[a.purpose] : 'Не указана'}</dd>
          </dl>
          <AdditionalInformation info={a.additionalInfo} />
          <ErrorBox error={mutation.error} />
          {confirmedEtag !== etag && (
            <p className="alert">
              Заявка изменилась. Закройте окно и проверьте актуальные сведения.
            </p>
          )}
          <button
            className="primary"
            disabled={
              unsupportedPurpose ||
              confirmedEtag !== etag ||
              blocked ||
              mutation.busy ||
              !personal ||
              !bureau
            }
            onClick={() => void submit()}
          >
            Отправить заявку
          </button>
        </Modal>
      )}
    </section>
  );
}
export function ChangesForm({
  app: a,
  etag,
  refresh,
  blocked,
  mode,
  onDone,
}: {
  app: Application;
  etag: string | null;
  refresh: () => Promise<void>;
  blocked: boolean;
  mode: 'info' | 'terms';
  onDone?: () => void;
}) {
  const { api } = useSession();
  const [fields, setFields] = useState<Fields>(() => fieldsOf(a)),
    [dirty, setDirty] = useState(false),
    [product, setProduct] = useState(a.product.id + ':' + a.product.version);
  useDirty(dirty);
  const products = useResource(
    async (s) =>
      mode === 'terms' ? (await api!.call('listProducts', {}, undefined, s)).data.items : [],
    [api, mode],
  );
  const mutation = useMutation(a.id, refresh);
  const paths =
    mode === 'info'
      ? Array.from(
          new Set([
            ...(a.informationRequest?.requirements.map((r) => r.path) || []),
            ...Object.keys(fieldLabels).filter(
              (k) => k.startsWith('/financialData/') || k.startsWith('/additionalInfo/'),
            ),
          ]),
        )
      : Object.keys(fieldLabels);
  const selectedProduct =
    product === a.product.id + ':' + a.product.version
      ? a.product
      : products.data?.find((p) => p.id + ':' + p.version === product);
  const unknown = a.informationRequest?.requirements.some((r) => !fieldLabels[r.path]);
  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const changes = changesOf(
        fields,
        a,
        mode === 'info' ? a.informationRequest!.requirements.map((r) => r.path) : [],
      );
      if (product !== a.product.id + ':' + a.product.version) {
        const p = products.data?.find((p) => p.id + ':' + p.version === product);
        if (!p) throw new Error('Продукт недоступен');
        changes.productId = p.id;
        changes.productVersion = p.version;
      }
      const result =
        mode === 'info'
          ? await mutation.run(
              'provideInformation',
              { applicationId: a.id },
              { informationRequestId: a.informationRequest!.id, changes },
              etag || undefined,
            )
          : await mutation.run('changeTerms', { applicationId: a.id }, changes, etag || undefined);
      if (result) {
        setDirty(false);
        onDone?.();
      }
    } catch (e) {
      mutation.setError(e);
    }
  }
  return (
    <ValidatedForm onSubmit={submit}>
      {mode === 'terms' && (
        <>
          <p className="alert">
            Изменение суммы, срока или продукта запустит проверки заново. Прежнее предложение
            перестанет быть актуальным.
          </p>
          <div className="field">
            <label htmlFor="change-product">Продукт</label>
            <Select
              id="change-product"
              value={product}
              onChange={(e) => {
                const value = e.target.value;
                const next =
                  value === a.product.id + ':' + a.product.version
                    ? a.product
                    : products.data?.find((p) => p.id + ':' + p.version === value);
                setProduct(value);
                setFields((fields) => ({
                  ...fields,
                  '/purpose': next?.purposeCodes.some((code) => code === fields['/purpose'])
                    ? fields['/purpose']
                    : '',
                }));
                setDirty(true);
              }}
            >
              <option value={a.product.id + ':' + a.product.version}>
                {a.product.name} · версия {a.product.version}
              </option>
              {products.data
                ?.filter((p) => p.id !== a.product.id || p.version !== a.product.version)
                .map((p) => (
                  <option key={p.id + ':' + p.version} value={p.id + ':' + p.version}>
                    {p.name} · версия {p.version}
                  </option>
                ))}
            </Select>
          </div>
        </>
      )}
      <DataFields
        purposeCodes={selectedProduct?.purposeCodes}
        fields={fields}
        setFields={(f) => {
          setFields(f);
          setDirty(true);
        }}
        paths={paths}
        requiredPaths={paths.filter(
          (path) =>
            !path.startsWith('/additionalInfo/') ||
            (mode === 'info' &&
              a.informationRequest!.requirements.some((requirement) => requirement.path === path)),
        )}
        errors={errorsForFields(mutation.error)}
        errorKey={mutation.error}
        disabled={mutation.busy}
      />
      <ErrorBox error={products.error} />
      <ErrorBox error={mutation.error} />
      <div className="form-footer">
        <small className="muted">{dirty ? 'Есть несохранённые изменения' : ''}</small>
        <button
          className="primary"
          disabled={blocked || mutation.busy || (mode === 'info' && unknown)}
        >
          {mode === 'info' ? 'Отправить сведения' : 'Подтвердить новые условия'}
        </button>
      </div>
    </ValidatedForm>
  );
}
function Decision({ app: a, staff }: { app: Application; staff: boolean }) {
  const d = a.decision!,
    offer = d.offer;
  if (!staff && !offer) return null;
  return (
    <section className="panel">
      <span className="eyebrow">РЕЗУЛЬТАТ РАССМОТРЕНИЯ</span>
      <h2>
        {offer
          ? offer.type === 'COUNTER'
            ? 'Встречное предложение'
            : 'Условия финансирования'
          : 'Решение и факторы'}
      </h2>
      {offer && (
        <>
          <table className="comparison">
            <thead>
              <tr>
                <th>Условия</th>
                <th>Вы запросили</th>
                <th>Предложено</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <th>Сумма</th>
                <td>{money(a.amountRub)}</td>
                <td>
                  <strong>{money(offer.amountRub)}</strong>
                </td>
              </tr>
              <tr>
                <th>Срок</th>
                <td>{a.termMonths} мес.</td>
                <td>
                  <strong>{offer.termMonths} мес.</strong>
                </td>
              </tr>
              <tr>
                <th>Ставка</th>
                <td>—</td>
                <td>{offer.annualRateBps / 100}% годовых</td>
              </tr>
              <tr>
                <th>Платёж в месяц</th>
                <td>—</td>
                <td>{money(offer.monthlyPaymentRub)}</td>
              </tr>
            </tbody>
          </table>
          <p className="muted small">Суммы и платёж рассчитаны сервером для этого предложения.</p>
        </>
      )}
      {staff && d.reasons.map((r, i) => <p key={i}>{r.message}</p>)}
      {staff && d.risk && <RiskOverview risk={d.risk} />}
      {staff && d.staffAudit && (
        <p className="muted small">
          Решение сотрудника · {dateTime(d.staffAudit.decidedAt)}
          <br />
          {d.staffAudit.reason}
        </p>
      )}
    </section>
  );
}
function ActionPanel({
  app: a,
  etag,
  blocked,
  canInterrupt,
  expired,
  refresh,
}: {
  app: Application;
  etag: string | null;
  blocked: boolean;
  canInterrupt: boolean;
  expired: boolean;
  refresh: () => Promise<void>;
}) {
  const [dialog, setDialog] = useState<'accept' | 'terms' | 'cancel' | null>(null),
    [intentEtag, setIntentEtag] = useState<string | null>(null),
    [reason, setReason] = useState('');
  const mutation = useMutation(a.id, refresh);
  const allow = (s: Application['availableActions'][number]) => a.availableActions.includes(s);
  const disabled = blocked || mutation.busy;
  const interruptDisabled = mutation.busy || (blocked && !canInterrupt);
  const changed = dialog !== null && intentEtag !== etag;
  function show(value: 'accept' | 'terms' | 'cancel') {
    setIntentEtag(etag);
    setDialog(value);
  }
  async function action() {
    if (changed) return;
    if (dialog === 'accept' && a.decision?.offer) {
      const o = a.decision.offer;
      const r = await mutation.run(
        'acceptOffer',
        { applicationId: a.id },
        { offerId: o.id, offerVersion: o.version, dataVersion: a.dataVersion },
        etag || undefined,
      );
      if (r) setDialog(null);
    } else if (dialog === 'cancel') {
      const r = await mutation.run(
        'cancelApplication',
        { applicationId: a.id },
        { reason },
        etag || undefined,
      );
      if (r) setDialog(null);
    }
  }
  return (
    <section className="panel actions">
      <span className="eyebrow">СЛЕДУЮЩИЙ ШАГ</span>
      <h2>Действия по заявке</h2>
      {allow('ACCEPT_OFFER') && (
        <button
          className="primary full"
          onClick={() => show('accept')}
          disabled={disabled || expired}
        >
          Принять предложение
        </button>
      )}
      {allow('CHANGE_TERMS') && (
        <button
          className="secondary full"
          onClick={() => show('terms')}
          disabled={disabled || expired}
        >
          Изменить условия
        </button>
      )}
      {allow('SIGN') && (
        <a className="button primary full" href={'#document'}>
          Прочитать и подписать
        </a>
      )}
      {allow('RETRY_PROCESSING') && (
        <button
          className="primary full"
          onClick={() =>
            void mutation.run(
              'retryProcessing',
              { applicationId: a.id },
              undefined,
              etag || undefined,
            )
          }
          disabled={interruptDisabled}
        >
          Повторить обработку
        </button>
      )}
      {allow('CANCEL') && (
        <button
          className="text-button danger full"
          onClick={() => show('cancel')}
          disabled={interruptDisabled || expired}
        >
          Отменить заявку
        </button>
      )}
      {!a.availableActions.length && (
        <p className="muted">
          Доступных действий сейчас нет. {a.processing ? 'Ожидаем подтверждение сервера.' : ''}
        </p>
      )}
      <ErrorBox error={mutation.error} />
      {dialog && (
        <Modal
          title={
            dialog === 'accept'
              ? 'Принять предложение'
              : dialog === 'terms'
                ? 'Изменить условия'
                : 'Отменить заявку'
          }
          close={() => setDialog(null)}
        >
          {changed && (
            <p className="alert">
              Заявка изменилась. Закройте окно, проверьте данные и подтвердите действие заново.
            </p>
          )}
          {dialog === 'terms' ? (
            <ChangesForm
              mode="terms"
              app={a}
              etag={etag}
              blocked={changed || blocked || expired || !allow('CHANGE_TERMS')}
              refresh={refresh}
              onDone={() => setDialog(null)}
            />
          ) : (
            <ValidatedForm
              onSubmit={(e) => {
                e.preventDefault();
                void action();
              }}
            >
              {dialog === 'accept' ? (
                <>
                  <p>
                    Вы принимаете конкретное предложение № {a.decision?.offer?.id.slice(-8)} для
                    версии данных {a.dataVersion}.
                  </p>
                  <p className="offer-amount">
                    {money(a.decision?.offer?.amountRub)} · {a.decision?.offer?.termMonths} мес.
                  </p>
                  <p>
                    {a.decision?.offer?.annualRateBps! / 100}% годовых ·{' '}
                    {money(a.decision?.offer?.monthlyPaymentRub)} в месяц
                  </p>
                </>
              ) : (
                <>
                  <p>
                    Отмена будет подтверждена после проверки сервером. При уже состоявшейся подписи
                    возможен другой исход.
                  </p>
                  <div className="field">
                    <label htmlFor="cancel-reason">Причина отмены</label>
                    <Textarea
                      required
                      maxLength={1000}
                      id="cancel-reason"
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                    />
                  </div>
                </>
              )}
              <ErrorBox error={mutation.error} />
              <button
                className="primary"
                disabled={
                  changed ||
                  (dialog === 'cancel' ? interruptDisabled : disabled) ||
                  expired ||
                  !(dialog === 'accept' ? allow('ACCEPT_OFFER') : allow('CANCEL'))
                }
              >
                {dialog === 'accept' ? 'Подтвердить принятие' : 'Подтвердить отмену'}
              </button>
            </ValidatedForm>
          )}
        </Modal>
      )}
    </section>
  );
}
export function AgreementPage() {
  const { id = '' } = useParams();
  return <ApplicationCard key={id} id={id} agreementPage />;
}
function AgreementView({
  app: a,
  etag,
  blocked,
  refresh,
}: {
  app: Application;
  etag: string | null;
  blocked: boolean;
  refresh: () => Promise<void>;
}) {
  const { api, session } = useSession();
  const r = useResource(
    async (signal) =>
      (await api!.call('getApplicationAgreement', { applicationId: a.id }, undefined, signal)).data,
    [
      api,
      a.id,
      a.agreement?.id,
      a.agreement?.documentVersion,
      a.agreement?.documentSha256,
      a.agreement?.status,
    ],
  );
  const [consent, setConsent] = useState(false);
  const mutation = useMutation(a.id, refresh);
  useEffect(() => {
    setConsent(false);
  }, [r.data?.id, r.data?.document.version, r.data?.document.sha256]);
  const ag = r.data;
  const matches =
    ag &&
    ag.id === a.agreement?.id &&
    ag.document.version === a.agreement.documentVersion &&
    ag.document.sha256 === a.agreement.documentSha256;
  async function sign(e: FormEvent) {
    e.preventDefault();
    if (!ag || !matches) return;
    await mutation.run(
      'signApplicationAgreement',
      { applicationId: a.id },
      {
        agreementId: ag.id,
        documentVersion: ag.document.version,
        documentSha256: ag.document.sha256,
        consent: true,
      },
      etag || undefined,
    );
  }
  return (
    <section className="panel" id="document">
      <span className="eyebrow">КРЕДИТНЫЙ ДОГОВОР</span>
      <h2>Документ и подпись</h2>
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : (
        ag && (
          <>
            <p>
              Версия документа {ag.document.version} ·{' '}
              {ag.status === 'SIGNED'
                ? 'Подписан'
                : ag.status === 'CLOSED'
                  ? 'Закрыт'
                  : 'Готов к подписи'}
            </p>
            <p className="muted small">Подписать до {dateTime(ag.signBy)}</p>
            <pre className="document" tabIndex={0} aria-label="Полный текст договора">
              {ag.document.content}
            </pre>
            {!matches && (
              <p className="alert">
                Документ обновился. Перечитайте актуальную заявку и ознакомьтесь с новой версией.
              </p>
            )}
            {session?.role === 'customer' && a.availableActions.includes('SIGN') && (
              <ValidatedForm onSubmit={sign}>
                <label className="check">
                  <Input
                    type="checkbox"
                    checked={consent}
                    onChange={(e) => setConsent(e.target.checked)}
                  />
                  Я прочитал полный документ и согласен подписать его в демонстрационном режиме
                </label>
                <ErrorBox error={mutation.error} />
                <button
                  className="primary"
                  disabled={!consent || !matches || blocked || mutation.busy}
                >
                  Подписать договор
                </button>
              </ValidatedForm>
            )}
            {ag.signature && (
              <p className="success-text">
                ✓ Подпись подтверждена {dateTime(ag.signature.signedAt)}
              </p>
            )}
          </>
        )
      )}
    </section>
  );
}
function PayoutForm({
  app: a,
  etag,
  blocked,
  refresh,
}: {
  app: Application;
  etag: string | null;
  blocked: boolean;
  refresh: () => Promise<void>;
}) {
  const { api } = useSession();
  const r = useResource(
    async (signal) =>
      (
        await api!.call(
          'listCompanySettlementAccounts',
          { companyId: a.company.id },
          undefined,
          signal,
        )
      ).data,
    [api, a.company.id],
  );
  const [account, setAccount] = useState('');
  const mutation = useMutation(a.id, refresh);
  return (
    <ValidatedForm
      onSubmit={(e) => {
        e.preventDefault();
        void mutation.run(
          'updatePayoutAccount',
          { applicationId: a.id },
          { settlementAccountId: account },
          etag || undefined,
        );
      }}
    >
      <p className="muted">
        Изменится только поручение на зачисление. Подписанные условия договора сохранятся.
      </p>
      <ErrorBox error={r.error} retry={r.reload} />
      <div className="field">
        <label htmlFor="payout-account">Ваш счет</label>
        <Select
          id="payout-account"
          value={account}
          required
          onChange={(e) => setAccount(e.target.value)}
        >
          <option value="">Выберите счет</option>
          {r.data?.items.map((acc) => (
            <option
              disabled={
                acc.status !== 'ACTIVE' || acc.id === a.payoutInstruction.settlementAccountId
              }
              key={acc.id}
              value={acc.id}
            >
              {acc.label} · {acc.id.slice(-8)}
            </option>
          ))}
        </Select>
      </div>
      <ErrorBox error={mutation.error} />
      <button className="primary" disabled={blocked || mutation.busy || !account}>
        Изменить счет
      </button>
    </ValidatedForm>
  );
}

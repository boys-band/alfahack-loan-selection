import { ValidatedForm, Input, Select, Textarea, Fieldset } from '../components/validated-form';
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import type {
  CreateAdminProductRequest,
  Product,
  PublishProductVersionRequest,
} from '../api/generated';
import { ErrorBox, Empty, Loading } from '../components/common';
import { useDirty } from '../components/fields';
import { useResource, useUnauthorized } from '../core/hooks';
import { useSession } from '../core/session';
import { money, purposeLabels } from '../core/state';
import type { FormIssues } from '../components/validated-form';

const numbers = [
  ['minAmountRub', 'Минимальная сумма, ₽', 1, 1_000_000_000],
  ['maxAmountRub', 'Максимальная сумма, ₽', 1, 1_000_000_000],
  ['minTermMonths', 'Минимальный срок, мес.', 1, 120],
  ['maxTermMonths', 'Максимальный срок, мес.', 1, 120],
  ['annualRateBps', 'Годовая ставка, б.п.', 0, 10_000],
  ['minBusinessAgeMonths', 'Минимальный возраст бизнеса, мес.', 0, 1_000_000],
  ['maxDebtServiceRatioBps', 'Предельная нагрузка, б.п.', 1, 10_000],
] as const;
type NumberKey = (typeof numbers)[number][0];
type Draft = {
  name: string;
  description: string;
  purposeCodes: Product['purposeCodes'];
  reason: string;
} & Record<NumberKey, string>;

function fromProduct(product: Product): Draft {
  return {
    name: product.name,
    description: product.description,
    purposeCodes: [...product.purposeCodes],
    minAmountRub: String(product.minAmountRub),
    maxAmountRub: String(product.maxAmountRub),
    minTermMonths: String(product.minTermMonths),
    maxTermMonths: String(product.maxTermMonths),
    annualRateBps: String(product.annualRateBps),
    minBusinessAgeMonths: String(product.minBusinessAgeMonths),
    maxDebtServiceRatioBps: String(product.maxDebtServiceRatioBps),
    reason: '',
  };
}

function fromSavedRequest(body: string): Draft {
  const request = JSON.parse(body) as PublishProductVersionRequest;
  return {
    name: request.name,
    description: request.description,
    purposeCodes: [...request.purposeCodes],
    minAmountRub: String(request.minAmountRub),
    maxAmountRub: String(request.maxAmountRub),
    minTermMonths: String(request.minTermMonths),
    maxTermMonths: String(request.maxTermMonths),
    annualRateBps: String(request.annualRateBps),
    minBusinessAgeMonths: String(request.minBusinessAgeMonths),
    maxDebtServiceRatioBps: String(request.maxDebtServiceRatioBps),
    reason: request.reason,
  };
}

function requestOf(draft: Draft): PublishProductVersionRequest {
  return {
    name: draft.name,
    description: draft.description,
    purposeCodes: draft.purposeCodes,
    minAmountRub: Number(draft.minAmountRub),
    maxAmountRub: Number(draft.maxAmountRub),
    minTermMonths: Number(draft.minTermMonths),
    maxTermMonths: Number(draft.maxTermMonths),
    annualRateBps: Number(draft.annualRateBps),
    minBusinessAgeMonths: Number(draft.minBusinessAgeMonths),
    maxDebtServiceRatioBps: Number(draft.maxDebtServiceRatioBps),
    reason: draft.reason,
  };
}

function reasonError(reason: string): Error | undefined {
  const length = Array.from(reason).length;
  return length < 10 || length > 2000 || !reason.trim()
    ? new Error('Обоснование должно содержать от 10 до 2000 символов.')
    : undefined;
}

function productIssues(draft: Draft): FormIssues {
  const issues: FormIssues = {};
  for (const [minimum, maximum, label] of [
    ['minAmountRub', 'maxAmountRub', 'сумма'],
    ['minTermMonths', 'maxTermMonths', 'срок'],
  ] as const) {
    if (draft[minimum] && draft[maximum] && Number(draft[minimum]) > Number(draft[maximum])) {
      const related = ['admin-' + minimum, 'admin-' + maximum];
      for (const id of related)
        issues[id] = {
          message:
            label === 'сумма'
              ? 'Минимальная сумма не должна превышать максимальную.'
              : 'Минимальный срок не должен превышать максимальный.',
          related,
        };
    }
  }
  if (!draft.purposeCodes.length)
    issues['admin-purposes'] = { message: 'Выберите хотя бы одну цель кредита.' };
  return issues;
}

function diff(product: Product, draft: Draft): Array<[string, string, string]> {
  const rows: Array<[string, string, string]> = [];
  for (const [key, label] of numbers)
    if (String(product[key]) !== draft[key]) rows.push([label, String(product[key]), draft[key]]);
  if (product.name !== draft.name) rows.push(['Название', product.name, draft.name]);
  if (product.description !== draft.description)
    rows.push(['Описание', product.description, draft.description]);
  if (JSON.stringify(product.purposeCodes) !== JSON.stringify(draft.purposeCodes))
    rows.push([
      'Цели',
      product.purposeCodes.map((code) => purposeLabels[code]).join(', '),
      draft.purposeCodes.map((code) => purposeLabels[code]).join(', '),
    ]);
  return rows;
}

function adminCardPath(id: string): string {
  return id === 'new' ? '/admin/products/id/new' : '/admin/products/' + encodeURIComponent(id);
}

function useRetryNow(first?: number, second?: number): number {
  const [clock, setNow] = useState(Date.now());
  // A deadline may already have elapsed before the effect runs after a busy render.
  const now = Math.max(clock, Date.now());
  useEffect(() => {
    const next = [first, second]
      .filter((value): value is number => !!value && value > now)
      .sort((a, b) => a - b)[0];
    if (next === undefined) return;
    const timer = window.setTimeout(() => setNow(Date.now()), Math.max(20, next - Date.now() + 20));
    return () => window.clearTimeout(timer);
  }, [first, second, now]);
  return now;
}

function ProductFields({
  draft,
  setDraft,
  disabled,
  reasonLabel = 'Причина изменения (10–2000 символов)',
}: {
  draft: Draft;
  setDraft: (draft: Draft) => void;
  disabled: boolean;
  reasonLabel?: string;
}) {
  return (
    <div className="form-grid">
      <div className="field">
        <label htmlFor="admin-name">Название</label>
        <Input
          id="admin-name"
          required
          disabled={disabled}
          value={draft.name}
          onChange={(event) => setDraft({ ...draft, name: event.target.value })}
        />
      </div>
      <div className="field wide">
        <label htmlFor="admin-description">Описание</label>
        <Textarea
          id="admin-description"
          required
          disabled={disabled}
          value={draft.description}
          onChange={(event) => setDraft({ ...draft, description: event.target.value })}
        />
      </div>
      {numbers.map(([key, label, min, max]) => (
        <div className="field" key={key}>
          <label htmlFor={'admin-' + key}>{label}</label>
          <Input
            id={'admin-' + key}
            type="number"
            inputMode="numeric"
            min={min}
            max={max}
            step="1"
            required
            disabled={disabled}
            value={draft[key]}
            onChange={(event) => setDraft({ ...draft, [key]: event.target.value })}
          />
        </div>
      ))}
      <Fieldset id="admin-purposes" className="purpose-options wide" disabled={disabled}>
        <legend>Разрешённые цели</legend>
        {Object.entries(purposeLabels).map(([code, label]) => (
          <label className="check" key={code}>
            <Input
              type="checkbox"
              disabled={disabled}
              checked={draft.purposeCodes.includes(code as Product['purposeCodes'][number])}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  purposeCodes: event.target.checked
                    ? [...draft.purposeCodes, code as Product['purposeCodes'][number]]
                    : draft.purposeCodes.filter((item) => item !== code),
                })
              }
            />
            <span>{label}</span>
          </label>
        ))}
      </Fieldset>
      <div className="field wide">
        <label htmlFor="admin-reason">{reasonLabel}</label>
        <Textarea
          id="admin-reason"
          required
          minLength={10}
          data-max-length="2000"
          data-length-message="Обоснование должно содержать от 10 до 2000 символов."
          disabled={disabled}
          value={draft.reason}
          onChange={(event) => setDraft({ ...draft, reason: event.target.value })}
        />
      </div>
    </div>
  );
}

export function AdminProducts() {
  const { api, session } = useSession();
  const [filter, setFilter] = useState<'active' | 'archived' | 'all'>('active');
  const resource = useResource(
    async (signal) => (await api!.call('listAdminProducts', {}, undefined, signal)).data,
    [api, session?.identity],
  );
  return (
    <>
      <div className="page-title">
        <div>
          <span className="eyebrow">АДМИНИСТРИРОВАНИЕ</span>
          <h1>Условия кредитных продуктов</h1>
          <p>Новые условия действуют для нового выбора продукта; старые снимки сохраняются.</p>
        </div>
        <Link className="button primary" to="/admin/products/new">
          Новая программа
        </Link>
      </div>
      <div className="panel">
        <label htmlFor="admin-product-filter">Показать программы</label>{' '}
        <Select
          id="admin-product-filter"
          value={filter}
          onChange={(event) => setFilter(event.target.value as typeof filter)}
        >
          <option value="active">Активные</option>
          <option value="archived">Архивные</option>
          <option value="all">Все</option>
        </Select>
      </div>
      <ErrorBox error={resource.error} retry={resource.reload} />
      {resource.loading && !resource.data && <Loading />}
      {resource.data?.items.filter(
        ({ active }) => filter === 'all' || active === (filter === 'active'),
      ).length === 0 && <Empty>Программ в этом разделе пока нет.</Empty>}
      <div className="product-grid">
        {resource.data?.items
          .filter(({ active }) => filter === 'all' || active === (filter === 'active'))
          .map(({ product, active }) => (
            <article className="product panel" key={product.id}>
              <span className="eyebrow">
                ВЕРСИЯ {product.version} · {active ? 'АКТИВНА' : 'В АРХИВЕ'}
              </span>
              <h2>{product.name}</h2>
              <p>{product.description}</p>
              <strong className="product-rate">{product.annualRateBps / 100}% годовых</strong>
              <p>
                {money(product.minAmountRub)} — {money(product.maxAmountRub)}
              </p>
              <Link className="button primary" to={adminCardPath(product.id)}>
                {active ? 'Изменить условия →' : 'Открыть архив →'}
              </Link>
            </article>
          ))}
      </div>
    </>
  );
}

const emptyDraft: Draft = {
  name: '',
  description: '',
  purposeCodes: [],
  reason: '',
  minAmountRub: '',
  maxAmountRub: '',
  minTermMonths: '',
  maxTermMonths: '',
  annualRateBps: '',
  minBusinessAgeMonths: '',
  maxDebtServiceRatioBps: '',
};

export function AdminProductNew() {
  const { api, adminCommands } = useSession();
  const unauthorized = useUnauthorized();
  const navigate = useNavigate();
  const [draft, setDraft] = useState<Draft & { id: string }>(() => {
    const saved = adminCommands?.all().find((item) => item.type === 'create');
    return saved
      ? { ...fromSavedRequest(saved.body), id: saved.productId }
      : { ...emptyDraft, id: '' };
  });
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const pending = adminCommands?.all().find((item) => item.type === 'create');
  const now = useRetryNow(pending?.retryAt);
  useDirty(!submitted && !!(draft.id || draft.name || draft.reason) && !pending);

  async function sendExisting() {
    if (!pending || !adminCommands || !api) return;
    setBusy(true);
    setError(undefined);
    try {
      const reply = await adminCommands.send(api, pending);
      setSubmitted(true);
      navigate(adminCardPath(reply.data.product.id));
    } catch (failure) {
      setError(failure);
      unauthorized(failure);
    } finally {
      setBusy(false);
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!api || !adminCommands || pending || busy) return;
    const body: CreateAdminProductRequest = { id: draft.id, ...requestOf(draft) };
    const invalidReason = reasonError(body.reason);
    if (invalidReason) {
      setError(invalidReason);
      return;
    }
    if (body.minAmountRub > body.maxAmountRub || body.minTermMonths > body.maxTermMonths) {
      setError(new Error('Минимальные значения не должны превышать максимальные.'));
      return;
    }
    if (!window.confirm('Создать и опубликовать программу с показанными условиями?')) return;
    try {
      const command = adminCommands.prepareCreate(body);
      setBusy(true);
      setError(undefined);
      const reply = await adminCommands.send(api, command);
      setSubmitted(true);
      navigate(adminCardPath(reply.data.product.id));
    } catch (failure) {
      setError(failure);
      unauthorized(failure);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-title">
        <div>
          <Link to="/admin/products">← Все программы</Link>
          <h1>Новая программа</h1>
          <p>После подтверждения программа сразу станет доступна для новых заявок как версия 1.</p>
        </div>
      </div>
      <ErrorBox error={error} />
      {pending && (
        <div className="alert" role="status">
          <p>
            {pending.state === 'unknown'
              ? 'Исход создания неизвестен. Повторите исходную команду с тем же ключом.'
              : pending.state === 'conflict'
                ? 'Команда столкнулась с изменением. Проверьте данные.'
                : 'Создание отклонено. Исправьте форму и подтвердите новое намерение.'}
          </p>
          {pending.state === 'unknown' ? (
            <button
              className="secondary"
              disabled={busy || now < pending.retryAt}
              onClick={() => void sendExisting()}
            >
              Проверить и повторить
            </button>
          ) : (
            <button
              className="secondary"
              onClick={() => adminCommands?.acknowledge(pending.productId, 'create')}
            >
              Ознакомился, редактировать заново
            </button>
          )}
        </div>
      )}
      <ValidatedForm
        className="panel"
        validate={() => productIssues(draft)}
        onSubmit={(event) => void create(event)}
      >
        <div className="field">
          <label htmlFor="admin-product-id">Идентификатор</label>
          <Input
            id="admin-product-id"
            required
            pattern={'[a-z][a-z0-9\\-]{1,63}'}
            data-format-message="От 2 до 64 символов: строчные латинские буквы, цифры и дефис; первый символ — буква."
            minLength={2}
            maxLength={64}
            disabled={!!pending}
            value={draft.id}
            onChange={(event) => setDraft({ ...draft, id: event.target.value })}
          />
        </div>
        <ProductFields
          draft={draft}
          setDraft={(fields) => setDraft({ ...fields, id: draft.id })}
          disabled={!!pending}
          reasonLabel="Обоснование (10–2000 символов)"
        />
        <h2>Итоговые условия</h2>
        <dl className="facts">
          <div>
            <dt>Идентификатор</dt>
            <dd>{draft.id || '—'}</dd>
          </div>
          <div>
            <dt>Название</dt>
            <dd>{draft.name || '—'}</dd>
          </div>
          <div>
            <dt>Описание</dt>
            <dd>{draft.description || '—'}</dd>
          </div>
          <div>
            <dt>Цели</dt>
            <dd>{draft.purposeCodes.map((code) => purposeLabels[code]).join(', ') || '—'}</dd>
          </div>
          {numbers.map(([key, label]) => (
            <div key={key}>
              <dt>{label}</dt>
              <dd>{draft[key] || '—'}</dd>
            </div>
          ))}
          <div>
            <dt>Обоснование</dt>
            <dd>{draft.reason || '—'}</dd>
          </div>
        </dl>
        <button className="primary" disabled={busy || !!pending}>
          {busy ? 'Создаём…' : 'Создать программу'}
        </button>
      </ValidatedForm>
    </>
  );
}

export function AdminProductEditor() {
  const { id = '' } = useParams();
  const { api, adminCommands, session, revision } = useSession();
  const unauthorized = useUnauthorized();
  const [draft, setDraft] = useState<Draft>();
  const [loadedId, setLoadedId] = useState<string>();
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [archiveReason, setArchiveReason] = useState(() => {
    const saved = adminCommands?.get(id, 'archive');
    return saved ? (JSON.parse(saved.body) as { reason: string }).reason : '';
  });
  const resource = useResource(
    (signal) => api!.call('getAdminProduct', { productId: id }, undefined, signal),
    [api, id, session?.identity],
  );
  useEffect(() => {
    if (resource.data && (loadedId !== id || !draft)) {
      const pending = adminCommands?.get(id);
      setDraft(pending ? fromSavedRequest(pending.body) : fromProduct(resource.data.data.product));
      setLoadedId(id);
    }
  }, [resource.data, loadedId, id, draft, adminCommands]);
  const product = resource.data?.data.product;
  const pending = adminCommands?.get(id);
  const pendingArchive = adminCommands?.get(id, 'archive');
  const now = useRetryNow(pending?.retryAt, pendingArchive?.retryAt);
  const active = resource.data?.data.active ?? false;
  const changed = product && draft ? diff(product, draft) : [];
  useDirty(
    (!!draft && (!!changed.length || !!draft.reason) && !pending) ||
      (!!archiveReason && !pendingArchive && active),
  );

  function cancelEdit(event: FormEvent) {
    event.preventDefault();
    if (!product || busy || pending || pendingArchive || resource.loading) return;
    setDraft(fromProduct(product));
    setError(undefined);
    setMessage('Изменения условий отменены.');
  }

  async function sendArchiveExisting() {
    if (!pendingArchive || !api || !adminCommands) return;
    setBusy(true);
    setError(undefined);
    try {
      await adminCommands.send(api, pendingArchive);
      setMessage('Программа архивирована.');
      resource.reload();
    } catch (failure) {
      setError(failure);
      unauthorized(failure);
      resource.reload();
    } finally {
      setBusy(false);
    }
  }

  async function archive(event: FormEvent) {
    event.preventDefault();
    if (!active || !api || !adminCommands || !resource.data?.etag || pendingArchive || busy) return;
    const invalidReason = reasonError(archiveReason);
    if (invalidReason) {
      setError(invalidReason);
      return;
    }
    if (
      !window.confirm(
        'Программа станет недоступна для новых заявок. Ранее созданные заявки и договоры сохранятся',
      )
    )
      return;
    try {
      const command = adminCommands.prepareArchive(id, archiveReason, resource.data.etag);
      setBusy(true);
      setError(undefined);
      await adminCommands.send(api, command);
      setMessage('Программа архивирована.');
      resource.reload();
    } catch (failure) {
      setError(failure);
      unauthorized(failure);
      resource.reload();
    } finally {
      setBusy(false);
    }
  }

  async function sendExisting() {
    if (!pending || !api || !adminCommands) return;
    setBusy(true);
    setError(undefined);
    try {
      await api.call('getAdminProduct', { productId: id }, undefined);
      const reply = await adminCommands.send(api, pending);
      setDraft(fromProduct(reply.data.product));
      setMessage('Версия ' + reply.data.product.version + ' опубликована.');
      resource.reload();
    } catch (failure) {
      setError(failure);
      unauthorized(failure);
      resource.reload();
    } finally {
      setBusy(false);
    }
  }

  async function publish(event: FormEvent) {
    event.preventDefault();
    if (!draft || !product || !resource.data?.etag || !api || !adminCommands || busy || pending)
      return;
    const invalidReason = reasonError(draft.reason);
    if (invalidReason) {
      setError(invalidReason);
      return;
    }
    if (!changed.length) {
      setError(new Error('Измените хотя бы одно условие продукта.'));
      return;
    }
    if (!window.confirm('Опубликовать новую версию с указанными изменениями?')) return;
    try {
      const command = adminCommands.prepare(id, requestOf(draft), resource.data.etag);
      setBusy(true);
      setError(undefined);
      const reply = await adminCommands.send(api, command);
      setDraft(fromProduct(reply.data.product));
      setMessage('Версия ' + reply.data.product.version + ' опубликована.');
      resource.reload();
    } catch (failure) {
      setError(failure);
      unauthorized(failure);
      resource.reload();
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-title">
        <div>
          <Link to="/admin/products">← Все продукты</Link>
          <h1>Условия продукта</h1>
          {product && (
            <p>
              {product.name} · текущая версия {product.version} {active ? '' : '· в архиве'}
            </p>
          )}
        </div>
      </div>
      <ErrorBox error={resource.error} retry={resource.reload} />
      <ErrorBox error={error} />
      {message && (
        <div className="alert" role="status">
          {message}
        </div>
      )}
      {resource.loading && !resource.data && <Loading />}
      {pending && (
        <div className="alert" role="status">
          <p>
            {pending.state === 'unknown'
              ? 'Исход публикации неизвестен. Повторите исходную команду с тем же ключом.'
              : pending.state === 'conflict'
                ? 'Продукт изменился. Ознакомьтесь с текущими условиями и подтвердите новое намерение.'
                : 'Публикация отклонена. Исправьте форму и подтвердите новое намерение.'}
          </p>
          {pending.state === 'unknown' ? (
            <button
              className="secondary"
              disabled={busy || now < pending.retryAt}
              onClick={() => void sendExisting()}
            >
              Проверить и повторить
            </button>
          ) : (
            <button
              className="secondary"
              onClick={() => {
                adminCommands?.acknowledge(id);
                resource.reload();
              }}
            >
              Ознакомился, редактировать заново
            </button>
          )}
        </div>
      )}
      {product && draft && active && (
        <ValidatedForm
          onSubmit={(event) => void publish(event)}
          onReset={cancelEdit}
          validate={() => productIssues(draft)}
          className="panel"
        >
          <ProductFields draft={draft} setDraft={setDraft} disabled={!!pending} />
          <h2>{pending ? 'Сохранённый проект и текущие условия' : 'Что изменится'}</h2>
          {pending && (
            <p>
              Сравните сохранённую команду с текущей версией {product.version} перед новым
              намерением.
            </p>
          )}
          {changed.length ? (
            <dl className="facts">
              {changed.map(([label, before, after]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>
                    {before} → {after || 'пусто'}
                  </dd>
                </div>
              ))}
            </dl>
          ) : (
            <p>Изменений условий нет.</p>
          )}
          <div className="form-footer">
            <button className="primary" disabled={busy || !!pending || !changed.length}>
              {busy ? 'Публикуем…' : 'Проверить и опубликовать новую версию'}
            </button>
            <button
              type="reset"
              className="secondary"
              disabled={busy || !!pending || !!pendingArchive || resource.loading}
            >
              Отменить изменения
            </button>
          </div>
        </ValidatedForm>
      )}
      {pendingArchive && (
        <div className="alert" role="status">
          <p>
            {pendingArchive.state === 'unknown'
              ? 'Исход архивирования неизвестен. Повторите исходную команду с тем же ключом.'
              : pendingArchive.state === 'conflict'
                ? 'Программа изменилась. Ознакомьтесь с текущими условиями и подтвердите новое намерение.'
                : 'Архивирование отклонено. Проверьте обоснование.'}
          </p>
          {pendingArchive.state === 'unknown' ? (
            <button
              className="secondary"
              disabled={busy || now < pendingArchive.retryAt}
              onClick={() => void sendArchiveExisting()}
            >
              Проверить и повторить архивирование
            </button>
          ) : (
            <button
              className="secondary"
              onClick={() => {
                adminCommands?.acknowledge(id, 'archive');
                resource.reload();
              }}
            >
              Ознакомился, редактировать архивирование
            </button>
          )}
        </div>
      )}
      {active && (
        <ValidatedForm className="panel" onSubmit={(event) => void archive(event)}>
          <h2>Архивировать программу</h2>
          <p>
            Программа станет недоступна для новых заявок. Ранее созданные заявки и договоры
            сохранятся.
          </p>
          <div className="field">
            <label htmlFor="archive-reason">Обоснование архивирования (10–2000 символов)</label>
            <Textarea
              id="archive-reason"
              required
              minLength={10}
              data-max-length="2000"
              data-length-message="Обоснование должно содержать от 10 до 2000 символов."
              disabled={!!pendingArchive}
              value={archiveReason}
              onChange={(event) => setArchiveReason(event.target.value)}
            />
          </div>
          <button className="secondary" disabled={busy || !!pendingArchive}>
            Архивировать
          </button>
        </ValidatedForm>
      )}
      {resource.data && (
        <section className="panel">
          <h2>Сохранённые версии</h2>
          <ul>
            {resource.data.data.versions.map((version) => (
              <li key={version.version}>
                Версия {version.version}: {version.annualRateBps / 100}% годовых,
                {money(version.minAmountRub)} — {money(version.maxAmountRub)}
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

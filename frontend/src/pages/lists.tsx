import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useSession } from '../core/session';
import { useResource } from '../core/hooks';
import {
  money,
  dateTime,
  purposeLabels,
  workflowText,
  manualReviewStatusLabels,
} from '../core/state';
import { Empty, ErrorBox, Loading, Status } from '../components/common';
export function Products() {
  const { api, session } = useSession();
  const r = useResource(
    async (signal) => (await api!.call('listProducts', {}, undefined, signal)).data,
    [api, session?.identity],
  );
  return (
    <>
      <div className="page-title">
        <div>
          <span className="eyebrow">ФИНАНСИРОВАНИЕ БИЗНЕСА</span>
          <h1>Кредитные продукты</h1>
          <p>Выберите решение под задачи вашей компании.</p>
        </div>
      </div>
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : r.data?.items.length ? (
        <div className="product-grid">
          {r.data.items.map((p) => (
            <article className="product panel" key={p.id}>
              <div className="product-icon" aria-hidden>
                ↗
              </div>
              <span className="eyebrow">КРЕДИТ ДЛЯ БИЗНЕСА · ВЕРСИЯ {p.version}</span>
              <h2>{p.name}</h2>
              <p>{p.description}</p>
              <strong className="product-rate">
                {p.annualRateBps / 100}% <small>годовых</small>
              </strong>
              <dl className="facts">
                <dt>Сумма</dt>
                <dd>
                  {money(p.minAmountRub)} — {money(p.maxAmountRub)}
                </dd>
                <dt>Срок</dt>
                <dd>
                  {p.minTermMonths}–{p.maxTermMonths} мес.
                </dd>
                <dt>Возраст бизнеса</dt>
                <dd>От {p.minBusinessAgeMonths} мес.</dd>
                <dt>Предельная нагрузка</dt>
                <dd>{p.maxDebtServiceRatioBps / 100}%</dd>
              </dl>
              <p className="muted small">
                {p.purposeCodes.map((x) => purposeLabels[x]).join(' · ')}
              </p>
              <Link
                className="button primary"
                to={
                  '/applications/new?product=' +
                  encodeURIComponent(p.id) +
                  '&version=' +
                  p.version +
                  '&from=products'
                }
              >
                Выбрать продукт →
              </Link>
            </article>
          ))}
        </div>
      ) : (
        <Empty>Сейчас нет доступных продуктов.</Empty>
      )}
    </>
  );
}
export function ApplicationList() {
  const { api, session } = useSession();
  const [cursor, setCursor] = useState<string>();
  const r = useResource(
    async (signal) =>
      (await api!.call('listApplications', { limit: 20, cursor }, undefined, signal)).data,
    [api, cursor, session?.identity],
  );
  return (
    <>
      <div className="page-title">
        <div>
          <span className="eyebrow">КРЕДИТНЫЙ КАБИНЕТ</span>
          <h1>Мои заявки</h1>
          <p>Условия, решения и следующий шаг — в одном месте.</p>
        </div>
        <Link to="/applications/new" className="button primary">
          ＋ Новая заявка
        </Link>
      </div>
      <div className="intro-banner">
        <div>
          <span className="eyebrow">ДВИГАЙТЕ БИЗНЕС ВПЕРЁД</span>
          <h2>Финансирование с понятным процессом</h2>
          <p>Выберите продукт, заполните сведения и следите за рассмотрением.</p>
        </div>
        <Link to="/products" className="button secondary">
          Посмотреть продукты ↗
        </Link>
      </div>
      <div className="section-heading">
        <h2>Ваши заявки</h2>
        <button className="text-button" onClick={r.reload}>
          ↻ Обновить
        </button>
      </div>
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : r.data?.items.length ? (
        <div className="panel table-scroll">
          <table>
            <thead>
              <tr>
                <th>Компания / заявка</th>
                <th>Продукт и сумма</th>
                <th>Статус</th>
                <th>Обновлено</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {r.data.items.map((a) => (
                <tr key={a.id}>
                  <td>
                    <strong>{a.company.legalName}</strong>
                    <small>№ {a.id.slice(-8)}</small>
                  </td>
                  <td>
                    {a.product.name}
                    <small>
                      {money(a.amountRub)} · {a.termMonths ?? '—'} мес.
                    </small>
                  </td>
                  <td>
                    <Status app={a} />
                    <small>{workflowText(a.nextActionHint)}</small>
                  </td>
                  <td className="nowrap">{dateTime(a.updatedAt)}</td>
                  <td>
                    <Link
                      aria-label={'Открыть заявку ' + a.id.slice(-8)}
                      to={'/applications/' + a.id}
                    >
                      Открыть →
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty>Заявок пока нет. Создайте первую заявку или изучите продукты.</Empty>
      )}
      <div className="pagination">
        {cursor && (
          <button className="secondary" onClick={() => setCursor(undefined)}>
            К началу
          </button>
        )}
        {r.data?.nextCursor && (
          <button className="secondary" onClick={() => setCursor(r.data!.nextCursor!)}>
            Следующая страница →
          </button>
        )}
      </div>
    </>
  );
}
export function StaffQueue() {
  const { api } = useSession();
  const [params, setParams] = useSearchParams();
  const cursor = params.get('cursor') || undefined;
  const setCursor = (value: string | undefined) => setParams(value ? { cursor: value } : {});
  const r = useResource(
    async (signal) =>
      (await api!.call('listStaffManualReviews', { limit: 20, cursor }, undefined, signal)).data,
    [api, cursor],
  );
  return (
    <>
      <div className="page-title">
        <div>
          <span className="eyebrow">РАБОЧЕЕ МЕСТО СОТРУДНИКА</span>
          <h1>Ручное рассмотрение</h1>
          <p>Открытые дела и сведения, требующие вашего решения.</p>
        </div>
        <button className="secondary" onClick={r.reload}>
          ↻ Обновить очередь
        </button>
      </div>
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : r.data?.items.length ? (
        <div className="panel table-scroll">
          <table>
            <thead>
              <tr>
                <th>Заявка</th>
                <th>Версия</th>
                <th>Причина</th>
                <th>Создано</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {r.data.items.map((c) => (
                <tr key={c.id}>
                  <td>
                    № {c.applicationId.slice(-8)}
                    <small>{manualReviewStatusLabels[c.status]}</small>
                  </td>
                  <td>{c.dataVersion}</td>
                  <td>{c.reason}</td>
                  <td>{dateTime(c.createdAt)}</td>
                  <td>
                    <Link
                      to={
                        '/staff/applications/' +
                        c.applicationId +
                        '?from=' +
                        encodeURIComponent(
                          '/staff/manual-reviews' + (params.size ? '?' + params.toString() : ''),
                        )
                      }
                    >
                      Рассмотреть →
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty>Открытых дел нет.</Empty>
      )}
      <div className="pagination">
        {cursor && <button onClick={() => setCursor(undefined)}>К началу</button>}
        {r.data?.nextCursor && (
          <button onClick={() => setCursor(r.data!.nextCursor!)}>Следующая страница →</button>
        )}
      </div>
    </>
  );
}

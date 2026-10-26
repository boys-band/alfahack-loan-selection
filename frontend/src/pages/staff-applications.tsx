import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { useSession } from '../core/session';
import { useResource } from '../core/hooks';
import { approvalLabel } from '../core/staff-navigation';
import { money, dateTime, statusLabels } from '../core/state';
import { Empty, ErrorBox, Loading, Status } from '../components/common';

export function StaffApplications() {
  const { api, session } = useSession();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const status = params.get('status') || undefined;
  const approvalType = params.get('approvalType') || undefined;
  const cursor = params.get('cursor') || undefined;
  const limit = params.has('limit') ? Number(params.get('limit')) : 20;
  const r = useResource(
    async (signal) =>
      (
        await api!.call(
          'listStaffApplications',
          { status, approvalType, cursor, limit },
          undefined,
          signal,
        )
      ).data,
    [api, session?.identity, status, approvalType, cursor, limit],
  );
  function filter(key: string, value: string) {
    const next = new URLSearchParams(params);
    next.delete('cursor');
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next);
  }
  function page(nextCursor?: string) {
    const next = new URLSearchParams(params);
    if (nextCursor) next.set('cursor', nextCursor);
    else next.delete('cursor');
    setParams(next);
  }
  return (
    <>
      <div className="page-title">
        <div>
          <span className="eyebrow">РАБОЧЕЕ МЕСТО СОТРУДНИКА</span>
          <h1>Все заявки</h1>
          <p>Все клиенты и статусы. Реестр доступен только для чтения.</p>
        </div>
        <button className="secondary" onClick={r.reload}>
          ↻ Обновить реестр
        </button>
      </div>
      <section className="panel form-grid" aria-label="Фильтры реестра">
        <label>
          Статус заявки
          <select
            aria-label="Статус заявки"
            value={status || ''}
            onChange={(e) => filter('status', e.target.value)}
          >
            <option value="">Все статусы</option>
            {Object.entries(statusLabels).map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Тип одобрения
          <select
            aria-label="Тип одобрения"
            value={approvalType || ''}
            onChange={(e) => filter('approvalType', e.target.value)}
          >
            <option value="">Все</option>
            <option value="AUTO">Автоматически</option>
            <option value="MANUAL">Сотрудником</option>
          </select>
        </label>
      </section>
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : r.error ? null : r.data?.items.length ? (
        <div className="panel table-scroll">
          <table>
            <thead>
              <tr>
                <th>Компания / ИНН / заявка</th>
                <th>Программа</th>
                <th>Сумма / срок</th>
                <th>Статус</th>
                <th>Текущее решение</th>
                <th>Создано / обновлено</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {r.data.items.map((a) => (
                <tr key={a.id}>
                  <td>
                    <strong>{a.company.legalName}</strong>
                    <small>ИНН {a.company.inn}</small>
                    <small>{a.id}</small>
                  </td>
                  <td>
                    {a.product.name}
                    <small>Версия {a.product.version}</small>
                  </td>
                  <td>
                    {money(a.amountRub)}
                    <small>{a.termMonths ?? '—'} мес.</small>
                  </td>
                  <td>
                    <Status app={a} />
                  </td>
                  <td>{approvalLabel(a)}</td>
                  <td>
                    {dateTime(a.createdAt)}
                    <small>{dateTime(a.updatedAt)}</small>
                  </td>
                  <td>
                    <Link
                      to={
                        '/staff/applications/' +
                        a.id +
                        '?from=' +
                        encodeURIComponent(location.pathname + location.search)
                      }
                      aria-label={'Открыть заявку ' + a.id.slice(-8)}
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
        <Empty>Заявок по выбранным фильтрам нет.</Empty>
      )}
      <div className="pagination">
        {cursor && (
          <button className="secondary" onClick={() => page()}>
            К первой странице
          </button>
        )}
        {!r.loading && !r.error && r.data?.nextCursor && (
          <button className="secondary" onClick={() => page(r.data!.nextCursor!)}>
            Следующая страница →
          </button>
        )}
      </div>
    </>
  );
}

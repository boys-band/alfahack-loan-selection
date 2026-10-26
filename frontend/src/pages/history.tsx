import { Link, useParams } from 'react-router-dom';
import { useSession } from '../core/session';
import { useResource } from '../core/hooks';
import { dateTime, money, purposeLabels, statusLabels, timelineReason } from '../core/state';
import { ErrorBox, Loading, Status, Empty } from '../components/common';
import { SavedDataDetails } from '../components/application-data';
import { useStaffNavigation } from '../core/staff-navigation';
export function History() {
  const { id = '' } = useParams();
  const { api, session } = useSession();
  const navigation = useStaffNavigation(id);
  const base = session?.role === 'staff' ? navigation.base : '/applications/' + id;
  const suffix = session?.role === 'staff' ? navigation.suffix : '';
  const r = useResource(
    async (signal) =>
      (await api!.call('getTimeline', { applicationId: id }, undefined, signal)).data,
    [api, id],
  );
  return (
    <>
      <Link className="back" to={base + suffix}>
        ← Актуальная заявка
      </Link>
      <div className="page-title">
        <div>
          <span className="eyebrow">ЗАЯВКА № {id.slice(-8)}</span>
          <h1>История и версии</h1>
          <p>События в порядке серверной последовательности.</p>
        </div>
      </div>
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : (
        <section className="panel">
          {r.data?.items.length ? (
            <ol className="timeline">
              {[...r.data.items]
                .sort((a, b) => a.sequence - b.sequence)
                .map((e) => (
                  <li key={e.id}>
                    <div className="timeline-mark" aria-hidden>
                      {e.sequence}
                    </div>
                    <div>
                      <div className="section-heading">
                        <Status app={e} />
                        <span className="muted small">{dateTime(e.occurredAt)}</span>
                      </div>
                      <h3>
                        {session?.role === 'staff'
                          ? timelineReason(e)
                          : e.eventType === 'DRAFT_CREATED'
                            ? 'Черновик создан'
                            : e.eventType === 'DRAFT_UPDATED'
                              ? 'Черновик сохранён'
                              : statusLabels[e.status]}
                      </h3>
                      <p className="muted small">
                        {
                          { CUSTOMER: 'Предприниматель', STAFF: 'Сотрудник', SYSTEM: 'Система' }[
                            e.actor.type
                          ]
                        }
                      </p>
                      {e.dataVersion > 0 && (
                        <Link to={base + '/versions/' + e.dataVersion + suffix}>
                          Снимок данных {e.dataVersion} →
                        </Link>
                      )}
                    </div>
                  </li>
                ))}
            </ol>
          ) : (
            <Empty>История пока пуста.</Empty>
          )}
        </section>
      )}
    </>
  );
}
export function Version() {
  const { id = '', dataVersion = '' } = useParams();
  const { api, session } = useSession();
  const navigation = useStaffNavigation(id);
  const r = useResource(
    async (signal) =>
      (
        await api!.call(
          'getDataVersion',
          { applicationId: id, dataVersion: Number(dataVersion) },
          undefined,
          signal,
        )
      ).data,
    [api, id, dataVersion],
  );
  return (
    <>
      <Link
        className="back"
        to={
          session?.role === 'staff'
            ? navigation.base + '/history' + navigation.suffix
            : '/applications/' + id + '/history'
        }
      >
        ← История заявки
      </Link>
      <div className="page-title">
        <div>
          <span className="eyebrow">НЕИЗМЕНЯЕМЫЙ СНИМОК</span>
          <h1>Версия данных {dataVersion}</h1>
          <p>Сохранённые сведения доступны только для чтения.</p>
        </div>
      </div>
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : (
        r.data && (
          <section className="panel">
            <h2>{r.data.data.company.legalName}</h2>
            <p>{r.data.data.product.name}</p>
            <dl className="facts">
              <dt>Сумма</dt>
              <dd>{money(r.data.data.amountRub)}</dd>
              <dt>Срок</dt>
              <dd>{r.data.data.termMonths} мес.</dd>
              <dt>Цель</dt>
              <dd>{purposeLabels[r.data.data.purpose]}</dd>
              <dt>Выручка</dt>
              <dd>{money(r.data.data.financialData.monthlyRevenueRub)}</dd>
              <dt>Расходы</dt>
              <dd>{money(r.data.data.financialData.monthlyExpensesRub)}</dd>
              <dt>Текущие платежи</dt>
              <dd>{money(r.data.data.financialData.existingMonthlyDebtPaymentsRub)}</dd>
              <dt>Сохранено</dt>
              <dd>{dateTime(r.data.createdAt)}</dd>
              <dt>Источник</dt>
              <dd>
                {
                  {
                    INITIAL_SUBMISSION: 'Первоначальная отправка',
                    CUSTOMER_INFO: 'Дополнение сведений предпринимателем',
                    CUSTOMER_TERMS: 'Изменение условий предпринимателем',
                    STAFF_AMENDMENT: 'Уточнение сотрудника',
                  }[r.data.origin]
                }
              </dd>
            </dl>
            <SavedDataDetails data={r.data.data} staff={session?.role === 'staff'} />
          </section>
        )
      )}
    </>
  );
}

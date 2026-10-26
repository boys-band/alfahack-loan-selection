import { ValidatedForm, Select } from '../components/validated-form';
import { flushSync } from 'react-dom';
import { useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useSession } from '../core/session';
import { useResource } from '../core/hooks';
import { DataFields, useDirty } from '../components/fields';
import { fieldsOf, draftValues } from '../core/forms';
import { CommandBanner, useMutation } from '../components/commands';
import { ErrorBox, Loading, Empty, errorsForFields } from '../components/common';
import { money } from '../core/state';
export function Create() {
  const { api, commands } = useSession();
  const [params] = useSearchParams();
  const fromProducts = params.get('from') === 'products';
  const nav = useNavigate();
  const r = useResource(
    async (signal) => {
      const [companies, products] = await Promise.all([
        api!.call('listCompanies', {}, undefined, signal),
        api!.call('listProducts', {}, undefined, signal),
      ]);
      return { companies: companies.data.items, products: products.data.items };
    },
    [api],
  );
  const [company, setCompany] = useState(''),
    [product, setProduct] = useState(params.get('product') || ''),
    [fields, setFields] = useState(() =>
      fieldsOf({
        amountRub: null,
        termMonths: null,
        purpose: null,
        financialData: null,
        additionalInfo: {},
      }),
    );
  const [dirty, setDirty] = useState(false);
  useDirty(dirty);
  const refresh = async () => {
    await api!.call('listApplications', {}, undefined);
  };
  const mutation = useMutation('new', refresh);
  const submitLock = useRef(false);
  const selected = r.data?.products.find((p) => p.id === product);
  const productVersion =
    selected?.id === params.get('product')
      ? params.get('version') || selected?.version
      : selected?.version;
  const productResource = useResource(
    async (signal) =>
      selected
        ? (
            await api!.call(
              'getProduct',
              { productId: selected.id, version: productVersion },
              undefined,
              signal,
            )
          ).data
        : undefined,
    [api, selected?.id, productVersion],
  );
  const productDetail = productResource.data;
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (submitLock.current) return;
    submitLock.current = true;
    try {
      const detail = productDetail;
      if (!detail) throw new Error('Дождитесь загрузки условий выбранного продукта.');
      const own = await api!.call('getCompany', { companyId: company }, undefined);
      if (own.data.id !== company) return;
      const result = await mutation.run(
        'createApplication',
        {},
        {
          companyId: company,
          productId: detail.id,
          productVersion: detail.version,
          ...draftValues(fields),
        },
      );
      if (result) {
        flushSync(() => setDirty(false));
        nav('/applications/' + result.data.id);
      }
    } catch (e) {
      mutation.setError(e);
    } finally {
      submitLock.current = false;
    }
  }
  return (
    <>
      <Link className="back" to={fromProducts ? '/products' : '/applications'}>
        {fromProducts ? '← Кредитные продукты' : '← Мои заявки'}
      </Link>
      <div className="page-title">
        <div>
          <span className="eyebrow">НОВАЯ ЗАЯВКА</span>
          <h1>Начнём с вашего бизнеса</h1>
          <p>Черновик можно сохранить и заполнить позже.</p>
        </div>
      </div>
      <CommandBanner
        scope="new"
        refresh={refresh}
        onResult={(id) => {
          flushSync(() => setDirty(false));
          nav('/applications/' + id);
        }}
      />
      <ErrorBox error={r.error} retry={r.reload} />
      {r.loading ? (
        <Loading />
      ) : r.data && (!r.data.companies.length || !r.data.products.length) ? (
        <Empty>Для новой заявки нужны доступные компания и кредитный продукт.</Empty>
      ) : (
        <div className="create-layout">
          <ValidatedForm className="panel form-panel" onSubmit={submit}>
            <h2>Компания и продукт</h2>
            <div className="form-grid">
              <div className="field">
                <label htmlFor="company">Ваша компания</label>
                <Select
                  required
                  id="company"
                  value={company}
                  onChange={(e) => {
                    setCompany(e.target.value);
                    setDirty(true);
                  }}
                >
                  <option value="">Выберите компанию</option>
                  {r.data?.companies.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.legalName} · ИНН {c.inn}
                    </option>
                  ))}
                </Select>
              </div>
              <div className="field">
                <label htmlFor="product">Продукт</label>
                <Select
                  required
                  id="product"
                  value={product}
                  onChange={(e) => {
                    setProduct(e.target.value);
                    setFields((fields) => ({ ...fields, '/purpose': '' }));
                    setDirty(true);
                  }}
                >
                  <option value="">Выберите продукт</option>
                  {r.data?.products.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </Select>
              </div>
            </div>
            <h2>Условия и сведения</h2>
            <DataFields
              purposeCodes={productDetail?.purposeCodes}
              errors={errorsForFields(mutation.error)}
              errorKey={mutation.error}
              fields={fields}
              setFields={(f) => {
                setFields(f);
                setDirty(true);
              }}
            />
            <ErrorBox error={productResource.error} retry={productResource.reload} />
            <ErrorBox error={mutation.error} />
            <div className="form-footer">
              <span className="muted small">
                {dirty ? 'Есть несохранённые изменения' : 'Заполните доступные сведения'}
              </span>
              <button
                className="primary"
                disabled={!productDetail || mutation.busy || !!commands!.get('new')}
              >
                Создать черновик
              </button>
            </div>
          </ValidatedForm>
          <aside className="panel summary-panel">
            <span className="eyebrow">ВАШ ПРОДУКТ</span>
            <h2>{productDetail?.name || selected?.name || 'Выберите продукт'}</h2>
            {(productDetail || selected) && (
              <>
                <p>{(productDetail || selected)!.description}</p>
                <dl className="facts">
                  <dt>Сумма</dt>
                  <dd>
                    {money((productDetail || selected)!.minAmountRub)} —{' '}
                    {money((productDetail || selected)!.maxAmountRub)}
                  </dd>
                  <dt>Срок</dt>
                  <dd>
                    {(productDetail || selected)!.minTermMonths}–
                    {(productDetail || selected)!.maxTermMonths} мес.
                  </dd>
                  <dt>Ставка</dt>
                  <dd>{(productDetail || selected)!.annualRateBps / 100}% годовых</dd>
                </dl>
              </>
            )}
            <p className="muted small">
              Решение и доступные условия определяются после проверки данных. Создание черновика не
              отправляет заявку на рассмотрение.
            </p>
          </aside>
        </div>
      )}
    </>
  );
}

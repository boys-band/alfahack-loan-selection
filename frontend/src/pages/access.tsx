import { ValidatedForm, Input } from '../components/validated-form';
import { useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useSession } from '../core/session';
import { config } from '../core/config';
import { safeReturn } from '../core/state';
import { ErrorBox } from '../components/common';
import { ApiError } from '../api/client';
export function Access() {
  const { login } = useSession();
  const [role, setRole] = useState<'customer' | 'staff' | 'admin'>('customer'),
    [token, setToken] = useState(''),
    [error, setError] = useState<unknown>(),
    [invalidToken, setInvalidToken] = useState(false),
    [busy, setBusy] = useState(false);
  const nav = useNavigate(),
    location = useLocation();
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(undefined);
    setInvalidToken(false);
    try {
      if (!token.trim()) {
        setInvalidToken(true);
        setError(new Error('Введите демотокен.'));
        return;
      }
      await login(role, token.trim());
      const dest = safeReturn(location.state?.returnTo);
      nav(
        dest &&
          (role === 'staff'
            ? dest.startsWith('/staff')
            : role === 'admin'
              ? dest.startsWith('/admin')
              : !dest.startsWith('/staff') && !dest.startsWith('/admin'))
          ? dest
          : role === 'customer'
            ? '/applications'
            : role === 'staff'
              ? '/staff/manual-reviews'
              : '/admin/products',
        { replace: true },
      );
    } catch (e) {
      const status = e instanceof ApiError ? e.status : null;
      setInvalidToken(status === 401 || status === 403);
      setError(
        new Error(
          status === 401
            ? 'Демотокен не принят. Проверьте его и попробуйте снова.'
            : status === 403
              ? 'Этот токен не подходит для выбранной роли. Выберите другую роль или введите соответствующий токен.'
              : status === 0
                ? 'Не удалось подключиться к сервису входа. Проверьте соединение и попробуйте снова.'
                : 'Сервис входа временно недоступен. Попробуйте позже.',
        ),
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="access">
      <section className="access-story">
        <div className="brand">
          <span className="brand-mark">А</span>
          <span>
            АЛЬФА-БАНК <small>ДЛЯ БИЗНЕСА</small>
          </span>
        </div>
        <div>
          <span className="eyebrow">УМНЫЙ КРЕДИТНЫЙ КАБИНЕТ</span>
          <h1>
            Ваш следующий
            <br />
            шаг в бизнесе.
          </h1>
          <p>
            От идеи до финансирования —<br />с понятными условиями на каждом этапе.
          </p>
        </div>
        <small>Демонстрационная система · Синтетические данные</small>
      </section>
      <section className="access-form">
        <div className="access-card">
          <span className="eyebrow">ДОБРО ПОЖАЛОВАТЬ</span>
          <h2>Войти в кабинет</h2>
          <p className="muted">Выберите роль и введите свой демотокен.</p>
          {config.mock && <div className="mock-badge">◈ Режим HTTP-стабов</div>}
          <aside className="access-hints" aria-label="Тестовые токены">
            <h3>Тестовые токены</h3>
            <p>
              {config.mock ? 'Для текущего деморежима:' : 'Для стандартного локального стенда:'}
            </p>
            <dl>
              <dt>Предприниматель</dt>
              <dd>
                <code>{config.mock ? 'demo-customer' : 'demo-customer-only'}</code>
              </dd>
              <dt>Сотрудник</dt>
              <dd>
                <code>{config.mock ? 'demo-staff' : 'demo-staff-only'}</code>
              </dd>
              <dt>Администратор продуктов</dt>
              <dd>
                <code>{config.mock ? 'demo-admin' : 'demo-product-admin-only'}</code>
              </dd>
            </dl>
            {!config.mock && (
              <p>Если доступ на стенде настроен иначе, уточните токен у организатора.</p>
            )}
          </aside>
          <ValidatedForm onSubmit={submit}>
            <fieldset className="role-picker" disabled={busy}>
              <legend>Роль</legend>
              <label>
                <Input
                  type="radio"
                  name="role"
                  checked={role === 'customer'}
                  onChange={() => {
                    setRole('customer');
                    setToken('');
                    setError(undefined);
                    setInvalidToken(false);
                  }}
                />
                Предприниматель
              </label>
              <label>
                <Input
                  type="radio"
                  name="role"
                  checked={role === 'staff'}
                  onChange={() => {
                    setRole('staff');
                    setToken('');
                    setError(undefined);
                    setInvalidToken(false);
                  }}
                />
                Сотрудник
              </label>
              <label>
                <Input
                  type="radio"
                  name="role"
                  checked={role === 'admin'}
                  onChange={() => {
                    setRole('admin');
                    setToken('');
                    setError(undefined);
                    setInvalidToken(false);
                  }}
                />
                Администратор продуктов
              </label>
            </fieldset>
            <div className="field">
              <label htmlFor="token">Демотокен</label>
              <Input
                id="token"
                type="password"
                autoComplete="off"
                required
                data-required-message="Введите демотокен."
                disabled={busy}
                aria-invalid={invalidToken || undefined}
                aria-describedby={invalidToken ? 'access-error' : undefined}
                value={token}
                onChange={(e) => {
                  setToken(e.target.value);
                  setError(undefined);
                  setInvalidToken(false);
                }}
              />
            </div>
            <div id="access-error">
              <ErrorBox error={error} />
            </div>
            <button className="primary full" disabled={busy}>
              {busy ? 'Проверяем доступ…' : 'Войти →'}
            </button>
          </ValidatedForm>
          <p className="muted small">Договор, подпись и выдача моделируются.</p>
        </div>
      </section>
    </div>
  );
}

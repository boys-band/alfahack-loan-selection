import {
  NavLink,
  Navigate,
  Outlet,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import { useSession } from './core/session';
import { config } from './core/config';
import { Access } from './pages/access';
import { ApplicationList, Products, StaffQueue } from './pages/lists';
import { Create } from './pages/create';
import { ApplicationPage, AgreementPage } from './pages/application';
import { History, Version } from './pages/history';
import { StaffPage } from './pages/staff';
import { StaffApplications } from './pages/staff-applications';
import { AdminProducts, AdminProductEditor, AdminProductNew } from './pages/admin-products';
function Shell() {
  const { session, logout } = useSession();
  const location = useLocation(),
    nav = useNavigate();
  if (!session)
    return <Navigate to="/demo-access" replace state={{ returnTo: location.pathname }} />;
  const staff = session.role === 'staff';
  const admin = session.role === 'admin';
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">А</span>
          <span>
            АЛЬФА-БАНК<small>КРЕДИТНЫЙ КАБИНЕТ</small>
          </span>
        </div>
        <div className="workspace-label">
          {admin ? 'АДМИНИСТРАТОР ПРОДУКТОВ' : staff ? 'СОТРУДНИК БАНКА' : 'ДЛЯ ПРЕДПРИНИМАТЕЛЯ'}
        </div>
        <nav aria-label="Основная навигация">
          {admin ? (
            <NavLink to="/admin/products">
              <span aria-hidden>◇</span> Условия продуктов
            </NavLink>
          ) : staff ? (
            <>
              <NavLink to="/staff/applications">
                <span aria-hidden>▤</span> Все заявки
              </NavLink>
              <NavLink to="/staff/manual-reviews">
                <span aria-hidden>▤</span> Ручное рассмотрение
              </NavLink>
            </>
          ) : (
            <>
              <NavLink to="/applications">
                <span aria-hidden>▤</span> Мои заявки
              </NavLink>
              <NavLink to="/products">
                <span aria-hidden>◇</span> Кредитные продукты
              </NavLink>
            </>
          )}
        </nav>
        <div className="sidebar-bottom">
          <div className="avatar" aria-hidden>
            {admin ? 'А' : staff ? 'С' : 'П'}
          </div>
          <strong>{admin ? 'Администратор' : staff ? 'Сотрудник' : 'Предприниматель'}</strong>
          <small>Демонстрационный доступ</small>
          <button
            onClick={() => {
              logout();
              nav('/demo-access', { replace: true });
            }}
          >
            Выйти из кабинета ↗
          </button>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <span>
            Бизнес / <strong>Кредитный кабинет</strong>
          </span>
          <div>
            {config.mock && <span className="mock-badge">◈ HTTP-стабы</span>}
            <span className="topbar-note">Демо · без реальных платежей</span>
          </div>
        </header>
        <main id="main-content" tabIndex={-1}>
          <Outlet />
        </main>
        <footer>
          Альфа · Умный кредитный кабинет{' '}
          <span>Синтетические данные · Демонстрационная система</span>
        </footer>
      </div>
    </div>
  );
}
function Index() {
  const { session } = useSession();
  return (
    <Navigate
      replace
      to={
        !session
          ? '/demo-access'
          : session.role === 'staff'
            ? '/staff/manual-reviews'
            : session.role === 'admin'
              ? '/admin/products'
              : '/applications'
      }
    />
  );
}
function Role({
  role,
  children,
}: {
  role: 'customer' | 'staff' | 'admin';
  children: React.ReactNode;
}) {
  const { session } = useSession();
  return session?.role === role ? (
    children
  ) : (
    <div className="panel">
      <h1>Недостаточно прав</h1>
      <p>Этот экран доступен для другой роли.</p>
    </div>
  );
}
export function App() {
  return (
    <>
      <a className="skip-link" href="#main-content">
        К основному содержимому
      </a>
      <Routes>
        <Route path="/demo-access" element={<Access />} />
        <Route element={<Shell />}>
          <Route index element={<Index />} />
          <Route
            path="/admin/products"
            element={
              <Role role="admin">
                <AdminProducts />
              </Role>
            }
          />
          <Route
            path="/admin/products/new"
            element={
              <Role role="admin">
                <AdminProductNew />
              </Role>
            }
          />
          <Route
            path="/admin/products/id/:id"
            element={
              <Role role="admin">
                <AdminProductEditor />
              </Role>
            }
          />
          <Route
            path="/admin/products/:id"
            element={
              <Role role="admin">
                <AdminProductEditor />
              </Role>
            }
          />
          <Route
            path="/products"
            element={
              <Role role="customer">
                <Products />
              </Role>
            }
          />
          <Route
            path="/applications"
            element={
              <Role role="customer">
                <ApplicationList />
              </Role>
            }
          />
          <Route
            path="/applications/new"
            element={
              <Role role="customer">
                <Create />
              </Role>
            }
          />
          <Route
            path="/applications/:id"
            element={
              <Role role="customer">
                <ApplicationPage />
              </Role>
            }
          />
          <Route
            path="/applications/:id/history"
            element={
              <Role role="customer">
                <History />
              </Role>
            }
          />
          <Route
            path="/applications/:id/versions/:dataVersion"
            element={
              <Role role="customer">
                <Version />
              </Role>
            }
          />
          <Route
            path="/applications/:id/agreement"
            element={
              <Role role="customer">
                <AgreementPage />
              </Role>
            }
          />
          <Route
            path="/staff/applications"
            element={
              <Role role="staff">
                <StaffApplications />
              </Role>
            }
          />
          <Route
            path="/staff/applications/:id/history"
            element={
              <Role role="staff">
                <History />
              </Role>
            }
          />
          <Route
            path="/staff/applications/:id/versions/:dataVersion"
            element={
              <Role role="staff">
                <Version />
              </Role>
            }
          />
          <Route
            path="/staff/applications/:id/agreement"
            element={
              <Role role="staff">
                <AgreementPage />
              </Role>
            }
          />
          <Route
            path="/staff/manual-reviews"
            element={
              <Role role="staff">
                <StaffQueue />
              </Role>
            }
          />
          <Route
            path="/staff/applications/:id"
            element={
              <Role role="staff">
                <StaffPage />
              </Role>
            }
          />
          <Route
            path="*"
            element={
              <div className="panel">
                <h1>Страница не найдена</h1>
                <p>Проверьте адрес или выберите раздел в меню.</p>
              </div>
            }
          />
        </Route>
      </Routes>
    </>
  );
}

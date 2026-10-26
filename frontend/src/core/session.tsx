import { createContext, useContext, useState, useMemo, useRef, type ReactNode } from 'react';
import { Api } from '../api/client';
import { Commands } from './commands';
import { AdminCommands } from './admin-commands';
import { config } from './config';
export type Session = { role: 'customer' | 'staff' | 'admin'; token: string; identity: string };
function restore(): Session | null {
  try {
    const s = JSON.parse(sessionStorage.getItem('alfa.session') || 'null');
    return s &&
      ['customer', 'staff', 'admin'].includes(s.role) &&
      typeof s.token === 'string' &&
      typeof s.identity === 'string'
      ? s
      : null;
  } catch {
    return null;
  }
}
const Context = createContext<null | {
  session: Session | null;
  api: Api | null;
  commands: Commands | null;
  adminCommands: AdminCommands | null;
  revision: number;
  epoch: AbortSignal;
  login: (role: Session['role'], token: string) => Promise<void>;
  logout: () => void;
}>(null);
export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(restore);
  const [revision, setRevision] = useState(0);
  const controller = useRef(new AbortController());
  const api = useMemo(() => (session ? new Api(config.apiBase, session.token) : null), [session]);
  const commands = useMemo(
    () =>
      session
        ? new Commands(sessionStorage, session.identity, () => setRevision((n) => n + 1))
        : null,
    [session],
  );
  const adminCommands = useMemo(
    () =>
      session?.role === 'admin'
        ? new AdminCommands(sessionStorage, session.identity, () => setRevision((n) => n + 1))
        : null,
    [session],
  );
  function rotate() {
    controller.current.abort();
    controller.current = new AbortController();
  }
  async function login(role: Session['role'], token: string) {
    const probe = new Api(config.apiBase, token);
    await (role === 'customer'
      ? probe.call('listCompanies', {}, undefined)
      : role === 'staff'
        ? probe.call('listStaffManualReviews', {}, undefined)
        : probe.call('listAdminProducts', {}, undefined));
    const bytes = await crypto.subtle.digest(
      'SHA-256',
      new TextEncoder().encode(role + ':' + token),
    );
    const identity = Array.from(new Uint8Array(bytes), (n) => n.toString(16).padStart(2, '0')).join(
      '',
    );
    rotate();
    const s = { role, token, identity };
    sessionStorage.setItem('alfa.session', JSON.stringify(s));
    setSession(s);
  }
  function logout() {
    rotate();
    sessionStorage.removeItem('alfa.session');
    setSession(null);
  }
  return (
    <Context.Provider
      value={{
        session,
        api,
        commands,
        adminCommands,
        revision,
        epoch: controller.current.signal,
        login,
        logout,
      }}
    >
      {children}
    </Context.Provider>
  );
}
export function useSession() {
  const ctx = useContext(Context);
  if (!ctx) throw new Error('Missing session');
  return ctx;
}

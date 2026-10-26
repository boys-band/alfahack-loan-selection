import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ApiError, type Reply } from '../api/client';
import type { Application } from '../api/generated';
import { useSession } from './session';
import { pollingDelay } from './state';
export function useUnauthorized() {
  const { logout } = useSession();
  const nav = useNavigate(),
    location = useLocation();
  return useCallback(
    (error: unknown) => {
      if (error instanceof ApiError && error.status === 401) {
        logout();
        nav('/demo-access', { replace: true, state: { returnTo: location.pathname } });
      }
    },
    [logout, nav, location.pathname],
  );
}
export function useResource<T>(load: (signal: AbortSignal) => Promise<T>, deps: unknown[]) {
  const { epoch } = useSession();
  const resourceDeps = [...deps, epoch];
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  const priorDeps = useRef<unknown[]>([]);
  const current =
    resourceDeps.length === priorDeps.current.length &&
    resourceDeps.every((value, index) => Object.is(value, priorDeps.current[index]));
  const unauthorized = useUnauthorized();
  const loader = useRef(load);
  loader.current = load;
  const unauthorizedRef = useRef(unauthorized);
  unauthorizedRef.current = unauthorized;
  useEffect(() => {
    const ctrl = new AbortController();
    const signal = AbortSignal.any([ctrl.signal, epoch]);
    const changed =
      resourceDeps.length !== priorDeps.current.length ||
      resourceDeps.some((x, i) => !Object.is(x, priorDeps.current[i]));
    priorDeps.current = resourceDeps;
    setLoading(changed || data === undefined);
    setError(undefined);
    if (changed) setData(undefined);
    loader
      .current(signal)
      .then((x) => {
        if (!signal.aborted) setData(x);
      })
      .catch((e) => {
        if (!signal.aborted) {
          setError(e);
          unauthorizedRef.current(e);
        }
      })
      .finally(() => {
        if (!signal.aborted) setLoading(false);
      });
    return () => ctrl.abort();
  }, [...deps, epoch, attempt]);
  return {
    data: current ? data : undefined,
    error: current ? error : undefined,
    loading: !current || loading,
    reload: () => setAttempt((n) => n + 1),
  };
}
export function useApplication(id: string) {
  const { api, commands, session, epoch } = useSession();
  const [data, setData] = useState<Reply<Application>>();
  const [error, setError] = useState<unknown>();
  const [refreshing, setRefreshing] = useState(false);
  const refreshRef = useRef<() => Promise<void>>(async () => {});
  const unauthorized = useUnauthorized();
  const unauthorizedRef = useRef(unauthorized);
  unauthorizedRef.current = unauthorized;
  useEffect(() => {
    if (!api || !commands) return;
    let alive = true,
      timer: ReturnType<typeof setTimeout> | undefined,
      running: Promise<void> | null = null,
      latest: Application | undefined,
      failures = 0;
    const ctrl = new AbortController();
    const signal = AbortSignal.any([ctrl.signal, epoch]);
    setData(undefined);
    setError(undefined);
    const plan = (retryAfter = 0) => {
      clearTimeout(timer);
      const delay = pollingDelay(latest, failures, retryAfter);
      if (delay !== null && !document.hidden && alive) timer = setTimeout(() => void read(), delay);
    };
    const read = (): Promise<void> => {
      if (running) return running;
      if (!alive || signal.aborted) return Promise.resolve();
      clearTimeout(timer);
      setRefreshing(true);
      running = (async () => {
        let retry = 0;
        try {
          const reply = await api.call('getApplication', { applicationId: id }, undefined, signal);
          if (!alive) return;
          if (!latest || reply.data.version >= latest.version) {
            latest = reply.data;
            setData(reply);
            commands.reconcile(reply.data);
          }
          failures = 0;
          setError(undefined);
        } catch (e) {
          if (!alive || signal.aborted) return;
          failures++;
          retry = e instanceof ApiError ? e.retryAfter : 0;
          setError(e);
          unauthorizedRef.current(e);
        } finally {
          running = null;
          if (alive) {
            setRefreshing(false);
            plan(retry);
          }
        }
      })();
      return running;
    };
    refreshRef.current = async () => {
      if (running) await running;
      await read();
    };
    const focus = () => {
      if (!document.hidden) void read();
      else clearTimeout(timer);
    };
    document.addEventListener('visibilitychange', focus);
    window.addEventListener('focus', focus);
    void read();
    return () => {
      alive = false;
      ctrl.abort();
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', focus);
      window.removeEventListener('focus', focus);
    };
  }, [id, api, commands, session?.identity, epoch]);
  return { data, error, refreshing, refresh: useCallback(() => refreshRef.current(), []) };
}
export function useClock(serverDate: number | null | undefined) {
  const [tick, setTick] = useState(Date.now());
  const [offset, setOffset] = useState(0);
  useEffect(() => {
    if (serverDate) setOffset(serverDate - Date.now());
  }, [serverDate]);
  useEffect(() => {
    const t = setInterval(() => setTick(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  return { now: tick + offset, approximate: !serverDate };
}

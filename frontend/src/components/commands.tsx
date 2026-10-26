import { useEffect, useRef, useState } from 'react';
import type { Operations } from '../api/generated';
import type { Operation } from '../api/client';
import { useSession } from '../core/session';
import { useUnauthorized } from '../core/hooks';
import { ErrorBox } from './common';
export function useMutation(scope: string, refresh: () => Promise<void>) {
  const { api, commands, epoch } = useSession();
  const [error, setError] = useState<unknown>(),
    [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const alive = useRef(true);
  const unauthorized = useUnauthorized();
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  async function run<N extends Operation>(
    name: N,
    params: Operations[N]['params'],
    body: Operations[N]['body'],
    etag?: string,
  ) {
    if (lock.current || !alive.current || epoch.aborted) return null;
    lock.current = true;
    setBusy(true);
    setError(undefined);
    try {
      const c = commands!.prepare(name, params, body, scope, etag);
      const reply = await commands!.send(api!, c, epoch);
      if (!alive.current || epoch.aborted) return null;
      try {
        await refresh();
      } catch (e) {
        setError(e);
        unauthorized(e);
      }
      return alive.current && !epoch.aborted ? reply : null;
    } catch (e) {
      if (alive.current && !epoch.aborted) {
        setError(e);
        unauthorized(e);
        try {
          await refresh();
        } catch {
          /* preserve the original command error */
        }
      }
      return null;
    } finally {
      lock.current = false;
      if (alive.current) setBusy(false);
    }
  }
  return { run, error, setError, busy };
}
export function CommandBanner({
  scope,
  refresh,
  onResult,
}: {
  scope: string;
  refresh: () => Promise<void>;
  onResult?: (id: string) => void;
}) {
  const { commands, api, epoch } = useSession();
  const unauthorized = useUnauthorized();
  const [error, setError] = useState<unknown>(),
    [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now());
  const alive = useRef(true);
  const lock = useRef(false);
  useEffect(
    () => () => {
      alive.current = false;
    },
    [],
  );
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const command = commands?.get(scope);
  if (!command) return null;
  async function retry() {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError(undefined);
    try {
      await refresh();
      if (!alive.current || epoch.aborted) return;
      const r = await commands!.send(api!, command!, epoch);
      if (alive.current && !epoch.aborted) {
        try {
          await refresh();
        } catch (e) {
          setError(e);
        }
        if (alive.current && !epoch.aborted) onResult?.(r.data.id);
      }
    } catch (e) {
      if (alive.current && !epoch.aborted) {
        setError(e);
        unauthorized(e);
      }
    } finally {
      lock.current = false;
      if (alive.current) setBusy(false);
    }
  }
  return (
    <div className="alert command-banner" role="status">
      <strong>
        {command.state === 'conflict'
          ? 'Заявка изменилась'
          : command.state === 'blocked'
            ? 'Действие не выполнено'
            : command.state === 'accepted'
              ? 'Команда принята'
              : 'Исход команды пока неизвестен'}
      </strong>
      <p>
        {command.message ||
          'Сначала сверим заявку, затем повторим исходную команду с тем же ключом.'}
      </p>
      {command.state === 'unknown' && (
        <button className="secondary" onClick={retry} disabled={busy || now < command.retryAt}>
          {now < command.retryAt
            ? `Повтор через ${Math.ceil((command.retryAt - now) / 1000)} с`
            : 'Проверить и повторить исходную команду'}
        </button>
      )}
      {command.state === 'accepted' && (
        <button className="secondary" onClick={() => void refresh()}>
          Уточнить результат
        </button>
      )}
      {(command.state === 'conflict' || command.state === 'blocked') && (
        <button
          className="secondary"
          onClick={async () => {
            try {
              await refresh();
              if (alive.current && !epoch.aborted) commands!.acknowledge(command.key);
            } catch (e) {
              if (alive.current && !epoch.aborted) {
                setError(e);
                unauthorized(e);
              }
            }
          }}
        >
          Я ознакомился с изменениями — вернуться к форме
        </button>
      )}
      <ErrorBox error={error} />
    </div>
  );
}

import operations from '../../contract/operations.json';
import type { Operations, Problem } from './generated';
import { validate } from './schema';
export type Operation = keyof Operations;
export type Reply<T> = {
  data: T;
  etag: string | null;
  requestId: string | null;
  date: number | null;
  status: number;
};
export class ApiError extends Error {
  constructor(
    public status: number,
    public problem: Problem | null,
    public requestId: string | null = null,
    public retryAfter = 0,
  ) {
    super(
      (problem?.code === 'VALIDATION_ERROR' && problem.errors.length
        ? 'Проверьте заполненные поля.'
        : problem?.detail) ||
        {
          401: 'Демотокен не принят. Войдите снова.',
          403: 'Недостаточно прав для этого действия.',
          404: 'Ресурс недоступен или отсутствует.',
        }[status] ||
        (status
          ? 'Сервис временно недоступен. Повторите запрос.'
          : 'Ответ не получен. Исход команды пока неизвестен.'),
    );
  }
}
export function retryDelay(value: string | null, now = Date.now()) {
  if (!value) return 0;
  const sec = Number(value);
  return Number.isFinite(sec) ? Math.max(0, sec * 1000) : Math.max(0, Date.parse(value) - now) || 0;
}
export function describe<N extends Operation>(name: N, params: Operations[N]['params']) {
  const op = operations[name];
  let path: string = op.path;
  const query = new URLSearchParams();
  for (const p of op.params) {
    const value = (params as Record<string, unknown>)[p.name];
    if (value === undefined) {
      if (p.required) throw new Error(`Отсутствует ${p.name}`);
      continue;
    }
    if (p.location === 'path')
      path = path.replace(`{${p.name}}`, encodeURIComponent(String(value)));
    else query.set(p.name, String(value));
  }
  return {
    method: op.method,
    path: path + (query.size ? '?' + query : ''),
    bodySchema: op.body,
    responseSchema: op.response,
  };
}
export class Api {
  constructor(
    public base: string,
    public token: string,
    public fetcher: typeof fetch = fetch,
  ) {}
  async raw<T>(
    method: string,
    path: string,
    body: string | undefined,
    extra: Record<string, string> = {},
    signal?: AbortSignal,
    responseSchema?: string,
  ): Promise<Reply<T>> {
    const timeout = AbortSignal.timeout(10000);
    const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    let res: Response;
    try {
      res = await this.fetcher.call(globalThis, this.base + path, {
        method,
        headers: {
          Authorization: `Bearer ${this.token}`,
          'X-Request-Id': crypto.randomUUID(),
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...extra,
        },
        body,
        cache: 'no-store',
        signal: combined,
      });
    } catch (e) {
      if (signal?.aborted) throw e;
      throw new ApiError(0, null);
    }
    const requestId = res.headers.get('X-Request-Id');
    const date = Date.parse(res.headers.get('Date') || '');
    let data: unknown;
    try {
      data = await res.json();
    } catch {
      throw new ApiError(res.status >= 400 ? res.status : 502, null, requestId);
    }
    if (!res.ok) {
      let problem: Problem | null = null;
      try {
        validate('Problem', data);
        problem = data as Problem;
      } catch {
        /* never display arbitrary HTML */
      }
      throw new ApiError(
        res.status,
        problem,
        requestId,
        retryDelay(res.headers.get('Retry-After')),
      );
    }
    if (responseSchema) {
      try {
        validate(responseSchema, data);
      } catch {
        throw new Error(
          'Ответ API не соответствует согласованной схеме. Обновите данные или обратитесь к разработчику.',
        );
      }
    }
    return {
      data: data as T,
      etag: res.headers.get('ETag'),
      requestId,
      date: Number.isNaN(date) ? null : date,
      status: res.status,
    };
  }
  call<N extends Operation>(
    name: N,
    params: Operations[N]['params'],
    body: Operations[N]['body'],
    signal?: AbortSignal,
  ): Promise<Reply<Operations[N]['response']>> {
    const d = describe(name, params);
    if (d.bodySchema) validate(d.bodySchema, body);
    if (d.method !== 'GET') throw new Error('Команды отправляются только через журнал');
    return this.raw(d.method, d.path, undefined, {}, signal, d.responseSchema);
  }
}

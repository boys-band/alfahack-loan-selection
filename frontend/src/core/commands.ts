import { Api, ApiError, describe, type Operation, type Reply } from '../api/client';
import type { Application, Operations } from '../api/generated';
import { validate } from '../api/schema';
export type Command = {
  key: string;
  scope: string;
  name: Operation;
  method: string;
  path: string;
  body?: string;
  etag?: string;
  state: 'unknown' | 'accepted' | 'conflict' | 'blocked';
  receiptVersion?: number;
  resultId?: string;
  retryAt: number;
  message?: string;
};
export class Commands {
  private storageKey: string;
  private inFlight = new Set<string>();
  constructor(
    private storage: Storage,
    identity: string,
    private changed: () => void = () => {},
  ) {
    this.storageKey = 'alfa.commands.' + identity;
  }
  all(): Command[] {
    try {
      return JSON.parse(this.storage.getItem(this.storageKey) || '[]');
    } catch {
      throw new Error(
        'Журнал команд поврежден. Не отправляйте команды повторно до проверки заявки.',
      );
    }
  }
  get(scope: string) {
    return this.all().find((c) => c.scope === scope || c.resultId === scope);
  }
  private save(command: Command, replacedKey?: string) {
    const all = this.all().filter((c) => c.key !== command.key && c.key !== replacedKey);
    all.push(command);
    this.storage.setItem(this.storageKey, JSON.stringify(all));
    this.changed();
  }
  busy(scope: string) {
    return this.inFlight.has(scope);
  }
  prepare<N extends Operation>(
    name: N,
    params: Operations[N]['params'],
    body: Operations[N]['body'],
    scope: string,
    etag?: string,
  ): Command {
    const previous = this.get(scope);
    const interrupt =
      previous?.state === 'accepted' &&
      (name === 'cancelApplication' || name === 'retryProcessing');
    if (previous && (!interrupt || this.busy(scope)))
      throw new Error('Сначала разрешите исход предыдущей команды этой заявки.');
    const d = describe(name, params);
    if (d.bodySchema) validate(d.bodySchema, body);
    if (d.method === 'GET') throw new Error('GET не является командой');
    if (name !== 'createApplication' && !etag)
      throw new Error('Сначала перечитайте заявку для получения ETag.');
    const command: Command = {
      key: crypto.randomUUID(),
      scope,
      name,
      method: d.method,
      path: d.path,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      ...(etag ? { etag } : {}),
      state: 'unknown',
      retryAt: 0,
    };
    // A confirmed 202 may be superseded by a server-authorized cancellation or
    // retry. Unknown/conflicting requests must first retain and resolve their key.
    this.save(command, interrupt ? previous?.key : undefined);
    return command;
  }
  async send(api: Api, command: Command, signal?: AbortSignal): Promise<Reply<Application>> {
    const current = this.all().find((c) => c.key === command.key);
    if (!current) throw new Error('Команда не найдена в журнале');
    if (current.state === 'conflict' || current.state === 'blocked')
      throw new Error('Требуется подтверждение нового намерения.');
    if (this.inFlight.has(current.scope)) throw new Error('Команда уже отправляется.');
    if (Date.now() < current.retryAt) throw new Error('Сервис попросил подождать перед повтором.');
    this.inFlight.add(current.scope);
    this.changed();
    try {
      // Immutable wire request, including no-body and original ETag.
      const reply = await api.raw<Application>(
        current.method,
        current.path,
        current.body,
        { 'Idempotency-Key': current.key, ...(current.etag ? { 'If-Match': current.etag } : {}) },
        signal,
        'Application',
      );
      if (reply.status === 202)
        this.save({
          ...current,
          state: 'accepted',
          receiptVersion: reply.data.version,
          resultId: reply.data.id,
          message:
            current.name === 'cancelApplication'
              ? 'Проверяем возможность отмены'
              : 'Команда принята. Ожидаем фактический результат.',
        });
      else this.remove(current.key);
      return reply;
    } catch (error) {
      if (signal?.aborted) throw error;
      const e = error instanceof ApiError ? error : null;
      // A confirmed validation rejection did not save the draft. Corrected input
      // is a new intention; uncertain outcomes and conflicts still retain their key.
      if (
        e?.status === 422 &&
        e.problem?.code === 'VALIDATION_ERROR' &&
        (current.name === 'createApplication' || current.name === 'updateDraft')
      ) {
        this.remove(current.key);
        throw error;
      }
      const retryable =
        !e ||
        e.status === 0 ||
        e.status === 408 ||
        e.status === 429 ||
        e.status >= 500 ||
        e.problem?.code === 'IDEMPOTENCY_IN_PROGRESS';
      this.save({
        ...current,
        state: e?.status === 412 ? 'conflict' : retryable ? 'unknown' : 'blocked',
        retryAt: Date.now() + (e?.retryAfter || 0),
        message: error instanceof Error ? error.message : 'Ответ не получен',
      });
      throw error;
    } finally {
      this.inFlight.delete(current.scope);
      this.changed();
    }
  }
  reconcile(app: Application) {
    const c = this.get(app.id);
    if (c?.state === 'accepted' && app.version > (c.receiptVersion || Infinity) && !app.processing)
      this.remove(c.key);
  }
  acknowledge(key: string) {
    const c = this.all().find((c) => c.key === key);
    if (c && (c.state === 'conflict' || c.state === 'blocked')) this.remove(key);
    else throw new Error('Нельзя забыть команду с неизвестным исходом');
  }
  private remove(key: string) {
    this.storage.setItem(this.storageKey, JSON.stringify(this.all().filter((c) => c.key !== key)));
    this.changed();
  }
}

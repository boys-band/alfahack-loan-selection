import { Api, ApiError, describe, type Reply } from '../api/client';
import type {
  AdminProduct,
  CreateAdminProductRequest,
  PublishProductVersionRequest,
} from '../api/generated';
import { validate } from '../api/schema';

export type AdminCommand = {
  type?: 'publish' | 'create' | 'archive';
  key: string;
  productId: string;
  path: string;
  body: string;
  etag: string;
  headers?: Record<string, string>;
  state: 'unknown' | 'conflict' | 'blocked';
  retryAt: number;
};

export class AdminCommands {
  private storageKey: string;
  private inFlight = new Set<string>();

  constructor(
    private storage: Storage,
    identity: string,
    private changed: () => void = () => {},
  ) {
    this.storageKey = 'alfa.admin.commands.' + identity;
  }

  all(): AdminCommand[] {
    const data = JSON.parse(this.storage.getItem(this.storageKey) || '[]') as unknown;
    if (!Array.isArray(data)) throw new Error('Журнал публикаций повреждён.');
    return data as AdminCommand[];
  }

  get(
    productId: string,
    type: 'publish' | 'create' | 'archive' = 'publish',
  ): AdminCommand | undefined {
    return this.all().find(
      (entry) => entry.productId === productId && (entry.type || 'publish') === type,
    );
  }

  private scope(command: AdminCommand): string {
    return (command.type || 'publish') + ':' + command.productId;
  }

  private headers(key: string, etag?: string): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      'X-Request-Id': crypto.randomUUID(),
      'Idempotency-Key': key,
      ...(etag ? { 'If-Match': etag } : {}),
    };
  }

  busy(productId: string, type: 'publish' | 'create' | 'archive' = 'publish'): boolean {
    return this.inFlight.has(type + ':' + productId);
  }

  prepare(productId: string, body: PublishProductVersionRequest, etag: string): AdminCommand {
    if (this.get(productId)) throw new Error('Сначала разрешите исход прежней публикации.');
    if (!etag) throw new Error('Сначала загрузите актуальную версию продукта.');
    validate('PublishProductVersionRequest', body);
    const key = crypto.randomUUID();
    const command: AdminCommand = {
      type: 'publish',
      key,
      productId,
      path: describe('publishProductVersion', { productId }).path,
      body: JSON.stringify(body),
      etag,
      headers: this.headers(key, etag),
      state: 'unknown',
      retryAt: 0,
    };
    this.save(command);
    return command;
  }

  prepareCreate(body: CreateAdminProductRequest): AdminCommand {
    if (this.get(body.id, 'create')) throw new Error('Сначала разрешите исход прежнего создания.');
    validate('CreateAdminProductRequest', body);
    const key = crypto.randomUUID();
    const command: AdminCommand = {
      type: 'create',
      key,
      productId: body.id,
      path: describe('createAdminProduct', {}).path,
      body: JSON.stringify(body),
      etag: '',
      headers: this.headers(key),
      state: 'unknown',
      retryAt: 0,
    };
    this.save(command);
    return command;
  }

  prepareArchive(productId: string, reason: string, etag: string): AdminCommand {
    if (this.get(productId, 'archive'))
      throw new Error('Сначала разрешите исход прежнего архивирования.');
    if (!etag) throw new Error('Сначала загрузите актуальную версию продукта.');
    const body = { reason };
    validate('ArchiveAdminProductRequest', body);
    const key = crypto.randomUUID();
    const command: AdminCommand = {
      type: 'archive',
      key,
      productId,
      path: describe('archiveAdminProduct', { productId }).path,
      body: JSON.stringify(body),
      etag,
      headers: this.headers(key, etag),
      state: 'unknown',
      retryAt: 0,
    };
    this.save(command);
    return command;
  }

  async send(api: Api, command: AdminCommand, signal?: AbortSignal): Promise<Reply<AdminProduct>> {
    const type = command.type || 'publish';
    const current = this.get(command.productId, type);
    if (!current || current.key !== command.key)
      throw new Error('Административная команда не найдена.');
    if (current.state !== 'unknown') throw new Error('Нужно подтвердить новое намерение.');
    if (this.busy(command.productId, type)) throw new Error('Команда уже отправляется.');
    if (Date.now() < current.retryAt) throw new Error('Сервис попросил подождать перед повтором.');
    this.inFlight.add(this.scope(command));
    this.changed();
    try {
      const reply = await api.raw<AdminProduct>(
        'POST',
        current.path,
        current.body,
        current.headers || {
          ...(type === 'create' ? {} : { 'If-Match': current.etag }),
          'Idempotency-Key': current.key,
        },
        signal,
        'AdminProduct',
      );
      this.remove(command.productId, type);
      return reply;
    } catch (error) {
      if (signal?.aborted) throw error;
      const e = error instanceof ApiError ? error : null;
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
      });
      throw error;
    } finally {
      this.inFlight.delete(this.scope(command));
      this.changed();
    }
  }

  acknowledge(productId: string, type: 'publish' | 'create' | 'archive' = 'publish'): void {
    const command = this.get(productId, type);
    if (!command || command.state === 'unknown')
      throw new Error('Неизвестный исход нужно разрешить повтором исходной команды.');
    this.remove(productId, type);
  }

  private save(command: AdminCommand): void {
    this.storage.setItem(
      this.storageKey,
      JSON.stringify([
        ...this.all().filter((item) => this.scope(item) !== this.scope(command)),
        command,
      ]),
    );
    this.changed();
  }

  private remove(productId: string, type: 'publish' | 'create' | 'archive'): void {
    this.storage.setItem(
      this.storageKey,
      JSON.stringify(this.all().filter((item) => this.scope(item) !== type + ':' + productId)),
    );
    this.changed();
  }
}

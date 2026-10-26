import type {
  AdminProduct,
  CreateAdminProductRequest,
  Product,
  PublishProductVersionRequest,
} from '../api/generated';
import { validate } from '../api/schema';
import { MockBank, type MockReply } from './bank';

const clone = <T>(value: T): T => structuredClone(value);
const fields = [
  'name',
  'description',
  'purposeCodes',
  'minAmountRub',
  'maxAmountRub',
  'minTermMonths',
  'maxTermMonths',
  'annualRateBps',
  'minBusinessAgeMonths',
  'maxDebtServiceRatioBps',
] as const;

export class MockAdminCatalog {
  constructor(private bank: MockBank) {}

  private problem(status: number, code: string, detail: string, requestId: string): MockReply {
    return {
      status,
      headers: { 'X-Request-Id': requestId, 'Cache-Control': 'no-store' },
      body: {
        type: 'about:blank',
        title: code,
        status,
        detail,
        code,
        requestId,
        errors: [],
      },
    };
  }

  private detail(id: string): AdminProduct | undefined {
    const versions = this.bank.productVersions(id);
    const product = versions.at(-1);
    return product ? { product, active: !this.bank.state.archived?.[id], versions } : undefined;
  }

  async handle(
    method: string,
    url: string,
    body: string | undefined,
    headers: Headers,
  ): Promise<MockReply> {
    const path = new URL(url, 'http://mock').pathname.replace(/^\/api/, '');
    const requestId = headers.get('X-Request-Id') || crypto.randomUUID();
    const auth = headers.get('Authorization');
    if (auth !== 'Bearer demo-admin')
      return this.problem(
        auth === 'Bearer demo-customer' || auth === 'Bearer demo-staff' ? 403 : 401,
        auth === 'Bearer demo-customer' || auth === 'Bearer demo-staff'
          ? 'FORBIDDEN'
          : 'UNAUTHENTICATED',
        'Требуется роль администратора продукта',
        requestId,
      );
    const baseHeaders = {
      'X-Request-Id': requestId,
      'Cache-Control': 'no-store',
      Date: new Date(this.bank.now()).toUTCString(),
    };
    if (path === '/v2/admin/products' && method === 'GET') {
      const ids = new Set([
        ...this.bank.currentProducts().map((product) => product.id),
        ...Object.keys(this.bank.state.catalog || {}),
        'working-capital',
        'equipment-finance',
      ]);
      const data = {
        items: [...ids]
          .map((id) => this.detail(id))
          .filter((item): item is AdminProduct => !!item)
          .map(({ product, active }) => ({ product, active })),
      };
      validate('AdminProductList', data);
      return { status: 200, headers: baseHeaders, body: data };
    }
    if (path === '/v2/admin/products' && method === 'POST') {
      const key = headers.get('Idempotency-Key');
      if (!key) return this.problem(428, 'PRECONDITION_REQUIRED', 'Нужен ключ', requestId);
      let parsed: CreateAdminProductRequest;
      try {
        parsed = JSON.parse(body || '') as CreateAdminProductRequest;
      } catch {
        return this.problem(400, 'MALFORMED_JSON', 'Некорректный JSON', requestId);
      }
      const receiptKey = ['admin', method, path, key].join('|');
      const fingerprint = JSON.stringify([method, path, parsed]);
      const saved = this.bank.state.receipts[receiptKey];
      if (saved)
        return saved.fingerprint === fingerprint
          ? clone(saved.reply)
          : this.problem(
              409,
              'IDEMPOTENCY_CONFLICT',
              'Ключ использован с другим запросом',
              requestId,
            );
      const fault =
        this.bank.nextFault?.operation === 'createAdminProduct' ? this.bank.nextFault : null;
      if (fault) this.bank.nextFault = null;
      if (fault?.status) {
        const reply = this.problem(
          fault.status,
          fault.code || 'DEPENDENCY_UNAVAILABLE',
          'Управляемый сбой',
          requestId,
        );
        if (fault.retryAfter) reply.headers['Retry-After'] = String(fault.retryAfter);
        return reply;
      }
      try {
        validate('CreateAdminProductRequest', parsed);
      } catch {
        return this.problem(422, 'VALIDATION_ERROR', 'Проверьте поля', requestId);
      }
      if (parsed.minAmountRub > parsed.maxAmountRub || parsed.minTermMonths > parsed.maxTermMonths)
        return this.problem(422, 'VALIDATION_ERROR', 'Минимум превышает максимум', requestId);
      const existing = this.detail(parsed.id);
      if (existing && this.bank.state.createdRequests?.[parsed.id] !== JSON.stringify(parsed))
        return this.problem(409, 'PRODUCT_ALREADY_EXISTS', 'Идентификатор занят', requestId);
      if (!existing) {
        const { reason: _reason, ...terms } = parsed;
        this.bank.publishCatalog({ ...terms, version: '1' });
        (this.bank.state.createdRequests ||= {})[parsed.id] = JSON.stringify(parsed);
      }
      const current = this.detail(parsed.id)!;
      const reply: MockReply = {
        status: existing ? 200 : 201,
        headers: { ...baseHeaders, ETag: await this.bank.etag(current) },
        body: current,
      };
      this.bank.state.receipts[receiptKey] = { fingerprint, reply: clone(reply) };
      this.bank.checkpoint();
      return { ...reply, lost: fault?.lost, delay: fault?.delay };
    }
    const match = path.match(
      /^\/v2\/admin\/products\/([a-z][a-z0-9-]{1,63})(\/versions|\/archive)?$/,
    );
    if (!match) return this.problem(404, 'NOT_FOUND', 'Продукт не найден', requestId);
    const id = match[1];
    const current = this.detail(id);
    if (!current) return this.problem(404, 'NOT_FOUND', 'Продукт не найден', requestId);
    const etag = await this.bank.etag(current);
    if (method === 'GET' && !match[2]) {
      validate('AdminProduct', current);
      return { status: 200, headers: { ...baseHeaders, ETag: etag }, body: current };
    }
    if (method !== 'POST' || !match[2])
      return this.problem(405, 'METHOD_NOT_ALLOWED', 'Метод недоступен', requestId);
    const key = headers.get('Idempotency-Key');
    const ifMatch = headers.get('If-Match');
    if (!key || !ifMatch)
      return this.problem(428, 'PRECONDITION_REQUIRED', 'Нужны ключ и If-Match', requestId);
    let parsed: PublishProductVersionRequest | { reason: string };
    try {
      parsed = JSON.parse(body || '') as PublishProductVersionRequest;
    } catch {
      return this.problem(400, 'MALFORMED_JSON', 'Некорректный JSON', requestId);
    }
    const receiptKey = ['admin', method, path, key].join('|');
    const fingerprint = JSON.stringify([method, path, ifMatch, parsed]);
    const saved = this.bank.state.receipts[receiptKey];
    if (saved)
      return saved.fingerprint === fingerprint
        ? clone(saved.reply)
        : this.problem(
            409,
            'IDEMPOTENCY_CONFLICT',
            'Ключ использован с другим запросом',
            requestId,
          );
    const fault =
      this.bank.nextFault?.operation ===
      (match[2] === '/archive' ? 'archiveAdminProduct' : 'publishProductVersion')
        ? this.bank.nextFault
        : null;
    if (fault) this.bank.nextFault = null;
    if (fault?.status) {
      const reply = this.problem(
        fault.status,
        fault.code || 'DEPENDENCY_UNAVAILABLE',
        'Управляемый сбой',
        requestId,
      );
      if (fault.retryAfter) reply.headers['Retry-After'] = String(fault.retryAfter);
      return reply;
    }
    if (ifMatch !== etag)
      return this.problem(412, 'VERSION_CONFLICT', 'Продукт изменился', requestId);
    if (match[2] === '/archive') {
      try {
        validate('ArchiveAdminProductRequest', parsed);
      } catch {
        return this.problem(422, 'VALIDATION_ERROR', 'Проверьте обоснование', requestId);
      }
      if (current.active) (this.bank.state.archived ||= {})[id] = true;
      const after = this.detail(id)!;
      const reply: MockReply = {
        status: 200,
        headers: { ...baseHeaders, ETag: await this.bank.etag(after) },
        body: after,
      };
      this.bank.state.receipts[receiptKey] = { fingerprint, reply: clone(reply) };
      this.bank.checkpoint();
      return { ...reply, lost: fault?.lost, delay: fault?.delay };
    }
    if (!current.active)
      return this.problem(409, 'PRODUCT_ARCHIVED', 'Программа архивирована', requestId);
    try {
      validate('PublishProductVersionRequest', parsed);
    } catch (error) {
      return this.problem(
        422,
        'VALIDATION_ERROR',
        error instanceof Error ? error.message : 'Проверьте поля',
        requestId,
      );
    }
    const publishing = parsed as PublishProductVersionRequest;
    if (
      publishing.maxAmountRub < publishing.minAmountRub ||
      publishing.maxTermMonths < publishing.minTermMonths
    )
      return this.problem(422, 'VALIDATION_ERROR', 'Минимум превышает максимум', requestId);
    if (
      fields.every(
        (field) => JSON.stringify(current.product[field]) === JSON.stringify(publishing[field]),
      )
    )
      return this.problem(422, 'VALIDATION_ERROR', 'Нет изменений условий', requestId);
    const product = {
      ...current.product,
      ...Object.fromEntries(fields.map((field) => [field, publishing[field]])),
      version: String(BigInt(current.product.version) + 1n),
    } as Product;
    this.bank.publishCatalog(product);
    const after = this.detail(id)!;
    const reply: MockReply = {
      status: 201,
      headers: { ...baseHeaders, ETag: await this.bank.etag(after) },
      body: after,
    };
    this.bank.state.receipts[receiptKey] = { fingerprint, reply: clone(reply) };
    this.bank.checkpoint();
    return { ...reply, lost: fault?.lost, delay: fault?.delay };
  }
}

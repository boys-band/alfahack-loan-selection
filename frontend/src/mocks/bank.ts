// Contract HTTP stub. No part of this state machine is used in real API mode.
import examples from '../../contract/examples.json';
import seed from '../../contract/seed.json';
import operations from '../../contract/operations.json';
import { validate } from '../api/schema';
import type {
  Application,
  Agreement,
  DataVersion,
  TimelineEntry,
  ManualReviewCase,
  Company,
  Product,
  Account,
  DecisionResult,
  VerificationResult,
} from '../api/generated';
export type Scenario =
  | 'approved'
  | 'counteroffer'
  | 'declined'
  | 'validation-info'
  | 'verification-info'
  | 'decision-info'
  | 'manual'
  | 'unconfirmed'
  | 'payout'
  | 'technical';
type Exchange = {
  operationId: string;
  request: Record<string, unknown>;
  response: { status: number; headers?: Record<string, string>; body: unknown };
};
const ex = examples as unknown as Record<string, Exchange[]>;
const clone = <T>(x: T): T => structuredClone(x);
function fixture<T>(file: string, op: string, predicate: (x: any) => boolean = () => true): T {
  return clone(
    ex[file].find(
      (e) => e.operationId === op && e.response.status < 400 && predicate(e.response.body),
    )!.response.body as T,
  );
}
const allApproved = () =>
  ex.approved
    .filter((e) => e.operationId === 'getApplication')
    .map((e) => clone(e.response.body as Application));
export type MockReply = {
  status: number;
  headers: Record<string, string>;
  body: unknown;
  lost?: boolean;
  delay?: number;
};
type Job = { id: string; due: number; stage: string; body?: any };
type State = {
  catalog?: Record<string, Product[]>;
  archived?: Record<string, boolean>;
  createdRequests?: Record<string, string>;
  apps: Record<string, Application>;
  agreements: Record<string, Agreement>;
  versions: Record<string, DataVersion[]>;
  timelines: Record<string, TimelineEntry[]>;
  scenarios: Record<string, Scenario>;
  jobs: Job[];
  receipts: Record<string, { fingerprint: string; reply: MockReply }>;
};
export class MockBank {
  state: State;
  scenario: Scenario = 'approved';
  nextFault: {
    operation: string;
    status?: number;
    code?: string;
    retryAfter?: number;
    lost?: boolean;
    delay?: number;
  } | null = null;
  constructor(
    private storage?: Storage,
    public now: () => number = Date.now,
    public step = 1200,
  ) {
    let saved: State | null = null;
    try {
      saved = JSON.parse(storage?.getItem('alfa.mock.bank') || 'null');
    } catch {}
    this.state = saved || {
      apps: {},
      agreements: {},
      versions: {},
      timelines: {},
      scenarios: {},
      jobs: [],
      receipts: {},
      catalog: {},
    };
    this.state.catalog ||= {};
    this.state.archived ||= {};
    this.state.createdRequests ||= {};
  }
  reset() {
    this.state = {
      apps: {},
      agreements: {},
      versions: {},
      timelines: {},
      scenarios: {},
      jobs: [],
      receipts: {},
      catalog: {},
      archived: {},
      createdRequests: {},
    };
    this.save();
  }
  checkpoint() {
    this.save();
  }
  private save() {
    this.storage?.setItem('alfa.mock.bank', JSON.stringify(this.state));
  }
  private timestamp() {
    return new Date(this.now()).toISOString();
  }
  private future(hours = 72) {
    return new Date(this.now() + hours * 3600000).toISOString();
  }
  private problem(status: number, code: string, detail: string): MockReply {
    return {
      status,
      headers: { 'Retry-After': '1' },
      body: {
        type: 'urn:alfa:problem:' + code.toLowerCase(),
        title: 'Не удалось выполнить запрос',
        status,
        code,
        detail,
        requestId: crypto.randomUUID(),
        errors: [],
      },
    };
  }
  private productErrors(data: Application, product: Product): MockReply | null {
    const errors: Array<{ path: string; message: string }> = [];
    if (
      data.amountRub !== null &&
      (data.amountRub < product.minAmountRub || data.amountRub > product.maxAmountRub)
    )
      errors.push({
        path: '/amountRub',
        message: `Сумма кредита должна быть от ${product.minAmountRub} до ${product.maxAmountRub} ₽.`,
      });
    if (
      data.termMonths !== null &&
      (data.termMonths < product.minTermMonths || data.termMonths > product.maxTermMonths)
    )
      errors.push({
        path: '/termMonths',
        message: `Срок кредита должен быть от ${product.minTermMonths} до ${product.maxTermMonths} мес.`,
      });
    if (data.purpose !== null && !product.purposeCodes.includes(data.purpose))
      errors.push({
        path: '/purpose',
        message: 'Выберите цель кредита, доступную для выбранного продукта.',
      });
    if (!errors.length) return null;
    const reply = this.problem(422, 'VALIDATION_ERROR', 'Проверьте условия выбранной программы.');
    (reply.body as { errors: typeof errors }).errors = errors;
    return reply;
  }
  private timeline(
    a: Application,
    reason: string,
    from: Application['status'] | null,
    fromStage: Application['reviewStage'] = null,
    actor: 'SYSTEM' | 'CUSTOMER' | 'STAFF' = 'SYSTEM',
  ) {
    const list = (this.state.timelines[a.id] ||= []);
    list.push({
      id: crypto.randomUUID(),
      applicationId: a.id,
      sequence: list.length + 1,
      occurredAt: this.timestamp(),
      fromStatus: from,
      fromReviewStage: fromStage,
      status: a.status,
      reviewStage: a.reviewStage,
      dataVersion: a.dataVersion,
      actor: {
        type: actor,
        id:
          actor === 'SYSTEM'
            ? null
            : actor === 'CUSTOMER'
              ? a.customerId
              : '00000000-0000-4000-8000-000000000901',
      },
      eventType: from === null ? 'DRAFT_CREATED' : 'STATE_CHANGED',
      reason,
    });
  }
  private change(
    a: Application,
    patch: Partial<Application>,
    reason: string,
    actor: 'SYSTEM' | 'CUSTOMER' | 'STAFF' = 'SYSTEM',
  ) {
    const from = a.status,
      stage = a.reviewStage;
    Object.assign(a, patch, { version: a.version + 1, updatedAt: this.timestamp() });
    this.timeline(a, reason, from, stage, actor);
    this.save();
  }
  private snapshot(a: Application, origin: DataVersion['origin']) {
    const data: DataVersion['data'] = {
      company: clone(a.company),
      product: clone(a.product),
      amountRub: a.amountRub!,
      termMonths: a.termMonths!,
      purpose: a.purpose!,
      financialData: clone(a.financialData) as DataVersion['data']['financialData'],
      additionalInfo: clone(a.additionalInfo),
      consents: clone(a.consents!),
      staffAttestation: null,
    };
    (this.state.versions[a.id] ||= []).push({
      applicationId: a.id,
      dataVersion: a.dataVersion,
      data,
      createdAt: this.timestamp(),
      origin,
      actorId: a.customerId,
    });
  }
  private schedule(id: string, stage: string, body?: unknown) {
    this.state.jobs.push({ id, stage, body, due: this.now() + this.step });
    this.save();
  }
  private processing(
    step: NonNullable<Application['processing']>['step'],
  ): Application['processing'] {
    return { step, state: 'PENDING', operationId: crypto.randomUUID(), attemptCount: 0 };
  }
  private verification(a: Application) {
    const v = fixture<VerificationResult>('approved', 'createVerification');
    return {
      ...v,
      applicationId: a.id,
      dataVersion: a.dataVersion,
      completedAt: this.timestamp(),
      asOf: this.timestamp(),
    };
  }
  private decision(a: Application, kind = 'approved') {
    const d = fixture<DecisionResult>(kind, 'createDecision');
    d.applicationId = a.id;
    d.dataVersion = a.dataVersion;
    d.evaluatedAt = this.timestamp();
    if (d.offer) {
      d.offer = {
        ...d.offer,
        applicationId: a.id,
        dataVersion: a.dataVersion,
        productId: a.product.id,
        productVersion: a.product.version,
        expiresAt: this.future(168),
      };
    }
    return d;
  }
  private requestInfo(
    a: Application,
    source: NonNullable<Application['informationRequest']>['source'],
    requirements?: any,
  ) {
    const req = fixture<Application>(
      'information-and-versions',
      'getApplication',
      (x) => x.informationRequest?.source === (source === 'STAFF' ? 'ENRICHMENT' : source),
    ).informationRequest!;
    this.change(
      a,
      {
        status: 'NEEDS_INFO',
        reviewStage: null,
        processing: null,
        availableActions: ['PROVIDE_INFO', 'CANCEL'],
        deadlineKind: 'INFO',
        actionDeadline: this.future(),
        informationRequest: {
          ...req,
          id: crypto.randomUUID(),
          applicationId: a.id,
          dataVersion: a.dataVersion,
          createdAt: this.timestamp(),
          deadline: this.future(),
          source,
          requirements: requirements || req.requirements,
        },
      },
      'Нужны дополнительные сведения',
    );
  }
  private offer(a: Application, d?: DecisionResult) {
    d =
      d ||
      this.decision(a, this.state.scenarios[a.id] === 'counteroffer' ? 'counteroffer' : 'approved');
    this.change(
      a,
      {
        status: 'OFFER_READY',
        reviewStage: null,
        processing: null,
        technicalError: null,
        decision: d,
        availableActions: ['ACCEPT_OFFER', 'CHANGE_TERMS', 'CANCEL'],
        deadlineKind: 'OFFER',
        actionDeadline: d.offer!.expiresAt,
      },
      'Предложение подготовлено',
    );
  }
  private manual(a: Application, unconfirmed = false) {
    const c = fixture<ManualReviewCase>(
      'manual-review',
      'createManualReview',
      (x) => !!x.assessmentId !== unconfirmed,
    );
    c.id = crypto.randomUUID();
    c.applicationId = a.id;
    c.dataVersion = a.dataVersion;
    c.createdAt = this.timestamp();
    const v = this.verification(a);
    if (unconfirmed) {
      v.confirmed = false;
      v.outcome = 'MANUAL_REVIEW';
      v.facts = null;
    }
    this.change(
      a,
      {
        status: 'MANUAL_REVIEW',
        reviewStage: null,
        processing: null,
        verification: v,
        decision: unconfirmed ? null : this.decision(a, 'manual-review'),
        manualReview: c,
        availableActions: ['CANCEL'],
      },
      'Передано сотруднику',
    );
  }
  tick() {
    const due = this.state.jobs.filter((j) => j.due <= this.now());
    this.state.jobs = this.state.jobs.filter((j) => j.due > this.now());
    for (const job of due) {
      const a = this.state.apps[job.id];
      if (!a || ['CANCELLED', 'EXPIRED', 'REJECTED', 'DISBURSED'].includes(a.status)) continue;
      const s = this.state.scenarios[a.id];
      switch (job.stage) {
        case 'validation':
          this.change(
            a,
            { status: 'VALIDATION', reviewStage: null, processing: this.processing('VALIDATION') },
            'Первичная проверка',
          );
          this.schedule(a.id, 'enrichment');
          break;
        case 'enrichment':
          if (
            (s === 'validation-info' || a.purpose === 'REFINANCING') &&
            !a.additionalInfo.refinancedDebtRub
          ) {
            this.requestInfo(a, 'VALIDATION');
            break;
          }
          this.change(
            a,
            {
              status: 'IN_REVIEW',
              reviewStage: 'ENRICHMENT',
              processing: this.processing('ENRICHMENT'),
            },
            'Получаем и сверяем сведения',
          );
          this.schedule(a.id, 'scoring');
          break;
        case 'scoring':
          if (s === 'verification-info' && !a.additionalInfo.businessDescription) {
            this.requestInfo(a, 'ENRICHMENT');
            break;
          }
          if (s === 'unconfirmed') {
            this.manual(a, true);
            break;
          }
          this.change(
            a,
            {
              status: 'IN_REVIEW',
              reviewStage: 'SCORING',
              verification: this.verification(a),
              processing: this.processing('SCORING'),
            },
            'Оцениваем риск и условия',
          );
          this.schedule(a.id, 'decision');
          break;
        case 'decision':
          if (s === 'decision-info' && !a.additionalInfo.revenueExplanation) {
            this.requestInfo(a, 'SCORING');
            break;
          }
          if (s === 'manual') {
            this.manual(a);
            break;
          }
          if (s === 'technical') {
            const err = fixture<Application>('errors', 'getApplication');
            this.change(
              a,
              {
                technicalError: { ...err.technicalError!, lastAttemptAt: this.timestamp() },
                processing: { ...this.processing('SCORING')!, state: 'PAUSED' },
                availableActions: ['RETRY_PROCESSING', 'CANCEL'],
              },
              'Техническое ожидание',
            );
            break;
          }
          if (s === 'declined') {
            this.change(
              a,
              {
                status: 'REJECTED',
                reviewStage: null,
                decision: this.decision(a, 'declined'),
                availableActions: [],
                processing: null,
                closureReason: 'CREDIT_REJECTED',
              },
              'Решение: отказ',
            );
            break;
          }
          this.offer(a);
          break;
        case 'agreement': {
          const agreement = fixture<Agreement>('approved', 'getApplicationAgreement');
          agreement.id = crypto.randomUUID();
          agreement.applicationId = a.id;
          agreement.dataVersion = a.dataVersion;
          agreement.companyId = a.company.id;
          agreement.offer = clone(a.decision!.offer!);
          agreement.acceptedAt = a.acceptedOffer!.acceptedAt;
          agreement.createdAt = this.timestamp();
          agreement.signBy = this.future();
          this.state.agreements[a.id] = agreement;
          this.change(
            a,
            {
              status: 'AWAITING_SIGNATURE',
              processing: null,
              agreement: {
                id: agreement.id,
                status: 'READY',
                documentVersion: agreement.document.version,
                documentSha256: agreement.document.sha256,
                signBy: agreement.signBy,
                signedAt: null,
              },
              deadlineKind: 'SIGNATURE',
              actionDeadline: agreement.signBy,
              availableActions: ['SIGN', 'CANCEL'],
            },
            'Договор готов',
          );
          break;
        }
        case 'signed': {
          const ag = this.state.agreements[a.id];
          ag.status = 'SIGNED';
          ag.signature = {
            id: crypto.randomUUID(),
            customerId: a.customerId,
            documentVersion: ag.document.version,
            documentSha256: ag.document.sha256,
            signedAt: this.timestamp(),
            method: 'DEMO_CONSENT',
          };
          this.change(
            a,
            {
              status: 'SIGNED',
              deadlineKind: null,
              actionDeadline: null,
              agreement: { ...a.agreement!, status: 'SIGNED', signedAt: ag.signature.signedAt },
              processing: this.processing('PAYOUT_VALIDATION'),
              availableActions: [],
            },
            'Подпись подтверждена',
          );
          this.schedule(a.id, 'payout');
          break;
        }
        case 'payout':
          if (s === 'payout' && a.payoutInstruction.payoutVersion === 1) {
            const check = fixture<Application>('payout-recovery', 'getApplication').payoutCheck!;
            this.change(
              a,
              {
                processing: null,
                payoutCheck: {
                  ...check,
                  applicationId: a.id,
                  companyId: a.company.id,
                  agreementId: a.agreement!.id,
                  dataVersion: a.dataVersion,
                  settlementAccountId: a.payoutInstruction.settlementAccountId,
                  checkedAt: this.timestamp(),
                },
                availableActions: ['UPDATE_PAYOUT_ACCOUNT'],
              },
              'Проверьте реквизиты счета',
            );
            break;
          }
          this.change(
            a,
            {
              status: 'DISBURSING',
              processing: this.processing('DISBURSEMENT'),
              payoutInstruction: { ...a.payoutInstruction, operationId: crypto.randomUUID() },
              availableActions: [],
            },
            'Подтверждаем зачисление',
          );
          this.schedule(a.id, 'disbursed');
          break;
        case 'disbursed': {
          const d = fixture<Application>(
            'approved',
            'getApplication',
            (x) => x.status === 'DISBURSED',
          ).disbursement!;
          this.change(
            a,
            {
              status: 'DISBURSED',
              processing: null,
              availableActions: [],
              disbursement: {
                ...d,
                applicationId: a.id,
                companyId: a.company.id,
                agreementId: a.agreement!.id,
                dataVersion: a.dataVersion,
                operationId: a.payoutInstruction.operationId!,
                payoutVersion: a.payoutInstruction.payoutVersion,
                settlementAccountId: a.payoutInstruction.settlementAccountId,
                amountRub: a.decision!.offer!.amountRub,
                executedAt: this.timestamp(),
              },
            },
            'Выдача подтверждена',
          );
          break;
        }
        case 'cancel':
          if (this.state.agreements[a.id]?.status === 'SIGNED') {
            this.schedule(a.id, 'signed');
            break;
          }
          if (this.state.agreements[a.id]) {
            this.state.agreements[a.id].status = 'CLOSED';
            this.state.agreements[a.id].closureReason = 'CANCELLED';
          }
          this.change(
            a,
            {
              status: 'CANCELLED',
              processing: null,
              availableActions: [],
              deadlineKind: null,
              actionDeadline: null,
              closureReason: 'CUSTOMER_CANCELLED',
              agreement: a.agreement ? { ...a.agreement, status: 'CLOSED' } : null,
            },
            'Отмена подтверждена',
          );
          break;
        case 'staff':
          this.resolveStaff(a, job.body);
          break;
      }
    }
    for (const a of Object.values(this.state.apps)) {
      if (a.actionDeadline && this.now() >= Date.parse(a.actionDeadline) && !a.processing) {
        const reason =
          a.deadlineKind === 'INFO'
            ? 'INFO_TIMEOUT'
            : a.deadlineKind === 'OFFER'
              ? 'OFFER_TIMEOUT'
              : 'SIGNATURE_TIMEOUT';
        if (this.state.agreements[a.id]) {
          this.state.agreements[a.id].status = 'CLOSED';
          this.state.agreements[a.id].closureReason = 'EXPIRED';
        }
        this.change(
          a,
          {
            status: 'EXPIRED',
            reviewStage: null,
            deadlineKind: null,
            actionDeadline: null,
            availableActions: [],
            processing: null,
            technicalError: null,
            closureReason: reason,
            agreement: a.agreement ? { ...a.agreement, status: 'CLOSED' } : null,
          },
          'Срок истёк',
        );
      }
    }
    this.save();
  }
  private resolveStaff(a: Application, b: any) {
    a.manualReview = { ...a.manualReview!, status: 'RESOLVED', allowedActions: [] };
    if (b.action === 'APPROVE') {
      const terms = a.decision!.evaluatedTerms.find((t) => t.id === b.termsId)!;
      const d = this.decision(a);
      d.kind = 'MANUAL';
      d.staffAudit = {
        staffId: '00000000-0000-4000-8000-000000000901',
        actionId: crypto.randomUUID(),
        reason: b.reason,
        decidedAt: this.timestamp(),
      };
      d.offer = {
        ...d.offer!,
        amountRub: terms.amountRub,
        termMonths: terms.termMonths,
        termsId: terms.id,
      };
      this.offer(a, d);
    } else if (b.action === 'REJECT') {
      const d = this.decision(a, 'declined');
      d.kind = 'MANUAL';
      d.staffAudit = {
        staffId: '00000000-0000-4000-8000-000000000901',
        actionId: crypto.randomUUID(),
        reason: b.reason,
        decidedAt: this.timestamp(),
      };
      this.change(
        a,
        {
          status: 'REJECTED',
          processing: null,
          availableActions: [],
          decision: d,
          closureReason: 'CREDIT_REJECTED',
        },
        b.reason,
        'STAFF',
      );
    } else if (b.action === 'REQUEST_INFO') {
      this.requestInfo(a, 'STAFF', b.requirements);
    } else {
      Object.assign(a, b.changes || {});
      a.dataVersion++;
      this.snapshot(a, 'STAFF_AMENDMENT');
      if (b.factsResolution) {
        this.state.versions[a.id].at(-1)!.data.staffAttestation = {
          caseActionId: crypto.randomUUID(),
          staffId: '00000000-0000-4000-8000-000000000901',
          reason: b.reason,
          recordedAt: this.timestamp(),
          confirmedFacts: b.factsResolution.confirmedFacts,
        };
      }
      this.state.scenarios[a.id] = 'approved';
      this.change(
        a,
        {
          status: 'VALIDATION',
          reviewStage: null,
          processing: this.processing('VALIDATION'),
          verification: null,
          decision: null,
          manualReview: null,
        },
        b.reason,
        'STAFF',
      );
      this.schedule(a.id, 'enrichment');
    }
  }
  seed(s: Scenario, status?: Application['status']) {
    const a = fixture<Application>('approved', 'createApplication');
    a.id = crypto.randomUUID();
    a.createdAt = a.updatedAt = this.timestamp();
    this.state.apps[a.id] = a;
    this.state.scenarios[a.id] = s;
    this.timeline(a, 'Создана демозаявка', null);
    if (status && status !== 'DRAFT') {
      a.dataVersion = 1;
      a.consents = {
        personalDataConsent: true,
        creditBureauConsent: true,
        acceptedAt: this.timestamp(),
      };
      a.verification = this.verification(a);
      this.snapshot(a, 'INITIAL_SUBMISSION');
      if (status === 'MANUAL_REVIEW') this.manual(a, s === 'unconfirmed');
      else if (status === 'NEEDS_INFO')
        this.requestInfo(
          a,
          s === 'validation-info' ? 'VALIDATION' : s === 'decision-info' ? 'SCORING' : 'ENRICHMENT',
        );
      else if (status === 'OFFER_READY') this.offer(a);
      else if (status === 'IN_REVIEW') {
        a.status = 'IN_REVIEW';
        a.reviewStage = 'SCORING';
        this.state.scenarios[a.id] = 'technical';
        this.schedule(a.id, 'decision');
      } else {
        const t = allApproved().find((x) => x.status === status);
        if (t) {
          Object.assign(a, {
            ...t,
            id: a.id,
            createdAt: this.timestamp(),
            updatedAt: this.timestamp(),
          });
          if (a.decision?.offer) a.decision.offer.expiresAt = this.future(168);
          if (a.agreement) {
            const ag = fixture<Agreement>('approved', 'getApplicationAgreement');
            ag.applicationId = a.id;
            ag.signBy = this.future();
            this.state.agreements[a.id] = ag;
            a.agreement.signBy = ag.signBy;
          }
          if (a.actionDeadline) a.actionDeadline = this.future();
        }
      }
    }
    this.save();
    return a.id;
  }
  private project(a: Application) {
    const copy = clone(a);
    if (copy.actionDeadline && this.now() >= Date.parse(copy.actionDeadline))
      copy.availableActions = [];
    return copy;
  }
  async etag(a: unknown) {
    function sorted(v: any): any {
      return v && typeof v === 'object'
        ? Array.isArray(v)
          ? v.map(sorted)
          : Object.fromEntries(
              Object.keys(v)
                .sort()
                .map((k) => [k, sorted(v[k])]),
            )
        : v;
    }
    const bytes = await crypto.subtle.digest(
      'SHA-256',
      new TextEncoder().encode(JSON.stringify(sorted(a))),
    );
    return (
      '"' + Array.from(new Uint8Array(bytes), (x) => x.toString(16).padStart(2, '0')).join('') + '"'
    );
  }
  currentProducts(): Product[] {
    const ids = new Set([
      ...(seed.products as Product[]).map((product) => product.id),
      ...Object.keys(this.state.catalog || {}),
    ]);
    return [...ids]
      .filter((id) => !this.state.archived?.[id])
      .map((id) => this.productVersions(id).at(-1)!);
  }
  productVersions(id: string): Product[] {
    const original = (seed.products as Product[]).find((product) => product.id === id);
    return clone(this.state.catalog?.[id] || (original ? [original] : []));
  }
  publishCatalog(product: Product): void {
    const versions = this.productVersions(product.id);
    versions.push(clone(product));
    (this.state.catalog ||= {})[product.id] = versions;
    this.save();
  }
  async handle(
    method: string,
    url: string,
    body: string | undefined,
    headers: Headers,
  ): Promise<MockReply> {
    const path = new URL(url, 'http://mock').pathname.replace(/^\/api/, '');
    const query = new URL(url, 'http://mock').searchParams;
    const entry = Object.entries(operations).find(
      ([, o]) =>
        o.method === method &&
        new RegExp('^' + o.path.replace(/\{[^}]+\}/g, '[^/]+') + '$').test(path),
    );
    if (!entry) return this.problem(404, 'NOT_FOUND', 'Маршрут не найден');
    const [name, op] = entry;
    const auth = headers.get('Authorization');
    const role =
      auth === 'Bearer demo-customer' ? 'customer' : auth === 'Bearer demo-staff' ? 'staff' : null;
    if (!role)
      return this.problem(401, 'UNAUTHENTICATED', 'Введите синтетический демотокен из README.');
    const staff = path.startsWith('/v2/staff');
    if (
      (staff && role !== 'staff') ||
      (role === 'staff' &&
        !staff &&
        (method !== 'GET' || path.includes('/companies') || path.includes('/products')))
    )
      return this.problem(403, 'FORBIDDEN', 'Для действия нужна другая роль');
    const fault = this.nextFault?.operation === name ? this.nextFault : null;
    if (fault) this.nextFault = null;
    if (fault?.status)
      return this.problem(
        fault.status,
        fault.code ||
          {
            412: 'VERSION_CONFLICT',
            429: 'RATE_LIMITED',
            503: 'DEPENDENCY_UNAVAILABLE',
            422: 'VALIDATION_ERROR',
          }[fault.status] ||
          'INVALID_STATE',
        'Управляемый сбой HTTP-стаба',
      );
    const parts = path.split('/');
    const id = staff ? parts[4] : parts[3];
    const a = this.state.apps[id];
    const key = [auth, method, path, headers.get('Idempotency-Key')].join('|');
    const fingerprint = JSON.stringify([method, path, body, headers.get('If-Match')]);
    if (method !== 'GET') {
      if (!headers.get('Idempotency-Key'))
        return this.problem(400, 'VALIDATION_ERROR', 'Требуется Idempotency-Key');
      const saved = this.state.receipts[key];
      if (saved)
        return saved.fingerprint === fingerprint
          ? clone(saved.reply)
          : this.problem(
              409,
              'IDEMPOTENCY_CONFLICT',
              'Команда с этим ключом имеет другое тело или ETag',
            );
    }
    let b: any;
    try {
      b = body === undefined ? undefined : JSON.parse(body);
      if (op.body) validate(op.body, b);
      else if (body !== undefined) throw new Error('Неожиданное тело');
    } catch (e) {
      return this.problem(
        422,
        'VALIDATION_ERROR',
        e instanceof Error ? e.message : 'Поля не прошли проверку',
      );
    }
    const companies = seed.companies as Company[],
      products = this.currentProducts();
    let data: unknown;
    let status = 200;
    if (name === 'listCompanies') data = { items: companies };
    else if (name === 'getCompany') data = companies.find((c) => c.id === parts[3]);
    else if (name === 'listCompanySettlementAccounts') {
      const company = companies.find((c) => c.id === parts[3]);
      data = company
        ? {
            items: [
              {
                id: company.settlementAccountId,
                companyId: company.id,
                label: 'Основной демосчет',
                status: 'ACTIVE',
              },
              {
                id: '00000000-0000-4000-8000-000000001198',
                companyId: company.id,
                label: 'Резервный демосчет',
                status: 'ACTIVE',
              },
            ] satisfies Account[],
          }
        : undefined;
    } else if (name === 'listProducts') data = { items: products };
    else if (name === 'getProduct')
      data = query.get('version')
        ? this.productVersions(parts[3]).find((p) => p.version === query.get('version'))
        : products.find((p) => p.id === parts[3]);
    else if (name === 'listStaffApplications') {
      const status = query.get('status'),
        approvalType = query.get('approvalType');
      const limit = Number(query.get('limit') ?? 20);
      const statuses = new Set([
        'DRAFT',
        'SUBMITTED',
        'VALIDATION',
        'IN_REVIEW',
        'NEEDS_INFO',
        'MANUAL_REVIEW',
        'OFFER_READY',
        'OFFER_ACCEPTED',
        'AWAITING_SIGNATURE',
        'SIGNED',
        'DISBURSING',
        'DISBURSED',
        'REJECTED',
        'CANCELLED',
        'EXPIRED',
      ]);
      if (
        !Number.isInteger(limit) ||
        limit < 1 ||
        limit > 100 ||
        (status !== null && !statuses.has(status)) ||
        (approvalType !== null && !['AUTO', 'MANUAL'].includes(approvalType))
      )
        return this.problem(422, 'VALIDATION_ERROR', 'Неверные фильтры');
      let after: { createdAt: string; id: string } | undefined;
      if (query.has('cursor')) {
        try {
          const c = JSON.parse(atob(query.get('cursor')!));
          if (
            c.v !== 1 ||
            c.status !== status ||
            c.approvalType !== approvalType ||
            typeof c.id !== 'string' ||
            !Number.isFinite(Date.parse(c.createdAt))
          )
            throw Error();
          after = c;
        } catch {
          return this.problem(422, 'VALIDATION_ERROR', 'Неверный курсор');
        }
      }
      const all = Object.values(this.state.apps)
        .filter(
          (a) =>
            (!status || a.status === status) &&
            (!approvalType ||
              (a.decision?.kind === approvalType &&
                a.decision.outcome === 'APPROVED' &&
                a.decision.dataVersion === a.dataVersion)) &&
            (!after ||
              a.createdAt < after.createdAt ||
              (a.createdAt === after.createdAt && a.id < after.id)),
        )
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id));
      const page = all.slice(0, limit),
        last = page.at(-1);
      data = {
        items: page.map((a) => this.project(a)),
        nextCursor:
          all.length > limit && last
            ? btoa(
                JSON.stringify({
                  v: 1,
                  createdAt: last.createdAt,
                  id: last.id,
                  status,
                  approvalType,
                }),
              )
            : null,
      };
    } else if (name === 'listApplications' || name === 'listStaffManualReviews') {
      const all = Object.values(this.state.apps).filter(
        (x) => name === 'listApplications' || x.manualReview?.status === 'OPEN',
      );
      const offset = query.get('cursor') ? Number(atob(query.get('cursor')!)) : 0;
      const limit = Number(query.get('limit') || 20);
      if (!Number.isInteger(offset) || !Number.isInteger(limit) || limit < 1 || limit > 100)
        return this.problem(422, 'VALIDATION_ERROR', 'Неверный cursor/limit');
      data = {
        items: all
          .slice(offset, offset + limit)
          .map((x) => (name === 'listApplications' ? this.project(x) : x.manualReview)),
        nextCursor: offset + limit < all.length ? btoa(String(offset + limit)) : null,
      };
    } else if (name === 'createApplication') {
      const company = companies.find((c) => c.id === b.companyId),
        product = products.find((p) => p.id === b.productId && p.version === b.productVersion);
      if (!company || !product)
        return this.problem(404, 'NOT_FOUND', 'Компания или продукт недоступны');
      const draft = fixture<Application>('approved', 'createApplication');
      const id = crypto.randomUUID();
      Object.assign(draft, {
        id,
        company,
        product,
        amountRub: null,
        termMonths: null,
        purpose: null,
        financialData: null,
        additionalInfo: {},
        ...b,
      });
      delete (draft as any).companyId;
      delete (draft as any).productId;
      delete (draft as any).productVersion;
      draft.createdAt = draft.updatedAt = this.timestamp();
      this.state.apps[id] = draft;
      this.state.scenarios[id] = this.scenario;
      this.timeline(draft, 'Черновик создан', null, null, 'CUSTOMER');
      data = draft;
      status = 201;
    } else {
      if (!a) return this.problem(404, 'NOT_FOUND', 'Заявка недоступна или отсутствует');
      if (method !== 'GET') {
        if (headers.get('If-Match') !== (await this.etag(this.project(a))))
          return this.problem(
            412,
            'VERSION_CONFLICT',
            'Заявка изменилась. Перечитайте её и подтвердите новое действие.',
          );
        if (a.actionDeadline && this.now() >= Date.parse(a.actionDeadline))
          return this.problem(409, 'DEADLINE_EXPIRED', 'Срок действия истёк');
      }
      switch (name) {
        case 'getApplication':
          data = this.project(a);
          break;
        case 'getTimeline':
          data = { items: this.state.timelines[id] || [] };
          break;
        case 'getDataVersion':
          data = this.state.versions[id]?.find((v) => v.dataVersion === Number(parts[5]));
          break;
        case 'getApplicationAgreement':
          data = this.state.agreements[id];
          break;
        case 'getStaffManualReview':
          data = a.manualReview;
          break;
        default: {
          const allowed: Record<string, string> = {
            updateDraft: 'EDIT',
            submitApplication: 'SUBMIT',
            provideInformation: 'PROVIDE_INFO',
            changeTerms: 'CHANGE_TERMS',
            acceptOffer: 'ACCEPT_OFFER',
            signApplicationAgreement: 'SIGN',
            cancelApplication: 'CANCEL',
            updatePayoutAccount: 'UPDATE_PAYOUT_ACCOUNT',
            retryProcessing: 'RETRY_PROCESSING',
          };
          if (
            name !== 'submitStaffReviewAction' &&
            !a.availableActions.includes(allowed[name] as any)
          )
            return this.problem(409, 'INVALID_STATE', 'Действие сейчас недоступно');
          status = name === 'updateDraft' ? 200 : 202;
          const draftProduct =
            a.status === 'DRAFT' && (name === 'updateDraft' || name === 'submitApplication')
              ? this.currentProducts().find((product) => product.id === a.product.id)
              : undefined;
          if (a.status === 'DRAFT' && (name === 'updateDraft' || name === 'submitApplication')) {
            if (
              !draftProduct ||
              (name === 'submitApplication' && draftProduct.version !== a.product.version)
            )
              return this.problem(
                409,
                'PRODUCT_NOT_CURRENT',
                'Условия программы изменились. Проверьте новые условия и сохраните черновик перед отправкой.',
              );
            const invalid = this.productErrors(
              { ...a, ...(name === 'updateDraft' ? b : {}) },
              draftProduct,
            );
            if (invalid) return invalid;
          }
          if (name === 'updateDraft') {
            this.change(a, { ...b, product: draftProduct! }, 'Черновик сохранён', 'CUSTOMER');
          } else if (name === 'submitApplication') {
            if (
              !a.amountRub ||
              !a.termMonths ||
              !a.purpose ||
              !a.financialData ||
              Object.values(a.financialData).some((v) => v === null)
            )
              return this.problem(422, 'VALIDATION_ERROR', 'Заполните обязательные данные');
            a.dataVersion = 1;
            a.consents = { ...b, acceptedAt: this.timestamp() };
            this.snapshot(a, 'INITIAL_SUBMISSION');
            this.change(
              a,
              {
                status: 'SUBMITTED',
                processing: this.processing('VALIDATION'),
                availableActions: ['CANCEL'],
              },
              'Заявка отправлена',
              'CUSTOMER',
            );
            this.schedule(id, 'validation');
          } else if (name === 'provideInformation' || name === 'changeTerms') {
            if (
              name === 'provideInformation' &&
              b.informationRequestId !== a.informationRequest?.id
            )
              return this.problem(409, 'INVALID_STATE', 'Дозапрос изменился');
            const changes = name === 'provideInformation' ? b.changes : b;
            if (
              name === 'changeTerms' &&
              !['amountRub', 'termMonths', 'productId'].some(
                (k) =>
                  k in changes && changes[k] !== (k === 'productId' ? a.product.id : (a as any)[k]),
              )
            )
              return this.problem(422, 'VALIDATION_ERROR', 'Условия должны измениться');
            const { productId, productVersion, ...rest } = changes;
            Object.assign(a, rest);
            if (productId) {
              const product = products.find(
                (p) => p.id === productId && p.version === productVersion,
              );
              if (!product) return this.problem(409, 'PRODUCT_NOT_CURRENT', 'Продукт недоступен');
              a.product = product;
            }
            a.dataVersion++;
            this.snapshot(a, name === 'provideInformation' ? 'CUSTOMER_INFO' : 'CUSTOMER_TERMS');
            this.change(
              a,
              {
                status: name === 'provideInformation' ? 'SUBMITTED' : 'VALIDATION',
                reviewStage: null,
                processing: this.processing('VALIDATION'),
                deadlineKind: null,
                actionDeadline: null,
                informationRequest: null,
                decision: null,
                verification: null,
                manualReview: null,
                availableActions: ['CANCEL'],
              },
              'Создана новая версия данных',
              'CUSTOMER',
            );
            this.schedule(id, name === 'provideInformation' ? 'validation' : 'enrichment');
          } else if (name === 'acceptOffer') {
            const offer = a.decision?.offer;
            if (
              !offer ||
              b.offerId !== offer.id ||
              b.offerVersion !== offer.version ||
              b.dataVersion !== a.dataVersion
            )
              return this.problem(409, 'OFFER_NOT_CURRENT', 'Предложение больше не актуально');
            this.change(
              a,
              {
                status: 'OFFER_ACCEPTED',
                acceptedOffer: { ...b, acceptedAt: this.timestamp() },
                processing: this.processing('AGREEMENT'),
                actionDeadline: null,
                deadlineKind: null,
                availableActions: ['CANCEL'],
              },
              'Предложение принято',
              'CUSTOMER',
            );
            this.schedule(id, 'agreement');
          } else if (name === 'signApplicationAgreement') {
            const ag = this.state.agreements[id];
            if (
              !ag ||
              ag.id !== b.agreementId ||
              ag.document.sha256 !== b.documentSha256 ||
              ag.document.version !== b.documentVersion
            )
              return this.problem(409, 'DOCUMENT_MISMATCH', 'Документ изменился');
            this.change(
              a,
              { processing: this.processing('SIGNATURE'), availableActions: [] },
              'Команда подписи принята',
              'CUSTOMER',
            );
            this.schedule(id, 'signed');
          } else if (name === 'cancelApplication') {
            if (a.status === 'AWAITING_SIGNATURE') {
              this.change(
                a,
                { processing: this.processing('CLOSURE'), availableActions: [] },
                'Проверяем возможность отмены',
                'CUSTOMER',
              );
              this.schedule(id, 'cancel');
            } else {
              status = 200;
              this.change(
                a,
                {
                  status: 'CANCELLED',
                  reviewStage: null,
                  processing: null,
                  technicalError: null,
                  availableActions: [],
                  actionDeadline: null,
                  deadlineKind: null,
                  closureReason: 'CUSTOMER_CANCELLED',
                },
                b.reason,
                'CUSTOMER',
              );
              this.state.jobs = this.state.jobs.filter((j) => j.id !== id);
            }
          } else if (name === 'updatePayoutAccount') {
            if (
              ![a.company.settlementAccountId, '00000000-0000-4000-8000-000000001198'].includes(
                b.settlementAccountId,
              )
            )
              return this.problem(422, 'VALIDATION_ERROR', 'Выберите свой счет');
            this.change(
              a,
              {
                payoutInstruction: {
                  ...a.payoutInstruction,
                  payoutVersion: a.payoutInstruction.payoutVersion + 1,
                  settlementAccountId: b.settlementAccountId,
                },
                processing: this.processing('PAYOUT_VALIDATION'),
                availableActions: [],
              },
              'Реквизиты обновлены',
              'CUSTOMER',
            );
            this.schedule(id, 'payout');
          } else if (name === 'retryProcessing') {
            this.state.scenarios[id] = 'approved';
            this.change(
              a,
              {
                technicalError: null,
                processing: this.processing('SCORING'),
                availableActions: ['CANCEL'],
              },
              'Обработка возобновлена',
              'CUSTOMER',
            );
            this.schedule(id, 'decision');
          } else if (name === 'submitStaffReviewAction') {
            const c = a.manualReview;
            if (
              a.status !== 'MANUAL_REVIEW' ||
              !c ||
              c.status !== 'OPEN' ||
              c.dataVersion !== a.dataVersion ||
              !c.allowedActions.includes(b.action)
            )
              return this.problem(409, 'INVALID_STATE', 'Дело уже изменилось');
            if (
              b.action === 'APPROVE' &&
              (!a.verification?.confirmed ||
                !c.assessmentId ||
                !a.decision?.evaluatedTerms.some((t) => t.id === b.termsId && t.eligible))
            )
              return this.problem(409, 'SCORING_REQUIRED', 'Нет завершённой оценки');
            this.change(a, { processing: this.processing('MANUAL_ACTION') }, b.reason, 'STAFF');
            this.schedule(id, 'staff', b);
          }
          data = this.project(a);
        }
      }
    }
    if (data == null) return this.problem(404, 'NOT_FOUND', 'Ресурс недоступен или отсутствует');
    validate(op.response, data);
    const responseHeaders: Record<string, string> = {
      'X-Request-Id': headers.get('X-Request-Id') || crypto.randomUUID(),
      Date: new Date(this.now()).toUTCString(),
      'Cache-Control': 'no-store',
    };
    if (op.response === 'Application') responseHeaders.ETag = await this.etag(data);
    const reply = { status, headers: responseHeaders, body: clone(data) };
    if (method !== 'GET') {
      this.state.receipts[key] = { fingerprint, reply: clone(reply) };
      this.save();
    }
    return { ...reply, lost: fault?.lost, delay: fault?.delay };
  }
}

#!/usr/bin/env python3
"""Real HTTP smoke scenarios. Creates synthetic applications in the running stack."""
import json
import sys
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = (sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8080').rstrip('/') + '/api/v2'
CUSTOMER = 'demo-customer-only'
STAFF = 'demo-staff-only'
FINANCIAL = dict(monthlyRevenueRub=1000000, monthlyExpensesRub=500000, existingMonthlyDebtPaymentsRub=20000)


def request(method, path, body=None, token=CUSTOMER, etag=None, key=None):
    headers = {'Authorization': 'Bearer ' + token}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    if method != 'GET':
        headers['Idempotency-Key'] = key or str(uuid.uuid4())
    if etag:
        headers['If-Match'] = etag
    req = Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urlopen(req, timeout=20) as response:
            return json.load(response), response.headers
    except HTTPError as error:
        raise AssertionError(f'{method} {path}: {error.code} {error.read().decode()}') from error


def get(app_id, token=CUSTOMER):
    return request('GET', '/applications/' + app_id, token=token)


def command(app_id, name, body=None, token=CUSTOMER):
    _, headers = get(app_id, token)
    path = '/applications/' + app_id + '/' + name
    if name == 'manual-review/actions':
        path = '/staff' + path
    return request('POST', path, body, token, headers['ETag'])


def wait(app_id, status):
    until = time.monotonic() + 75
    seen = []
    while time.monotonic() < until:
        app, _ = get(app_id)
        stage = app['status'] + ('/' + app['reviewStage'] if app['reviewStage'] else '')
        if not seen or seen[-1] != stage:
            seen.append(stage)
        if app['status'] == status:
            print(f'  {app_id}: {" → ".join(seen)}', flush=True)
            return app
        if app['technicalError']:
            raise AssertionError(f'{app_id}: {app["technicalError"]}')
        time.sleep(.4)
    raise AssertionError(f'{app_id}: expected {status}, got {app["status"]}')


def create(company=101, financial=None, months=12, purpose='WORKING_CAPITAL', submit=True):
    app, _ = request('POST', '/applications', dict(
        companyId=f'00000000-0000-4000-8000-{company:012}', productId='working-capital',
        productVersion='1', amountRub=1000000, termMonths=months, purpose=purpose,
        financialData=financial or FINANCIAL, additionalInfo={}))
    print('created', app['id'], flush=True)
    if submit:
        command(app['id'], 'submit', dict(personalDataConsent=True, creditBureauConsent=True))
    return app['id']


def payout(app_id):
    app = wait(app_id, 'OFFER_READY')
    offer = app['decision']['offer']
    command(app_id, 'accept-offer', dict(offerId=offer['id'], offerVersion=offer['version'], dataVersion=app['dataVersion']))
    wait(app_id, 'AWAITING_SIGNATURE')
    agreement, _ = request('GET', '/applications/' + app_id + '/agreement')
    document = agreement['document']
    assert document['content']
    body = dict(agreementId=agreement['id'], documentVersion=document['version'], documentSha256=document['sha256'], consent=True)
    _, headers = get(app_id)
    key = str(uuid.uuid4())
    path = '/applications/' + app_id + '/sign'
    receipt, _ = request('POST', path, body, etag=headers['ETag'], key=key)
    replay, _ = request('POST', path, body, etag=headers['ETag'], key=key)
    assert replay == receipt, 'Repeated sign must replay the accepted command'
    app = wait(app_id, 'DISBURSED')
    assert app['disbursement']['amountRub'] == offer['amountRub']
    timeline, _ = request('GET', '/applications/' + app_id + '/timeline')
    assert timeline['items']
    request('GET', '/applications/' + app_id + '/versions/1')


def main():
    assert len(request('GET', '/companies')[0]['items']) == 5
    assert request('GET', '/products')[0]['items']
    print('Happy path, document, signature, payout, history:', flush=True)
    payout(create())
    print('Credit rejection:', flush=True)
    wait(create(102), 'REJECTED')
    print('Verification information request, version 2:', flush=True)
    app_id = create(103)
    app = wait(app_id, 'NEEDS_INFO')
    command(app_id, 'provide-info', dict(informationRequestId=app['informationRequest']['id'], changes=dict(additionalInfo=dict(businessDescription='Производство и ремонт оборудования'))))
    assert wait(app_id, 'OFFER_READY')['dataVersion'] == 2
    print('Validation information request:', flush=True)
    app_id = create(purpose='REFINANCING')
    app = wait(app_id, 'NEEDS_INFO')
    command(app_id, 'provide-info', dict(informationRequestId=app['informationRequest']['id'], changes=dict(additionalInfo=dict(refinancedDebtRub=1000000))))
    wait(app_id, 'OFFER_READY')
    print('Decision information request:', flush=True)
    app_id = create(financial={**FINANCIAL, 'monthlyExpensesRub': 0})
    app = wait(app_id, 'NEEDS_INFO')
    command(app_id, 'provide-info', dict(informationRequestId=app['informationRequest']['id'], changes=dict(additionalInfo=dict(revenueExplanation='Расходы учтены и подтверждены отчетностью'))))
    wait(app_id, 'OFFER_READY')
    print('Counteroffer, changed terms and version history:', flush=True)
    app_id = create(financial={**FINANCIAL, 'monthlyRevenueRub':600000})
    app = wait(app_id, 'OFFER_READY')
    offer = app['decision']['offer']
    assert offer['amountRub'] == 750000 and offer['termMonths'] == 36, offer
    command(app_id, 'change-terms', {'amountRub':700000, 'termMonths':36})
    assert wait(app_id, 'OFFER_READY')['dataVersion'] == 2
    request('GET', '/applications/' + app_id + '/versions/1')
    print('Manual approval with scored facts:', flush=True)
    app_id = create(105, {**FINANCIAL, 'existingMonthlyDebtPaymentsRub':160000}, 36)
    app = wait(app_id, 'MANUAL_REVIEW')
    assert 'APPROVE' in app['manualReview']['allowedActions']
    request('GET', '/staff/manual-reviews', token=STAFF)
    request('GET', '/staff/applications/' + app_id + '/manual-review', token=STAFF)
    terms = next(t for t in app['decision']['evaluatedTerms'] if t['eligible'])
    command(app_id, 'manual-review/actions', dict(action='APPROVE', reason='Данные и условия проверены сотрудником', termsId=terms['id'], requirements=[], changes=None, factsResolution=None), STAFF)
    wait(app_id, 'OFFER_READY')
    print('Disputed facts forbid manual approval:', flush=True)
    app_id = create(104)
    app = wait(app_id, 'MANUAL_REVIEW')
    assert 'APPROVE' not in app['manualReview']['allowedActions']
    print('Cancellation:', flush=True)
    app_id = create(submit=False)
    command(app_id, 'cancel', dict(reason='Проверка отмены тестовой заявки'))
    wait(app_id, 'CANCELLED')
    print('ALL SMOKE SCENARIOS PASSED', flush=True)

if __name__ == '__main__':
    main()

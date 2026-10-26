#!/usr/bin/env python3
"""Exercise schemas on a disposable PostgreSQL container; requires Docker only."""
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea'
SERVICES = ('applications', 'catalog', 'verification', 'decision', 'agreements', 'disbursement')

class Raw(str):
    pass

def literal(value):
    if isinstance(value, Raw): return str(value)
    if value is None: return 'NULL'
    if isinstance(value, bool): return 'true' if value else 'false'
    if isinstance(value, (int, float)): return str(value)
    if isinstance(value, (dict, list)):
        return "'" + json.dumps(value, ensure_ascii=False).replace("'", "''") + "'::jsonb"
    return "'" + str(value).replace("'", "''") + "'"

def insert(table, **values):
    return f"INSERT INTO {table} ({', '.join(values)}) VALUES ({', '.join(literal(v) for v in values.values())});"

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()

def ident(number):
    return f'10000000-0000-4000-8000-{number:012d}'

def main():
    container = 'alfa-db-test-' + uuid.uuid4().hex[:12]
    count = 0
    started = False
    def command(args, **kwargs):
        # Preserve SQL document bytes on Windows: text-mode stdin rewrites LF to CRLF,
        # changing the hash computed by PostgreSQL for multiline agreement content.
        if isinstance(kwargs.get('input'), str):
            kwargs['input'] = kwargs['input'].encode('utf-8')
            result = subprocess.run(args, capture_output=True, **kwargs)
            result.stdout = result.stdout.decode('utf-8', errors='replace')
            result.stderr = result.stderr.decode('utf-8', errors='replace')
            return result
        return subprocess.run(args, text=True, capture_output=True, **kwargs)
    def check(service, label, sql, error=None, user=None):
        nonlocal count
        result = command(['docker', 'exec', '-i', container, 'psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1',
                          '-v', 'VERBOSITY=verbose', '-U', user or service + '_app', '-d', 'alfa_' + service],
                         input='BEGIN;\n' + sql + '\nCOMMIT;\n')
        if error is None and result.returncode:
            raise AssertionError(f'{service}: {label}\n{result.stderr}')
        if error is not None and (result.returncode == 0 or error not in result.stderr):
            raise AssertionError(f'{service}: {label}; expected SQLSTATE {error}\n{result.stderr}')
        count += 1
    try:
        result = command(['docker', 'run', '--detach', '--rm', '--name', container, '--network', 'none',
                          '--tmpfs', '/var/lib/postgresql/data:rw', '-e', 'POSTGRES_PASSWORD=temporary-schema-check',
                          '-v', str(ROOT / 'database') + ':/schema/database:ro',
                          '-v', str(ROOT / 'services') + ':/schema/services:ro', IMAGE])
        if result.returncode: raise RuntimeError(result.stderr)
        started = True
        deadline = time.monotonic() + 40
        while command(['docker', 'exec', container, 'sh', '-c',
                      'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres']).returncode:
            if time.monotonic() >= deadline: raise RuntimeError('PostgreSQL startup timed out')
            time.sleep(0.2)
        result = command(['docker', 'exec', container, 'bash', '/schema/database/bootstrap.sh'])
        if result.returncode: raise RuntimeError(result.stderr + '\n' + result.stdout[-3000:])
        seed = json.loads((ROOT / 'contracts/examples/seed.json').read_text())
        approved = json.loads((ROOT / 'contracts/examples/approved.json').read_text())['exchanges']
        def exchange(operation): return next(x for x in approved if x['operationId'] == operation)
        data = exchange('createVerification')['request']['body']['data']
        verification = exchange('createVerification')['response']['body']
        decision = exchange('createDecision')['response']['body']
        agreement = exchange('createAgreement')['response']['body']
        signed = exchange('signAgreement')['response']['body']
        payout = exchange('createPayoutCheck')['response']['body']
        disbursement = exchange('createDisbursement')['response']['body']
        app = verification['applicationId']; customer = seed['customer']['id']; company = data['company']['id']
        for service in SERVICES:
            check(service, 'schema owner is separate', 'CREATE TABLE forbidden_ddl (id integer);', '42501')
            result = command(['docker', 'exec', container, 'psql', '-X', '-At', '-U', 'postgres', '-d', 'postgres', '-c',
                f"SELECT count(*) FROM pg_database WHERE datname LIKE 'alfa_%' AND has_database_privilege('{service}_app', oid, 'CONNECT');"])
            assert result.returncode == 0 and result.stdout.strip() == '1', f'Unexpected DB access: {service}'
            count += 1
        # Existing seeds may be applied again without mutating immutable data.
        for service in SERVICES:
            sql = (ROOT / 'database/seeds' / (service + '.sql')).read_text().replace('BEGIN;', '').replace('COMMIT;', '')
            check(service, 'idempotent seed', sql)
        check('catalog', 'product version is immutable', "UPDATE product_versions SET annual_rate_bps=1000;", '23514')
        check('catalog', 'current pointer needs existing version', "UPDATE current_products SET version='999';", '23503')
        draft = json.loads(json.dumps(data)); draft['consents'] = None
        def event(version, status, stage=None, revision=1, application=app):
            return insert('application_timeline', id=ident(100+version), application_id=application,
                sequence=version, aggregate_version=version, data_version=revision, status=status,
                review_stage=stage, actor_type='SYSTEM', actor_id=None, event_type='STATE_CHANGED', reason='Schema verification')
        check('applications', 'create draft with atomic history',
              insert('applications', id=app, customer_id=customer, input_data=draft) + event(1, 'DRAFT', revision=0))
        check('applications', 'missing history rolls back',
              insert('applications', id=ident(999), customer_id=customer, input_data=draft), '23514')
        check('applications', 'optimistic version must increase', f"UPDATE applications SET version=1 WHERE id={literal(app)};", '23514')
        check('applications', 'submit and immutable data version',
              f"UPDATE applications SET version=2, data_version=1, status='SUBMITTED', input_data={literal(data)} WHERE id={literal(app)};" +
              insert('application_data_versions', application_id=app, data_version=1, payload=data, origin='INITIAL_SUBMISSION', actor_id=customer) + event(2, 'SUBMITTED'))
        check('applications', 'submitted snapshot is immutable', "UPDATE application_data_versions SET payload=payload;", '23514')
        modified = json.loads(json.dumps(data)); modified['amountRub'] = 900000
        check('applications', 'submitted data cannot change in place',
              f"UPDATE applications SET version=3,input_data={literal(modified)} WHERE id={literal(app)};", '23514')
        check('applications', 'validation', f"UPDATE applications SET version=3,status='VALIDATION' WHERE id={literal(app)};" + event(3,'VALIDATION'))
        check('applications', 'enrichment substate', f"UPDATE applications SET version=4,status='IN_REVIEW',review_stage='ENRICHMENT' WHERE id={literal(app)};" + event(4,'IN_REVIEW','ENRICHMENT'))
        check('applications', 'review stage is mandatory', f"UPDATE applications SET version=5,review_stage=NULL WHERE id={literal(app)};", '23514')
        check('applications', 'review cannot skip scoring', f"UPDATE applications SET version=5,status='VALIDATION',review_stage=NULL WHERE id={literal(app)};", '23514')
        request = exchange('createVerification')['request']['body']
        verification_sql = dict(id=verification['id'], application_id=app, data_version=1, outcome='COMPLETE',
            as_of=verification['asOf'], completed_at=verification['completedAt'], raw_facts=verification['rawFacts'],
            facts=verification['facts'], confirmed=True, requirements=[], findings=verification['findings'],
            request_payload=request, request_hash=digest(request))
        check('verification', 'persist completed facts', insert('verification_results', **verification_sql))
        check('verification', 'one check per data version', insert('verification_results', **{**verification_sql, 'id':ident(301)}), '23505')
        check('verification', 'complete result requires confirmation', insert('verification_results', **{**verification_sql, 'id':ident(302),'application_id':ident(201),'confirmed':False,'request_payload':{**request,'applicationId':ident(201)}}), '23514')
        check('verification', 'results are immutable', 'UPDATE verification_results SET facts=facts;', '23514')
        check('applications', 'scoring after complete facts', f"UPDATE applications SET version=5,review_stage='SCORING',verification_result={literal(verification)} WHERE id={literal(app)};" + event(5,'IN_REVIEW','SCORING'))
        check('applications', 'scoring cannot move back to enrichment', f"UPDATE applications SET version=6,review_stage='ENRICHMENT' WHERE id={literal(app)};", '23514')
        assessment = decision['assessmentId']; candidate = decision['evaluatedTerms'][0]
        check('decision', 'assessment and evaluated candidate',
            insert('assessments', id=assessment, application_id=app, data_version=1, verification_id=verification['id'],
                verification_snapshot=verification, input_snapshot=data, policy_version=decision['policyVersion']) +
            insert('evaluated_terms', id=candidate['id'], assessment_id=assessment, ordinal=1,
                amount_rub=candidate['amountRub'],term_months=candidate['termMonths'],annual_rate_bps=candidate['annualRateBps'],
                monthly_payment_rub=candidate['monthlyPaymentRub'],risk_snapshot=candidate['risk'],eligible=True,reasons=[]))
        drequest=exchange('createDecision')['request']['body']; offer=decision['offer']
        drow=dict(id=decision['id'],application_id=app,data_version=1,kind='AUTO',outcome='APPROVED',policy_version=decision['policyVersion'],
            assessment_id=assessment,selected_terms_id=candidate['id'],reasons=decision['reasons'],offer_id=offer['id'],offer_version=1,
            request_payload=drequest,request_hash=digest(drequest))
        check('decision','approval requires corresponding offer',insert('decisions',**drow),'23503')
        check('decision','approval with evaluated offer',insert('decisions',**drow)+insert('offers',id=offer['id'],version=1,
            decision_id=decision['id'],application_id=app,data_version=1,assessment_id=assessment,terms_id=candidate['id'],
            product_id=offer['productId'],product_version=offer['productVersion'],type='REQUESTED',issued_at=Raw('clock_timestamp()'),expires_at=Raw("clock_timestamp()+interval '7 days'")))
        case=ident(501);action=ident(502)
        check('decision','manual case before scoring',insert('manual_review_cases',id=case,application_id=app,data_version=1,
            verification_id=verification['id'],reason='Disputed facts',request_payload={},request_hash=digest({})))
        check('decision','manual approval cannot skip scoring',insert('manual_review_actions',id=action,case_id=case,
            application_id=app,data_version=1,staff_id=seed['staff']['id'],action='APPROVE',reason='Manually verified evidence',
            terms_id=candidate['id'],decision_id=ident(503)),'23514')
        check('decision','manual request and resolution are atomic',insert('manual_review_actions',id=action,case_id=case,
            application_id=app,data_version=1,staff_id=seed['staff']['id'],action='REQUEST_INFO',reason='Request detailed evidence',
            requirements=[{'path':'/additionalInfo/revenueExplanation','message':'Explain revenue'}])+f"UPDATE manual_review_cases SET status='RESOLVED',resolved_action_id={literal(action)},resolved_at=clock_timestamp() WHERE id={literal(case)};")
        now=datetime.now(timezone.utc); future=(now+timedelta(days=7)).isoformat();offer={**offer,'expiresAt':future}
        arequest={**exchange('createAgreement')['request']['body'],'offer':offer}
        arow=dict(id=agreement['id'],application_id=app,data_version=1,customer_id=customer,company_id=company,
            offer_id=offer['id'],offer_version=1,offer_snapshot=offer,accepted_at=Raw("clock_timestamp()-interval '1 minute'"),
            sign_by=Raw("clock_timestamp()+interval '3 days'"),document_content=agreement['document']['content'],
            request_payload=arequest,request_hash=digest(arequest))
        check('agreements','create unsigned immutable document',insert('agreements',**arow))
        check('agreements','signed status requires signature',f"UPDATE agreements SET status='SIGNED',signature_id={literal(ident(601))} WHERE id={literal(agreement['id'])};",'23503')
        sigrow=dict(id=signed['signature']['id'],agreement_id=agreement['id'],customer_id=customer,document_version=1,
            document_sha256=agreement['document']['sha256'])
        check('agreements','orphan signature cannot commit',insert('signatures',**sigrow),'23503')
        check('agreements','signature and status commit together',insert('signatures',**sigrow)+
            f"UPDATE agreements SET status='SIGNED',signature_id={literal(sigrow['id'])} WHERE id={literal(agreement['id'])};")
        check('agreements','signed agreement cannot be closed',f"UPDATE agreements SET status='CLOSED',signature_id=NULL,closure_reason='CANCELLED',closed_at=clock_timestamp() WHERE id={literal(agreement['id'])};",'23514')
        check('agreements','document hash is immutable',f"UPDATE agreements SET document_sha256={literal('0'*64)} WHERE id={literal(agreement['id'])};",'23514')
        prequest=exchange('createPayoutCheck')['request']['body']
        prow=dict(id=payout['id'],application_id=app,data_version=1,payout_version=1,agreement_id=agreement['id'],company_id=company,
            settlement_account_id=payout['settlementAccountId'],outcome='READY',request_payload=prequest,request_hash=digest(prequest))
        check('disbursement','ready account of company',insert('payout_checks',**prow))
        check('disbursement','unknown account cannot be ready',insert('payout_checks',**{**prow,'id':ident(701),'application_id':ident(201),'settlement_account_id':ident(999)}),'23514')
        brequest=exchange('createDisbursement')['request']['body']
        brow=dict(id=disbursement['id'],operation_id=disbursement['operationId'],application_id=app,data_version=1,
            agreement_id=agreement['id'],payout_version=1,payout_check_id=payout['id'],company_id=company,
            settlement_account_id=payout['settlementAccountId'],amount_rub=offer['amountRub'],credit_entry_id=disbursement['creditEntryId'],
            agreement_snapshot=signed,request_payload=brequest,request_hash=digest(brequest))
        check('disbursement','issuance without credit entry cannot commit',insert('disbursements',**brow),'23503')
        credit=dict(id=brow['credit_entry_id'],disbursement_id=brow['id'],settlement_account_id=brow['settlement_account_id'],amount_rub=brow['amount_rub'])
        check('disbursement','ledger amount must match issuance',insert('disbursements',**brow)+insert('credit_entries',**{**credit,'amount_rub':credit['amount_rub']+1}),'23503')
        check('disbursement','atomic one-time issuance',insert('disbursements',**brow)+insert('credit_entries',**credit))
        check('disbursement','same application cannot receive second credit',insert('disbursements',**{**brow,'id':ident(702),'operation_id':ident(703),'credit_entry_id':ident(704)}),'23505')
        check('disbursement','posted credit is immutable','UPDATE credit_entries SET amount_rub=1;','23514')
        for service in SERVICES:
            check(service,'runtime cannot alter ownership/triggers','ALTER TABLE '+ ('product_versions' if service=='catalog' else 'idempotency_records')+' DISABLE TRIGGER ALL;','42501')
        print(f'OK: six isolated PostgreSQL databases, migrations and seeds; {count} integrity/permission checks on PostgreSQL 16.')
    finally:
        if started: command(['docker','rm','-f',container])

if __name__ == '__main__':
    main()

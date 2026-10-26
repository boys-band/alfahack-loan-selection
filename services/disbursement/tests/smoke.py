#!/usr/bin/env python3
"""Bad account -> new payoutVersion -> credit -> recovery, using real HTTP."""
import json
import os
import urllib.request
import uuid

base = os.environ.get("SERVICE_URL", "http://localhost:8006").rstrip("/")
token = os.environ["INTERNAL_API_TOKEN"]


def call(method, path, body=None, key=None):
    headers = {"Authorization": "Bearer " + token, "X-Request-Id": str(uuid.uuid4())}
    if key:
        headers["Idempotency-Key"] = key
    if body is not None:
        headers["Content-Type"] = "application/json"
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=10) as response:
        return response.status, json.load(response)


call("GET", "/health/live")
call("GET", "/health/ready")
company = "00000000-0000-4000-8000-000000000101"
call("GET", "/v2/company-accounts/" + company)
request = {
    "applicationId": "00000000-0000-4000-8000-000000000201", "dataVersion": 1,
    "agreementId": "00000000-0000-4000-8000-000000000601", "companyId": company,
    "payoutVersion": 1, "settlementAccountId": "00000000-0000-4000-8000-000000001199",
}
_, bad = call("POST", "/v2/payout-checks", request, "smoke-payout-bad-v1")
assert bad["outcome"] == "NEEDS_CORRECTION" and bad["reasonCodes"] == ["ACCOUNT_INVALID"]
request.update(payoutVersion=2, settlementAccountId="00000000-0000-4000-8000-000000001101")
_, good = call("POST", "/v2/payout-checks", request, "smoke-payout-good-v2")
assert good["outcome"] == "READY"
assert call("GET", "/v2/payout-checks/" + bad["id"])[1] == bad
request.update(payoutCheckId=good["id"], operationId="00000000-0000-4000-8000-000000002201")
_, executed = call("POST", "/v2/disbursements", request, "smoke-disburse-operation-2201")
assert executed["status"] == "EXECUTED" and executed["amountRub"] == 1000000
assert call("GET", "/v2/disbursements/by-operation/" + request["operationId"])[1] == executed
assert call("GET", "/v2/disbursements/" + executed["id"])[1] == executed
assert call("POST", "/v2/disbursements", request, "smoke-disburse-operation-2201")[1] == executed
assert call("POST", "/v2/disbursements", request, "smoke-disburse-alias-2201")[0] == 200
print("HTTP smoke passed: correction, execution, lookup and replay; one operation.")

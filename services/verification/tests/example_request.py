#!/usr/bin/env python3
"""Print a full synthetic request; asOf belongs to the internal orchestrator."""
from datetime import datetime, timezone
import json
from pathlib import Path

request = json.loads((Path(__file__).resolve().parents[1] / 'src/test/resources/request.json').read_text())
now = datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')
request['asOf'] = now
request['data']['consents']['acceptedAt'] = now
print(json.dumps(request, ensure_ascii=False, indent=2))

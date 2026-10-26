#!/usr/bin/env python3
"""Development-only HTTP stub using the accepted synthetic Agreement fixture."""
import argparse
import json
import os
from pathlib import Path
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=8005)
parser.add_argument("--state", choices=["SIGNED", "READY", "CLOSED", "404", "503"], default="SIGNED")
parser.add_argument("--delay-ms", type=int, default=0)
args = parser.parse_args()
token = os.environ["INTERNAL_API_TOKEN"]
agreement = json.loads((Path(__file__).parent / "resources/signed-agreement.json").read_text())
if args.state in ("READY", "CLOSED"):
    agreement["status"] = args.state
    agreement["signature"] = None
    agreement["closureReason"] = "CANCELLED" if args.state == "CLOSED" else None


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        request_id = self.headers.get("X-Request-Id", str(uuid.uuid4()))
        status = 200
        if self.headers.get("Authorization") != "Bearer " + token:
            status = 401
        elif self.path != "/v2/agreements/" + agreement["id"]:
            status = 404
        elif args.state in ("404", "503"):
            status = int(args.state)
        time.sleep(args.delay_ms / 1000)
        body = agreement if status == 200 else {
            "type": "urn:alfa:problem:stub", "title": "Stub response", "status": status,
            "detail": "Development dependency response.", "code": "STUB_ERROR",
            "requestId": request_id, "errors": [],
        }
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json" if status == 200 else "application/problem+json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Request-Id", request_id)
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


print(f"Development agreements stub listening on :{args.port}, state={args.state}", flush=True)
ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()

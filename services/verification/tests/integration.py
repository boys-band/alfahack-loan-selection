#!/usr/bin/env python3
"""An isolated PostgreSQL; no project DB/volume is accessed. Always remove our container."""
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea'
name = 'verification-test-' + uuid.uuid4().hex[:12]

def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)

def sql(text):
    run('docker', 'exec', '-i', name, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
        '-U', 'postgres', '-d', 'alfa_verification', input=text, text=True, stdout=subprocess.DEVNULL)

try:
    run('docker', 'run', '--rm', '-d', '--name', name, '-e', 'POSTGRES_PASSWORD=demo-admin-only',
        '-e', 'POSTGRES_DB=alfa_verification', '-p', '127.0.0.1::5432',
        '--tmpfs', '/var/lib/postgresql/data', IMAGE, stdout=subprocess.DEVNULL)
    for attempt in range(90):
        ready = subprocess.run(['docker', 'exec', name, 'sh', '-c', 'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres -d alfa_verification'], capture_output=True)
        if ready.returncode == 0:
            break
        time.sleep(.25)
    else:
        raise RuntimeError('PostgreSQL did not start')
    run('docker', 'exec', name, 'mkdir', '-p', '/tmp/verification-schema')
    run('docker', 'cp', str(ROOT / 'migrations'), name + ':/tmp/verification-schema/migrations')
    run('docker', 'cp', str(ROOT / 'tests/seed.sql'), name + ':/tmp/verification-schema/seed.sql')
    run('docker', 'cp', str(ROOT / 'database/init.sh'), name + ':/tmp/verification-init.sh')
    run('docker', 'exec', '-e', 'VERIFICATION_SQL_ROOT=/tmp/verification-schema', name,
        'bash', '/tmp/verification-init.sh', stdout=subprocess.DEVNULL)
    port = subprocess.check_output(['docker', 'port', name, '5432/tcp'], text=True).strip().rsplit(':', 1)[1]
    env = dict(os.environ, TEST_DATABASE_URL=f'postgresql://verification_app:demo-verification-only@127.0.0.1:{port}/alfa_verification',
               TEST_ADMIN_DATABASE_URL=f'postgresql://postgres:demo-admin-only@127.0.0.1:{port}/alfa_verification')
    run(str(ROOT / 'gradlew'), '-p', str(ROOT), 'integrationTest', '--rerun-tasks', *sys.argv[1:], env=env)
finally:
    subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

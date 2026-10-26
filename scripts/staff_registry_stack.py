#!/usr/bin/env python3
"""Isolated real-E2E stack. Generates package-only build stages using preinstalled Gradle.

Unit/integration suites run separately; this wrapper only packages the real production code.
No application API, clock, business outcomes, or databases are replaced by stubs.
"""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ('applications', 'catalog', 'verification', 'decision', 'agreements', 'disbursement')
BUILDER = ('FROM gradle:8.14.3-jdk21@sha256:21bd311ed01360c189b8870c6b6e988199ff10f72d445d02fb39d3cff9da91d7 AS build\n'
           'USER root\nENV GRADLE_USER_HOME=/root/.gradle\n')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', required=True)
    parser.add_argument('--port', type=int, default=18084)
    parser.add_argument('--down', action='store_true')
    args = parser.parse_args()
    if not args.project.startswith('alfa-staff-it-'):
        parser.error('Use an isolated project name starting alfa-staff-it-')
    command = ['docker', 'compose', '-p', args.project, '-f', 'compose.yaml', '-f', 'frontend/e2e-real/compose.staff-registry.yaml']
    env = dict(os.environ, STAFF_E2E_PORT=str(args.port))
    created = []
    try:
        if args.down:
            subprocess.run(command + ['down', '--volumes', '--remove-orphans'], cwd=ROOT, env=env, check=True)
            return
        for service in SERVICES:
            directory = ROOT / 'services' / service
            path = directory / 'Dockerfile.staff-registry-e2e'
            if path.exists():
                raise RuntimeError(f'Refusing to overwrite {path}')
            source = (directory / 'Dockerfile').read_text(encoding='utf-8').replace('\r\n', '\n')
            first, rest = source.split('\n', 1)
            if not first.endswith(' AS build'):
                raise RuntimeError(f'Unexpected build stage: {service}')
            build, runtime = rest.split('\nFROM ', 1)
            build = build.replace('chmod +x gradlew && ./gradlew', 'gradle').replace('./gradlew --no-daemon', 'gradle --no-daemon')
            build = build.replace('--no-daemon test ', '--no-daemon ')
            build = build.replace('RUN gradle ', 'RUN --mount=type=cache,target=/root/.gradle gradle ')
            path.write_text(BUILDER + build + '\nFROM ' + runtime, encoding='utf-8', newline='\n')
            created.append(path)
        subprocess.run(command + ['--parallel', '1', 'build'], cwd=ROOT, env=env, check=True)
        subprocess.run(command + ['up', '-d', '--wait', '--wait-timeout', '240'], cwd=ROOT, env=env, check=True)
        print(f'Real stack ready: http://127.0.0.1:{args.port}; project={args.project}')
    finally:
        for path in created:
            path.unlink()

if __name__ == '__main__':
    main()

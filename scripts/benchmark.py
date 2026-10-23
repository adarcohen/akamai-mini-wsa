#!/usr/bin/env python3
"""Benchmark an isolated Compose stack; never connects to the development database."""
import argparse
import hashlib
import json
import math
import os
import platform
import statistics
import subprocess
import time
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from generate_events import generate_events

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='new directory for measurements and plans')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    project = 'miniwsa-bench-' + uuid.uuid4().hex[:12]
    env = dict(os.environ, APP_PORT='0', DB_PORT='0', DB_PASSWORD='benchmark-only')
    command = ['docker', 'compose', '-p', project]

    def compose(*parts, capture=False, sql=None):
        return subprocess.run(command + list(parts), cwd=ROOT, env=env, check=True,
                              input=sql, text=True, stdout=subprocess.PIPE if capture else None).stdout

    def db(sql):
        return compose('exec', '-T', 'db', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'miniwsa', '-d', 'miniwsa', '-At', capture=True, sql=sql)

    def literal(value):
        return "'" + str(value).replace("'", "''") + "'"

    report = {'measuredAtUtc': datetime.now(timezone.utc).isoformat(), 'architecture': platform.machine(),
              'batchSize': 500, 'concurrentWriters': 1, 'scenarios': []}
    report['repositoryCodeSha256'] = hashlib.sha256(
        (ROOT / 'src/main/java/com/akamai/miniwsa/repository/EventRepository.java').read_bytes()).hexdigest()
    report['docker'] = json.loads(subprocess.check_output(['docker', 'info', '--format',
        '{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}},"version":"{{.ServerVersion}}"}'], env=env, text=True))
    try:
        compose('up', '--build', '-d', '--wait', '--wait-timeout', '180')
        port = compose('port', 'app', '8080', capture=True).strip().rsplit(':', 1)[1]
        base = 'http://127.0.0.1:' + port

        def request(path, body=None):
            data = json.dumps(body).encode() if body is not None else None
            req = urllib.request.Request(base + path, data=data, headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=120) as response:
                return response.status, json.load(response)

        # Small warm-up is excluded from the measurements and removed before each scenario.
        warmup = list(generate_events(500, 42, datetime(2026, 5, 20, tzinfo=timezone.utc), 'warmup'))
        assert request('/v1/events/ingest', warmup)[0] == 201
        for count in (10000, 100000):
            db('TRUNCATE TABLE security_events;')
            iterator = iter(generate_events(count, 42, datetime(2026, 5, 20, tzinfo=timezone.utc), f'bench-{count}'))
            latencies = []
            unique_ips = []
            elapsed = 0
            last = None
            last_batch = None
            # Generate outside timing; include serialization, network and response parsing.
            for _ in range(count // 500):
                batch = [next(iterator) for _ in range(500)]
                last = batch[-1]
                last_batch = batch
                unique_ips.append(len({event['clientIp'] for event in batch}))
                started = time.perf_counter()
                status, body = request('/v1/events/ingest', batch)
                duration = time.perf_counter() - started
                assert status == 201 and body['accepted'] == 500
                elapsed += duration
                latencies.append(duration * 1000)
            assert int(db('SELECT count(*) FROM security_events;').strip()) == count
            db('ANALYZE security_events;')
            start = '2026-05-20T00:00:00Z'
            end = last['timestamp']
            path = f'/v1/stats/summary?from={start}&to={end}'
            assert request(path)[1]['totalEvents'] == count
            query_ms = []
            for _ in range(5):
                began = time.perf_counter()
                assert request(path)[1]['totalEvents'] == count
                query_ms.append((time.perf_counter() - began) * 1000)
            # These statements mirror categoryStats() and recentCounts(), including the bounded LIMIT 5.
            aggregate = f'''SELECT category, count(*) AS event_count, round(avg(threat_score), 1) AS avg_score
                FROM security_events WHERE event_time >= {literal(start)}::timestamptz
                AND event_time <= {literal(end)}::timestamptz GROUP BY category'''
            now = datetime.now(timezone.utc)
            cutoff = now - timedelta(minutes=10)
            clients = ','.join(literal(ip) for ip in sorted({event['clientIp'] for event in last_batch}))
            repeat = f'''SELECT clients.ip, (SELECT count(*) FROM (
                SELECT 1 FROM security_events history
                WHERE history.client_ip = CAST(clients.ip AS inet)
                AND history.received_at >= {literal(cutoff.isoformat())}::timestamptz
                AND history.received_at <= {literal(now.isoformat())}::timestamptz
                LIMIT 5) recent) AS recent_count
                FROM unnest(ARRAY[{clients}]::text[]) AS clients(ip)'''
            plans = {}
            for label, sql in [('aggregation', aggregate), ('repeat-offender', repeat)]:
                plan = json.loads(db('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' + sql + ';'))
                (args.output / f'{count}-{label}-plan.json').write_text(json.dumps(plan, indent=2) + '\n')
                plans[label] = {'planningMs': plan[0]['Planning Time'], 'executionMs': plan[0]['Execution Time']}
            sample = {'events': count, 'batches': len(latencies), 'ingestionSeconds': round(elapsed, 3),
                      'eventsPerSecond': round(count / elapsed, 1),
                      'batchP50Ms': round(statistics.median(latencies), 2),
                      'batchP95Ms': round(sorted(latencies)[math.ceil(len(latencies)*.95)-1], 2),
                      'meanUniqueIpsPerBatch': round(statistics.mean(unique_ips), 1),
                      'summaryP50Ms': round(statistics.median(query_ms), 2), 'summaryMeasuredRequests': 5,
                      'plans': plans}
            report['scenarios'].append(sample)
            print(json.dumps(sample), flush=True)
            (args.output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    finally:
        # Only this randomly named benchmark project and its temporary volume are removed.
        compose('down', '-v', '--remove-orphans')


if __name__ == '__main__':
    main()

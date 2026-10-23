#!/usr/bin/env python3
"""Generate ingestion-ready JSON batches using only the Python standard library."""
import argparse
import json
import random
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

RULES = {
    "INJECTION": ("950001", "SQL_INJECTION", "SQL injection probe detected"),
    "XSS": ("941100", "XSS_ATTACK", "Cross-site scripting probe detected"),
    "PROTOCOL_VIOLATION": ("920100", "INVALID_HTTP", "Malformed HTTP request"),
    "DATA_LEAKAGE": ("950200", "DATA_EXPOSURE", "Sensitive resource access detected"),
    "BOT": ("300001", "BOT_ACTIVITY", "Automated credential attempt detected"),
    "DOS": ("400001", "DOS_ACTIVITY", "Request flood detected"),
    "RATE_LIMIT": ("500001", "RATE_LIMIT", "Request threshold exceeded"),
}
LOCATIONS = [("US", "New York"), ("CN", "Beijing"), ("DE", "Berlin"), ("GB", "London")]
PATHS = ["/", "/products", "/api/search", "/api/v1/login", "/admin", "/api/export"]


def parse_start(value):
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise argparse.ArgumentTypeError("start must be an ISO-8601 timestamp") from error
    if result.tzinfo is None:
        raise argparse.ArgumentTypeError("start must include a timezone")
    return result.astimezone(timezone.utc)


def generate_events(count, seed, start, run_id):
    """Yield a background stream with 50-event attack waves every 200 events.

    Each wave occupies five seconds and shares IP, path, category and configuration.
    Client timestamps describe simulated time; the API assigns its own receivedAt.
    """
    rng = random.Random(seed)
    for index in range(count):
        block, position = divmod(index, 200)
        wave = position < 50
        category = ("INJECTION", "BOT", "DOS")[block % 3] if wave else rng.choice(list(RULES))
        path = ("/admin", "/api/v1/login", "/api/search")[block % 3] if wave else rng.choice(PATHS)
        client_ip = f"203.0.113.{1 + block % 254}" if wave else f"198.51.100.{rng.randint(1, 254)}"
        config_id = (14227, 14228, 14229)[block % 3] if wave else rng.choice((14227, 14228, 14229))
        elapsed = block * 300 + (position / 10 if wave else 5 + (position - 50) * 1.9)
        timestamp = (start + timedelta(seconds=elapsed)).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        action = rng.choices(["DENY", "ALERT", "MONITOR"], weights=[65, 25, 10])[0]
        severity = rng.choice(["CRITICAL", "HIGH"]) if wave else rng.choice(["CRITICAL", "HIGH", "MEDIUM", "LOW"])
        rule_id, name, message = RULES[category]
        country, city = rng.choice(LOCATIONS)
        yield {
            "eventId": f"gen-{run_id}-{index:08d}", "timestamp": timestamp,
            "configId": config_id, "policyId": f"pol-{config_id}", "clientIp": client_ip,
            "hostname": f"site-{config_id}.example.com", "path": path,
            "method": "POST" if "login" in path else rng.choice(["GET", "POST"]),
            "statusCode": 403 if action == "DENY" else rng.choice([200, 200, 404]),
            "userAgent": rng.choice(["Mozilla/5.0", "curl/8.0", "SecurityTestBot/1.0"]),
            "rule": {"id": rule_id, "name": name, "message": message, "severity": severity, "category": category},
            "action": action, "geoLocation": {"country": country, "city": city},
            "requestSize": rng.randint(100, 8192), "responseSize": rng.randint(0, 32768),
        }


def write_batches(events, output, batch_size):
    # Refuse existing directories so reruns cannot mix old and new batches.
    output.mkdir(parents=True, exist_ok=False)
    batch = []
    files = 0
    for event in events:
        batch.append(event)
        if len(batch) == batch_size:
            files += 1
            (output / f"batch-{files:06d}.json").write_text(json.dumps(batch) + "\n", encoding="utf-8")
            batch.clear()
    if batch:
        files += 1
        (output / f"batch-{files:06d}.json").write_text(json.dumps(batch) + "\n", encoding="utf-8")
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=10000)
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--start", type=parse_start, default=datetime.now(timezone.utc))
    parser.add_argument("--output", type=Path, required=True, help="new directory for JSON batches")
    args = parser.parse_args()
    if args.count < 1:
        parser.error("count must be positive")
    if not 1 <= args.batch_size <= 1000:
        parser.error("batch-size must be between 1 and 1000")
    run_id = uuid.uuid4().hex
    try:
        files = write_batches(generate_events(args.count, args.seed, args.start, run_id), args.output, args.batch_size)
    except OSError as error:
        parser.exit(1, f"Cannot write batches: {error}\n")
    print(f"Generated {args.count} events in {files} batches at {args.output}; run ID: {run_id}")


if __name__ == "__main__":
    main()

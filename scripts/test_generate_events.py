import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from generate_events import generate_events, parse_start, write_batches


class GeneratorTest(unittest.TestCase):
    def events(self, count=10000):
        return list(generate_events(count, 42, datetime(2026, 5, 20, tzinfo=timezone.utc), "test"))

    def test_volume_identity_and_coverage(self):
        events = self.events()
        self.assertEqual(10000, len(events))
        self.assertEqual(10000, len({e['eventId'] for e in events}))
        self.assertEqual(7, len({e['rule']['category'] for e in events}))
        self.assertEqual({'DENY', 'ALERT', 'MONITOR'}, {e['action'] for e in events})
        self.assertEqual(3, len({e['configId'] for e in events}))
        self.assertTrue(all('receivedAt' not in e and 'threatScore' not in e for e in events))

    def test_wave_and_time_order(self):
        events = self.events(401)
        wave = events[200:250]
        self.assertEqual(1, len({(e['clientIp'], e['path'], e['configId'], e['rule']['category']) for e in wave}))
        times = [parse_start(e['timestamp']) for e in events]
        self.assertEqual(sorted(times), times)
        self.assertLess((times[249] - times[200]).total_seconds(), 5)

    def test_seed_reproducibility(self):
        self.assertEqual(self.events(100), self.events(100))

    def test_batch_boundaries_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'batches'
            self.assertEqual(3, write_batches(iter(self.events(1001)), output, 500))
            batches = [json.loads(p.read_text()) for p in sorted(output.glob('*.json'))]
            self.assertEqual([500, 500, 1], [len(b) for b in batches])
            self.assertEqual(self.events(1001), [e for b in batches for e in b])
            with self.assertRaises(FileExistsError):
                write_batches(iter([]), output, 500)


if __name__ == '__main__':
    unittest.main()

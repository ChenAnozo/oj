"""Summarize diagnostic wall times; parent/child stages must not be added."""
import argparse
from collections import defaultdict
import csv
import json
from pathlib import Path
import re

EVENT = re.compile(r'sandbox_timing task=(.*?) stage=(\w+) elapsed_us=(\d+)')


def summarize(log_path, output_dir, expected):
    events = []
    for line in log_path.read_text(encoding='utf-8-sig', errors='replace').splitlines():
        match = EVENT.search(line)
        if match:
            task, stage, elapsed = match.groups()
            events.append((task, stage, int(elapsed)))
    tasks = {task for task, stage, _ in events if stage == 'submission_total'}
    grouped = defaultdict(list)
    for task, stage, elapsed in events:
        if task in tasks:
            grouped[stage].append((task, elapsed))
    rows = []
    for stage, samples in sorted(grouped.items()):
        total_us = sum(elapsed for _, elapsed in samples)
        rows.append({
            'stage': stage, 'events': len(samples),
            'tasksWithStage': len({task for task, _ in samples}),
            'meanEventMs': round(total_us / len(samples) / 1000, 3),
            'meanPerSubmissionMs': round(total_us / len(tasks) / 1000, 3),
        })
    output_dir.mkdir(parents=True, exist_ok=True)
    with (output_dir / 'stage-times.csv').open('w', newline='', encoding='utf-8-sig') as file:
        writer = csv.DictWriter(file, fieldnames=['stage', 'events', 'tasksWithStage', 'meanEventMs', 'meanPerSubmissionMs'])
        writer.writeheader()
        writer.writerows(rows)
    report = {
        'expectedSubmissions': expected, 'completedSandboxRequests': len(tasks),
        'complete': len(tasks) == expected,
        'scope': 'Completed sandbox calls, including failures. Wall time excludes MQ wait and client polling. submission_total starts after workspace creation. Stages nest and must not be added; callback close can occur more than once.',
        'stages': rows,
    }
    (output_dir / 'stage-times.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('Completed sandbox requests: %d / %d' % (len(tasks), expected))
    print('%-24s %8s %14s %20s' % ('Stage', 'Events', 'Mean/event ms', 'Mean/submission ms'))
    for row in rows:
        print('%-24s %8d %14.3f %20.3f' % (row['stage'], row['events'], row['meanEventMs'], row['meanPerSubmissionMs']))
    print('Nested stages must not be added. These are wall times, not CPU times or verdict execution limits.')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--expected', type=int, default=100)
    args = parser.parse_args()
    result = summarize(args.log, args.output_dir, args.expected)
    raise SystemExit(0 if result['complete'] else 1)

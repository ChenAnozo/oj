"""Build 20 deterministic cases using the separately compiled previous-row oracle."""
import argparse
import json
from pathlib import Path
import random
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--oracle', type=Path, required=True)
    args = parser.parse_args()
    rng = random.Random(2026102102)
    datasets = [([(1, 1)], 1), ([(2, 10)], 1),
                ([(3, 5), (4, 6), (2, 3)], 6),
                ([(10, 20), (5, 12), (5, 12)], 10)]
    # Boundaries, equal items, large input, and full-scale random cases.
    datasets += [([(10, 7)] * 2000, 20000),
                 ([(11, 10000)] * 2000, 20000),
                 ([(20001, 10000)] * 2000, 20000),
                 ([(1, (i % 10000) + 1) for i in range(2000)], 20000)]
    for i in range(12):
        n = 400 if i < 2 else 1000 if i < 4 else 2000
        capacity = 5000 if i < 2 else 10000 if i < 4 else 20000
        items = [(rng.randint(1, 100 if i % 2 else 1000), rng.randint(1, 10000))
                 for _ in range(n)]
        datasets.append((items, capacity))
    cases = []
    for items, capacity in datasets:
        text = f'{len(items)} {capacity}\n' + ''.join(f'{w} {v}\n' for w, v in items)
        output = subprocess.run([str(args.oracle.resolve())], input=text, text=True,
                                capture_output=True, check=True, timeout=30).stdout.strip()
        assert output.isdigit()
        cases.append({'input': text, 'output': output})
    question = {
        'title': 'OJ load test - full 0/1 knapsack v2',
        'content': 'Choose each of n items at most once to maximize total value with total weight <= W. '
                   'Input: n W followed by n lines of weight value. Output: maximum total value. '
                   '1 <= n <= 2000, 1 <= W <= 20000, 1 <= weight <= 20001, '
                   '1 <= value <= 10000. Twenty test cases, including boundary and full-size cases.',
        'tags': ['load-test', 'dynamic-programming'],
        'answer': 'O(nW) descending-capacity dynamic programming; O(W) space.',
        'judgeCase': cases,
        'judgeConfig': {'timeLimit': 2000, 'memoryLimit': 131072, 'stackLimit': 10000},
    }
    size = len(json.dumps(cases, ensure_ascii=False).encode('utf-8'))
    assert 65535 < size < 1024 * 1024
    Path(__file__).with_name('question.json').write_text(
        json.dumps(question, indent=2) + '\n', encoding='utf-8')
    print(f'Generated {len(cases)} cases; serialized judgeCase bytes={size}')


if __name__ == '__main__':
    main()

"""Regenerate deterministic 0/1 knapsack fixtures, without any service calls."""
import json
from pathlib import Path
import random


def reference(items, capacity):
    # Each item reads only the previous row: independent of C++'s in-place update.
    previous = [0] * (capacity + 1)
    for weight, value in items:
        current = previous.copy()
        for c in range(weight, capacity + 1):
            current[c] = max(previous[c], previous[c - weight] + value)
        previous = current
    return previous[capacity]


def main():
    rng = random.Random(20261021)
    cases = []
    sizes = [(8, 30), (60, 20000), (120, 100000), (200, 200000), (200, 200000)]
    for index, (n, capacity) in enumerate(sizes):
        items = [(rng.randint(1, 12 if index == 0 else 5000), rng.randint(1, 10000))
                 for _ in range(n)]
        text = f'{n} {capacity}\n' + ''.join(f'{w} {v}\n' for w, v in items)
        cases.append({'input': text, 'output': str(reference(items, capacity))})
    question = {
        'title': 'OJ load test - 0/1 knapsack v1',
        'content': 'Given n items with positive integer weights and values, select each item at most once '
                   'to maximize total value without exceeding capacity W. Input: n W, then n lines of '
                   'weight value. Output: maximum value. 1 <= n <= 200, 1 <= W <= 200000, '
                   '1 <= weight <= 5000, 1 <= value <= 10000. Five deterministic test cases.',
        'tags': ['load-test', 'dynamic-programming'],
        'answer': '0/1 knapsack DP, O(nW) time and O(W) space; update capacities in descending order.',
        'judgeCase': cases,
        'judgeConfig': {'timeLimit': 10000, 'memoryLimit': 131072, 'stackLimit': 10000},
    }
    assert len(json.dumps(cases, ensure_ascii=False)) <= 8192, 'Exceeds question API judgeCase limit'
    Path(__file__).with_name('question.json').write_text(
        json.dumps(question, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Generated five knapsack cases; max n=200, W=200000.')


if __name__ == '__main__':
    main()

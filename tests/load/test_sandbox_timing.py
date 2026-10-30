import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import sandbox_timing


class TimingSummaryTest(unittest.TestCase):
    def test_groups_cases_per_submission_and_excludes_incomplete_requests(self):
        lines = [
            'prefix sandbox_timing task=task with spaces stage=exec_run elapsed_us=1000',
            'sandbox_timing task=task with spaces stage=exec_run elapsed_us=3000',
            'sandbox_timing task=task with spaces stage=submission_total elapsed_us=10000',
            'sandbox_timing task=second stage=exec_run elapsed_us=2000',
            'sandbox_timing task=second stage=submission_total elapsed_us=8000',
            'sandbox_timing task=unfinished stage=exec_run elapsed_us=999999',
        ]
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(tmp)
            log = root / 'timing.log'
            log.write_text('\n'.join(lines), encoding='utf-8-sig')
            report = sandbox_timing.summarize(log, root, 2)
            self.assertTrue(report['complete'])
            stages = {row['stage']: row for row in report['stages']}
            self.assertEqual(stages['exec_run']['events'], 3)
            self.assertEqual(stages['exec_run']['meanEventMs'], 2)
            self.assertEqual(stages['exec_run']['meanPerSubmissionMs'], 3)
            self.assertEqual(stages['submission_total']['meanPerSubmissionMs'], 9)
            self.assertTrue((root / 'stage-times.csv').exists())
            self.assertEqual(json.loads((root / 'stage-times.json').read_text())['completedSandboxRequests'], 2)
            self.assertFalse(sandbox_timing.summarize(log, root, 3)['complete'])


if __name__ == '__main__':
    unittest.main()

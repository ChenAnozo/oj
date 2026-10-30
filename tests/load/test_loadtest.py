"""Offline checks for fixture safety, exact IDs, business errors and reporting."""
import contextlib
import base64
import io
import json
import re
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import loadtest


class FakeApi:
    registrations = 0

    def __init__(self, base_url):
        pass

    def call(self, path, body=None):
        if path == '/api/user/register':
            type(self).registrations += 1
            return 1988123456789012345 + type(self).registrations
        if path == '/api/question/add':
            return 1988123456789012999
        return {}


class LoadTestChecks(unittest.TestCase):
    def test_plan_preserves_cpp_escape_sequences_in_runtime_payload(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(loadtest, 'ROOT', Path(tmp)), contextlib.redirect_stdout(io.StringIO()):
            fixture = Path(tmp) / 'fixtures/knapsack'
            fixture.mkdir(parents=True)
            source = "#include <iostream>\nint main(){ std::cout << '\\n' << \"quoted\\\\path\"; }\n"
            (fixture / 'solution.cpp').write_text(source, encoding='utf-8')
            loadtest.build_plan('knapsack')
            root = ET.parse(Path(tmp) / 'judge.jmx').getroot()
            submit = next(r for r in root.iter('HTTPSamplerProxy') if r.attrib['testname'] == 'Submit')
            body = next(p.text for p in submit.iter('stringProp') if p.attrib.get('name') == 'Argument.value')
            self.assertEqual(body, '${submission_body}')
            script = next(p.text for p in root.iter('stringProp')
                          if p.attrib.get('name') == 'script' and 'getDecoder().decode' in (p.text or ''))
            encoded = re.search(r"decode\('([A-Za-z0-9+/=]+)'\)", script).group(1)
            decoded = base64.b64decode(encoded).decode('utf-8')
            self.assertEqual(decoded, source)
            self.assertEqual(json.loads(json.dumps({'code': decoded}))['code'], source)

    def test_switch_workload_reuses_accounts_and_keeps_old_question(self):
        class QuestionApi:
            def __init__(self):
                self.calls = []

            def call(self, path, body):
                self.calls.append((path, body))
                return 1988123456789013000

        with tempfile.TemporaryDirectory() as tmp, patch.object(loadtest, 'ROOT', Path(tmp)):
            fixture = Path(tmp) / 'fixtures/knapsack'
            fixture.mkdir(parents=True)
            (fixture / 'question.json').write_text('{"title":"Knapsack"}', encoding='utf-8')
            state = {'runTag': 'test', 'questionId': '1988123456789012999',
                     'accounts': [{'userAccount': 'existing'}]}
            path = Path(tmp) / 'state.json'
            api = QuestionApi()
            loadtest.select_question(api, state, path, 'knapsack')
            self.assertEqual(state['questionId'], '1988123456789013000')
            self.assertEqual(state['accounts'], [{'userAccount': 'existing'}])
            self.assertEqual(api.calls[0][0], '/api/question/add')
            loadtest.select_question(api, state, path, 'sum')
            self.assertEqual(state['questionId'], '1988123456789012999')
            loadtest.select_question(api, state, path, 'knapsack')
            self.assertEqual(len(api.calls), 1)
            state['pendingQuestionTitle'] = 'ambiguous'
            with self.assertRaises(loadtest.ApiError):
                loadtest.select_question(api, state, path, 'knapsack')

    def test_knapsack_reference_matches_exhaustive_search(self):
        import importlib.util
        import random
        spec = importlib.util.spec_from_file_location(
            'knapsack_generator', loadtest.ROOT / 'fixtures/knapsack/generate.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        rng = random.Random(7)
        for _ in range(20):
            items = [(rng.randint(1, 12), rng.randint(1, 30)) for _ in range(8)]
            capacity = 20
            expected = max(sum(v for i, (w, v) in enumerate(items) if mask >> i & 1)
                           for mask in range(1 << len(items))
                           if sum(w for i, (w, v) in enumerate(items) if mask >> i & 1) <= capacity)
            self.assertEqual(generator.reference(items, capacity), expected)

    def test_real_client_initializes_cookie_handler(self):
        # Construct the real client without making a network request.
        api = loadtest.Api('http://localhost:18101/')
        self.assertEqual(api.base, 'http://localhost:18101')
        handlers = [h for h in api.opener.handlers
                    if isinstance(h, loadtest.HTTPCookieProcessor)]
        self.assertEqual(len(handlers), 1)
        self.assertIsNotNone(handlers[0].cookiejar)

    def test_business_error_is_not_http_success(self):
        self.assertEqual(loadtest.application_data({'code': 0, 'data': 123}), 123)
        for value in [{'code': 42900}, {'code': False}, {'data': 1}, []]:
            with self.assertRaises(loadtest.ApiError):
                loadtest.application_data(value)

    def test_large_id_is_exact_and_float_is_rejected(self):
        raw = json.loads('{"code":0,"data":1988123456789012345}')
        self.assertEqual(loadtest.numeric_id(loadtest.application_data(raw)), '1988123456789012345')
        for value in [1.9881234567890123e18, True, 0, '-1']:
            with self.assertRaises(loadtest.ApiError):
                loadtest.numeric_id(value)

    def test_prepare_reuses_fixtures_without_extra_registrations(self):
        FakeApi.registrations = 0
        with tempfile.TemporaryDirectory() as tmp, patch.object(loadtest, 'ROOT', Path(tmp)), patch.object(loadtest, 'Api', FakeApi), contextlib.redirect_stdout(io.StringIO()):
            (Path(tmp) / 'fixtures').mkdir()
            (Path(tmp) / 'fixtures/question.json').write_text('{"title":"Test sum"}', encoding='utf-8')
            loadtest.prepare('http://localhost:18101', 2)
            loadtest.prepare('http://localhost:18101', 2)
            self.assertEqual(FakeApi.registrations, 2)
            state = json.loads((Path(tmp) / '.local/state.json').read_text(encoding='utf-8'))
            self.assertEqual(state['questionId'], '1988123456789012999')
            with self.assertRaises(loadtest.ApiError):
                loadtest.prepare('http://localhost:18081', 2)

    def test_pending_account_is_kept_after_ambiguous_creation(self):
        class FailingApi(FakeApi):
            def call(self, path, body=None):
                if path == '/api/user/register':
                    raise loadtest.ApiError('Connection closed after sending request')
                return super().call(path, body)
        with tempfile.TemporaryDirectory() as tmp, patch.object(loadtest, 'ROOT', Path(tmp)), patch.object(loadtest, 'Api', FailingApi):
            with self.assertRaises(loadtest.ApiError):
                loadtest.prepare('http://localhost:18101', 1)
            state = json.loads((Path(tmp) / '.local/state.json').read_text(encoding='utf-8'))
            self.assertIn('pendingAccount', state)
            self.assertEqual(state['accounts'], [])

    def test_extend_accounts_keeps_question_and_existing_users(self):
        FakeApi.registrations = 0
        with tempfile.TemporaryDirectory() as tmp, patch.object(loadtest, 'ROOT', Path(tmp)), patch.object(loadtest, 'Api', FakeApi), contextlib.redirect_stdout(io.StringIO()):
            (Path(tmp) / 'fixtures').mkdir()
            (Path(tmp) / 'fixtures/question.json').write_text('{"title":"Test sum"}', encoding='utf-8')
            loadtest.prepare('http://localhost:18101', 10)
            path = Path(tmp) / '.local/state.json'
            original = json.loads(path.read_text(encoding='utf-8'))
            loadtest.prepare('http://localhost:18101', 20)
            extended = json.loads(path.read_text(encoding='utf-8'))
            self.assertEqual(extended['questionId'], original['questionId'])
            self.assertEqual(extended['accounts'][:10], original['accounts'])
            self.assertEqual(FakeApi.registrations, 20)
            self.assertEqual(len({a['userAccount'] for a in extended['accounts']}), 20)
            loadtest.prepare('http://localhost:18101', 50)
            extended = json.loads(path.read_text(encoding='utf-8'))
            self.assertEqual(extended['questionId'], original['questionId'])
            self.assertEqual(extended['accounts'][:10], original['accounts'])
            self.assertEqual(FakeApi.registrations, 50)
            self.assertEqual(len({a['userAccount'] for a in extended['accounts']}), 50)
            loadtest.prepare('http://localhost:18101', 100)
            extended = json.loads(path.read_text(encoding='utf-8'))
            self.assertEqual(extended['questionId'], original['questionId'])
            self.assertEqual(extended['accounts'][:10], original['accounts'])
            self.assertEqual(FakeApi.registrations, 100)
            self.assertEqual(len({a['userAccount'] for a in extended['accounts']}), 100)
            extended['pendingAccount'] = {'userAccount':'unconfirmed'}
            loadtest.write_json(path, extended)
            with self.assertRaises(loadtest.ApiError):
                loadtest.prepare('http://localhost:18101', 50)

    def test_report_fails_for_missing_or_rejected_users(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            directory = Path(tmp)
            loadtest.write_json(directory / 'task-0.json', {'outcome': 'accepted', 'elapsedMs': 1200})
            self.assertFalse(loadtest.summarize(directory, 2))
            loadtest.write_json(directory / 'task-1.json', {'outcome': 'judge_failed', 'elapsedMs': 500})
            self.assertFalse(loadtest.summarize(directory, 2))
            summary = json.loads((directory / 'summary.json').read_text(encoding='utf-8'))
            self.assertEqual(summary['meanAcceptedMs'], 1200)
            loadtest.write_json(directory / 'task-1.json', {'outcome': 'accepted', 'elapsedMs': 2000})
            self.assertTrue(loadtest.summarize(directory, 2))

    def test_plan_has_paired_trees_and_one_submit_with_scoped_timer(self):
        with contextlib.redirect_stdout(io.StringIO()):
            loadtest.build_plan()
        root = ET.parse(loadtest.ROOT / 'judge.jmx').getroot()
        for tree in root.iter('hashTree'):
            elements = list(tree)
            self.assertEqual(len(elements) % 2, 0)
            for i in range(0, len(elements), 2):
                self.assertNotEqual(elements[i].tag, 'hashTree')
                self.assertEqual(elements[i + 1].tag, 'hashTree')
        requests = list(root.iter('HTTPSamplerProxy'))
        self.assertEqual([r.attrib['testname'] for r in requests], ['Login', 'Submit', 'Query result'])
        self.assertEqual(len(list(root.iter('ConstantTimer'))), 1)
        self.assertIn('Accepted', ET.tostring(root, encoding='unicode'))


if __name__ == '__main__':
    unittest.main()

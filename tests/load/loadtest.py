"""Standard-library helpers: prepare fixtures, build JMeter plan, summarize results.

No service configuration changes, database deletes, or automatic submit retries.
Account credentials and generated results stay in git-ignored directories.
"""
import argparse
import base64
import csv
import http.cookiejar
import json
import math
from pathlib import Path
import secrets
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
DEFAULT_URL = "http://127.0.0.1:18101"


class ApiError(RuntimeError):
    pass


def application_data(raw):
    """Python integers preserve the project's 64-bit Snowflake identifiers."""
    if not isinstance(raw, dict) or raw.get("code") != 0 or isinstance(raw.get("code"), bool):
        code = raw.get("code") if isinstance(raw, dict) else "invalid-json-shape"
        raise ApiError(f"Business response failed, code={code}")
    return raw.get("data")


class Api:
    def __init__(self, base_url):
        self.base = base_url.rstrip("/")
        self.opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = Request(self.base + path, data=data,
                      headers={"Content-Type": "application/json; charset=utf-8"})
        try:
            with self.opener.open(req, timeout=20) as response:
                raw = json.load(response)
        except (HTTPError, URLError, OSError, ValueError) as exc:
            # Never print request bodies, cookies, or test passwords.
            raise ApiError(f"{path}: {type(exc).__name__}: {exc}") from exc
        return application_data(raw)


def numeric_id(value):
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        raise ApiError("Expected an exact integer ID")
    text = str(value)
    if not text.isdigit() or int(text) <= 0:
        raise ApiError("Invalid ID")
    return text


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def export_csv(state):
    with (ROOT / ".local/accounts.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["userAccount", "userPassword", "questionId"])
        for account in state["accounts"]:
            writer.writerow([account["userAccount"], account["userPassword"], state["questionId"]])


def ensure_accounts(api, state, state_path, users):
    if state.get("pendingAccount"):
        raise ApiError("Unconfirmed account creation in state.json. Check pendingAccount before retrying.")
    for i in range(len(state["accounts"]), users):
        account = {"userAccount": f"load_{state['runTag']}_{i:02d}",
                   "userPassword": secrets.token_hex(12)}
        # Persist intent before mutation: an ambiguous response must not cause a blind retry.
        state["pendingAccount"] = account
        write_json(state_path, state)
        user_id = api.call("/api/user/register", {**account, "checkPassword": account["userPassword"]})
        account["userId"] = numeric_id(user_id)
        state["accounts"].append(account)
        state.pop("pendingAccount", None)
        write_json(state_path, state)


def fixture_path(workload, source=False):
    if workload == 'sum':
        return ROOT / 'fixtures' / ('sum.cpp' if source else 'question.json')
    if workload == 'knapsack':
        return ROOT / 'fixtures/knapsack' / ('solution.cpp' if source else 'question.json')
    if workload == 'knapsack-full':
        return ROOT / ('fixtures/knapsack/solution.cpp' if source else 'fixtures/knapsack-full/question.json')
    raise ApiError('Unknown workload')


def select_question(api, state, state_path, workload):
    if state.get('pendingQuestionTitle'):
        raise ApiError('Unconfirmed question creation in state.json; inspect before retrying.')
    questions = state.setdefault('questions', {})
    if state.get('questionId'):
        questions.setdefault(state.get('workload', 'sum'), state['questionId'])
    if workload not in questions:
        question = json.loads(fixture_path(workload).read_text(encoding='utf-8'))
        question['title'] += ' ' + state['runTag']
        state['pendingQuestionTitle'] = question['title']
        write_json(state_path, state)
        questions[workload] = numeric_id(api.call('/api/question/add', question))
        state.pop('pendingQuestionTitle', None)
        print('Created dedicated question for workload=' + workload)
    state['questionId'] = questions[workload]
    state['workload'] = workload
    write_json(state_path, state)


def prepare(base_url, users, fresh=False, workload='sum'):
    api = Api(base_url)
    api.call("/api/question/list/page/vo", {"current": 1, "pageSize": 1})
    state_path = ROOT / ".local/state.json"
    if state_path.exists() and not fresh:
        state = json.loads(state_path.read_text(encoding="utf-8"))
        if state["baseUrl"] != base_url.rstrip("/"):
            raise ApiError("Existing fixtures use another URL. Use --fresh for a new fixture set.")
        if not state.get("accounts") or not state.get("questionId"):
            raise ApiError("Incomplete fixtures. Inspect state.json; use --fresh if needed.")
        account = state["accounts"][0]
        api.call("/api/user/login", {k: account[k] for k in ("userAccount", "userPassword")})
        api.call("/api/question/get/vo?id=" + numeric_id(state["questionId"]))
        old_count = len(state["accounts"])
        ensure_accounts(api, state, state_path, users)
        select_question(api, state, state_path, workload)
        export_csv(state)
        if len(state["accounts"]) > old_count:
            print(f"Added {len(state['accounts']) - old_count} test accounts.")
        print(f"Reused {len(state['accounts'])} test accounts; questionId={state['questionId']}")
        return
    if fresh and state_path.exists():
        # Retain the prior manifest, including partial creations, for diagnosis.
        archive = ROOT / ".local" / ("state-" + secrets.token_hex(6) + ".json")
        archive.write_bytes(state_path.read_bytes())
    state = {"baseUrl": base_url.rstrip("/"), "runTag": secrets.token_hex(6),
             "accounts": [], "questionId": None}
    write_json(state_path, state)
    ensure_accounts(api, state, state_path, users)
    owner = state["accounts"][0]
    api.call("/api/user/login", {k: owner[k] for k in ("userAccount", "userPassword")})
    select_question(api, state, state_path, workload)
    export_csv(state)
    print(f"Created {users} test accounts; questionId={state['questionId']}")


def add(tree, tag, name, gui, props=None):
    element = ET.SubElement(tree, tag, {"guiclass": gui, "testclass": tag,
                                      "testname": name, "enabled": "true"})
    for key, value in (props or {}).items():
        kind = "boolProp" if isinstance(value, bool) else "stringProp"
        ET.SubElement(element, kind, {"name": key}).text = str(value).lower() if isinstance(value, bool) else str(value)
    return element, ET.SubElement(tree, "hashTree")


def groovy(tree, tag, name, script):
    return add(tree, tag, name, "TestBeanGUI", {"scriptLanguage": "groovy",
               "cacheKey": "true", "parameters": "", "filename": "", "script": script.strip()})


def http_request(tree, name, method, path, body=None):
    element, children = add(tree, "HTTPSamplerProxy", name, "HttpTestSampleGui", {
        "HTTPSampler.domain": "${__P(host,127.0.0.1)}", "HTTPSampler.port": "${__P(port,18101)}",
        "HTTPSampler.protocol": "${__P(protocol,http)}", "HTTPSampler.path": path,
        "HTTPSampler.method": method, "HTTPSampler.contentEncoding": "UTF-8",
        "HTTPSampler.follow_redirects": False, "HTTPSampler.auto_redirects": False,
        "HTTPSampler.use_keepalive": True, "HTTPSampler.connect_timeout": "5000",
        "HTTPSampler.response_timeout": "15000", "HTTPSampler.postBodyRaw": body is not None})
    args = ET.SubElement(element, "elementProp", {"name": "HTTPsampler.Arguments", "elementType": "Arguments"})
    collection = ET.SubElement(args, "collectionProp", {"name": "Arguments.arguments"})
    if body is not None:
        arg = ET.SubElement(collection, "elementProp", {"name": "", "elementType": "HTTPArgument"})
        ET.SubElement(arg, "boolProp", {"name": "HTTPArgument.always_encode"}).text = "false"
        ET.SubElement(arg, "stringProp", {"name": "Argument.value"}).text = body
        ET.SubElement(arg, "stringProp", {"name": "Argument.metadata"}).text = "="
    return children


def build_plan(workload='sum'):
    root = ET.Element("jmeterTestPlan", {"version": "1.2", "properties": "5.0", "jmeter": "5.6.3"})
    top = ET.SubElement(root, "hashTree")
    plan, tree = add(top, "TestPlan", "OJ: login -> submit once -> poll -> Accepted", "TestPlanGui",
                     {"TestPlan.functional_mode": False, "TestPlan.serialize_threadgroups": False})
    variables = ET.SubElement(plan, "elementProp", {"name": "TestPlan.user_defined_variables",
                                                   "elementType": "Arguments", "guiclass": "ArgumentsPanel",
                                                   "testclass": "Arguments", "testname": "Variables"})
    ET.SubElement(variables, "collectionProp", {"name": "Arguments.arguments"})
    group, children = add(tree, "ThreadGroup", "One account and one submission per user", "ThreadGroupGui", {
        "ThreadGroup.num_threads": "${__P(users,1)}", "ThreadGroup.ramp_time": "${__P(ramp,1)}",
        "ThreadGroup.on_sample_error": "continue", "ThreadGroup.scheduler": False,
        "ThreadGroup.same_user_on_next_iteration": True})
    loop = ET.SubElement(group, "elementProp", {"name": "ThreadGroup.main_controller",
                          "elementType": "LoopController", "guiclass": "LoopControlPanel",
                          "testclass": "LoopController", "testname": "Run once", "enabled": "true"})
    ET.SubElement(loop, "boolProp", {"name": "LoopController.continue_forever"}).text = "false"
    ET.SubElement(loop, "stringProp", {"name": "LoopController.loops"}).text = "1"
    add(children, "CSVDataSet", "Dedicated accounts (never recycle)", "TestBeanGUI", {
        "filename": "${__P(accounts_file," + (ROOT / ".local/accounts.csv").as_posix() + ")}",
        "fileEncoding": "UTF-8", "variableNames": "", "ignoreFirstLine": False,
        "delimiter": ",", "quotedData": True, "recycle": False, "stopThread": True,
        "shareMode": "shareMode.all"})
    cookie, _ = add(children, "CookieManager", "Independent login session per thread", "CookiePanel",
                   {"CookieManager.clearEachIteration": False, "CookieManager.controlledByThreadGroup": False})
    ET.SubElement(cookie, "collectionProp", {"name": "CookieManager.cookies"})
    header, _ = add(children, "HeaderManager", "JSON requests", "HeaderPanel")
    headers = ET.SubElement(header, "collectionProp", {"name": "HeaderManager.headers"})
    h = ET.SubElement(headers, "elementProp", {"name": "", "elementType": "Header"})
    ET.SubElement(h, "stringProp", {"name": "Header.name"}).text = "Content-Type"
    ET.SubElement(h, "stringProp", {"name": "Header.value"}).text = "application/json; charset=utf-8"

    login = http_request(children, "Login", "POST", "/api/user/login",
                 '{"userAccount":"${userAccount}","userPassword":"${userPassword}"}')
    groovy(login, "JSR223PostProcessor", "Check business login result", '''
vars.put('login_ok', 'false'); vars.put('outcome', 'login_failed')
try {
    def r = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
    if (!prev.isSuccessful() || r.code != 0 || r.data == null) throw new Exception('Login failed')
    vars.put('login_ok', 'true'); vars.put('outcome', 'submission_failed')
} catch (Exception e) { prev.setSuccessful(false); prev.setResponseMessage('Business login failed') }
''')
    _, logged_in = add(children, "IfController", "Only submit after successful login", "IfControllerPanel",
                       {"IfController.condition": "${login_ok}", "IfController.evaluateAll": False,
                        "IfController.useExpression": True})
    _, journey = add(logged_in, "TransactionController", "Judge journey", "TransactionControllerGui",
                     {"TransactionController.parent": True, "TransactionController.includeTimers": True})
    code = fixture_path(workload, source=True).read_text(encoding="utf-8")
    # JMeter unescapes backslashes embedded directly in sampler properties.
    # Decode the source and serialize JSON at runtime to preserve C++ literals.
    encoded_code = base64.b64encode(code.encode('utf-8')).decode('ascii')
    submit = http_request(journey, "Submit", "POST", "/api/question/question_submit/do", '${submission_body}')
    groovy(submit, "JSR223PreProcessor", "Start end-to-end clock", """
def source = new String(java.util.Base64.getDecoder().decode('%s'), 'UTF-8')
vars.put('submission_body', groovy.json.JsonOutput.toJson(
    [questionId: vars.get('questionId'), language: 'cpp', code: source]))
""" % encoded_code + '''
vars.put('started_ns', System.nanoTime().toString())
vars.put('started_at', java.time.Instant.now().toString())
vars.put('poll', 'false')
''')
    groovy(submit, "JSR223PostProcessor", "Extract exact submission ID and check business result", '''
try {
    def r = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
    if (!prev.isSuccessful() || r.code != 0 || r.data == null || !(r.data.toString() ==~ /[1-9][0-9]*/)) {
        vars.put('business_code', r.code == null ? 'missing' : r.code.toString())
        throw new Exception('Submission rejected')
    }
    vars.put('submitId', r.data.toString())
    vars.put('outcome', 'pending'); vars.put('poll', 'true')
} catch (Exception e) {
    vars.put('poll', 'false'); prev.setSuccessful(false)
    prev.setResponseMessage('Business submission failed; no automatic retry')
}
''')
    _, polling = add(journey, "WhileController", "Poll until terminal state or observation deadline",
                      "WhileControllerGui", {"WhileController.condition": "${poll}"})
    query = http_request(polling, "Query result", "GET", "/api/question/question_submit/get/vo?id=${submitId}")
    add(query, "ConstantTimer", "One-second polling interval", "ConstantTimerGui",
        {"ConstantTimer.delay": "${__P(poll_ms,1000)}"})
    groovy(query, "JSR223PostProcessor", "Read terminal state; status 2 alone is not Accepted", '''
try {
    def r = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
    if (!prev.isSuccessful() || r.code != 0 || r.data == null || r.data.id?.toString() != vars.get('submitId')) {
        throw new Exception('Result query failed')
    }
    def d = r.data
    if (!(d.status instanceof Number) || !(d.status.intValue() in [0,1,2,3,4])) throw new Exception('Invalid status')
    vars.put('final_status', d.status.toString())
    def info = d.judgeInfo
    if (info instanceof String) info = new groovy.json.JsonSlurper().parseText(info)
    String verdict = info?.message == null ? '' : info.message.toString()
    vars.put('verdict', verdict)
    if (d.status.intValue() in [2,3,4]) {
        vars.put('poll', 'false')
        vars.put('outcome', d.status.intValue() == 2 && verdict == 'Accepted' ? 'accepted' : 'judge_failed')
    }
} catch (Exception e) {
    vars.put('poll', 'false'); vars.put('outcome', 'query_failed')
    prev.setSuccessful(false); prev.setResponseMessage('Business result query failed')
}
long elapsed = (System.nanoTime() - vars.get('started_ns').toLong()) / 1000000L
if (vars.get('poll') == 'true' && elapsed >= props.getProperty('timeout_seconds', '360').toLong() * 1000L) {
    vars.put('poll', 'false'); vars.put('outcome', 'observation_timeout')
}
''')
    groovy(journey, "JSR223Sampler", "Check Accepted verdict", '''
boolean accepted = vars.get('outcome') == 'accepted'
SampleResult.setSuccessful(accepted)
SampleResult.setResponseCode(accepted ? '200' : '500')
SampleResult.setResponseMessage(vars.get('outcome') ?: 'unknown')
''')
    groovy(children, "JSR223Sampler", "Save user outcome", '''
Long elapsed = vars.get('started_ns') == null ? null : (System.nanoTime() - vars.get('started_ns').toLong()).intdiv(1000000L)
def result = [user: vars.get('userAccount'), submissionId: vars.get('submitId'),
    startedAt: vars.get('started_at'), observedAt: java.time.Instant.now().toString(),
    elapsedMs: elapsed, status: vars.get('final_status'), verdict: vars.get('verdict'),
    outcome: vars.get('outcome'), businessCode: vars.get('business_code')]
def dir = new File(props.getProperty('result_dir', props.getProperty('user.dir') + '/oj-manual-results'))
dir.mkdirs()
new File(dir, 'task-' + ctx.getThreadNum() + '.json').setText(groovy.json.JsonOutput.toJson(result), 'UTF-8')
boolean accepted = result.outcome == 'accepted'
SampleResult.setSuccessful(accepted)
SampleResult.setResponseCode(accepted ? '200' : '500')
SampleResult.setResponseMessage(result.outcome ?: 'unknown')
SampleResult.setResponseData(groovy.json.JsonOutput.toJson(result), 'UTF-8')
''')
    ET.indent(root, space="  ")
    path = ROOT / "judge.jmx"
    ET.ElementTree(root).write(path, encoding="UTF-8", xml_declaration=True)
    print(f"Generated {path}")


def summarize(directory, expected):
    rows = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(directory.glob("task-*.json"))]
    accepted = [r for r in rows if r.get("outcome") == "accepted"]
    durations = sorted(r["elapsedMs"] for r in accepted if r.get("elapsedMs") is not None)
    outcomes = {}
    for row in rows:
        key = row.get("outcome") or "unknown"
        outcomes[key] = outcomes.get(key, 0) + 1
    summary = {"expectedUsers": expected, "observedUsers": len(rows), "accepted": len(accepted),
               "outcomes": outcomes, "allPassed": len(rows) == expected and len(accepted) == expected,
               "meanAcceptedMs": round(sum(durations) / len(durations), 1) if durations else None,
               "p95AcceptedMs": durations[math.ceil(len(durations) * .95) - 1] if durations else None,
               "timing": "Client-observed submit-to-terminal time; includes polling delay, excludes login.",
               "scope": "Small fixed batch, not a sustained capacity/QPS measurement."}
    write_json(directory / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=True, indent=2))
    return summary["allPassed"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("build")
    p.add_argument('--workload', choices=('sum', 'knapsack', 'knapsack-full'), default='sum')
    p = sub.add_parser("prepare")
    p.add_argument("--base-url", default=DEFAULT_URL)
    p.add_argument("--users", type=int, default=10, choices=range(1, 501))
    p.add_argument("--fresh", action="store_true")
    p.add_argument('--workload', choices=('sum', 'knapsack', 'knapsack-full'), default='sum')
    p = sub.add_parser("report")
    p.add_argument("directory", type=Path)
    p.add_argument("--users", type=int, required=True)
    args = parser.parse_args()
    try:
        if args.command == "build":
            build_plan(args.workload)
        elif args.command == "prepare":
            parsed = urlsplit(args.base_url)
            if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username:
                raise ApiError("Use an HTTP(S) origin without a path, query, or credentials")
            prepare(args.base_url.rstrip("/"), args.users, args.fresh, args.workload)
        else:
            if not summarize(args.directory, args.users):
                return 1
    except (ApiError, OSError, ValueError, KeyError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

/* Browser smoke checks with fixture APIs. Requires a local Chrome/Edge installation.
 * Run after npm run build: node tests/ux-smoke.cjs
 * No requests are sent to the real backend.
 */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const os = require("node:os");
const { spawn } = require("node:child_process");

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const root = path.resolve(__dirname, "../dist");
const chrome =
  process.env.CHROME_PATH ||
  [
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  ].find((file) => fs.existsSync(file));
if (!chrome) throw new Error("Set CHROME_PATH to a Chrome or Edge executable.");
if (!fs.existsSync(path.join(root, "index.html")))
  throw new Error("Run npm run build first.");
const profile = fs.mkdtempSync(path.join(os.tmpdir(), "codeorder-ux-"));
const apiCalls = [];
const runtimeErrors = [];
let loggedIn = false;
let failList = false;
let submissionCount = 0;
const question = {
  id: "1890000000000000011",
  title: "两数之和",
  tags: ["数组", "哈希表"],
  content: "# 两数之和\n\n给定一个整数数组，找到和为目标值的两个整数。",
  submitNum: 10,
  acceptedNum: 4,
  judgeConfig: { timeLimit: 1000, memoryLimit: 262144 },
};
let browser, ws, server, cdp, evaluate;

async function main() {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    const localPath = path.resolve(
      root,
      "." + decodeURIComponent(url.pathname)
    );
    if (!localPath.startsWith(root + path.sep)) {
      res.writeHead(403);
      res.end();
      return;
    }
    const file =
      fs.existsSync(localPath) && fs.statSync(localPath).isFile()
        ? localPath
        : path.join(root, "index.html");
    const types = {
      ".html": "text/html",
      ".js": "text/javascript",
      ".css": "text/css",
      ".svg": "image/svg+xml",
      ".woff2": "font/woff2",
      ".ttf": "font/ttf",
    };
    res.setHeader(
      "Content-Type",
      types[path.extname(file)] || "application/octet-stream"
    );
    fs.createReadStream(file).pipe(res);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  browser = spawn(
    chrome,
    [
      "--headless=new",
      "--disable-gpu",
      "--no-first-run",
      "--no-default-browser-check",
      "--remote-debugging-port=0",
      "--user-data-dir=" + profile,
      "about:blank",
    ],
    { windowsHide: true, stdio: ["ignore", "ignore", "pipe"] }
  );
  let browserErrors = "";
  browser.stderr.on("data", (data) => {
    browserErrors += data.toString();
  });
  browser.on("error", (error) => {
    browserErrors += error.message;
  });
  let endpoint;
  for (let i = 0; i < 100; i++) {
    const file = path.join(profile, "DevToolsActivePort");
    if (fs.existsSync(file)) {
      endpoint = fs.readFileSync(file, "utf8").split("\n")[0];
      break;
    }
    await sleep(100);
  }
  if (!endpoint)
    throw new Error("Headless browser did not start: " + browserErrors);
  const targets = await (
    await fetch("http://127.0.0.1:" + endpoint + "/json/list")
  ).json();
  ws = new WebSocket(
    targets.find((item) => item.type === "page").webSocketDebuggerUrl
  );
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = reject;
  });
  let nextId = 0;
  const pending = new Map();
  cdp = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++nextId;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });
  ws.onmessage = async (event) => {
    const data = JSON.parse(event.data);
    if (data.id) {
      const item = pending.get(data.id);
      if (item) {
        pending.delete(data.id);
        data.error
          ? item.reject(new Error(JSON.stringify(data.error)))
          : item.resolve(data.result);
      }
    } else if (data.method === "Runtime.exceptionThrown") {
      runtimeErrors.push(
        data.params.exceptionDetails.text +
          " " +
          (data.params.exceptionDetails.exception?.description || "")
      );
    } else if (data.method === "Fetch.requestPaused") {
      const { requestId, request } = data.params;
      const url = new URL(request.url);
      if (request.method === "OPTIONS") {
        await cdp("Fetch.fulfillRequest", {
          requestId,
          responseCode: 204,
          responseHeaders: [
            {
              name: "Access-Control-Allow-Origin",
              value: "http://127.0.0.1:" + port,
            },
            { name: "Access-Control-Allow-Credentials", value: "true" },
            {
              name: "Access-Control-Allow-Methods",
              value: "GET, POST, OPTIONS",
            },
            {
              name: "Access-Control-Allow-Headers",
              value: "content-type, authorization",
            },
          ],
        });
        return;
      }
      if (request.method !== "OPTIONS")
        apiCalls.push({
          path: url.pathname,
          body: request.postData ? JSON.parse(request.postData) : null,
        });
      let payload = { code: 0, data: null };
      if (url.pathname.endsWith("/user/get/login"))
        payload = loggedIn
          ? {
              code: 0,
              data: { id: "7", userName: "测试用户", userRole: "user" },
            }
          : { code: 40100, message: "未登录" };
      else if (url.pathname.endsWith("/user/login")) {
        loggedIn = true;
        payload.data = { id: "7" };
      } else if (url.pathname.endsWith("/user/logout")) {
        loggedIn = false;
        payload.data = true;
      } else if (url.pathname.endsWith("/question/list/page/vo"))
        payload = failList
          ? { code: 50000, message: "模拟网络故障" }
          : { code: 0, data: { records: [question], total: 21 } };
      else if (url.pathname.endsWith("/question/get/vo"))
        payload.data = question;
      else if (url.pathname.endsWith("/question_submit/do")) {
        submissionCount++;
        payload.data = "1950000000000000001";
      } else if (url.pathname.endsWith("/question_submit/get/vo"))
        payload.data = {
          id: "1950000000000000001",
          status: 2,
          judgeInfo: { message: "Accepted", time: 12, memory: 8192 },
        };
      else if (url.pathname.endsWith("/question_submit/list/page"))
        payload.data = {
          records: [
            {
              id: "1950000000000000001",
              questionId: question.id,
              status: 2,
              language: "java",
              judgeInfo: { message: "Accepted", time: 12, memory: 8192 },
              createTime: "2026-10-30T08:00:00",
            },
          ],
          total: 1,
        };
      try {
        await cdp("Fetch.fulfillRequest", {
          requestId,
          responseCode: 200,
          responseHeaders: [
            { name: "Content-Type", value: "application/json" },
            {
              name: "Access-Control-Allow-Origin",
              value: request.headers.Origin || "http://127.0.0.1:" + port,
            },
            { name: "Access-Control-Allow-Credentials", value: "true" },
            {
              name: "Access-Control-Allow-Methods",
              value: "GET, POST, OPTIONS",
            },
            {
              name: "Access-Control-Allow-Headers",
              value: "content-type, authorization",
            },
          ],
          body: Buffer.from(JSON.stringify(payload)).toString("base64"),
        });
      } catch (e) {
        runtimeErrors.push("Fixture error: " + e.message);
      }
    }
  };
  await cdp("Runtime.enable");
  await cdp("Page.enable");
  await cdp("Page.addScriptToEvaluateOnNewDocument", {
    source:
      "window.WebSocket = class { constructor() { throw new Error('Fixture: WebSocket unavailable'); } };",
  });
  await cdp("Fetch.enable", {
    patterns: [{ urlPattern: "*://*/api/*", requestStage: "Request" }],
  });
  evaluate = async (expression) => {
    const result = await cdp("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (result.exceptionDetails)
      throw new Error(
        result.exceptionDetails.exception?.description ||
          result.exceptionDetails.text
      );
    return result.result.value;
  };
  const wait = async (expression, label) => {
    for (let i = 0; i < 100; i++) {
      if (await evaluate(expression)) return;
      await sleep(100);
    }
    console.error(
      "DIAGNOSTICS",
      JSON.stringify({
        body: await evaluate("document.body.innerText"),
        calls: apiCalls,
        errors: runtimeErrors,
      })
    );
    throw new Error("Timed out: " + label);
  };
  const clickText = (text) =>
    evaluate(
      "Array.from(document.querySelectorAll('button,a')).find(e => e.textContent.trim() === " +
        JSON.stringify(text) +
        ")?.click()"
    );
  const input = (selector, value) =>
    evaluate(
      "(() => { const el = document.querySelector(" +
        JSON.stringify(selector) +
        "); if (!el) throw new Error('Missing input'); const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; set.call(el," +
        JSON.stringify(value) +
        "); el.dispatchEvent(new Event('input', { bubbles: true })); })()"
    );
  await cdp("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await cdp("Page.navigate", {
    url: "http://127.0.0.1:" + port + "/questions?page=2",
  });
  await wait("document.body.innerText.includes('两数之和')", "question list");
  assert.equal(
    apiCalls.filter((c) => c.path.endsWith("/question/list/page/vo")).length,
    1,
    "initial list fetch should occur once"
  );
  assert.match(await evaluate("document.title"), /码序/);
  await input('input[placeholder="搜索你想挑战的题目"]', "两数");
  await sleep(300);
  assert.equal(
    apiCalls.filter((c) => c.path.endsWith("/question/list/page/vo")).length,
    1,
    "typing should not fetch"
  );
  await clickText("搜索题目");
  await wait(
    "location.search.includes('title=') && !location.search.includes('page=')",
    "search resets page"
  );
  assert.equal(apiCalls.at(-1).body.title, "两数");
  console.log(
    "PASS: one initial request; explicit search resets pagination and preserves filters in URL"
  );
  await clickText("两数之和");
  await wait("location.pathname === '/user/login'", "login redirect");
  assert.ok(
    await evaluate(
      "new URLSearchParams(location.search).get('redirect').includes('from=')"
    ),
    "return path preserves question and filter context"
  );
  await input('input[autocomplete="username"]', "fixture");
  await input('input[autocomplete="current-password"]', "fixture123");
  await clickText("登录");
  await wait(
    "location.pathname.includes('/view/question/') && document.querySelector('.monaco-editor')",
    "login returns to question"
  );
  assert.ok(await evaluate("document.body.innerText.includes('1000 ms')"));
  console.log(
    "PASS: guest question -> login -> original question; long IDs and limit units preserved"
  );
  // Use the editor's real paste path through the browser input protocol.
  await wait(
    "document.querySelector('.monaco-editor textarea')",
    "editor input ready"
  );
  await sleep(300);
  const editorPoint = await evaluate(
    "(() => { const r = document.querySelector('.monaco-editor .view-lines').getBoundingClientRect(); return { x: r.x + 12, y: r.y + 10 }; })()"
  );
  await cdp("Input.dispatchMouseEvent", {
    type: "mousePressed",
    ...editorPoint,
    button: "left",
    clickCount: 1,
  });
  await cdp("Input.dispatchMouseEvent", {
    type: "mouseReleased",
    ...editorPoint,
    button: "left",
    clickCount: 1,
  });
  const draft =
    "public class Main { public static void main(String[] args) {} }";
  await cdp("Input.insertText", { text: draft });
  await wait(
    "localStorage.getItem('codeorder:draft:7:" +
      question.id +
      ":java') === " +
      JSON.stringify(draft),
    "draft saved"
  );
  await cdp("Page.reload");
  await wait(
    "document.querySelector('.monaco-editor') && document.body.innerText.includes('已恢复上次的代码草稿')",
    "draft restored"
  );
  await wait(
    "document.querySelector('.view-lines')?.textContent.includes('Main')",
    "restored editor rendered"
  );
  await evaluate(
    "document.querySelector('.editor-toolbar .arco-select-view').click()"
  );
  await wait(
    "Array.from(document.querySelectorAll('.arco-select-option')).some(e => e.textContent.trim() === 'Go')",
    "language dropdown"
  );
  await evaluate(
    "Array.from(document.querySelectorAll('.arco-select-option')).find(e => e.textContent.trim() === 'Go').click()"
  );
  await sleep(100);
  assert.equal(
    await evaluate(
      "document.querySelector('.view-lines').textContent.includes('Main')"
    ),
    false,
    "new language should have its own draft"
  );
  await evaluate(
    "document.querySelector('.editor-toolbar .arco-select-view').click()"
  );
  await wait(
    "Array.from(document.querySelectorAll('.arco-select-option')).some(e => e.textContent.trim() === 'Java')",
    "language dropdown reopened"
  );
  await evaluate(
    "Array.from(document.querySelectorAll('.arco-select-option')).find(e => e.textContent.trim() === 'Java').click()"
  );
  await wait(
    "document.querySelector('.view-lines').textContent.includes('Main')",
    "Java draft restored after language switch"
  );
  console.log(
    "PASS: editor input persists a user/question/language draft and reload restores it"
  );
  await clickText("提交代码");
  await wait(
    "document.querySelector('.result-panel').innerText.includes('全部测试通过')",
    "polling reconciles result without WebSocket"
  );
  assert.equal(submissionCount, 1);
  assert.equal(
    apiCalls.find((c) => c.path.endsWith("/question_submit/do")).body.code,
    draft,
    "restored code must be submitted intact"
  );
  assert.ok(
    await evaluate(
      "document.querySelector('.metrics').innerText.includes('12 ms')"
    )
  );
  await clickText("提交代码");
  await sleep(200);
  await wait(
    "document.querySelector('.result-panel').innerText.includes('全部测试通过') && !document.querySelector('.submit-bar button').classList.contains('arco-btn-loading')",
    "second result"
  );
  assert.equal(
    submissionCount,
    2,
    "completed submissions must not block resubmission"
  );
  console.log(
    "PASS: API result fallback, inline metrics and resubmission after completion"
  );
  if (process.env.UX_SCREENSHOT_DIR || process.argv[2]) {
    fs.mkdirSync(process.env.UX_SCREENSHOT_DIR || process.argv[2], {
      recursive: true,
    });
    const shot = await cdp("Page.captureScreenshot", { format: "png" });
    fs.writeFileSync(
      path.join(
        process.env.UX_SCREENSHOT_DIR || process.argv[2],
        "codeorder-workspace.png"
      ),
      Buffer.from(shot.data, "base64")
    );
  }
  await cdp("Emulation.setDeviceMetricsOverride", {
    width: 390,
    height: 844,
    deviceScaleFactor: 1,
    mobile: true,
  });
  await sleep(200);
  assert.ok(
    await evaluate("document.documentElement.scrollWidth <= 390"),
    "mobile workspace must not overflow viewport"
  );
  await cdp("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await clickText("← 返回题库");
  await wait(
    "location.pathname === '/questions' && location.search.includes('title=')",
    "return filter"
  );
  failList = true;
  await clickText("搜索题目");
  await wait("document.body.innerText.includes('模拟网络故障')", "error state");
  failList = false;
  await clickText("重试");
  await wait(
    "document.body.innerText.includes('两数之和') && !document.body.innerText.includes('模拟网络故障')",
    "retry"
  );
  console.log(
    "PASS: return to filtered library; failed request renders retry and recovers"
  );
  if (process.env.UX_SCREENSHOT_DIR || process.argv[2]) {
    fs.mkdirSync(process.env.UX_SCREENSHOT_DIR || process.argv[2], {
      recursive: true,
    });
    const shot = await cdp("Page.captureScreenshot", { format: "png" });
    fs.writeFileSync(
      path.join(
        process.env.UX_SCREENSHOT_DIR || process.argv[2],
        "codeorder-library.png"
      ),
      Buffer.from(shot.data, "base64")
    );
  }
  await clickText("提交记录");
  await wait("document.body.innerText.includes('12 ms')", "submission list");
  const callsBefore = apiCalls.filter((c) =>
    c.path.endsWith("/question_submit/list/page")
  ).length;
  await clickText("刷新");
  await sleep(300);
  assert.equal(
    apiCalls.filter((c) => c.path.endsWith("/question_submit/list/page"))
      .length,
    callsBefore + 1
  );
  await cdp("Emulation.setDeviceMetricsOverride", {
    width: 390,
    height: 844,
    deviceScaleFactor: 1,
    mobile: true,
  });
  await sleep(200);
  assert.ok(
    await evaluate("document.documentElement.scrollWidth <= 390"),
    "mobile list must not overflow viewport"
  );
  await cdp("Page.navigate", {
    url: "http://127.0.0.1:" + port + "/user/register",
  });
  await wait(
    "document.body.innerText.includes('开启你的代码之旅')",
    "registration"
  );
  assert.ok(
    await evaluate("document.documentElement.scrollWidth <= 390"),
    "mobile auth must not overflow viewport"
  );
  await input('input[autocomplete="username"]', "fixture");
  const passwords = await evaluate(
    "document.querySelectorAll('input[autocomplete=\"new-password\"]').length"
  );
  assert.equal(passwords, 2);
  await input('input[autocomplete="new-password"]', "fixture123");
  await input('input[placeholder="再次输入密码"]', "different");
  await clickText("创建账号");
  await wait(
    "document.body.innerText.includes('两次输入的密码不一致')",
    "inline password validation"
  );
  assert.equal(
    apiCalls.filter((c) => c.path.endsWith("/user/register")).length,
    0,
    "invalid registration must not send request"
  );
  console.log(
    "PASS: submission refresh; mobile list/auth layout; inline registration validation"
  );
  if (process.env.UX_SCREENSHOT_DIR || process.argv[2]) {
    const shot = await cdp("Page.captureScreenshot", { format: "png" });
    fs.writeFileSync(
      path.join(
        process.env.UX_SCREENSHOT_DIR || process.argv[2],
        "codeorder-register-mobile.png"
      ),
      Buffer.from(shot.data, "base64")
    );
  }
  assert.deepEqual(runtimeErrors, [], "no browser runtime errors");
  console.log("All fixture browser smoke checks passed.");
}
main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(async () => {
    if (cdp && ws?.readyState === WebSocket.OPEN) {
      try {
        await cdp("Browser.close");
      } catch {
        /* Browser may already have closed. */
      }
    }
    ws?.close();
    browser?.kill();
    server?.close();
    // Temporary profile intentionally lives in the OS temp directory.
  });

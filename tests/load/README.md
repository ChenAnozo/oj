# OJ 最小判题压测

默认使用现有 Docker 网关 `http://127.0.0.1:18101`。通过真实业务接口准备 10 个专用账号和一道“两数相加”题目，由 JMeter 执行 **登录 → 提交一次 C++ 代码 → 每秒查询一次 → 检查 Accepted**。不修改消费者并发、业务限流或现有部署，不直接操作数据库。

## 最快运行

先启动平时使用的 Docker 部署。在项目根目录打开普通 PowerShell，运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Users 1
```

脚本会检查网关、准备测试账号和题目、生成 JMeter 计划。若未指定 JMeter，会下载并校验官方 JMeter 5.6.3 ZIP，解压至本目录的 `.tools`，然后执行一次真实判题。需要 Python 3.9+ 和 Java 8+；Python 不需要额外安装第三方库。

若已安装 JMeter，使用它的**根目录**：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Users 1 -JMeterHome 'C:\tools\apache-jmeter-5.6.3'
```

如果通过 Nginx 测整个网站入口，追加 `-BaseUrl http://127.0.0.1:18081`；旧本地开发部署可改为 `http://127.0.0.1:8101`。地址应当是 origin，不带 `/api`。切换入口会改变 Cookie 和测试条件，脚本要求显式加 `-Fresh` 创建新的一组数据。

## 逐步测试

一次只运行一条，上一轮完成后再开始下一轮：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Users 1
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Users 5
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Users 10
```

每个虚拟用户使用独立账号，每人只提交一次；五个或十个请求约在一秒内发起。它们是并发提交用户，**不是保证同时运行五个或十个容器**，实际执行并发取决于现有消费者和沙箱。

增加到二十用户时，执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Users 20
```

脚本会在已有十个账号基础上补齐到二十个，保留原题目、原账号和同一份 C++ 代码。二十人约在一秒内各提交一次；本命令不更新服务，沿用当前已运行的消费并发。结果中的 P95 此时不一定等于最大耗时。

五十用户也使用同一入口，自动补齐账号，无需重新部署：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Users 50
```

继续增加到一百用户时，保持当前消费者配置，只运行一轮：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Users 100
```

脚本会将已有账号补齐到一百个，保留原题目和已有账号。用户数表示本轮提交人数，实际同时执行的任务数仍取决于消费者和沙箱。

后续各档按单轮执行，不自动重复测试。

测试题包含三个固定用例；代码在 `fixtures/sum.cpp`，题目数据在 `fixtures/question.json`。状态 2 还必须搭配 `judgeInfo.message == Accepted` 才算通过。登录失败、提交业务拒绝、查询失败、错误判题结果和超过观察期限，都会计为失败；不会盲目重试提交请求。

默认最多观察 360 秒。每个 HTTP 查询有自己的 15 秒超时，最后一次查询可能使实际结束略晚于观察期限。若观察超时，提交 ID 会保留，程序可能仍在后端执行；先检查任务和队列，不要立即重复启动测试。

## 只准备数据或打开界面

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -PrepareOnly
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-LoadTest.ps1 -Gui -Users 1
```

GUI 使用的就是 `judge.jmx`，可以查看线程组、Cookie、CSV、HTTP 请求、业务校验及轮询逻辑。调试时可在线程组下添加“查看结果树”；正式施压使用命令行模式。GUI 模式结束后不会自动生成汇总报告。CSV 绝对路径会在生成计划时按本机位置填入，搬动目录后重新运行脚本即可。

## 看结果

### 两个消费者的对比测试

执行以下命令会先确认执行队列空闲，再用现有镜像更新判题服务，等待容器健康且 RabbitMQ 确认 Consumers 为 2，最后执行十用户测试：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-TwoConsumerTest.ps1
```

不重新构建镜像，不更新依赖容器。若队列忙、消费者数量异常或服务未就绪，脚本停止，不提交压测任务。消费者检查结果写入 `.local/last-consumer-check.json`。原来单消费者的十用户结果保留在独立结果目录中；对比时保持题目、代码、用户数和机器条件一致。

四消费者的同条件对比使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-FourConsumerTest.ps1
```

两个入口都调用 `Run-ConsumerTest.ps1`，以临时进程环境变量 `JUDGE_CONSUMERS` 指定本次要应用的并发数，脚本结束会恢复原环境变量。Compose 中只对判题服务引用它，当前无环境变量时默认并发为 4；服务实际运行值以本次更新及 RabbitMQ 检查为准。仅修改文件不会改变已运行容器。

需要恢复单消费者并执行对比时，运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-ConsumerTest.ps1 -Consumers 1
```

也可以恢复双消费者，重新运行 `Run-TwoConsumerTest.ps1`。这些入口每次都会执行十用户真实判题，不是只改配置。

### 文件说明

每轮输出到独立的 `results/时间-用户数-随机标识/`：

- `summary.json`：预期用户数、实际观察用户数、Accepted 数、失败分类及耗时。
- `task-*.json`：每个用户的提交 ID、最终状态、判题结论和耗时；不包含密码。
- `samples.jtl`：JMeter 原始请求记录。
- `html/index.html`：JMeter HTML 报告。
- `jmeter.log`：JMeter 执行日志。
- `run.json`：这轮的入口、用户数和起止时间。

自定义耗时从客户端开始提交算到查询到终态，包含排队、处理、网络及轮询间隔，不包含登录。P95 只针对 Accepted 样本，失败数另列；一到十个样本的分位数波动很大，只用于初步观察。JMeter 报告含登录、查询及父事务等不同标签，不要把 Total 的吞吐量解释为每秒完成判题数。

测试时可以手动观察 RabbitMQ `code_queue` 的 Ready 和 Unacked，以及 Docker/任务管理器的 CPU、内存。`code_delay_queue` 中的延迟检查消息不是待执行判题任务。停止发请求后观察积压是否消化、资源是否恢复。

## 数据与限流

账号随机命名为 `load_...`，题目标题包含 `OJ load test` 和随机标识；账号/题目/提交记录保留，便于排查。脚本不删除业务记录。`.local/state.json` 保存已创建数据的 ID 和测试密码，`.local/accounts.csv` 供 JMeter 使用，两者均被 Git 忽略，请勿分享。

重复运行默认复用数据，并在测试前等待六秒，减少五秒冷却的影响。现有分钟/小时/天限流仍然生效，频繁重复测试仍可能被拒绝。先等额度恢复；需要新测试数据时显式追加 `-Fresh`，旧清单会归档，不会清空 Redis 或提高限额。发生创建中断或响应不明确时，查看 `.local/state.json` 的 pending 字段核对创建情况，不要盲目重试。

## 当前验证范围

本工具不调整现有业务逻辑。测试单用户与小批量链路成功，不等于测出了最大稳定容量；先看真实结果，再决定是否另做多消费者对比测试。若运行环境限制本机网络或 Docker API，脚本无法代表用户完成实际测试，须在可访问服务的普通 PowerShell 中运行。

## Sandbox stage timing baseline

Run once from ordinary PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-SandboxProfileTest.ps1
```

This builds and tests the sandbox with locally cached Maven dependencies, verifies an idle queue, applies six consumers without a load run, rebuilds/restarts only the sandbox, then runs one monitored 100-user batch. Other users should not submit during this measurement. A missing Maven dependency stops the script before deployment; install the dependency through the normal Maven build before retrying.

The existing load and resource reports remain in their usual result directories. A separate results/*-sandbox-profile-* directory contains only timing log lines and stage-times.csv / stage-times.json. meanEventMs is the average per operation; meanPerSubmissionMs sums that stage's events per completed sandbox call and averages over all completed calls. Three exec_run events per request therefore contribute to one request's total for that stage. submission_total and runner_total are parent spans: do not add them to child stages. Stream-close events can repeat due to callback lifecycle. Timings include attempted operations that fail, exclude MQ waiting and polling, and do not replace verdict timings. submission_total starts just after workspace creation and includes workspace cleanup. Compare this instrumented baseline with later changes using the same logging, consumer count and workload. Extra diagnostic logging itself has overhead.

Stages cover compile, image_check, container_create, container_start, input_prepare, stats_open, exec_create, exec_run, exec_inspect, stats_command_close, stats_stream_close, output_stream_close, input_cleanup, container_remove and workspace_cleanup. exec_run includes Docker API/output handling and waiting, not pure program CPU time. Stats timing measures API calls and stream closure, not all background sampling costs. Each task is correlated by its workspace identifier; no source code or input is included in timing lines. If the summary does not contain 100 completed sandbox requests, inspect the log before using it as a complete batch result.

### Optimization 1: startup image validation

The sandbox now checks both configured Java/C++ images during Spring bean initialization. Missing images or an unavailable Docker daemon prevent startup. Normal submissions no longer call image inspection; if an image disappears later, container creation fails explicitly without pulling images or retrying the submission. No container reuse policy has changed.

Use the same Run-SandboxProfileTest.ps1 command for one deployment and one 100-user comparison run. Compare against the recorded baseline results/20261020-154911-sandbox-profile-9fc5d0 (compile 677.943 ms, image_check 79.570 ms, submission_total 2008.695 ms per submission; client mean 21082.3 ms and P95 35843 ms). image_check should be absent from new per-submission timing logs. The previous 79.570 ms includes Docker API latency/contention and is not a guaranteed improvement in every run.


## Two-sandbox concurrency comparison (4 / 6 / 8)

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-SandboxConcurrencyTest.ps1
```

Requires two running sandbox instances registered in Nacos. Runs exactly one monitored 100-user batch per setting, with the same existing C++ sum program and three test cases. Sets consumers and total sandbox slots to 4, 6 and 8 respectively (2, 3 and 4 slots per instance). This changes the end-to-end execution concurrency budget together, rather than leaving consumers as a lower bottleneck. It uses existing images and changes only the two sandbox services and judge service. Do not submit other work during this comparison.

The script verifies idle queues before reconfiguration, checks running slot values and consumer counts, and verifies both instances are registered. Each setting has separate load and monitor results, plus filtered execution start/end logs for the 100 recorded submission IDs. Actual peak concurrency is measured from those intervals; a lower observed peak is reported, not treated as full slot utilization. `comparison.csv` and `comparison.json` collect Accepted counts, mean/P95 completion times and observed total/per-instance peaks.

In a finally block, the script attempts to restore the original running consumer/slot values (normally 12 consumers and 6 slots per instance). It does not write these temporary settings to `.env`. Restoration waits for an idle queue; if the queue stays busy or Docker is unavailable, it warns and leaves the current deployment in place. In that case check the running settings before another test.

Each setting is a single fixed batch in the order 4, 6, 8. Treat differences as preliminary observations, including possible warm-up and host-load effects, not a sustained-QPS measurement or proof of an optimum. Both sandbox instances still share the same host resources.

To test only total concurrency 12 (6 slots per sandbox, 12 consumers), without rerunning 4/6/8:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-SandboxConcurrencyTest.ps1 -TotalConcurrency 12
```

This runs one 100-user batch and then attempts to restore the original running settings, following the same idle-queue rules.

For 8 slots per sandbox and 16 consumers, use `-TotalConcurrency 16`. This also runs only one 100-user batch and attempts to restore the original configuration afterward. Compare the result with total concurrency 12; a historical single-instance test with 8 consumers does not establish a fixed per-instance limit.


### Full-size algorithm workload and storage upgrade

`-Workload knapsack-full` selects an independent v2 question with 20 cases, up to n=2000 and W=20000. The JSON contains about 243 KiB of case data. It includes small boundaries, overweight items, equal items, and full-size random inputs. The expected O(nW) solution is checked against a separate previous-row oracle; a naive exponential DFS (`fixtures/knapsack-full/naive.cpp`) is available for a negative control. Local checks establish algorithm separation, not Docker verdict timing: verify one Accepted result after deployment before load testing. The question's verdict time limit is 2000 ms; the sandbox's existing process-kill timeout is a separate setting.

The question API now defaults to 1048576 UTF-8 bytes for serialized judgeCase, configurable with `JUDGE_CASE_MAX_BYTES` in local Compose (Spring property `judge.case-max-bytes`). Other question-field limits are unchanged. MySQL initialization uses MEDIUMTEXT; existing deployments are widened by `deploy/local/Update-JudgeCaseStorage.ps1`, also called by Start-Services. This does not erase data or volumes. Raising the API limit alone is insufficient with the old TEXT column. Increasing the limit beyond the default also requires reviewing database capacity and proxy body limits.

```powershell
# After building the updated question-service JAR and image and upgrading storage:
.\tests\load\Run-LoadTest.ps1 -Users 1 -Workload knapsack-full
.\tests\load\Run-SandboxConcurrencyTest.ps1 -SandboxInstances 3 -TotalConcurrency 18 -Users 500 -Workload knapsack-full
```

Use the same full-size workload for subsequent concurrency comparisons. It changes case count, input transfer, and computation together; it is not a measurement of computation alone. Existing `sum` and `knapsack` workloads and their questions remain available.

### Algorithm workload: 0/1 knapsack

Use `-Workload knapsack` on Run-LoadTest, Run-MonitoredLoadTest, or Run-SandboxConcurrencyTest. The default `sum` remains available for earlier comparisons. Workloads have separate questions; the existing test accounts are reused. No existing question or submissions are edited or deleted. Do not run these scripts concurrently because the generated plan and accounts CSV are shared.

```powershell
# Verify an end-to-end verdict first.
.\tests\load\Run-LoadTest.ps1 -Users 1 -Workload knapsack
# Then repeat the latest three-instance / 18-consumer experiment.
.\tests\load\Run-SandboxConcurrencyTest.ps1 -SandboxInstances 3 -TotalConcurrency 18 -Users 500 -Workload knapsack
```

The C++ solution uses O(nW) time and O(W) memory. Five deterministic cases have (n,W) = (8,30), (60,20000), (120,100000), (200,200000), (200,200000), with positive weights and values. The largest cases each perform approximately 40 million DP updates. Inputs together fit the existing 8192-character judgeCase validation limit. `fixtures/knapsack/generate.py` regenerates inputs and answers using a separate previous-row reference implementation; `solution.cpp` uses descending in-place updates. Limits remain 10 seconds and 128 MiB per case to avoid making host contention an intentional time-limit test. A local optimized build is fast; this is a defined algorithm workload, not a guaranteed one-second CPU workload or a proxy for all OJ problems. Five cases also add process/stream overhead versus the old three-case sum fixture. Reports record the selected workload; compare concurrency settings using the same workload.

### 500-user burst with the current deployment

```powershell
.\tests\load\Run-MonitoredLoadTest.ps1 -Users 500 -ExpectedConsumers 12
```

Starts 500 users over a one-second ramp; each logs in, submits once, and waits for its verdict. Login latency can spread submission times, so this is a burst rather than 500 requests at exactly the same instant. Existing accounts and the question are reused, with accounts extended to 500 as needed. The test retains the deployment's instance and slot settings and reports outcomes for all 500 users. Execution capacity remains 12 with two six-slot sandboxes; excess tasks wait in the queue. This uses the same C++ workload, increasing the batch size rather than program complexity.

### Three sandboxes: 18 consumers, 6 slots per instance

After starting and registering three sandbox instances:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\load\Run-SandboxConcurrencyTest.ps1 -SandboxInstances 3 -TotalConcurrency 18
```

Runs one monitored 100-user batch with the same workload. Verifies all three running slot settings and registry entries, exports per-instance execution evidence, and reports `sandbox1Peak`, `sandbox2Peak`, `sandbox3Peak` plus the total observed peak. A configured total of 18 does not guarantee that all slots overlap; the observed peak is reported separately. Both configured instance count and per-instance slot values must be consistent before changes. Temporary consumer and slot settings are restored to the pre-test running values after the queue drains; the instance count stays at three. The shared host resources do not increase merely because another sandbox service was started.


### Submission latency diagnostics

The question service emits `SUBMIT_TIMING` records (milliseconds), without source code or test data. The result polling endpoint no longer prints the submission object.

- `controller`: session access, Redis rate limits, user lock, pending-task count, transactional service call, and unlock.
- `service_body`: full question read, submission insert, question counter update, and callback registration. `success=true` here means the method body completed, not that the transaction committed.
- `after_commit_publish`: execution-message and delayed-check-message publication. A successful call does not itself establish broker confirmation.

Correlate scopes by `submissionId`; for failures before an ID is available, use the thread and timestamp. Scopes are nested and must not be added. The controller scope includes transaction startup/commit and synchronous callbacks, but excludes gateway/Tomcat queuing before controller entry and response serialization/session persistence afterward. Compare its transactional-call duration with the service-body and publish durations to identify unexplained transaction overhead; this is not a direct database connection-wait measurement. All records are emitted when their scope exits, including exceptions. Keep the client response timeout at 15 seconds during comparison.

After the queue drains, rebuild/recreate only the question service with the new JAR, then repeat the same workload and concurrency. Export question/gateway logs with timestamps for the new test interval and inspect `SUBMIT_TIMING` alongside gateway connection-reset errors.


### Database and connection-pool investigation

Keep connection limits, judging slots and the 15-second client timeout unchanged. Enable the temporary sampler in the current PowerShell session and deploy the rebuilt question-service JAR:

```powershell
$env:JUDGE_DIAGNOSTICS_ENABLED = 'true'
. .\deploy\local\Environment.Common.ps1
Invoke-LocalServices -DockerArguments @('up','-d','--no-deps','--build','--wait','yuoj-backend-question-service')
.\tests\load\Run-SandboxConcurrencyTest.ps1 -SandboxInstances 3 -TotalConcurrency 18 -Users 500 -Workload knapsack-full -DatabaseDiagnostics
```

Pool sampling is disabled by default and uses its own daemon thread without borrowing connections. `DB_POOL` records give active/idle/total/waiting/max each second. These are non-atomic snapshots, not time spent borrowing a connection. MySQL sampling runs independently of Docker statistics, approximately every two seconds plus command duration. It reads local-container root credentials internally to access `performance_schema`; it does not change global settings, enable instrumentation or grant privileges. Statements have a one-second SELECT execution limit and detailed snapshots have row limits. SQL digests normalize literal values; raw SQL text, source code and test cases are not collected. If statement instrumentation is disabled, digest fields can be null. SQL collection is only implemented/expected for the local MySQL 8 deployment.

`-DatabaseDiagnostics` preflights collection before starting load and writes these files under the run's monitor directory, even when the load test fails:

- `database.jsonl`: server row-lock counters, current lock-wait edges (table/index/requester/blocker), active sessions and normalized SQL digests, with UTC timestamps.
- `submission-diagnostics.log`: question, gateway and judge logs, including `DB_POOL`, `SUBMIT_TIMING` and timeout-consumer events.
- `database-errors.txt`: sampling failures, if any. Missing samples do not prove absence of contention.

Repeated `active=max` with `waiting>0` confirms connection-pool contention; concurrent waits on `question`/`PRIMARY` support a hot-row explanation. Correlate SQL digests and blocking threads before changing pool size. Additional sampling itself has some overhead, and short-lived waits between snapshots can be missed.

After the queue drains and diagnostic testing is finished, disable the sampler and recreate only the question service:

```powershell
$env:JUDGE_DIAGNOSTICS_ENABLED = 'false'
Invoke-LocalServices -DockerArguments @('up','-d','--no-deps','--wait','yuoj-backend-question-service')
```

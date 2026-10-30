# 代码沙箱接入 Nacos 与多实例运行

代码沙箱作为 yuoj-code-sandbox 服务注册到现有 Nacos。判题服务通过独立的 OpenFeign 客户端和 Spring Cloud LoadBalancer 按服务名调用 /executeCode。部署脚本支持 1、2、3 个实例，当前选择三个，网站入口仍是 http://127.0.0.1:18081 。

容器池方案已撤回。本次保持每次任务创建并销毁执行容器的模型，没有恢复预热池，也没有修改编译和答案比较算法。

## 日常启动与实例数量

启动 Docker Desktop 后，在项目根目录运行：

```powershell
.\deploy\local\Start-Website.ps1
```

脚本读取 deploy/local/.env 中保存的 SANDBOX_INSTANCES，恢复上次选择的实例数量。当前保存值为 3。显式调整数量：

```powershell
# 扩至三个实例，生成独立工作卷，并保存选择
.\deploy\local\Start-Services.ps1 -SandboxInstances 3

# 扩至两个实例，生成第二个独立工作卷，并保存选择
.\deploy\local\Start-Services.ps1 -SandboxInstances 2

# 回到一个实例；停止第二实例并等待 Nacos 更新，保留其卷
.\deploy\local\Start-Services.ps1 -SandboxInstances 1
```

Nacos 中实例都属于 yuoj-code-sandbox，metadata.sandbox-instance 分别为 sandbox-1、sandbox-2、sandbox-3。Compose 服务名分别为 code-sandbox、code-sandbox-2、code-sandbox-3。第二、第三服务分别使用 sandbox-scale、sandbox-scale-3 profile，启动脚本按保存的实例数自动启用。缩容时停止多余实例，保留其卷。

所有沙箱都在各自容器的 8090 端口监听。第一个保留本机 18090 端口用于原有直接检查；其余实例不映射宿主机端口，只通过 Docker 内部网络和服务发现访问。

## 调用方式与配置

| 配置 | 当前值或默认值 | 作用 |
|---|---|---|
| SANDBOX_INSTANCES | 当前本机 3；新环境默认 1 | 保存本地实例数量（1～3） |
| SANDBOX_CLIENT_TYPE | discovery | 按服务名调用；remote 为原固定 URL 模式 |
| codesandbox.service-id | yuoj-code-sandbox | Nacos 服务名 |
| SANDBOX_AUTH_SECRET | 原有独立密钥 | Feign 显式携带 auth 请求头，两个实例使用同一受保护接口契约 |
| codesandbox.connect-timeout-ms | 2000 | 连接超时 |
| codesandbox.read-timeout-ms | 90000 | 等待沙箱响应的超时 |
| SANDBOX_MAX_CONCURRENT | 6 | 每个沙箱同时执行的任务上限 |
| codesandbox.queue-wait-ms | 10000 | 容量满时最多等候 10 秒，之后返回 HTTP 429 |
| JUDGE_CONSUMERS | 沿用原配置，默认 12 | RabbitMQ 消费线程数，prefetch 改为 1 |

每实例容量 6、三个实例合计 18 个执行槽；当前仍为 12 个 RabbitMQ 消费线程，队列判题的实际并发也受消费者数量限制。额外已分派请求在各实例内等待，有界等待超时会失败。负载均衡不感知执行槽剩余量，也不保证任意时刻负载严格平均。增加实例不会增加宿主机 CPU 或内存资源。
原 oj.judge.max-processing=1 仍限制每个用户只有一个未完成任务，因此并发验收使用多个用户。该值不是全局执行容量。

Feign 客户端设置 NEVER_RETRY，Docker 中判题服务同时关闭 LoadBalancer 的自动重试，并禁止跟随 HTTP 重定向。连接失败、执行超时或容量不足不会自动把同一份代码投递到另一实例，以免重复执行。LoadBalancer 自身的实例缓存关闭，Nacos 客户端仍需要通过注册中心事件收敛实例列表。

原固定 URL 模式仍可使用：在 deploy/local/.env 中设置 SANDBOX_CLIENT_TYPE=remote 后运行 Start-Services.ps1；恢复 discovery 后再次运行即可。固定 URL 保留为 http://code-sandbox:8090/executeCode，指向第一个实例。

沙箱默认 application.yml 中关闭 Nacos 注册，原独立启动方式仍可使用。Docker 外部配置才启用注册、注入 Nacos 认证和实例标识。沙箱继续是独立 Maven 项目，仅引入所需的 Nacos 和匹配的 Spring Cloud 依赖管理，没有继承后端所有数据库、Redis 等依赖。

## 文件与职责

- yuoj-code-sandbox/pom.xml、src/main/resources/application.yml：Nacos 依赖及独立运行默认值。
- deploy/local/config/sandbox/application-docker.yml：注册配置、实例 metadata、容量和执行配置。
- CodeSandboxFeignClient.java：判题服务内的 Feign 接口，按服务名调用，复用现有请求/响应模型。
- CodeSandboxFeignConfiguration.java：仅对沙箱客户端生效的超时、禁止重试及禁止重定向配置。
- DiscoveryCodeSandboxClient.java：适配原 CodeSandboxClient 接口，携带认证并记录执行实例。
- JudgeServiceImpl.java、CodeSandboxClientFactory.java：调用模式选择，调用失败继续按原规则写回系统错误。
- CodeSandboxController.java：原认证及 JSON 接口保持兼容，增加容量控制、X-Sandbox-Instance 响应头和执行开始/结束日志。
- docker-compose.service.yml：第二、第三实例及独立卷、实例环境变量、判题客户端与消费配置。
- Environment.Common.ps1、Start-Services.ps1、Test-Services.ps1：注册状态检查、数量保存与恢复、兼容日常网站启动。
- Test-SandboxScale.ps1：按保存数量核对真实分流、并发执行与实例退出测试。

新增沙箱调用日志以 submission=<提交ID> instance=sandbox-1/2/3 关联实际实例，不记录调用密钥。开始和结束日志可用于核对真实执行重叠，而不只根据总耗时推断并发。

## 工作目录与安全边界

三个服务分别使用 yuoj-local-sandbox-work、yuoj-local-sandbox-work-2、yuoj-local-sandbox-work-3 命名卷，Start-Services.ps1 查询各卷的 Linux Mountpoint 后注入各自 Docker 绑定路径。每个任务继续使用 UUID 子目录，结束后仅清理自身目录与执行容器。认证失败的请求不会占用执行名额；执行失败也会释放名额。

本次沿用已授权的本机 Docker socket。所有实例仍共享同一个 Docker 引擎和电脑资源；独立工作卷不是针对已被攻陷服务的安全隔离边界。公网运行仍需要独立执行节点及进一步加固，不应把 /executeCode 配成浏览器可直接访问的网关路由。本次没有新增该网关路由。

## 构建与验收

修改 Java 代码后先分别运行 Maven package，再构建判题和沙箱镜像。单独执行 Start-Services.ps1 -Build 仍只构建镜像，不代替 Maven 打包：

```powershell
Push-Location .\yuoj-backend-microservice
mvn package -DskipTests
Pop-Location
Push-Location .\yuoj-code-sandbox
mvn package -DskipTests
Pop-Location
docker compose --env-file .\deploy\local\.env -f .\docker-compose.service.yml build yuoj-backend-judge-service code-sandbox
.\deploy\local\Start-Services.ps1 -SandboxInstances 2
```

检查网站和所有注册实例：

```powershell
.\deploy\local\Test-Services.ps1 -Website
```

完整单次业务验收与多实例并发验收：

```powershell
.\deploy\local\Test-Services.ps1 -Website -Exercise
.\deploy\local\Test-SandboxScale.ps1 -Website -ParallelUsers 6 -CheckInstanceExit
```

第二条会注册独立临时用户并并发提交 Java/C++，核对实际命中所有配置实例及执行区间重叠。CheckInstanceExit 会在本轮任务全部完成后停止编号最大的实例，等待 Nacos 摘除，再提交新任务确认剩余实例仍可执行，最后恢复原数量。它不验证正在运行任务的无损迁移，也不承诺故障发生时零失败。成功后只删除脚本创建的测试记录，失败时保留记录便于诊断。

## 双实例阶段取得的结果（历史记录）

- 后端构建通过，新增客户端 6 项测试及原判题/任务领取 25 项测试通过。
- 沙箱原执行、认证、生命周期、路径与镜像检查，加上新增容量/失败释放检查，共 52 项不同测试通过。
- 单实例 discovery 模式经网站完成 Java/C++ Accepted、登录会话和 WebSocket 验收。
- 临时切换 remote 模式后同一完整验收通过，随后已恢复 discovery。
- 双实例已注册，4 个用户同时提交，两种语言都在两个实例上实际执行并全部 Accepted。
- 开始/结束日志证明峰值 4 个任务同时执行。
- 第二实例正常退出、注册中心摘除后，新 Java 任务由第一实例完成并 Accepted，随后第二实例恢复注册。
- 两个独立工作卷均无残留任务目录；测试用户、题目和提交已清理，旧预热容器已在旧服务停止时清理。
- 日常 Start-Website.ps1 已验证会恢复保存的双实例配置，前端入口及现有数据结构保持原状。本次没有创建 Git 提交。

## 调整双实例执行并发（2026-10-20）

Docker 部署默认 `SANDBOX_MAX_CONCURRENT=6`，两个实例最多合计 12 个执行槽，与当前默认 12 个 RabbitMQ 消费者配合。沙箱独立运行时的 Java 兜底值仍为 2；Docker 环境以显式配置为准。可在 `deploy/local/.env` 覆盖额度。两个实例共享同一台机器和 Docker 引擎，提高额度是否改善耗时需要测量；已完成总并发 4、6、8、12、16 的单轮测试；当前选择总并发 12，各实例峰值 6，100 次全部 Accepted。

```powershell
.\deploy\local\Start-Services.ps1 -SandboxInstances 2
.\deploy\local\Test-SandboxScale.ps1 -Website -ParallelUsers 6 -ExpectedPeak 6
```

测试会读取两个运行中实例的环境变量，检查每实例执行峰值未超过配置额度，并在指定 `ExpectedPeak` 时要求总峰值达到指定值。仅增加实例或消费者不保证满额执行，负载均衡也不保证始终均匀。


## 双实例并发参数选择（历史记录，2026-10-20）

The selected two-instance configuration is `JUDGE_CONSUMERS=12` and `SANDBOX_MAX_CONCURRENT=6`. These values are saved in `deploy/local/.env` and are the Docker deployment defaults. Apply with `Start-Services.ps1 -SandboxInstances 2`; no load test is triggered. After this configuration is running, the concurrency comparison script captures 12/6 as its original settings and restores those values after future experiments.

## 第三个实例（2026-10-20）

第三实例复用 yuoj-code-sandbox:local 镜像，不修改 Java 业务代码，不重新打包。独立工作卷为 yuoj-local-sandbox-work-3，启动脚本自动查询其 Mountpoint 并保存 SANDBOX3_HOST_WORKSPACE_ROOT。三个实例共用原有认证配置和 Docker 引擎。

```powershell
.\deploy\local\Start-Services.ps1 -SandboxInstances 3
.\deploy\local\Start-Website.ps1
.\deploy\local\Test-Services.ps1 -Website -Exercise
.\deploy\local\Test-SandboxScale.ps1 -Website -ParallelUsers 6 -CheckInstanceExit
```

多实例验收读取保存的数量，要求所有实例实际执行提交、Java/C++ 全部 Accepted、日志证明执行重叠，并检查各实例容量和工作目录清理。退出检查停止编号最大的实例（当前为第三个），等待注册摘除后验证剩余实例继续判题，最后恢复原数量。它不测试执行中任务的无损迁移。测试成功后清理临时账户、题目和提交。

当前保留 JUDGE_CONSUMERS=12、SANDBOX_MAX_CONCURRENT=6，不自动调整吞吐参数。tests/load/Run-SandboxConcurrencyTest.ps1 是双实例专项比较脚本，仍要求先缩为两个实例；三实例分流验收使用上面的 Test-SandboxScale.ps1。

本次三实例部署验收已通过：

- 三个实例健康并注册，保存 SANDBOX_INSTANCES=3；第二、第三实例无宿主机端口映射。
- Test-Services.ps1 -Website -Exercise 通过：网站、登录会话、WebSocket、Java/C++ 全链路 Accepted。
- 6 用户并发提交全部 Accepted：sandbox-1 对应提交 2112529286428430338、2112529286684282882；sandbox-2 对应 2112529286499733505、2112529286801723394；sandbox-3 对应 2112529286415847425、2112529286579425281。
- 日志证明总执行峰值 6，各实例峰值 2，未超每实例 6 个槽的配置。
- 第三实例正常停止并摘除后，新提交由 sandbox-2 完成并 Accepted；第三实例随后恢复，最终三个实例和网站均健康。
- 三个工作目录无残留；两轮验收临时账户、题目和提交均已清理。无需重建镜像，没有创建 Git 提交。

本轮启动再次遇到 Docker Desktop 的残留 dockerInference 通信文件错误。确认后端已崩溃且目录仅有零字节通信文件后，将两个通信目录改名保留为 run.stale-20261020-205804、docker-secrets-engine.stale-20261020-205804，重启后恢复。没有重置 Docker 或删除磁盘、卷。

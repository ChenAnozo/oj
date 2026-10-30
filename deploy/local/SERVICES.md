# 第五步：本地业务服务

后续已完成沙箱 Nacos 服务发现，部署脚本支持 1～3 个实例，操作见 [沙箱服务发现说明](SANDBOX-DISCOVERY.md)。本页中的固定沙箱 URL 和单实例描述保留为第五步时的实现记录，当前默认调用模式为 discovery。

已通过 docker-compose.service.yml 启动网关、用户、题目、判题和代码沙箱，共五个应用。第四步的四个中间件继续独立运行。前端页面和 Nginx 尚未部署。

第六步已增加独立的前端联调模式，访问 http://127.0.0.1:18080 。启动与验收见 [前后端联调说明](FRONTEND.md)；本页保留第五步的后端操作方法。前端生产部署及 Nginx 仍待后续完成。

## 入口与网络

| 应用 | 本机入口 | 容器内端口 |
|---|---|---|
| 网关 | http://127.0.0.1:18101 | 8101 |
| 用户服务 | 仅内部访问，通过网关 /api/user/** | 8102 |
| 题目服务 | 仅内部访问，通过网关 /api/question/** | 8103 |
| 判题服务 | 仅内部访问，通过网关 /api/judge/** | 8104 |
| 沙箱 | http://127.0.0.1:18090/health；执行接口需要 auth 请求头 | 8090 |

18101 是后端 API 入口，直接打开根路径不提供网站首页。网关还转发 /api/question/ws/** WebSocket。

业务 Compose 项目名为 yuoj-services，复用第四步的外部网络 yuoj-local-network。地址使用 mysql、redis、rabbitmq、nacos、code-sandbox 等内部服务名；业务入口只绑定 127.0.0.1。

## 启动、检查、停止

首次构建或更新源码后：

```powershell
cd C:\projects\yuoj\yuoj-backend-microservice
mvn package -DskipTests
cd C:\projects\yuoj\yuoj-code-sandbox
mvn package -DskipTests
cd C:\projects\yuoj
& .\deploy\local\Start-Services.ps1 -Build
```

启动脚本先验证第四步环境，再为沙箱准备专用共享卷、持久保留的随机调用密钥和运行镜像。镜像逐个构建以减少同时构建的资源占用。没有代码更新时，省略 -Build，使用已有应用镜像启动。

只读检查容器、网关 API 和 Nacos 注册：

```powershell
& .\deploy\local\Test-Services.ps1
```

完整验收会创建临时用户、题目和提交，执行真实 Java/C++ 代码及 WebSocket 检查；成功后仅删除本次创建的数据库记录，失败时保留测试记录用于诊断：

```powershell
& .\deploy\local\Test-Services.ps1 -Exercise
```

查看状态和日志：

```powershell
docker compose --env-file .\deploy\local\.env -f .\docker-compose.service.yml ps
docker compose --env-file .\deploy\local\.env -f .\docker-compose.service.yml logs --tail 100
```

停止业务应用，保留中间件和共享卷：

```powershell
docker compose --env-file .\deploy\local\.env -f .\docker-compose.service.yml stop
```

业务配置中的健康检查主要验证 HTTP 可访问或监听端口；完整依赖和判题结果由 Test-Services.ps1 -Exercise 验证。判题健康检查本身只是 TCP，不单独证明消费链路正常。

## 配置与必要代码适配

运行时激活 docker profile，挂载 deploy/local/config/backend 和 config/sandbox，凭据通过本地 .env 注入，不重新写入镜像。本次没有修改原 application.yml 或前端请求地址。

为了让这一步能实际启动，提前完成了原第六步的必要部分：

- MySQL、Redis、RabbitMQ、Nacos 使用内部地址及第四步的独立认证；会话使用共享的 yuoj:docker:session 命名空间。
- Redisson 读取可选的 spring.redis.password，原无密码本地配置仍可运行。
- 判题队列通过 Spring AMQP Declarables 创建，使用 Spring 配置的连接和凭据，不再从 main 调用写死 localhost 的旧初始化入口；旧工具类保留供原来的手工用途。
- 远程沙箱 URL 与调用密钥可配置；原本地默认地址和密钥保留，docker profile 使用 code-sandbox:8090 和独立随机密钥。
- 沙箱工作目录及对应 Docker 守护进程路径可配置；未配置时仍使用原来的 user.dir/tmpCode。
- 判题运行镜像可配置。此环境 Java 使用固定摘要的 Temurin 8 JRE，C++ 使用固定摘要的 Alpine 3.22 运行已静态链接的程序；编译仍在沙箱服务容器内完成。
- 判题容器明确运行常驻 shell 命令，避免依赖基础镜像默认命令；每次执行结束仍按原逻辑清理。
- 四个后端以 UID/GID 10001 运行，Java user.home 和 Sentinel 日志目录指向 /tmp 下的可写位置；避免 SDK 日志初始化异常。

Java 运行参数限制应用堆内存及可见处理器数。原开发环境仍可使用原配置从 IDE 启动；第五步没有让前端开发服务器切换到 18101。

## 沙箱与 Docker 的权限边界

本次用户已明确授权本机演练沙箱挂载 /var/run/docker.sock。沙箱因此可管理本机 Docker，包括创建判题容器；它不是只有普通容器权限的隔离边界。这套配置仅用于当前本机演练，未使用 privileged 或公开的 Docker TCP 接口。

共享卷名为 yuoj-local-sandbox-work。Start-Services.ps1 查询它的真实 Linux Mountpoint 并存入本地 .env；沙箱挂载该卷为 /workspace，再将每个请求子目录映射为守护进程侧的 bind 路径。不要把 Windows 文件路径直接当作 Docker 守护进程的 Linux 路径。

本机 Docker API 最低版本实测为 1.40；沙箱 docker-java 客户端在该环境使用 api.version=1.41。更新 Docker 或 SDK 后应重新验证。

沙箱仍保持现有编译模式与资源限制，完整公开运行不可信代码的加固不属于本次完成项。

## 已验证结果

- Maven 后端构建成功；已有 JudgeTaskTest 8 项、QuestionSubmitClaimTest 17 项通过。
- 沙箱编译、认证、生命周期、路径映射等 47 项测试通过。
- 五个业务容器 healthy，四个后端在 Nacos 中有健康实例；网关可查询题目。
- 沙箱拒绝无 auth 请求；Java/C++ 各两组 stdin 输入实际执行并得到预期输出。
- 通过网关完成注册、登录、当前用户查询、题目创建；会话在用户和题目服务之间有效。
- 经 RabbitMQ、判题服务、共享卷和 Docker 运行器，Java/C++ 提交均为 Accepted。
- 网关 WebSocket 升级成功，并收到对应提交的当前状态消息。
- 注销成功，临时测试账号、题目和提交已清理。

部署过程中 Docker 引擎曾停止，重启 Docker Desktop 后恢复；Docker Hub 的 TLS 超时通过内置 Dockerfile 前端和已有镜像缓存重试解决。五个应用 Dockerfile 现已固定基础镜像摘要，不再额外下载 docker/dockerfile 前端。

# YUOJ 服务部署清单

本清单根据当前项目源码、配置和本机检查整理，供本地部署演练使用。已完成服务梳理、Maven 打包、应用镜像构建、第四步独立中间件部署、第五步业务服务部署、第六步前后端联调、第七步前端容器与 Nginx 部署，以及沙箱服务发现与双实例扩展。第 1 节保留原开发环境清单；本地演练环境的实际版本、端口和验收结果见第 8 至 12 节。

“当前版本”描述已取得的证据，不代表已验证可用于生产，也不代表已确定 Docker 镜像版本。教程截图中的版本不直接作为本项目版本。

## 1. 服务部署表

表中的端口为服务内部监听端口或明确标注的拟定映射，并非全部需要映射到电脑或开放公网。

| 服务名称 | 英文名 / 项目服务名 | 端口 | 当前版本依据 | 服务类别 | 关键配置 / 依赖 |
|---|---|---|---|---|---|
| 数据库 | mysql | 3306 | 原本机客户端与数据库服务端均为 9.1.0（只读核对） | 环境依赖 | 业务库 `yuoj`；账号、密码、初始化 SQL、持久化目录 |
| 缓存与会话 | redis | 6379 | 本机 redis-server 5.0.14.1 | 环境依赖 | Spring Redis 使用 DB 1；题目服务 Redisson 使用 DB 2；登录会话与缓存需核对持久化策略 |
| 消息队列 | rabbitmq | 5672；15672 为启用管理插件后的默认管理端口 | 未确认，不能按教程认定为 3.12.6 | 环境依赖 | 用户、密码、vhost、交换机和队列初始化；现有配置使用 guest 账号 |
| 注册中心 | nacos | 8848；客户端 gRPC 9848；节点通信另见下文 | 本机 nacos-server.jar 清单确认为 2.2.0 | 环境依赖 | 当前注册地址 `127.0.0.1:8848`；现有脚本按 standalone 启动 |
| 网关服务 | yuoj-backend-gateway | 8101 | 应用 0.0.1-SNAPSHOT；Java 8 源码目标；Spring Boot 2.6.13 | 自研后端 | Nacos；将 `/api/user/**`、`/api/question/**`、`/api/judge/**` 路由至对应服务 |
| 用户服务 | yuoj-backend-user-service | 8102 | 应用 0.0.1-SNAPSHOT；Java 8 源码目标；Spring Boot 2.6.13 | 自研后端 | MySQL、Redis、Nacos；上下文 `/api/user`；Cookie 路径 `/api` |
| 题目服务 | yuoj-backend-question-service | 8103 | 应用 0.0.1-SNAPSHOT；Java 8 源码目标；Spring Boot 2.6.13 | 自研后端 | MySQL、Redis、RabbitMQ、Nacos；上下文 `/api/question`；包含判题 WebSocket 接口 |
| 判题服务 | yuoj-backend-judge-service | 8104 | 应用 0.0.1-SNAPSHOT；Java 8 源码目标；Spring Boot 2.6.13 | 自研后端 | MySQL、Redis、RabbitMQ、Nacos、远程沙箱；上下文 `/api/judge` |
| 代码沙箱服务 | yuoj-code-sandbox | 8090 | 应用 0.0.1-SNAPSHOT；Java 8 源码目标；Spring Boot 2.6.13 | 自研沙箱 | Docker 引擎、宿主 JDK 的 javac、g++；工作目录 `tmpCode`；接口 `/executeCode`、`/health` |
| 前端静态站点与反向代理 | yuoj-frontend + nginx | 拟定容器 80 → 本机 8088；正式 HTTPS 入口待后续配置 | 前端应用 0.1.0、Vue 3；Nginx 版本尚未选定 | 前端 / 入口 | `npm run build` 生成 dist，由 Nginx 提供页面；代理 API、WebSocket；配置 history 路由回退 |

Java 8 是 Maven 中的源码/字节码目标，不是本机实际 JDK 版本。本机默认 `java -version` 为 11.0.6。业务后端声明 Spring Cloud 2021.0.5、Spring Cloud Alibaba 2021.0.5.0。前端 package-lock.json 锁定 Vue 3.5.29、Vue CLI Service 5.0.9。

本机 8088 是本清单为演练拟定的入口端口，尚未配置或验证空闲；若被占用，只修改宿主机映射，不必修改 Nginx 容器内的 80。

## 2. 沙箱所需镜像与构建工具

| 名称 | 当前版本 / 镜像 | 是否独立常驻服务 | 用途与说明 |
|---|---|---|---|
| Java 判题运行镜像 | `openjdk:8-alpine` | 否 | 沙箱按请求创建容器；当前 Java 运行超时 5 秒；镜像必须提前准备 |
| C++ 判题运行镜像 | `gcc:latest` | 否 | 沙箱按请求创建容器；当前 C++ 运行超时 10 秒；latest 未固定版本，部署前需固定并验证 |
| JDK / javac | 本机默认 JDK 11.0.6；项目源码目标 Java 8 | 否 | 构建 Java 应用；当前沙箱也在宿主环境调用 javac 编译提交代码；需验证编译产物与 Java 8 运行容器兼容 |
| g++ | 未确认 | 否 | 当前沙箱在宿主环境编译 C++，使用 `-static -std=c++11 -O2` |
| Node.js / npm | 本机 Node.js 22.14.0；npm 版本未核对 | 否 | 用于构建前端，不必作为生产前端常驻服务 |
| Maven | 版本未确认 | 否 | 用于构建后端及沙箱，不是独立业务服务 |
| Docker Desktop / Compose | 已安装，用户已成功运行 hello-world | 部署工具 | 当前仅查到 `hello-world:latest` 本地镜像；项目依赖镜像尚需准备 |

当前沙箱编译超时为 30 秒，容器禁用网络、只读根文件系统，内存上限为 100,000,000 字节。此处记录现状；不能据此认为沙箱已经完成公开运行不可信代码所需的安全验收。

## 3. 端口和数据准备

| 项目 | 本地部署演练约定 / 需核对内容 |
|---|---|
| 浏览器入口 | 拟定 `http://localhost:8088`；API 与 WebSocket 统一经过 Nginx 和网关 |
| 服务间通信 | Compose 网络内使用服务名，例如 `mysql:3306`、`redis:6379`；容器里的 localhost 指容器自己 |
| 管理界面 | Nacos 控制台、RabbitMQ 管理界面仅在需要调试时映射到本机回环地址，不开放公网 |
| Nacos 端口 | 2.x 客户端除 8848 外还需能访问 9848；9849、7848 用于节点间通信，不作为用户入口。应用在宿主机而 Nacos 在容器内时，要同时规划 8848 与 9848 映射 |
| 数据库与数据 | 准备 yuoj 建表和初始数据；演练使用独立数据库/卷，避免覆盖现有开发数据 |
| Redis | DB 1 与 DB 2 的用途需要保留；当前 Redisson 创建代码还未设置密码，部署时与 Redis 鉴权配置一起处理 |
| RabbitMQ | 核对 `code_exchange`、`code_queue`、`code_delay_queue`、`code_dlx_exchange`、`code_timeout_queue` 及对应绑定；判题启动入口调用 InitRabbitMq.doInit()，但初始化连接写死 localhost，需和 Spring RabbitMQ 配置统一，并验证各队列确实建齐 |
| 持久化 | MySQL、RabbitMQ、Nacos 的数据目录以及 Redis 持久化策略需在后续 Compose 中明确；沙箱 tmpCode 属于待清理的临时数据 |
| Docker 访问 | 沙箱需要访问 Docker 引擎；若沙箱本身容器化，需单独设计 Docker 连接和工作目录挂载，保证判题容器能看到相同源码目录 |

Nacos 端口依据：[Nacos 2.x 部署说明](https://nacos.io/en/docs/v2.4/manual/admin/deployment/deployment-overview/)。

## 4. 配置差异与后续待办

| 当前项目状态 | 部署时需要处理 |
|---|---|
| MySQL、Redis、RabbitMQ、Nacos 大量使用 localhost/127.0.0.1 | 拆分环境配置；容器之间改用内部服务名 |
| 前端 API 为 `http://localhost:8101`，WebSocket 为 `ws://localhost:8101/...` | 改为部署配置或同源地址；HTTPS 场景使用 wss |
| 判题端沙箱地址为 `http://127.0.0.1:8090/executeCode` | 提取为配置项，连接部署环境的沙箱地址 |
| 数据库密码和沙箱调用密钥存在明文配置/源码 | 更换并通过环境配置注入；本清单不记录凭据 |
| 已启用四个后端应用与独立沙箱的 Spring Boot repackage | 第二步已构建并核对 JAR 结构；三个公共依赖模块仍为普通 JAR，服务实际启动待后续验证 |
| 沙箱仍在宿主环境编译；Java 编译器与运行镜像版本可能不同 | 验证字节码兼容，并另行处理编译隔离与资源限制 |
| 已有五个应用 Dockerfile、环境 Compose 和数据库初始化 SQL；尚无业务 Compose、Nginx 部署配置 | 第四步环境已运行；业务接入与前端部署在后续步骤完成 |
| 教程和本机的中间件版本不同 | 下一步单独确定并锁定容器镜像版本，完成兼容测试，不照搬截图版本 |

以下模块无需作为独立服务启动：`yuoj-backend-common`、`yuoj-backend-model`、`yuoj-backend-service-client`。它们是其他服务的公共代码/依赖库；父项目 `yuoj-backend-microservice` 用于聚合构建，也不单独部署为应用。

## 5. 核对依据

- 各服务 `src/main/resources/application.yml`：服务名、端口、上下文路径和连接配置。
- 后端父 POM、各模块 POM、沙箱 POM：应用版本、Java 目标与 Spring 版本。
- 前端 package.json、Vue 配置、OpenAPI 配置与 useJudgeWebSocket：前端版本、构建方式、请求地址。
- 沙箱 README、语言规格及 Docker Executor：宿主编译方式、运行镜像和超时。
- 本机 mysql/redis-server/java/node 版本命令：已安装工具版本，不等同于已连接服务器的实际版本。
- 本机 Nacos JAR 的 META-INF/MANIFEST.MF：Nacos 2.2.0；现有 start-nacos 脚本：standalone 模式。
- Docker 本地镜像列表：本次检查仅有 hello-world:latest；未执行镜像下载或服务启动。

## 6. 第二步：Maven 打包结果

已调整后端父 POM，只声明 Spring Boot 插件；由四个应用子模块各自执行 repackage。独立沙箱也启用了 repackage。未修改 application.yml、数据库连接、默认环境和业务代码。

在 PowerShell 中可按以下命令重复打包：

```powershell
cd C:\projects\yuoj\yuoj-backend-microservice
mvn clean package -DskipTests

cd C:\projects\yuoj\yuoj-code-sandbox
mvn clean package -DskipTests
```

两个构建均已 BUILD SUCCESS。使用 Maven 3.9.9、JDK 11.0.6；本次编译了测试代码，但通过 -DskipTests 跳过测试执行。clean 会清理 target 构建产物，不清理源码和数据库。

| 应用 | 生成位置（相对项目根目录） | 大小（MiB，约） |
|---|---|---|
| 网关 | yuoj-backend-microservice/yuoj-backend-gateway/target/yuoj-backend-gateway-0.0.1-SNAPSHOT.jar | 85.56 |
| 用户服务 | yuoj-backend-microservice/yuoj-backend-user-service/target/yuoj-backend-user-service-0.0.1-SNAPSHOT.jar | 89.90 |
| 题目服务 | yuoj-backend-microservice/yuoj-backend-question-service/target/yuoj-backend-question-service-0.0.1-SNAPSHOT.jar | 98.05 |
| 判题服务 | yuoj-backend-microservice/yuoj-backend-judge-service/target/yuoj-backend-judge-service-0.0.1-SNAPSHOT.jar | 97.38 |
| 沙箱 | yuoj-code-sandbox/target/yuoj-code-sandbox-0.0.1-SNAPSHOT.jar | 43.61 |

五个应用 JAR 均已检查 JarLauncher、Manifest 启动类、BOOT-INF/classes 中的启动类文件及 BOOT-INF/lib 依赖目录；三个公共库未被重打包为可执行应用。后续 Dockerfile 使用 .jar，不使用 .jar.original。

本步骤未启动应用，不代表数据库连接、Nacos 注册或完整判题已经验证。Maven 仍报告父 POM 中 spring-cloud-dependencies 使用 import scope 却放在 dependencies 下的既有警告，本次未扩大范围调整依赖管理；后续单独核对。

## 7. 第三步：应用 Docker 镜像

五个应用目录各新增 Dockerfile 和 .dockerignore，直接复制第二步生成的明确名称的 JAR 为 `/app/app.jar`。构建上下文只包含打包好的应用 JAR 和构建定义，不包含源码、临时提交文件及 `.jar.original`。JAR 自身仍包含当前应用配置；这些本地镜像尚未清除开发配置中的凭据，不应推送至公开仓库。

四个后端使用 `eclipse-temurin:8-jre-jammy`，以 UID/GID 10001:10001 运行。沙箱使用 `eclipse-temurin:8-jdk-jammy` 并安装 g++、libc6-dev，保持 Java 8 编译及静态 C++11 编译所需环境。基础镜像支持通过构建参数 JAVA_BASE_IMAGE 替换，包括使用固定 digest；未使用 latest 标签。

从项目根目录执行以下 PowerShell 命令构建：

```powershell
cd C:\projects\yuoj
docker build -t yuoj-gateway:local .\yuoj-backend-microservice\yuoj-backend-gateway
docker build -t yuoj-user-service:local .\yuoj-backend-microservice\yuoj-backend-user-service
docker build -t yuoj-question-service:local .\yuoj-backend-microservice\yuoj-backend-question-service
docker build -t yuoj-judge-service:local .\yuoj-backend-microservice\yuoj-backend-judge-service
docker build -t yuoj-code-sandbox:local .\yuoj-code-sandbox
```

本地构建目标为 Linux/amd64；更换服务器架构时需为目标平台重新构建并测试。修改源码后，先重新执行 Maven 打包，再重新构建对应镜像，否则 COPY 的仍然是上次生成的 JAR。

镜像统一使用 `/app` 工作目录及 `java -jar /app/app.jar` 入口。不写死 prod 环境，可通过运行时 `SPRING_PROFILES_ACTIVE` 或追加 `--spring.profiles.active=...` 选择已准备的配置。Java 参数可通过 JAVA_TOOL_OPTIONS 注入。EXPOSE 仅记录端口，不发布端口、不启动容器。

### 沙箱运行约束

沙箱当前需调用 Docker 引擎，运行用户代码的判题镜像仍为源码指定的 `openjdk:8-alpine` 和 `gcc:latest`；第三步不会自动下载或修改它们。沙箱服务镜像与这两个判题运行镜像用途不同。

沙箱镜像目前保留 root 用户以便后续配置 Docker 引擎访问，仅作为本地演练构建产物；Docker socket 访问意味着可管理宿主引擎。后续不通过公开的无认证 TCP 端口连接 Docker，也不使用 privileged 模式。

容器化沙箱默认把源码写入 `/app/tmpCode`，随后让 Docker 引擎按该绝对路径创建判题容器的 bind mount。只挂载 Docker socket 不足以保证源码可见：必须在 Docker 守护进程所在 Linux 环境与沙箱服务内使用同一路径共享工作目录。Windows 路径不能直接当作 Linux 守护进程路径使用。准确挂载方案在后续 Compose 联调时实现，第三步不宣称完整判题已通过。

本次解析到的基础镜像摘要：

- JRE：`eclipse-temurin:8-jre-jammy@sha256:49b31e882495abeb1118de417ee696bdc929d2de238730920f1468025fc393a4`。
- JDK：`eclipse-temurin:8-jdk-jammy@sha256:185d4ef919b98cd195690c33f3fd93bd49c66c70f0b662e54566c0dbaebea6ce`。

第五步已将五个应用 Dockerfile 的默认 Java 基础镜像固定到上述摘要，并使用内置 Dockerfile 前端。沙箱的 apt 软件包仍从发行版仓库获取，未实现整个工具链的可复现锁定。

### 构建与验证结果

五个应用镜像均已在本机 Linux/amd64 引擎构建成功：

| 镜像标签 | 本次镜像 ID（短） | Docker 显示大小 |
|---|---|---|
| yuoj-gateway:local | e21e9240484d | 506 MB |
| yuoj-user-service:local | 6096022567ca | 515 MB |
| yuoj-question-service:local | fa5a65a1102f | 531 MB |
| yuoj-judge-service:local | 0f50a060ba2b | 530 MB |
| yuoj-code-sandbox:local | 639132a93e6b | 773 MB |

镜像共享基础层，表中大小不能直接相加作为新增磁盘占用。

已完成以下检查：

- 五个镜像的工作目录、启动命令和声明端口正确，镜像内 JAR 的 SHA-256 均与第二步产物一致。
- 四个后端镜像使用 UID/GID 10001:10001，Java 版本为 1.8.0_504。
- 沙箱 javac 版本为 1.8.0_504、g++ 为 11.4.0；在临时容器中成功编译并运行简单 Java 程序，javap 确认为 major version 52。
- 使用项目同样的 `g++ -static -std=c++11 -O2` 参数，成功编译并运行简单 C++ 程序。
- 验证容器均使用 --rm、--network none，不映射端口，不挂载 Docker socket，测试完成后自动删除；本步骤未启动任何常驻业务容器，未推送镜像。

可在 Docker Desktop 的 Images 页面，或用 `docker image ls` 查看这些镜像。第三步到此完成；数据库/Nacos/RabbitMQ 连接、服务注册、Java/C++ 判题运行镜像及端到端判题仍待后续 Compose 配置与联调验证。

## 8. 第四步：独立中间件环境

已新增项目根目录 docker-compose.env.yml 及 deploy/local 下的启动、验收、初始化和说明文件。操作方法见 [本地环境说明](../deploy/local/README.md)。

| 服务 | 实测版本 | Windows 本机入口 | 容器内地址 | 账号 |
|---|---|---|---|---|
| MySQL | 8.4.11 | 127.0.0.1:13306 | mysql:3306 | yuoj，数据库 yuoj |
| Redis | 7.4.11 | 127.0.0.1:16379 | redis:6379 | 密码认证，使用 DB 1 / DB 2 |
| RabbitMQ | 4.2.9 | AMQP 127.0.0.1:15673；管理页面 http://127.0.0.1:15674 | rabbitmq:5672 | yuoj，vhost / |
| Nacos | 2.5.4 | http://127.0.0.1:18848/nacos；gRPC 127.0.0.1:19848 | nacos:8848、9848 | nacos |

四个镜像已按 SHA-256 摘要固定。凭据首次随机生成到 deploy/local/.env，Git 已忽略该文件，不在文档或控制台输出密码。已有开发服务的端口及应用配置没有修改。

使用专用网络 yuoj-local-network 和 yuoj-local 前缀命名卷。MySQL 初始化 SQL 来自原本机数据库的只读表结构导出，包含七张表，不包含原业务记录。RabbitMQ 初始化业务队列和路由，Nacos 完成管理员密码初始化。

验收包括：四项健康检查、MySQL 独立账号及建表、Redis 认证与未认证拒绝、RabbitMQ 队列及路由、Nacos 登录与注册查询及 gRPC 端口、所有端口只绑定回环。保留卷强制重建四个容器后，认证、结构、队列及 Redis 测试数据仍可用，测试键已删除。

后续第五、六步还要准备业务 Compose、连接配置，处理 Redis 密码接入 Redisson、RabbitMQ 初始化地址及 Nacos 认证等。本次未启动四个后端应用、前端或代码沙箱服务，不代表完整登录和判题已经验证。

## 9. 第五步：业务服务与真实判题

已新增 docker-compose.service.yml、docker profile 外部配置、Start-Services.ps1 和 Test-Services.ps1，并对 Redisson、队列初始化、沙箱连接和工作目录映射做了保持原本地默认值的必要适配。操作和权限说明见 [业务服务说明](../deploy/local/SERVICES.md)。第 8 节末尾描述的是完成第四步时的状态，本节为最新进展。

- 五个业务容器均已 healthy；网关在 http://127.0.0.1:18101，沙箱健康接口在 http://127.0.0.1:18090/health，其余后端仅内部访问。
- 通过网关的注册、登录、跨服务会话、题目查询/创建、WebSocket 状态消息和注销已验证。
- 通过真实 RabbitMQ -> 判题 -> 沙箱 -> Docker 执行链路，Java/C++ 均得到 Accepted；临时测试记录已清理。
- 后端 25 项现有针对性测试、沙箱 47 项测试通过；应用镜像已重新构建，原第 7 节的镜像 ID/大小仅是第三步时的历史记录。
- 本机 Docker socket 沙箱权限已由用户明确授权，共享目录使用独立命名卷；尚未完成公开不可信提交所需的安全加固。
- 前端和 Nginx 尚未部署；原前端仍请求 localhost:8101，需要后续单独调整入口。

## 10. 第六步：前后端配置与联调

已增加前端 docker 模式及 Start-Frontend.ps1。浏览器统一访问 http://127.0.0.1:18080 ，HTTP /api 和判题 WebSocket 均由开发服务器转发到第五步的 Docker 网关 18101。原 npm run serve 仍保留默认后端地址。操作见 [前后端联调说明](../deploy/local/FRONTEND.md)。

- 通过前端代理完成页面历史路由、注册登录、共享会话、题目创建/查询、WebSocket 状态消息、Java/C++ 实际判题和注销；两个语言均为 Accepted，脚本临时数据已清理。
- 新账号没有昵称时，页头按用户 ID 识别登录状态，显示“已登录”。
- 浏览器登录、刷新后保持会话、Java/C++ 提交与结果弹窗均验证成功，两次均显示“通过”，随后注销并清理临时记录。
- 固定兼容 TypeScript 4.5 的 @types/node 18.11.18 后，类型检查与前端生产构建均通过；生成 dist 不代表已部署静态网页。
- 本步骤使用前端开发服务器，尚未构建前端容器或部署 Nginx，也未开放公网。

## 11. 第七步：前端容器与 Nginx

完整网站入口为 http://127.0.0.1:18081 。新增生产构建模式、前端 Dockerfile/.dockerignore、Nginx 配置、Compose website profile 及 Start-Website.ps1。网页、HTTP API 与判题 WebSocket 使用同一个访问入口，运行时不依赖 Windows 前端开发服务器。操作见 [本地网站说明](../deploy/local/WEBSITE.md)。

- Nginx 1.30.6，基础镜像锁定 sha256:970364228b1e4f932abc22d321932816782cca8a20b85a77552a7cfe9acfb3b5；网页来自 npm run build:container 的生产产物。
- TypeScript、生产构建、Nginx 配置、网页路由、资源响应、会话、WebSocket 和 Java/C++ 实际判题验收通过，两种语言均 Accepted。
- 原占位注册页及无动作的注册菜单已补齐；浏览器验证注册、登录、刷新会话、题目查询、Java 提交与“通过”弹窗，随后注销，临时记录已清理。
- 共四个中间件、六个应用容器运行。18081 仅绑定本机，HTTPS、域名及公网发布仍未执行。原有第三至第六步中的“尚无 Nginx”等描述属于对应步骤的历史状态。

## 12. 沙箱服务发现与双实例扩展

代码沙箱以 yuoj-code-sandbox 服务名注册到现有 Nacos，判题服务通过 OpenFeign 和 LoadBalancer 调用。当前部署三个实例，分别使用独立工作卷，第二、第三实例只在内部网络监听 8090，不新增宿主机映射端口。当前共四个中间件、八个应用容器（含前端）。启动脚本支持保存和恢复 1～3 个沙箱实例，第三工作卷为 yuoj-local-sandbox-work-3。操作见 [沙箱服务发现说明](../deploy/local/SANDBOX-DISCOVERY.md)。

- 固定 URL 模式保留并完成真实 Java/C++ 判题回归；当前已恢复 discovery 模式。
- 新增认证头传递、实例标识、调用超时、禁止自动执行重试及每实例执行容量配置。当前每实例最多同时执行 2 个任务。
- 双实例四用户并发测试中，Java/C++ 都实际分配到两个实例，全部 Accepted；执行开始/结束日志证明峰值 4 个任务同时执行。
- 第二实例正常退出并从 Nacos 摘除后，新提交由第一实例完成并 Accepted；第二实例已恢复。
- 后端 31 项针对性测试、沙箱 52 项不同测试通过。临时账号、题目和提交已清理，两个工作卷无残留任务目录。
- 容器池代码已撤回，运行仍采用每次创建并销毁执行容器的模型。日常 Start-Website.ps1 会恢复保存的实例数量。

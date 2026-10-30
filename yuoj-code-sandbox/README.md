# 代码沙箱执行结构

沙箱现已接入 Nacos，并支持独立工作卷的双实例部署；原独立运行默认关闭注册。启动、容量、调用模式与并发验收见 [沙箱服务发现说明](../deploy/local/SANDBOX-DISCOVERY.md)。

调用入口为 `CodeSandboxController → CodeSandboxServiceImpl → CodeSandboxExecutor`。
Service 按语言选择 Docker Executor；本地 Executor 保留为独立可用组件。

## Executor 职责

- `AbstractCodeSandboxExecutor`：统一创建工作目录、写源码、宿主编译、运行用例、转换响应与清理。
- `language`：Java/C++ 的文件名及命令参数。宿主路径用 `Path`，容器路径明确使用 Linux 路径；不包含 Docker 镜像。
- `runner`：本地和 Docker 运行器直接实现 `CodeRunner`。Docker 运行器通过构造器固定配置；Java/C++ Executor 分别创建运行器对象，共享 Spring 管理的同一个 DockerClient。容器、输出及内存采样状态只存在于本次运行中。
- `support`：工作目录生命周期、宿主进程操作和结果转换。
- `impl`：四个具体 Executor 只组合语言和运行环境。

## 执行约定

源码保存在服务工作目录下的 `tmpCode/<UUID>`。所有模式仍使用宿主环境的 `javac` 或 `g++` 编译，因此部署环境需要安装对应编译器。
`inputList` 的每个元素是一次执行的完整标准输入，按 UTF-8 原样传入；不拆分、不裁剪、不追加换行。Java 使用 `Scanner(System.in)` / `BufferedReader`，C++ 使用 `cin` 等读取输入；旧的 `args` / `argv` 输入方式不再支持。

本地执行并发读取输出和写入 stdin，写完关闭流以发送 EOF；写入也受运行超时约束。Docker 为每组输入创建临时文件，通过固定命令 `sh -c 'exec "$@" < "$0"' /app/输入文件 程序命令...` 重定向到 stdin，文件读完自然产生 EOF。用户输入不会拼接进 shell 命令；执行使用非 TTY 模式，保持 stdout/stderr 分离。用例结束后清理输入文件，超时先终止容器再清理文件。Java 本地和 Docker 的 JVM 参数分别保留原有配置。

| 环节 | 超时 | 镜像 |
|---|---|---|
| 宿主编译 | 30 秒 | — |
| 本地运行 | 5 秒 | — |
| Java Docker | 5 秒 | `openjdk:8-alpine` |
| C++ Docker | 10 秒 | `gcc:latest` |

Docker 镜像必须预先存在；缺失时明确报错，不自动拉取。客户端由 Spring 创建和关闭，使用现有 Apache HTTP 传输依赖。
容器沿用禁用网络、只读根文件系统、100,000,000 字节内存等原有配置。
Docker 用例超时后强制删除容器，后续用例创建新容器；正常用例在同一请求的容器中顺序运行。

编译超时返回编译错误。运行超时保留内部退出码 `null`，通过返回耗时由判题端按题目时限判断，不新增 HTTP 字段。
时间为毫秒，响应内存为 KB。工作目录和执行资源在异常路径中也会清理，清理异常记录日志。

代码保持 Java 8 源码兼容。在 Java 9+ 运行时通过 `ProcessHandle` 反射终止可识别子进程；Java 8 仅保证终止直接启动的进程。

## 验证

在本模块执行 `mvn clean test`。测试包含接口兼容、Service 路由、真实 Java 编译运行、进程超时与中断、异常清理和模拟 Docker 生命周期。
Docker 模拟测试无需 Docker 引擎；真实容器运行需另行在引擎和镜像可用的部署环境验证。

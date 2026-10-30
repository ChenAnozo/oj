# YUOJ 在线判题项目

基于 Vue 3、Spring Boot 和 Spring Cloud 的在线判题学习项目，包含用户、题目、提交记录、异步判题、代码沙箱及判题状态 WebSocket 通知。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `yuoj-frontend` | Vue 3 + TypeScript 前端 |
| `yuoj-backend-microservice` | 网关、用户、题目、判题及公共模块 |
| `yuoj-code-sandbox` | Java/C++ 代码执行沙箱 |
| `deploy/local` | Docker Compose 本地部署脚本、配置和表结构 |
| `tests/load` | 压测脚本和合成测试用例 |

## 本地启动

需要 PowerShell、Docker Desktop（Linux 容器引擎）、JDK 8 或兼容的 JDK、Maven、Node.js/npm，以及下载 Maven/npm 依赖和容器镜像的网络权限。推荐使用容器部署；Java 服务需要先在本机打包成 JAR，Dockerfile 再复制打包产物。以下命令在项目根目录执行，每一步成功后再执行下一步：

```powershell
Push-Location .\yuoj-backend-microservice
mvn.cmd package -DskipTests
Pop-Location
Push-Location .\yuoj-code-sandbox
mvn.cmd package -DskipTests
Pop-Location
Push-Location .\yuoj-frontend
npm.cmd ci
Pop-Location
& .\deploy\local\Start-Services.ps1 -Build
& .\deploy\local\Start-Website.ps1 -Build
```

访问 <http://127.0.0.1:18081>。首次运行脚本生成 `deploy/local/.env`，使用随机凭据；该文件不会提交到 Git。不要把 `.env.example` 的占位符直接当成密码使用。部署使用固定的 `yuoj-local` Compose 项目名，不要在已有实例运行时启动另一份副本。

在另一台机器、没有复用旧数据卷的情况下，数据库只初始化表结构，不含原开发者的用户、题目或提交记录。需要自行注册账号及准备题目数据。克隆仓库不会获取原开发者电脑上的数据库或 Docker 数据卷。

Codex 可以协助执行上述步骤，但运行环境必须允许安装依赖、启动 Docker 容器，并让沙箱服务访问 Docker 引擎。受限的云执行环境不一定满足完整部署条件；当前启动脚本面向 Windows PowerShell。上传 GitHub 本身不会自动托管这个网站。

详细说明见 [环境依赖](deploy/local/README.md)、[业务服务](deploy/local/SERVICES.md)、[网站部署](deploy/local/WEBSITE.md) 和 [沙箱扩展](deploy/local/SANDBOX-DISCOVERY.md)。这些文档中保留的验收结果属于原开发环境记录，本发布副本的检查见 [发布说明](PUBLICATION.md)。

## 手动运行 Java 服务

IDE 或命令行直接启动时，需要自行设置环境变量。Spring Boot 不会自动加载 `deploy/local/.env`；容器部署已通过 Compose 注入配置。

| 变量 | 用途 |
| --- | --- |
| `MYSQL_PASSWORD` | 数据库密码 |
| `RABBITMQ_PASSWORD` | RabbitMQ 密码 |
| `SANDBOX_AUTH_SECRET` | 判题端和沙箱共享的随机密钥，两端必须一致 |
| `SANDBOX_URL` | 直接调用沙箱的 URL，默认 `http://127.0.0.1:8090/executeCode` |
| `NACOS_ADMIN_PASSWORD` | Nacos 认证密码 |
| `NACOS_SERVER_ADDR` | 示例 Nacos 客户端的注册中心地址 |

主机、端口、Redis 认证等参数也需与实际环境对应。默认配置按本机常用端口编写，Docker 映射端口与其不同。沙箱缺少密钥时不再回退到固定默认密钥。

## 代码来源

项目包含程序员鱼皮 / 编程导航相关示例代码，原有作者与来源注释已保留。发布者需要确认原课程代码、题库和其他第三方材料的公开及再分发授权。此副本没有擅自为第三方代码添加开源许可证。

当前配置面向本地学习和演示。原用户模块仍采用 MD5 加固定盐和管理员创建用户的固定初始密码；本次脱敏没有改写账号系统，不应据此认为已完成生产安全加固。

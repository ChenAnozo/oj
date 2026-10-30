# 第四步：本地环境依赖

本目录用于独立的 Docker Compose 部署演练，不修改原先在 Windows 中运行的 MySQL、Redis、RabbitMQ 或 Nacos，也不启动业务服务。

第五步的业务容器已另行完成部署；入口、启动与判题验收见 [业务服务说明](SERVICES.md)。本页保留第四步环境的独立操作方法。

第六步前端联调见 [前后端联调说明](FRONTEND.md)。

第七步完整网站入口、启动和验收见 [本地网站说明](WEBSITE.md)。

代码沙箱现已接入 Nacos，部署脚本支持 1～3 个实例，调用方式、数量切换和并发验收见 [沙箱服务发现说明](SANDBOX-DISCOVERY.md)。

## 文件用途

| 文件 | 用途 |
|---|---|
| 项目根目录 docker-compose.env.yml | 四个中间件、健康检查、本机端口、专用网络和命名卷 |
| Start-Environment.ps1 | 首次生成随机凭据、校验配置、启动并等待健康、初始化 RabbitMQ 拓扑和 Nacos 管理员、执行检查 |
| Test-Environment.ps1 | 单独复查四项健康状态、MySQL 表结构、Redis 认证、RabbitMQ 队列、Nacos 登录及 gRPC 端口 |
| Environment.Common.ps1 | 脚本公用的路径、Compose 调用和认证辅助函数 |
| .env.example | 配置项示例；占位符不能作为实际密码 |
| .env | 启动脚本生成的本地凭据和端口，已在根 .gitignore 中忽略 |
| mysql-init/010-schema.sql | 从原本机 yuoj 库只读导出的七张表结构，无任何业务记录 |
| rabbitmq-definitions.json | 业务队列、交换机、绑定及 5 分钟延迟队列配置，不含账号密码 |

## 启动和检查

先启动 Docker Desktop，确认正在使用 Linux 容器引擎。在 PowerShell 执行：

```powershell
cd C:\projects\yuoj
& .\deploy\local\Start-Environment.ps1
```

脚本生成 .env 时不会打印密码；已有 .env 会保留，不会每次轮换。重复运行会保留数据卷，并重新检查队列定义和 Nacos 登录。

单独检查已启动环境：

```powershell
& .\deploy\local\Test-Environment.ps1
```

查看状态和日志：

```powershell
docker compose --env-file .\deploy\local\.env -f .\docker-compose.env.yml ps
docker compose --env-file .\deploy\local\.env -f .\docker-compose.env.yml logs --tail 100
```

不要直接粘贴未过滤的 docker compose config 或 docker inspect 输出，它们可能包含运行凭据；仅校验配置时使用 config --quiet。

## 连接信息

所有映射仅监听 127.0.0.1，不开放到局域网或公网。原来的开发服务端口保持不变。

| 服务 | Windows 本机连接 | 后续容器内连接 | 凭据位置 |
|---|---|---|---|
| MySQL | 127.0.0.1:13306，数据库 yuoj，用户 yuoj | mysql:3306 | .env 的 MYSQL_PASSWORD；root 密码另存 MYSQL_ROOT_PASSWORD |
| Redis | 127.0.0.1:16379，使用 DB 1、DB 2 | redis:6379 | .env 的 REDIS_PASSWORD |
| RabbitMQ AMQP | 127.0.0.1:15673，用户 yuoj，vhost / | rabbitmq:5672 | .env 的 RABBITMQ_PASSWORD |
| RabbitMQ 管理界面 | http://127.0.0.1:15674 | rabbitmq:15672 | 同上 |
| Nacos 控制台 / HTTP | http://127.0.0.1:18848/nacos，用户 nacos | nacos:8848 | .env 的 NACOS_ADMIN_PASSWORD |
| Nacos 客户端 gRPC | 127.0.0.1:19848 | nacos:9848 | 与 Nacos 登录配置配合 |

Nacos 本机 HTTP 和 gRPC 映射保持 +1000 关系。此处 19848 只是客户端通信入口，不是另一个网页。

实际验收版本为 MySQL 8.4.11、Redis 7.4.11、RabbitMQ 4.2.9、Nacos 2.5.4；与教程和原开发环境不同。Compose 已将四个镜像以标签加 sha256 摘要固定，摘要见项目根目录 docker-compose.env.yml。Nacos 保留 2.x 系列，后续业务服务连接和客户端兼容仍需专门验证。

## 数据与初始化

Compose 项目名为 yuoj-local，网络为 yuoj-local-network。数据保存在独立命名卷：

- yuoj-local_mysql-data
- yuoj-local_redis-data
- yuoj-local_rabbitmq-data
- yuoj-local_nacos-data
- yuoj-local_nacos-logs

MySQL 镜像只在空数据目录第一次初始化时执行 mysql-init，不会每次启动自动应用表结构变更。导入保留原表结构与索引，移除原库 AUTO_INCREMENT 当前值，不复制现有账号、密码哈希、题目或提交数据。新库中的业务表最初为空。

Redis 开启 AOF，RabbitMQ 使用持久化队列，Nacos standalone 使用自己的嵌入式存储。持久化卷解决容器重建后的保留问题，不等同于备份；.env 也需要随演练数据妥善保留。丢失或手动修改密码文件不会自动更改已有卷中的账号。

RabbitMQ 在默认用户创建之后通过管理 API 导入定义，避免启动时导入定义抑制默认用户创建；队列不删除、不清空。延迟队列 TTL=300000，与当前 oj.judge.timeout-minutes=5 一致，未来修改超时需要协调队列配置，不能直接对已有队列声明不同参数。

Nacos 首次启动通过管理员初始化 API 设置独立随机密码，后续启动先验证登录，不重置已有密码。

## 停止演练环境

```powershell
docker compose --env-file .\deploy\local\.env -f .\docker-compose.env.yml stop
```

如需移除本次演练的容器和网络但保留数据：

```powershell
docker compose --env-file .\deploy\local\.env -f .\docker-compose.env.yml down
```

上述命令不带 -v；加入 -v 会删除本项目命名卷中的演练数据，本步骤不执行此操作。

## 后续业务接入

本步骤没有修改 application.yml 或业务代码，后端仍保持原来的本地运行方式。后续接入本环境需要：

- 使用容器内服务名及内部端口，加入 yuoj-local-network。
- 配置独立 MySQL 用户、Redis 密码、RabbitMQ 用户、Nacos 账号密码。
- 修复 RedissonConfig 当前没有读取 Redis 密码的问题。
- 统一 InitRabbitMq 当前写死 localhost 的初始化连接。
- 验证登录会话、消息消费、服务发现和完整判题；第四步的环境健康不代表业务联调通过。

参考：[MySQL 官方镜像](https://hub.docker.com/_/mysql)、[Redis 官方镜像](https://hub.docker.com/_/redis)、[RabbitMQ 官方镜像](https://hub.docker.com/_/rabbitmq)、[Nacos 2.5 鉴权说明](https://nacos.io/docs/v2.5/manual/admin/auth/)。

## 本次验收结果

- 四个容器均为 running / healthy，所有发布端口实际检查为 127.0.0.1。
- MySQL 使用独立业务账号验证查询及七张表存在；原本机服务版本只读查询为 9.1.0，未迁移其业务记录。
- Redis DB 1、DB 2 认证 PING 通过，移除认证环境变量后请求被 NOAUTH 拒绝。
- RabbitMQ 认证、三个持久化队列、两个关键路由绑定、延迟队列参数与 AMQP 端口检查通过。
- Nacos 健康接口、管理员认证、注册中心查询和 gRPC TCP 端口检查通过。
- 未重新导入定义或重置管理员，保留卷强制重建四个容器后，上述检查再次通过；Redis 临时测试键跨重建保留并已清理，凭据文件校验值不变。
- 第四步只验证独立环境，不表示业务应用已经接入或端到端判题已经通过。

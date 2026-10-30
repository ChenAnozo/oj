# 第六步：前后端配置与本地联调

本步骤使用 Windows 上的 Vue 开发服务器连接第五步的 Docker 后端。前端入口为 http://127.0.0.1:18080 ，仅监听本机。前端镜像、Nginx、HTTPS 和公网发布尚未执行。

第七步现已另行完成前端容器与 Nginx 部署，入口 http://127.0.0.1:18081 ，详见 [本地网站说明](WEBSITE.md)。本页保留第六步开发服务器的联调方式。

## 启动与停止

先启动 Docker Desktop，在项目根目录执行：

```powershell
.\deploy\local\Start-Services.ps1
```

在另一个 PowerShell 窗口中执行，并保持该窗口运行：

```powershell
.\deploy\local\Start-Frontend.ps1
```

浏览器打开 http://127.0.0.1:18080 。首次编译需要等待，看到 App running at 后再访问。按 Ctrl+C 停止前端，不会停止后端或删除数据。

也可以在 yuoj-frontend 目录执行 npm run serve:docker。缺少依赖时，先执行 npm ci。不要将开发服务器用于公网正式部署。

## 原开发方式与联调方式

| 项目 | 原开发方式 | Docker 联调方式 |
|---|---|---|
| 前端启动 | npm run serve | npm run serve:docker |
| HTTP 接口 | 保留默认 http://localhost:8101 | 浏览器请求同源 /api，开发服务器代理到 http://127.0.0.1:18101 |
| 判题 WebSocket | 从默认 API 地址推导 ws://localhost:8101 | ws://127.0.0.1:18080/api/question/ws/judge，经代理到 Docker 网关 |
| 后端数据 | 原开发环境 | 第四步独立数据库和 Redis 会话 |

两个环境的数据与账号独立。原数据库中的账号不会自动出现在 Docker 数据库，首次使用请在联调页面注册。测试脚本创建的账号会在成功后清理，不作为日常登录账号。

请固定使用 127.0.0.1:18080，避免在 localhost 与 127.0.0.1 之间切换造成浏览器会话不一致。修改 Docker 网关端口后，也要调整 .env.docker 中的代理目标并重启前端。

## 文件与配置模型

- yuoj-frontend/.env.docker：独立联调模式，VUE_APP_API_BASE 显式设为空，使用同源请求；VUE_APP_DEV_PROXY_TARGET 指向本机 Docker 网关。这些前端变量是公开配置，不应存放密码或密钥。
- yuoj-frontend/vue.config.js：设置代理目标时启用 /api 的 HTTP 与 WebSocket 代理；过滤两种 ResizeObserver 布局通知的开发遮罩，其他错误仍显示。
- yuoj-frontend/src/config/api.ts：初始化时覆盖 OpenAPI.BASE，并从同一个 API 地址生成 ws/wss 地址；未设置变量时保留原默认地址。
- yuoj-frontend/src/main.ts：先初始化接口配置，再初始化路由、会话与页面。
- yuoj-frontend/src/composables/useJudgeWebSocket.ts：使用统一生成的 WebSocket 地址。
- yuoj-frontend/src/components/GobalHeader.vue：按用户 ID 判断登录状态，无昵称账号显示“已登录”。
- yuoj-frontend/package.json 与 package-lock.json：新增联调启动命令，并固定与 TypeScript 4.5 兼容的 Node 开发类型声明。
- deploy/local/Start-Frontend.ps1：启动入口。
- deploy/local/Test-Services.ps1：增加 -Frontend，HTTP 和 WebSocket 均经前端代理验收。

没有改写生成的 OpenAPI 客户端，也没有复制整套项目。后端 docker profile、内部地址、凭据和沙箱映射沿用第五步配置。

## 验收

只读检查前端页面、历史路由、题目接口、容器与 Nacos 注册：

```powershell
.\deploy\local\Test-Services.ps1 -Frontend
```

完整检查注册、登录、会话、题目创建、WebSocket 和 Java/C++ 实际判题：

```powershell
.\deploy\local\Test-Services.ps1 -Frontend -Exercise
```

完整检查使用独立临时账号及题目，成功后仅清理其记录；失败时保留测试数据供诊断。此命令会使用已授权的本机 Docker 沙箱。

前端编译仍会报告项目原有的 lint 警告，联调模式只在终端输出这些警告，不遮住页面；编译错误仍显示。实际检查发现 TypeScript 4.5 与原安装的 @types/node 25 不兼容，本步骤在 package.json 与 package-lock.json 中将开发类型依赖固定为 18.11.18；修复后 tsc --noEmit 已通过。这不改变实际 Node.js 运行时版本。

本次验收：前端开发启动成功、tsc --noEmit 和 npm run build 通过；经前端代理的完整脚本验收通过。真实浏览器中完成登录、刷新后保持会话、题目查询、Java 与 C++ 提交和结果弹窗，两次均显示“通过”，随后注销。临时浏览器账号、题目和两条提交记录已清理。

本地验收截图未包含在发布副本中。

后续生产部署需要单独设置构建时的 API 地址，并在 Nginx 配置 /api 转发、WebSocket 升级和页面历史路由回退。本步骤的开发代理不会被打包进生产网页。

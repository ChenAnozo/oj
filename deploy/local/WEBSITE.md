# 第七步：本地前端容器与 Nginx 网站

沙箱已进一步接入 Nacos，并支持保存和恢复 1～3 个实例的部署。日常网站启动命令不变，详见 [沙箱服务发现说明](SANDBOX-DISCOVERY.md)。

网站入口为 http://127.0.0.1:18081 。网页由 Docker 中的 Nginx 提供，接口和 WebSocket 通过同一个地址转发至网关。运行网站不再需要 Windows 上的前端开发服务器。端口只绑定本机，未配置公网入口或 HTTPS。

## 启动、更新与停止

先启动 Docker Desktop。在项目根目录执行：

```powershell
# 首次部署或修改前端、Nginx 配置后重新构建
.\deploy\local\Start-Website.ps1 -Build

# 已有镜像时日常启动
.\deploy\local\Start-Website.ps1
```

脚本会先启动并验证第四、五步的服务，再启动 frontend 容器。首次构建需要本机 Node.js 和前端依赖；若缺少依赖，在 yuoj-frontend 目录执行 npm ci。网页先在本机构建为 dist，然后由 Dockerfile 复制到固定摘要的 Nginx 镜像中，模型与之前“先构建 JAR，再制作运行镜像”一致。镜像不包含 node_modules、源码、部署 .env 或源码映射文件。

frontend 使用 Compose 的 website profile，保留原 Start-Services.ps1 的后端启动方式。若直接使用 Compose，网站需要 --profile website。单独停止网站，保留后端：

```powershell
docker compose --env-file .\deploy\local\.env -f .\docker-compose.service.yml --profile website stop frontend
```

不会删除容器、镜像或数据库卷。不要在日常停止命令中加入 -v。

## 三种前端运行方式

| 启动方式 | 入口 | 后端 | 用途 |
|---|---|---|---|
| npm run serve | 原开发端口 | localhost:8101 | 原开发流程 |
| npm run serve:docker | 127.0.0.1:18080 | 开发代理到 18101 | 第六步热更新联调 |
| Start-Website.ps1 | 127.0.0.1:18081 | Nginx 到 Docker 内部网关 8101 | 第七步生产网页部署演练 |

第六步与第七步使用同一套 Docker 后端数据，但浏览器访问入口不同。请固定使用 127.0.0.1，避免与 localhost 混用。现有 Docker 数据库未复制原 Windows 开发数据库的账号；新用户可通过网站“未登录 → 注册”创建账号。

修改端口时在 deploy/local/.env 中设置 WEBSITE_PORT，重新执行 Start-Website.ps1。生产网页使用同源 /api，不需要将具体端口写入前端代码。

## 主要文件与请求链路

- yuoj-frontend/.env.container：NODE_ENV=production，VUE_APP_API_BASE 为空，使用网站同源接口。
- yuoj-frontend/package.json：build:container 独立构建命令；原 build、serve、serve:docker 保留。
- yuoj-frontend/Dockerfile：复制生产网页与 Nginx 配置，基于 nginx:stable-alpine 的固定摘要。
- yuoj-frontend/.dockerignore：仅打包 dist、Dockerfile 与 Nginx 配置，排除 .map。
- yuoj-frontend/nginx/default.conf：静态文件、历史路由回退、/api HTTP 转发、WebSocket Upgrade/Connection 请求头、Docker DNS 解析及 /healthz。
- docker-compose.service.yml：frontend 服务、仅本机的 18081:80 映射、健康检查及 gateway 健康依赖。
- deploy/local/Start-Website.ps1：完整网站启动入口。
- deploy/local/Test-Services.ps1：-Website 验证 Nginx 网站入口，原 -Frontend 仍检查第六步开发服务器。

普通接口：浏览器 → Nginx:80 → gateway:8101 → 业务服务。

判题结果推送：浏览器 ws://127.0.0.1:18081/api/question/ws/judge → Nginx WebSocket 转发 → gateway → 题目服务。

Nginx 动态解析 Docker 内部网关名称，避免后端重建并更换 IP 后仍转发到旧地址。/user/login 等网页路径刷新时回退到 index.html，丢失的 .js/.css 等静态资源返回 404，/api 优先转发并保留后端响应。

Nginx 本次运行版本为 1.30.6，基础镜像摘要为 sha256:970364228b1e4f932abc22d321932816782cca8a20b85a77552a7cfe9acfb3b5。配置依据：[Nginx WebSocket 官方文档](https://nginx.org/en/docs/http/websocket.html)、[proxy_pass 官方文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)。

## 注册入口补齐

原 UserRegisterView.vue 只有占位文本，页头的“注册”菜单也没有路由处理。本次补齐了注册表单、密码确认、重复提交保护、请求失败提示、成功后进入登录页，以及页头跳转。退出登录现在等待注销完成后再切换页面，避免页面切换时重读到旧会话。

## 验收命令

```powershell
# 页面、资源响应、容器与注册中心的只读检查
.\deploy\local\Test-Services.ps1 -Website

# 临时注册、登录、会话、WebSocket 及 Java/C++ 实际判题
.\deploy\local\Test-Services.ps1 -Website -Exercise
```

完整验收沿用已授权的本机 Docker 沙箱。成功后按唯一 ID 清理脚本创建的测试账号、题目及提交；失败时保留测试记录供诊断。

本次结果：TypeScript 类型检查与 build:container 均通过；镜像构建、Nginx 配置检查、网页历史路由、静态资源 404、注册登录、跨服务会话、WebSocket 和 Java/C++ 真实判题通过，两个语言均 Accepted。真实浏览器完成密码不一致校验、注册、登录、刷新后保持会话、题目查询、Java 提交、判题“通过”弹窗和注销。脚本及浏览器使用的临时记录已清理，未预置日常账号。

本地验收截图未包含在发布副本中。

# 码序 CodeOrder 前端

## Project setup

```
npm install
```

### Compiles and hot-reloads for development

```
npm run serve
```

### Connect to the local Docker backend

```powershell
npm run serve:docker
```

Open http://127.0.0.1:18080 after compilation. The original `serve` command keeps using the original backend. See [本地前后端联调说明](../deploy/local/FRONTEND.md) for prerequisites, proxy configuration and verification.

### Serve the production website in Docker

From the repository root, run `./deploy/local/Start-Website.ps1 -Build`, then open http://127.0.0.1:18081. Later starts can omit `-Build`. See [本地 Nginx 网站说明](../deploy/local/WEBSITE.md).

### Compiles and minifies for production

```
npm run build
```

### Lints and fixes files

```
npm run lint
```

### Customize configuration

See [Configuration Reference](https://cli.vuejs.org/config/).

### 品牌与体验配置

- 页面品牌配置：`src/config/brand.ts`；静态页面标题与描述：`public/index.html`。
- Logo：`src/assets/oj-logo.svg`；浏览器图标：`public/favicon.svg`。
- 题库与提交记录的已应用筛选、页码保存在 URL，输入筛选条件后点击查询才发送请求。
- 代码草稿按用户、题号、语言保存到当前浏览器；清除浏览器数据会移除草稿。
- 判题使用 WebSocket 加接口补查；等待超过五分钟时可手动重新获取结果。

### 前端流程检查

```powershell
npm run build
npm run test:ux
```

需要 Node.js 22 及本机 Chrome / Edge（其他安装位置通过 `CHROME_PATH` 指定）。检查会启动独立的无界面浏览器，拦截 API 并使用模拟数据，不访问真实后端、不创建真实账号。覆盖搜索、登录回跳、长题号、草稿恢复与语言切换、断线后判题结果补查、重复提交保护、失败重试和移动端布局。

截图可运行 `node tests/ux-smoke.cjs <截图目录>`。真实后端与 WebSocket 服务仍需按本地联调文档验证。

# 发布副本说明

此目录从当前工作区复制，包含尚未提交的源码修改，使用独立 Git 仓库和新的首次提交。原仓库、历史提交及运行配置均保留在原目录。

## 已处理内容

- 未复制原 `.git`、真实 `.env`、源码 ZIP、本地验收截图、日志、构建产物、数据库备份和沙箱 `tmpCode`。
- 数据库、RabbitMQ 和示例 Nacos 密码改为环境变量引用。
- 删除默认沙箱密钥，判题服务和沙箱通过配置获取；单元测试使用显式测试值。
- 本机用户名路径改为通用路径，内网沙箱地址改为本机示例地址，个人云实例地址改为配置项。
- 移除文档中的个人 Docker 数据迁移记录，保留第三方署名和来源。
- 新增根目录忽略规则，保留前端公开运行参数和 `.env.example`。
- 首次提交采用占位身份 `Project Maintainer <maintainer@example.invalid>`，没有继承原提交者邮箱或旧提交。

## 本次验证

- 对发布文本逐一检查原本地凭据、原个人路径、原内网/云实例地址、常见密钥标记，未发现匹配残留。
- PowerShell、YAML、JSON、XML 和 JMeter 文件语法检查通过。
- `CodeSandboxControllerTest`：8 项通过，包含空密钥拒绝访问。
- `DiscoveryCodeSandboxClientTest`：7 项通过，包含 HTTP 客户端拒绝空密钥。
- `.env`、沙箱临时文件、依赖和构建产物的 Git 忽略检查通过。
- 本次未重新部署容器或运行完整网站端到端验收，也未完成生产安全审计。

## 上传 GitHub

先确认源代码和题库的再分发授权。请在本目录中配置新仓库远程地址，避免在上一级原项目目录中操作。

在 GitHub 创建空仓库后，在此目录执行，将占位地址替换为自己的仓库地址：

```powershell
git remote add origin https://github.com/YOUR_ACCOUNT/YOUR_REPOSITORY.git
git push -u origin main
```

本次仅创建本地首次提交，没有创建 GitHub 仓库或上传文件。后续提交可在本仓库配置 GitHub 提供的 noreply 邮箱。

不要从原目录复制 `.git`、真实配置或整目录备份到此仓库。如果原凭据曾在其他地方公开，需要在对应服务中更换；本次仅处理发布副本。

# 截图使用手册

本目录存放 Web 界面截图，供 [`README.md`](../../README.md) 引用。

## 截图列表

| 页面 | 路径 |
|------|------|
| 登录 | [`docs/manual/images/01-login.png`](images/01-login.png) |
| 首页 | [`docs/manual/images/02-home.png`](images/02-home.png) |
| 数据源列表 | [`docs/manual/images/03-datasource-list.png`](images/03-datasource-list.png) |
| 添加/编辑数据源 | [`docs/manual/images/04-datasource-form.png`](images/04-datasource-form.png) |
| 比对任务配置 | [`docs/manual/images/05-compare-config.png`](images/05-compare-config.png) |
| 同步任务列表 | [`docs/manual/images/06-sync-list.png`](images/06-sync-list.png) |
| 同步任务配置 | [`docs/manual/images/07-sync-config.png`](images/07-sync-config.png) |
| 报告列表 | [`docs/manual/images/08-report-list.png`](images/08-report-list.png) |
| 数据浏览 | [`docs/manual/images/09-viewer.png`](images/09-viewer.png) |
| 参数设置 | [`docs/manual/images/10-settings.png`](images/10-settings.png) |
| 比对报告 | [`docs/manual/images/11-compare-report.png`](images/11-compare-report.png) |
| 同步报告 | [`docs/manual/images/12-sync-report.png`](images/12-sync-report.png) |
| 同步确认页 | [`docs/manual/images/13-sync-confirm.png`](images/13-sync-confirm.png) |
| C盘清理 | [`docs/manual/images/14-cleanup.png`](images/14-cleanup.png) |

## 重新生成

1. 启动应用（默认 `8080`，或任意端口）  
2. 在项目根目录执行：

```bash
node scripts/capture-screenshots.mjs
```

可选环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `BASE_URL` | `http://localhost:8080` | 应用地址 |
| `DBCOMPARE_USER` | `admin` | 登录用户名 |
| `DBCOMPARE_PASS` | 见 `application.yml` | 登录密码 |
| `CHROME_PATH` | Chrome 默认安装路径 | 浏览器可执行文件 |

> 需已安装 Node.js 与 `puppeteer-core`（`node_modules` 已存在即可）。  
> 报告类截图（11/12/13）依赖本地 `reports/` 或 PENDING 草稿，不存在时会跳过。

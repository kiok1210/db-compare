# 数据库比对与同步工具（db-compare）

基于 Spring Boot + Thymeleaf 的 Web 工具：管理数据源、全库比对、数据同步（MySQL / GoldenDB / PostgreSQL / Oracle / SQL Server / H2 / SQLite）。  
当前版本：**v1.2.39**（详见 `src/main/resources/version-changelog.json`）。

---

## 当前改动摘要（截至 v1.2.33）

相对早期「仅比对」能力，近期重点补齐了**数据同步**与运维体验：

| 版本 | 要点 |
|------|------|
| **1.2.0** | 同步全量/增量；失败表报告与重同步；JumpServer 连接；库名映射对齐；应用版本与更新日志 |
| **1.2.1～1.2.3** | 探索 DDL 临时表切换后又拆分为可选 **DML 全量（默认）** / **DDL 全量** / **增量** |
| **1.2.4** | 大表清表后 `wait_timeout`：清表不用源连接、结束后重建连接再写入 |
| **1.2.5** | 表级并行可配（默认 8）；进度展示并行中多表 |
| **1.2.6** | 表状态：未同步（未清理）/ 清理失败 / 同步失败；一键重新提取并同步 |
| **1.2.7** | 前后台日志统一带 **库名 + 表序号**（`#12/1045 src.table → tgt.table`）；H2 读列按 schema 兼容 |
| **1.2.8** | **任务级共享 Hikari 连接池**：池上限≈并行度；连接按线程借还（不跨线程共享）；清表后归还并重新借出（池校验） |
| **1.2.9** | 超长清表每约 10 分钟重借目标连接 + 断链重试一批；校验 `FOREIGN_KEY_CHECKS` |
| **1.2.10** | **同步报告**：结束后生成 HTML/MD；「任务报告」分栏（比对/同步）；列表与进度页可查看 |
| **1.2.11** | 同步报告内可勾选失败表，**一键提取并新建同步任务** |
| **1.2.12** | 同步报告/重同步 API 单测；强制 `mvn test` 回归约定 |
| **1.2.13** | 数据源全量/增量 SQL 导出（`scripts/datasource/` + `/datasource/export.sql`） |
| **1.2.14** | 修复比对报告列表：恢复完整比对任务操作；同步报告仅作分栏 |
| **1.2.15** | 比对「条数差异」可提取为同步草稿 → 确认页手动「开始同步」 |
| **1.2.16** | 比对报告恢复顶部菜单；提取同步后可靠跳转确认页 |
| **1.2.17** | 报告直出 HTML 注入导航/提取（弃用 iframe）；旧报告亦可提取并跳转 |
| **1.2.18** | 修复提取后不跳转/列表看不到草稿：强制提取脚本 + PENDING 识别 |
| **1.2.19** | 条数差异「提取为同步任务」移到弹窗顶部工具条 |
| **1.2.20** | 同步草稿可编辑：勾选表、库名分类/映射前缀；列表增加「编辑」 |
| **1.2.21** | 同步确认弹窗改用应用内样式（取消/开始同步文案更清晰） |
| **1.2.22** | 修复比对报告「提取为同步任务」点击无后台请求（事件委托 + 强制替换脚本） |
| **1.2.23** | 多表演示库（12表/多条数差）；修复 H2 全库只扫 PUBLIC |
| **1.2.24** | 修复比对报告中文乱码（UTF-8 写入 + GBK 兼容读取）；加固提取跳转 |
| **1.2.25** | 修复提取按钮只关弹窗不发请求（按钮重建/遮罩误点/z-index） |
| **1.2.26** | 根因：Bootstrap `.modal-dialog{pointer-events:none}` 点击穿透，提取按钮无效 |
| **1.2.27** | 修复一键重同步 `Dangling meta character '?'`（失败表名误写为 `?`） |
| **1.2.28** | 原生 alert/confirm 改为 AppToast / AppConfirm 友好提示 |
| **1.2.29** | 修复并行同步 `CURRENT_TABLE` 超长导致 DataException 全表误失败 |
| **1.2.30** | 进度文案摘要+硬截断，不再靠加宽字段处理超长 |
| **1.2.31** | 应用库增量 DDL（`db/schema/Vn__*.sql`），内网升级不拷贝 data/ |
| **1.2.32** | 同步前探测连接；池初始化失败改为友好提示 |
| **1.2.33** | 新增参数设置模块（并行/批次等全局默认） |

同步权限场景：

- **DML 全量（默认）**：分批 `DELETE` + 批量 `INSERT`，无需 CREATE/TRUNCATE/DROP  
- **DDL 全量**：优先 `CREATE LIKE` → INSERT → `RENAME`，失败则 `TRUNCATE` → INSERT，再失败回退 DML  
- **增量**：不清空，MySQL/GoldenDB 用 `INSERT IGNORE`

---

## 架构概览

```
浏览器 (Thymeleaf + 静态 JS)
        │  HTTP
        ▼
Controller ── Sync / Compare / DataSource / Version / Viewer
        │
        ▼
Service ── SyncService / SyncTaskRunner（@Async + 表级线程池）
        │   CompareTaskRunner / DataSourceService / AppVersionService
        ▼
JDBC ── 源库 / 目标库（业务数据，按任务配置连接）
H2   ── 应用元数据：数据源、比对任务、同步任务、版本 schema（./data/dbcompare）
```

### 核心模块

| 路径 | 职责 |
|------|------|
| `controller/*` | Web 页面与 JSON API |
| `service/SyncTaskRunner` | 同步执行：模式分支、并行、清表/写入、取消与状态归档 |
| `service/CompareTaskRunner` | 比对任务异步执行 |
| `util/SchemaMappingHelper` | 库对库、表同名映射（与比对共用） |
| `util/JdbcConnectionHelper` | JDBC URL 规范化、JumpServer/GoldenDB、批量写入参数 |
| `compare/*` | 聚合 / 抽样 / 增量比对引擎 |
| `entity/SyncTask` | 同步任务与模式常量（`FULL_DML` / `FULL_DDL` / `INCREMENTAL`） |
| `version-changelog.json` | **唯一版本真相源**（前后端版本、schemaVersion、变更说明） |

### 同步执行要点

1. **任务级** `@Async("syncTaskExecutor")`，**表级**固定线程池并行（`parallelThreads`）。  
2. **单表内部**必须「先清后插」（同表不可边删边插）。  
3. **连接池（v1.2.8+）**：源/目标各一 Hikari 池，`maximumPoolSize ≈ parallelThreads`；worker **borrow → 独占使用 → close 归还**，禁止多线程共用一个 `Connection`（避免 catalog/会话变量串线）。清表只占用目标池连接；**v1.2.9** 清表中期段每约 10 分钟重借目标连接，阶段结束后再借源+目标（`ConnectionTestQuery`），避免空闲数小时被 `wait_timeout`/JumpServer 踢死。  
4. 非成功表写入 `failedTables` JSON，带 `status` / `statusLabel` / 库名与序号，供一键重同步。  
5. **同步报告（v1.2.10+）**：任务结束写入 `reports/sync/{id}/report.html`；v1.2.11 起报告内可勾选待重同步表并一键新建任务。

---

## 修改准则（必须遵守）

### 1. 版本与 Git：一次版本改动 → 一次 commit

- **每次变更应用版本号（或 schemaVersion）时，单独提交一个 git commit。**  
- Commit message 建议：`release: vX.Y.Z <一句话 why>`。  
- 禁止把多个版本号变更揉进同一个 commit。  
- 细节发版步骤见 [`docs/VERSIONING.md`](docs/VERSIONING.md)。

发版同步清单（同一 commit 内改齐）：

1. `src/main/resources/version-changelog.json`（版本 + releases 顶插 + 必要时 schemaVersion）  
2. `pom.xml` 的 `<version>`  
3. `src/main/resources/static/js/app-version.js` 的 `FRONTEND_VERSION`  
4. （建议）本 README 中「当前版本」一行

### 2. 代码与功能

- **最小改动**：只改任务需要的代码与文档；不做无关重构。  
- **同步策略按账号权限区分**：无 DDL 默认走 `FULL_DML`；有 DDL 再选用 `FULL_DDL`。  
- **映射规则**：库对库、表同名；系统库（如 `information_schema`、`_gdb_*`）不默认参与。  
- **日志**：同步相关日志/进度须带 **表序号** 与 **库名.表名**。  
- **连接与池**：同步走任务级共享池；禁止跨线程共享 `Connection`；长清表勿长时间占住源连接，阶段结束用「归还+再借」做存活校验。  
- **安全**：不提交密钥、Token、生产密码；`.env` / `data/` / `logs/` 已在 `.gitignore`。

### 3. 数据与演示

- 应用库：`jdbc:h2:file:./data/dbcompare`（勿手改跨版本破坏性 DDL，靠 `ddl-auto: update` + schemaVersion 提示）。  
- 演示库由 `DemoDataInitializer` 首次创建；本地 `data/`、`logs/`、`reports/` 不进仓库。

---

## 项目结构

```
db-compare/
├── src/main/java/com/gcoll/dbcompare/
│   ├── DbCompareApplication.java
│   ├── config/                 # 异步线程池、演示数据初始化
│   ├── controller/             # Web / API
│   ├── service/                # 同步、比对、数据源、版本
│   ├── compare/                # 比对引擎
│   ├── dictionary/             # 元数据
│   ├── util/                   # JDBC、映射、过滤
│   └── entity/                 # JPA 实体
├── src/main/resources/
│   ├── application.yml
│   ├── version-changelog.json  # 版本真相源
│   ├── static/js/app-version.js
│   └── templates/              # Thymeleaf（含 sync/）
├── docs/VERSIONING.md
├── data/                       # 本地运行生成（忽略提交）
├── logs/
├── reports/
└── pom.xml
```

---

## 快速开始

### 环境

- JDK 8+
- Maven 3.6+（本机示例：`D:\apache-maven-3.9.0`）

### 编译与启动

```bash
cd db-compare
mvn "-Dmaven.test.skip=true" package
java -jar target/db-compare-1.2.39.jar
```

访问：**http://localhost:8080/**

### 操作流程

1. **数据源管理** — 添加源库/目标库（支持 JumpServer Token 用户）  
2. **比对任务** — 配置映射、模式、表范围 → 查看进度与报告  
3. **同步任务** — 选择 DML/DDL/增量、并行数、表范围 → 进度页看并行状态与待重同步表  
4. 点击导航栏版本号查看更新日志；schema 升级时确认「数据库结构已更新」提示  

### 演示数据

首次启动（或演示库版本升级）会初始化 H2/SQLite **多表演示库**（约 12 张表，`PUBLIC` + `SHOP`），目标库含多处**条数差异**，用于比对报告里手动「提取为同步任务」。

推荐测试路径：比对任务 → 执行「【演示】多表条数差异比对（提取同步任务）」→ 报告 → 条数差异 → 提取。

强制重灌演示库：删除 `data/demo/.demo_version` 后重启应用。

---

## 比对模式

| 模式 | 说明 |
|------|------|
| **FULL** | 全表聚合（条数 + 金额 SUM + 行 Hash） |
| **SAMPLE** | 按 `sampleRate` 抽样逐行比对 |
| **INCREMENTAL** | 基于时间字段（优先 `update_time`）近 24 小时 |

## 同步模式

| 模式 | 说明 | 默认 |
|------|------|------|
| **FULL_DML** | 分批 DELETE + 批量 INSERT | 是 |
| **FULL_DDL** | CREATE LIKE / TRUNCATE 路径（需 DDL） | |
| **INCREMENTAL** | INSERT IGNORE，不清空 | |

---

## 配置要点（application.yml）

```yaml
spring.datasource.url: jdbc:h2:file:./data/dbcompare;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE
server.port: 8080
db-compare.encryption.key: change-me-in-production   # 生产务必修改
```

## 注意事项

1. 同步账号权限需与所选模式匹配（DML 至少 SELECT/DELETE/INSERT；DDL 模式另需 CREATE/RENAME/TRUNCATE 等）。  
2. 大表 DML 清表仍可能很慢；有权限时优先 DDL 全量或增量。  
3. 当前 Web **无登录认证**，勿暴露公网。  
4. JumpServer 临时账号过期需重新获取 Token。  

## 常见问题

**同步进度只看见一张表？**  
表级本身并行；进度文案为 `并行×N: #i/n db.t → …`。单表仍是先清后插。

**大表清完写入报 wait_timeout（如 last packet … 9150719 ms）？**  
典型是清表数小时时**另一侧连接空闲**被踢（旧版曾在清表时仍握着源连接）。v1.2.8 起清表不占源连接并用池重借；v1.2.9 清表过程中再周期性重借目标连接。请部署新版本后对失败表「重新同步」。外键错误另查 `FOREIGN_KEY_CHECKS` 是否生效或锁等待。

**打包跳过测试编译失败？**

```bash
mvn "-Dmaven.test.skip=true" package
```

## 开发与测试

**约定（强制）：** 新增/变更接口须同步补单测；每次代码改动后执行全量回归 `mvn test`（见 `.cursor/rules/regression-testing.mdc`）。

```bash
mvn test
mvn "-Dmaven.test.skip=true" package   # 仅打包时可跳过测试编译
```

### 6000 万行大表压测

见 [`scripts/loadtest/README.md`](scripts/loadtest/README.md)：灌数工具 + `FULL_DDL` 同步，用于验证 DataX 流式写入与超大表心跳。

### Maven / IDEA「Non-resolvable parent POM」超时

**不是代码错误。** 内网往往访问不了 `repo.maven.apache.org`（截图里 `connect timed out`）。  
若仍看到 Central 地址，说明 **IDEA 没用上本仓库的镜像配置**。

本仓库已提供：

- `.mvn/settings.xml` — 阿里云镜像 + 本地仓 `D:/zkj_mvn_repo`
- `.mvn/maven.config` — 命令行 `mvn` 自动带上上述 settings

**IntelliJ（必做）：**  
Settings → Build, Execution, Deployment → Build Tools → Maven  

1. **User settings file** → 勾选 Override → 选 `d:\db-compare\.mvn\settings.xml`  
2. **Local repository** → `D:\zkj_mvn_repo`（与 settings 一致）  
3. 点 **Reload All Maven Projects**

**内网完全无外网时（推荐）：**

1. 在外网机器先完整构建一次（依赖会进 `D:\zkj_mvn_repo`）  
2. 把整个 `D:\zkj_mvn_repo` 拷到内网同路径  
3. 内网执行：`mvn -o "-Dmaven.test.skip=true" package`（`-o` = 离线，不再访问远端）

若报 `present, but unavailable`：删掉本地残缺缓存后再 Reload：

`D:\zkj_mvn_repo\org\springframework\boot\spring-boot-starter-parent\2.2.13.RELEASE\`

有公司 Nexus 时：改 `.mvn/settings.xml` 里 `<mirror><url>` 为内网私服地址即可。

更多版本流程：[`docs/VERSIONING.md`](docs/VERSIONING.md)。

## 许可证

MIT License

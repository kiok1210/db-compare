# 应用库增量 DDL

应用库（H2 `./data/dbcompare`）的表结构变更通过 **classpath 增量脚本** 升级，不依赖拷贝数据文件。

## 为什么不用拷贝 data/

| 文件 | 内容 | 同步到内网？ |
|------|------|-------------|
| `data/dbcompare.mv.db` | 比对/同步任务、参数、AppMeta | **不建议**覆盖（会丢任务历史）；结构靠 `db/schema` 增量 |
| `data/datasource.mv.db` | **数据源配置（独立库）** | **禁止覆盖**（否则冲掉内网数据源） |

正确做法：内网只同步程序（jar / 代码），启动时自动执行尚未应用的 `Vn` 脚本；数据源库本地保留。  
首次从旧版升级时，若独立库为空且任务库仍有 `dbcompare_datasource` 表，启动会**按原 ID 自动迁入**独立库。

## 脚本位置与命名

| 项 | 约定 |
|----|------|
| 目录 | `src/main/resources/db/schema/`（打进 jar） |
| 文件名 | `V{n}__简短描述.sql`，`n` = `schemaVersion` |
| 示例 | `V6__current_table_min_len.sql` |

## 启动行为

1. 读取 `AppMeta.SCHEMA_VERSION`（无则 0）
2. 对照 `version-changelog.json` 的目标 `schemaVersion`
3. 按序执行 `old < n <= target` 的脚本；**每成功一个**回写版本号
4. 失败则中止后续脚本，不把版本标高

实现类：`com.gcoll.dbcompare.config.SchemaScriptMigrator`（由 `AppVersionService` 调用）。

## 发版检查清单

有表结构变更时：

1. 新增 `db/schema/V{n}__*.sql`
2. 抬高 `version-changelog.json` 的 `schemaVersion`
3. 在本版本 `dbChanges` 中写明变更
4. 同步 `pom.xml` / `app-version.js` / `README` 版本号（见 [VERSIONING.md](VERSIONING.md)）

## 与数据源脚本的区别

| | 应用库结构 | 数据源配置 |
|--|-----------|-----------|
| 路径 | `classpath:db/schema/` | `scripts/datasource/` |
| 用途 | DDL | 数据源行数据 MERGE/DELETE |
| 内网 | 随程序自动执行 | 按需用 **incremental**，勿盲目执行 full |

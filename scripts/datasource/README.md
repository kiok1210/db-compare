# 数据源 SQL 脚本

| 路径 | 说明 |
|------|------|
| `full/dbcompare_datasource_full.sql` | 当前全量快照（MERGE），启动与每次变更后刷新 |
| `incremental/*.sql` | 每次增/改/删一条增量脚本 |
| `CHANGELOG.md` | 变更索引 |

恢复到目标 H2 应用库时，使用与本环境相同的 `db-compare.encryption.key`，然后执行对应 SQL。

**内网同步：** 不要拷贝外网 `data/`（会覆盖内网数据源）。应用库表结构用 classpath 增量 DDL（见 `docs/SCHEMA_MIGRATION.md`）；数据源变更优先只同步/执行 `incremental/`，慎用 `full/`。

下载接口：`GET /datasource/export.sql`

# 版本更新说明

统一版本源：`src/main/resources/version-changelog.json`

## Git：每次版本改动单独 commit

**规则：一次版本号（或 schemaVersion）变更 = 一个 git commit。**

- 推荐 message：`release: vX.Y.Z <简要 why>`  
- 禁止多个版本挤在同一 commit  
- 文档-only、无版本号变更可用 `docs:` / `fix:` 等常规前缀，不强制升版本；一旦改了版本号文件则必须单独发版 commit  

示例：

```bash
# 改齐版本三件套 + changelog 后
git add pom.xml src/main/resources/version-changelog.json \
        src/main/resources/static/js/app-version.js README.md
git commit -m "$(cat <<'EOF'
release: v1.2.8 shorten sync reconnect path

EOF
)"
```

（Windows PowerShell 可用：`git commit -m "release: v1.2.8 …"`。）

## 发版时请同步修改

1. **`version-changelog.json`**
   - 提升 `version` / `frontendVersion` / `backendVersion`（通常三者相同）
   - 若有库表字段变更：提升 `schemaVersion`，新增 `src/main/resources/db/schema/V{n}__*.sql`，并在本版本 `dbChanges` 中写明（详见 [SCHEMA_MIGRATION.md](SCHEMA_MIGRATION.md)）
   - **禁止**把外网 `data/datasource*` 拷到内网（会覆盖数据源）；任务库 `data/dbcompare*` 一般也不要覆盖。内网只同步程序，启动自动跑增量 DDL，并在需要时把旧表数据源迁到独立库
   - 在 `releases` 数组**顶部**新增一条记录（含 `changes`、`dbChanges`、`date`）

2. **`pom.xml`** 的 `<version>` 与上述对齐

3. **`static/js/app-version.js`** 中的 `FRONTEND_VERSION` 与上述对齐

4. **`README.md`** 顶部「当前版本」与发版摘要（如有用户可见行为变化）

## 运行时行为

- 导航栏显示可点击版本号 `vX.Y.Z`，点击查看全部更新记录
- 若本次启动 `schemaVersion` 大于库中记录，顶部弹出**数据库结构已更新**提示
- 接口：`GET /api/version`、`GET /api/version/changelog`

## 测试与回归（强制）

1. **新接口 / 行为变更**：同步增加或更新 `src/test/java` 单测（优先 MockMvc / Mockito，不连真实业务库）。  
2. **每次改动后**：执行 `mvn test` 全量回归；禁止用跳过测试代替验证。  
3. Cursor Agent 遵循 `.cursor/rules/regression-testing.mdc`。  

```bash
mvn test
# 仅打包时可：mvn "-Dmaven.test.skip=true" package
```

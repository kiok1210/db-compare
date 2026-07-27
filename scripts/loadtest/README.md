# 6000 万行大表压测

验证 **DataX 流式写入**（估算 ≥10 万行走读→通道→写）与看门狗在超大表下的表现。

## 本地已装便携 MySQL（推荐先用）

本机已通过阿里云镜像下载并启动便携 MySQL 8.0.28（双库，无需 Docker）：

| 角色 | JDBC | 账号 |
|------|------|------|
| 源 | `jdbc:mysql://127.0.0.1:3306/loadtest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` | sync / sync123 |
| 目标 | `jdbc:mysql://127.0.0.1:3306/loadtest_tgt?...` | sync / sync123 |

数据目录：`D:\db-compare\tools\mysql-8.0.28`。重启 mysqld：

```powershell
Start-Process "D:\db-compare\tools\mysql-8.0.28\bin\mysqld.exe" -ArgumentList "--defaults-file=D:\db-compare\tools\mysql-8.0.28\my.ini","--console" -WindowStyle Hidden
```

## 本地 Docker（双实例 3307/3308）

**GoldenDB 没有公开 Docker 镜像**，本机用 **双 MySQL 8** 做协议兼容压测。Docker Desktop 安装包已下载到 `tools\downloads\DockerDesktopInstaller.exe`。

## 前置

1. 源库 / 目标库为 **MySQL**（或内网真实 GoldenDB）
2. 在应用「数据源管理」注册源、目标，记下 ID
3. 打包并启动应用：`mvn -DskipTests package` 后运行 jar

## 1. 建表

源、目标各执行：

```bash
mysql -hHOST -uUSER -p < scripts/loadtest/01_ddl.sql
```

或仅源库生成、目标空表同结构。

## 2. 灌数（源库）

### 方式 A：Java 生成器（推荐，可断点续跑）

```bash
mvn -q -DskipTests exec:java -Dexec.classpathScope=test ^
  -Dexec.mainClass=com.gcoll.dbcompare.loadtest.BigTableLoadGenerator ^
  -Dexec.args="jdbc:mysql://HOST:3306/loadtest USER PASS 60000000 2000 big_sync_60m"
```

GoldenDB 将 URL 换成对应 JDBC（若驱动为 MySQL 协议，多数可用 `jdbc:mysql://...`）。

- 默认目标 **60_000_000** 行；可先用 `1000000` / `10000000` 做阶梯验证
- 已有数据时从 `COUNT+1` 续写

### 方式 B：SQL 存储过程

```bash
mysql -hHOST -uUSER -p loadtest < scripts/loadtest/02_generate_60m.sql
```

（GoldenDB 若不支持该过程写法，请用方式 A。）

## 3. 同步参数建议

| 项 | 建议 |
|----|------|
| 模式 | `FULL_DDL`（避免 6000 万行 DELETE） |
| parallel | **1**（单表并行无效，反而抢连接） |
| batchSize | 20000–50000 |
| GoldenDB | 表级并行保持 ≤6（全局其它任务） |

也可 `POST /settings/api` 调整默认值。

## 4. 发起同步

UI：同步配置 → 勾选 `loadtest.big_sync_60m` → 模式 FULL_DDL → 执行。

或：

```bash
mvn -q -DskipTests exec:java -Dexec.classpathScope=test ^
  -Dexec.mainClass=com.gcoll.dbcompare.loadtest.StartBigTableSync ^
  -Dexec.args="http://localhost:8080 SRC_DS_ID TGT_DS_ID loadtest::big_sync_60m FULL_DDL 50000"
```

`includeTables` 格式与界面一致：`schema::table`。

## 5. 观察通过标准

- 进度日志出现 **`【DataX流式】读→通道→写`**
- 持续有 **`【心跳】仍在运行 · 已写入 …`**，不被「无心跳」跳过
- 结束后目标 `SELECT COUNT(*) FROM big_sync_60m` = 60000000
- 报告成功；耗时主要在写入（DDL 清表应很快）

## 阶梯建议

`10万 → 100万 → 1000万 → 6000万`，同一套模式与批次，便于对比吞吐与稳定性。

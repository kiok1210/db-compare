# DataX 同步：customer_payments / gcoll_tasks_func
#
# 这两张表是生产信创同步中的超大表，适合用官方 DataX 做 TRUNCATE + 多通道 INSERT。
# 本目录提供可渲染的作业模板；db-compare 1.2.63+ 也可在界面选「DataX 工具同步」自动生成并调用。
#
# ## 前置
# 1. 安装 [DataX](https://github.com/alibaba/DataX)（解压后有 `bin/datax.py`）
# 2. Python 2.7/3.x 可用；`python` 在 PATH 中
# 3. 目标库账号具备 `TRUNCATE` + `INSERT`（或改模板 preSql 为 DELETE）
# 4. 确认主键列名：模板默认 `splitPk=id`，若不是请改模板
#
# ## 手工跑两张表
#
# ```powershell
# cd D:\db-compare\scripts\datax
# copy connections.example.properties connections.properties
# # 编辑 connections.properties 填入 JDBC / 账号 / DATAX_HOME
#
# .\render-and-run.ps1 -Table customer_payments
# .\render-and-run.ps1 -Table gcoll_tasks_func
# # 或一次跑完：
# .\render-and-run.ps1 -All
# ```
#
# 生成的 JSON 在 `out/`（含明文密码，勿提交 Git）。
#
# ## 表映射
#
# | 源 | 目标 |
# |----|------|
# | `gcoll_aux.customer_payments` | `prd_gcoll_aux.customer_payments` |
# | `gcoll_aux.gcoll_tasks_func` | `prd_gcoll_aux.gcoll_tasks_func` |
#
# JDBC URL 里的库名已对应上述 schema；若你的库名不同，改 `connections.properties` 的 SRC_JDBC / TGT_JDBC。
#
# ## 调优建议
#
# - `CHANNEL`：8～16，过大易打满目标库
# - writer `batchSize`：1024～4096
# - **无 TRUNCATE**：界面选 **DataX DELETE**（先 COUNT 目标算 preSql 批次数、COUNT 源算写入进度；DataX 全量拷源）
# - **无 TRUNCATE 权限且 DELETE 太慢（覆盖写入）**：`CLEAR_MODE=NONE` + `WRITE_MODE=replace`
# - 界面同步：参数设置 → DataX 清表策略 / 写入模式
# - 目标侧临时关闭非必要 binlog / 调大 `innodb_buffer_pool`（DBA 评估）
#
# ## 界面调用（db-compare）
#
# 参数设置填写 **DataX 安装目录**、**清表策略**（含 DataX DELETE/TRUNCATE）、**写入模式**，
# 同步模式选 **DataX 工具同步**，选中表后开始同步即可。

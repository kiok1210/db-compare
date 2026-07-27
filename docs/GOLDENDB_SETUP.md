# GoldenDB 数据库配置指南

## 概述
GoldenDB 是中兴通讯开发的分布式关系型数据库，兼容 MySQL 协议。本工具支持 GoldenDB 作为数据源进行比对。

## 1. 驱动安装

### 方式一：从官方获取驱动
1. 联系 GoldenDB 官方获取 JDBC 驱动包（通常是 `goldendb-jdbc.jar`）
2. 将驱动包放到项目的 `libs/` 目录下
3. Maven 依赖已配置为 `system` 范围，会自动加载

### 方式二：安装到本地 Maven 仓库
```bash
mvn install:install-file \
  -Dfile=libs/goldendb-jdbc.jar \
  -DgroupId=com.goldendb \
  -DartifactId=goldendb-jdbc \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```

然后修改 `pom.xml`，将 `scope` 从 `system` 改为 `compile`。

## 2. 连接配置

### 在 Web 界面配置
1. 进入 "数据源管理" -> "添加数据源"
2. 选择数据库类型：**GoldenDB**
3. 填写连接信息：
   - **JDBC URL**: `jdbc:goldendb://host:3306/database`
   - **用户名**: 数据库用户名
   - **密码**: 数据库密码
   - **Schema**: 数据库名（可选）

### 示例配置
```yaml
name: GoldenDB生产库
dbType: GOLDENDB
url: jdbc:goldendb://192.168.1.100:3306/mydb
username: root
password: 123456
schema: mydb
```

## 3.  GoldenDB 特性支持

### 支持的 SQL 语法
GoldenDB 兼容 MySQL 5.7 语法，支持：
- ✅ 标准 SQL-92
- ✅ MySQL 扩展语法
- ✅ 分页查询（LIMIT/OFFSET）
- ✅ 事务支持

### 数据类型映射
| GoldenDB 类型 | 标准类型 |
|---------------|---------|
| INT           | INT     |
| BIGINT        | BIGINT  |
| VARCHAR       | VARCHAR |
| DECIMAL       | DECIMAL |
| DATETIME      | TIMESTAMP |

## 4. 常见问题

### Q1: 连接超时？
**A**: 检查防火墙设置，确保端口 3306 可访问。

### Q2: 驱动类找不到？
**A**: 确保 `goldendb-jdbc.jar` 已放到 `libs/` 目录，并且 `pom.xml` 中配置了正确的 `systemPath`。

### Q3: 比对速度慢？
**A**: GoldenDB 是分布式数据库，建议：
- 适当调整 `batchSize`（建议 500-1000）
- 使用 `SAMPLE` 模式抽样比对
- 增加并行线程数

## 5. 性能优化建议

1. **索引优化**：确保比对的主键和连接条件有索引
2. **网络优化**：应用服务器尽量靠近数据库服务器
3. **批量提交**：适当调整批次大小
4. **增量比对**：对于大表，使用增量比对模式

## 6. 技术支持

如有 GoldenDB 相关问题，请联系：
- GoldenDB 官方技术支持
- 或在本项目提交 Issue

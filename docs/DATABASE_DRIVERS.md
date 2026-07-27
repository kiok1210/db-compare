# 数据库驱动依赖说明

## 支持的数据库

本项目支持以下数据库：

| 数据库 | 驱动名称 | Maven 依赖 | 默认端口 |
|--------|---------|-----------|---------|
| **MySQL** | mysql-connector-java | ✅ 已配置 | 3306 |
| **PostgreSQL** | postgresql | ✅ 已配置 | 5432 |
| **Oracle** | ojdbc8 | ⚠️ 需手动安装 | 1521 |
| **GoldenDB** | goldendb-jdbc | ⚠️ 需手动安装 | 3306 |
| **SQL Server** | mssql-jdbc | ❌ 未配置 | 1433 |

---

## 1. MySQL（已配置）

### Maven 依赖
```xml
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <version>8.0.33</version>
    <scope>runtime</scope>
</dependency>
```

### 连接示例
```
JDBC URL: jdbc:mysql://localhost:3306/mydb
驱动类: com.mysql.cj.jdbc.Driver
```

---

## 2. PostgreSQL（已配置）

### Maven 依赖
```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.0</version>
    <scope>runtime</scope>
</dependency>
```

### 连接示例
```
JDBC URL: jdbc:postgresql://localhost:5432/mydb
驱动类: org.postgresql.Driver
```

---

## 3. Oracle（需手动安装）

### 方式一：从 Oracle 官网下载
1. 访问 [Oracle JDBC Downloads](https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html)
2. 下载 `ojdbc8.jar`（对应 Oracle 12c/19c）
3. 安装到本地 Maven 仓库：

```bash
mvn install:install-file \
  -Dfile=ojdbc8.jar \
  -DgroupId=com.oracle.database.jdbc \
  -DartifactId=ojdbc8 \
  -Dversion=21.9.0.0 \
  -Dpackaging=jar
```

### 方式二：使用 Maven Central（推荐）
Oracle 已将驱动发布到 Maven Central，可直接使用：

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc8</artifactId>
    <version>21.9.0.0</version>
    <scope>runtime</scope>
</dependency>
```

### 连接示例
```
JDBC URL: jdbc:oracle:thin:@localhost:1521:ORCL
驱动类: oracle.jdbc.OracleDriver
```

---

## 4. GoldenDB（需手动安装）

### 安装步骤
1. 联系 GoldenDB 官方获取 JDBC 驱动包（`goldendb-jdbc.jar`）
2. 将驱动包放到项目的 `libs/` 目录下
3. 确认 `pom.xml` 中已配置：

```xml
<dependency>
    <groupId>com.goldendb</groupId>
    <artifactId>goldendb-jdbc</artifactId>
    <version>1.0.0</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/libs/goldendb-jdbc.jar</systemPath>
</dependency>
```

### 连接示例
```
JDBC URL: jdbc:goldendb://localhost:3306/mydb
驱动类: com.goldendb.jdbc.Driver
```

---

## 5. SQL Server（可选配置）

### Maven 依赖
```xml
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
    <version>12.4.2.jre8</version>
    <scope>runtime</scope>
</dependency>
```

### 连接示例
```
JDBC URL: jdbc:sqlserver://localhost:1433;databaseName=mydb
驱动类: com.microsoft.sqlserver.jdbc.SQLServerDriver
```

---

## 驱动下载汇总

| 数据库 | 下载地址 |
|--------|---------|
| MySQL | https://dev.mysql.com/downloads/connector/j/ |
| PostgreSQL | https://jdbc.postgresql.org/download/ |
| Oracle | https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html |
| GoldenDB | 联系 GoldenDB 官方 |
| SQL Server | https://docs.microsoft.com/zh-cn/sql/connect/jdbc/ |

---

## 常见问题

### Q1: 驱动类找不到（ClassNotFoundException）？
**A**: 
1. 检查 `pom.xml` 依赖是否正确
2. 执行 `mvn clean package` 重新打包
3. 对于 `system` 范围的依赖，确保 `systemPath` 路径正确

### Q2: Oracle 驱动 Maven 下载失败？
**A**: Oracle 驱动需要认证，建议手动下载后安装到本地仓库。

### Q3: GoldenDB 驱动哪里获取？
**A**: 联系 GoldenDB 官方技术支持或销售人员获取驱动包。

---

## 验证驱动安装

运行以下命令验证驱动是否正确安装：

```bash
# 编译项目
mvn clean compile

# 如果没有错误，说明驱动配置正确
```

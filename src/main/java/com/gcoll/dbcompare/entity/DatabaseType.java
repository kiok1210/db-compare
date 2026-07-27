package com.gcoll.dbcompare.entity;

/**
 * 支持的数据库类型枚举
 */
public enum DatabaseType {

    MYSQL("MySQL", "com.mysql.cj.jdbc.Driver"),
    POSTGRESQL("PostgreSQL", "org.postgresql.Driver"),
    ORACLE("Oracle", "oracle.jdbc.driver.OracleDriver"),
    GOLDENDB("GoldenDB", "com.mysql.cj.jdbc.Driver"),
    SQLSERVER("SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    H2("H2", "org.h2.Driver"),
    SQLITE("SQLite", "org.sqlite.JDBC");

    private final String displayName;
    private final String driverClass;

    DatabaseType(String displayName, String driverClass) {
        this.displayName = displayName;
        this.driverClass = driverClass;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDriverClass() {
        return driverClass;
    }

    /**
     * 根据名称字符串查找数据库类型（忽略大小写）
     */
    public static DatabaseType fromString(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        for (DatabaseType type : values()) {
            if (type.name().equalsIgnoreCase(name) || type.displayName.equalsIgnoreCase(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的数据库类型: " + name);
    }
}

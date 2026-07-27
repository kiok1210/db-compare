package com.gcoll.dbcompare.util;

import com.gcoll.dbcompare.entity.DatabaseType;

import java.sql.SQLException;

/**
 * JDBC 连接辅助：URL 规范化、错误信息友好化
 */
public final class JdbcConnectionHelper {

    /** socketTimeout=0：禁用读超时，避免大表 DELETE/INSERT 长时间无包被驱动掐断 */
    private static final String MYSQL_DEFAULT_PARAMS =
            "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                    + "&connectTimeout=15000&socketTimeout=0"
                    + "&rewriteBatchedStatements=true&useServerPrepStmts=false"
                    + "&autoReconnect=true&maxReconnects=3";

    private JdbcConnectionHelper() {
    }

    /**
     * 规范化 JDBC URL（GoldenDB 转 MySQL 协议，补全常用连接参数）
     */
    public static String normalizeJdbcUrl(String url, DatabaseType dbType) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }
        String normalized = url.trim();
        if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
            if (normalized.regionMatches(true, 0, "jdbc:goldendb:", 0, "jdbc:goldendb:".length())) {
                normalized = "jdbc:mysql:" + normalized.substring("jdbc:goldendb:".length());
            }
            normalized = appendMySqlParamsIfMissing(normalized);
        }
        return normalized;
    }

    /**
     * 解析最终 JDBC URL：规范化 + 若 URL 未带库名则用 Schema 字段补全（JumpServer 等场景）
     */
    public static String resolveJdbcUrl(String url, String schema, DatabaseType dbType) {
        String normalized = normalizeJdbcUrl(url, dbType);
        if (dbType != DatabaseType.MYSQL && dbType != DatabaseType.GOLDENDB) {
            return normalized;
        }
        if (schema == null || schema.trim().isEmpty()) {
            return normalized;
        }
        String dbName = schema.split(",")[0].trim();
        if (dbName.isEmpty() || hasDatabaseInMysqlUrl(normalized)) {
            return normalized;
        }
        return forceDatabaseInMysqlUrl(normalized, dbName);
    }

    /**
     * 强制把 MySQL/GoldenDB JDBC URL 的库名设为指定 catalog（覆盖已有路径库名）。
     * 用于 DataX：连接串常无默认库或仅 host:port?params，否则报 No database selected。
     */
    public static String forceDatabaseInMysqlUrl(String url, String dbName) {
        if (url == null || dbName == null || dbName.trim().isEmpty()) {
            return url;
        }
        String db = dbName.trim();
        int qIdx = url.indexOf('?');
        String base = qIdx >= 0 ? url.substring(0, qIdx) : url;
        String query = qIdx >= 0 ? url.substring(qIdx) : "";
        int hostStart = base.indexOf("//");
        if (hostStart < 0) {
            return url;
        }
        int pathStart = base.indexOf('/', hostStart + 2);
        if (pathStart < 0) {
            // jdbc:mysql://host:3306?x → jdbc:mysql://host:3306/db?x
            return base + "/" + db + query;
        }
        // jdbc:mysql://host:3306/ 或 /other_db → /db
        return base.substring(0, pathStart + 1) + db + query;
    }

    private static boolean hasDatabaseInMysqlUrl(String url) {
        if (!url.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length())) {
            return true;
        }
        int hostStart = url.indexOf("//");
        if (hostStart < 0) {
            return true;
        }
        String afterAuthority = url.substring(hostStart + 2);
        int slash = afterAuthority.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String pathAndQuery = afterAuthority.substring(slash + 1);
        if (pathAndQuery.isEmpty()) {
            return false;
        }
        int q = pathAndQuery.indexOf('?');
        String dbPart = q >= 0 ? pathAndQuery.substring(0, q) : pathAndQuery;
        return !dbPart.isEmpty();
    }

    private static String appendMySqlParamsIfMissing(String url) {
        if (!url.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length())) {
            return url;
        }
        if (url.indexOf('?') < 0) {
            return url + "?" + MYSQL_DEFAULT_PARAMS;
        }
        StringBuilder sb = new StringBuilder(url);
        appendParamIfMissing(sb, "useSSL", "false");
        appendParamIfMissing(sb, "allowPublicKeyRetrieval", "true");
        appendParamIfMissing(sb, "serverTimezone", "Asia/Shanghai");
        appendParamIfMissing(sb, "connectTimeout", "15000");
        appendParamIfMissing(sb, "socketTimeout", "0");
        appendParamIfMissing(sb, "rewriteBatchedStatements", "true");
        appendParamIfMissing(sb, "useServerPrepStmts", "false");
        appendParamIfMissing(sb, "autoReconnect", "true");
        appendParamIfMissing(sb, "maxReconnects", "3");
        return sb.toString();
    }

    private static void appendParamIfMissing(StringBuilder url, String key, String value) {
        String current = url.toString();
        if (current.toLowerCase().contains(key.toLowerCase() + "=")) {
            return;
        }
        url.append(current.indexOf('?') >= 0 ? '&' : '?').append(key).append('=').append(value);
    }

    /**
     * 覆盖或追加 JDBC URL 查询参数（同步池等场景用于关闭 rewriteBatchedStatements）。
     */
    public static String overrideUrlParam(String url, String key, String value) {
        if (url == null || key == null || value == null) {
            return url;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url + "?" + key + "=" + value;
        }
        String base = url.substring(0, q + 1);
        String query = url.substring(q + 1);
        String[] parts = query.split("&");
        StringBuilder sb = new StringBuilder(base);
        boolean replaced = false;
        boolean first = true;
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            String k = eq >= 0 ? p.substring(0, eq) : p;
            if (!first) {
                sb.append('&');
            }
            if (k.equalsIgnoreCase(key)) {
                sb.append(key).append('=').append(value);
                replaced = true;
            } else {
                sb.append(p);
            }
            first = false;
        }
        if (!replaced) {
            if (!first) {
                sb.append('&');
            }
            sb.append(key).append('=').append(value);
        }
        return sb.toString();
    }

    /**
     * 将 SQLException 转为用户可读提示
     */
    public static String toUserMessage(Throwable e) {
        if (e == null) {
            return "数据库连接失败";
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return root.getClass().getSimpleName() + "：连接失败，请检查配置";
        }
        String lower = msg.toLowerCase();
        if (lower.contains("communications link failure")
                || lower.contains("connection refused")
                || lower.contains("could not create connection")
                || lower.contains("attempted reconnect")
                || lower.contains("failed to initialize pool")) {
            return "无法连接到数据库服务器（已多次重试仍失败）。请检查：主机/端口是否可达、防火墙、"
                    + "数据库是否启动；若经 JumpServer，请确认隧道/临时账号仍有效，并在数据源页「测试连接」";
        }
        if (lower.contains("access denied")) {
            return "用户名或密码错误，Token 可能已过期（JumpServer 临时账号需重新获取），或该账号无访问权限";
        }
        if (lower.contains("unknown database")) {
            return "数据库不存在，请检查 URL 中的库名是否正确";
        }
        if (lower.contains("unknown host") || lower.contains("nodename nor servname")) {
            return "无法解析主机名，请检查 URL 中的 host 是否正确";
        }
        if (lower.contains("malformed") || lower.contains("unable to parse") || lower.contains("invalid url")) {
            return "JDBC URL 格式不正确，MySQL/GoldenDB 请使用 jdbc:mysql://主机:3306/库名";
        }
        if (lower.contains("driver") && lower.contains("not found")) {
            return "数据库驱动未找到，请确认 MySQL 驱动已加入项目依赖";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "连接超时，请检查网络或增大 connectTimeout 参数";
        }
        // 去掉换行，截断过长消息
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return msg.length() > 240 ? msg.substring(0, 240) + "..." : msg;
    }

    public static String toUserMessage(SQLException e) {
        return toUserMessage((Throwable) e);
    }
}

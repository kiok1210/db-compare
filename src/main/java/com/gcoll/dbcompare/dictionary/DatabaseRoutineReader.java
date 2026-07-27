package com.gcoll.dbcompare.dictionary;

import com.gcoll.dbcompare.entity.DatabaseType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库函数与存储过程读取器
 * 适配 MySQL、PostgreSQL、Oracle、SQLServer、GoldenDB、H2、SQLite
 */
@Slf4j
public class DatabaseRoutineReader {

    /**
     * 读取所有函数
     */
    public static List<RoutineMeta> readFunctions(Connection conn, String schema, DatabaseType dbType) throws Exception {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return readMysqlRoutines(conn, schema, "FUNCTION");
            case POSTGRESQL:
                return readPgRoutines(conn, schema, "FUNCTION");
            case ORACLE:
                return readOracleRoutines(conn, schema, "FUNCTION");
            case SQLSERVER:
                return readSqlServerRoutines(conn, schema, "FN", "IF", "TF");
            case H2:
                return readH2Routines(conn, schema);
            case SQLITE:
                return new ArrayList<>();
            default:
                return new ArrayList<>();
        }
    }

    /**
     * 读取所有存储过程
     */
    public static List<RoutineMeta> readProcedures(Connection conn, String schema, DatabaseType dbType) throws Exception {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return readMysqlRoutines(conn, schema, "PROCEDURE");
            case POSTGRESQL:
                return readPgRoutines(conn, schema, "PROCEDURE");
            case ORACLE:
                return readOracleRoutines(conn, schema, "PROCEDURE");
            case SQLSERVER:
                return readSqlServerRoutines(conn, schema, "P");
            case H2:
            case SQLITE:
                return new ArrayList<>();
            default:
                return new ArrayList<>();
        }
    }

    /**
     * MySQL/GoldenDB: 通过 SHOW FUNCTION/PROCEDURE STATUS
     */
    private static List<RoutineMeta> readMysqlRoutines(Connection conn, String schema, String type) throws Exception {
        String sql = "SHOW " + type + " STATUS WHERE Db = '" + schema + "'";
        List<RoutineMeta> routines = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                RoutineMeta m = new RoutineMeta();
                m.setName(rs.getString("Name"));
                m.setType(type);
                m.setSchema(schema);
                // 尝试获取创建时间等信息
                try { m.setCreated(rs.getTimestamp("Created")); } catch (Exception ignored) {}
                try { m.setModified(rs.getTimestamp("Modified")); } catch (Exception ignored) {}
                routines.add(m);
            }
        }
        return routines;
    }

    /**
     * PostgreSQL: information_schema.routines
     */
    private static List<RoutineMeta> readPgRoutines(Connection conn, String schema, String type) throws Exception {
        String sql = "SELECT routine_name, routine_type, specific_name, created, last_altered " +
                     "FROM information_schema.routines " +
                     "WHERE routine_schema = '" + (schema != null ? schema : "public") + "' " +
                     "AND routine_type = '" + type + "' " +
                     "ORDER BY routine_name";
        List<RoutineMeta> routines = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                RoutineMeta m = new RoutineMeta();
                m.setName(rs.getString("routine_name"));
                m.setType(type);
                m.setSchema(schema != null ? schema : "public");
                try { m.setCreated(rs.getTimestamp("created")); } catch (Exception ignored) {}
                try { m.setModified(rs.getTimestamp("last_altered")); } catch (Exception ignored) {}
                routines.add(m);
            }
        }
        return routines;
    }

    /**
     * Oracle: all_procedures
     */
    private static List<RoutineMeta> readOracleRoutines(Connection conn, String schema, String type) throws Exception {
        String oracleType = "FUNCTION".equals(type) ? "FUNCTION" : "PROCEDURE";
        String sql = "SELECT object_name, object_type, created, last_ddl_time " +
                     "FROM all_objects " +
                     "WHERE owner = '" + (schema != null ? schema.toUpperCase() : "") + "' " +
                     "AND object_type = '" + oracleType + "' " +
                     "ORDER BY object_name";
        List<RoutineMeta> routines = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                RoutineMeta m = new RoutineMeta();
                m.setName(rs.getString("object_name"));
                m.setType(type);
                m.setSchema(schema);
                try { m.setCreated(rs.getTimestamp("created")); } catch (Exception ignored) {}
                try { m.setModified(rs.getTimestamp("last_ddl_time")); } catch (Exception ignored) {}
                routines.add(m);
            }
        }
        return routines;
    }

    /**
     * SQL Server: sys.objects
     */
    private static List<RoutineMeta> readSqlServerRoutines(Connection conn, String schema, String... typeFilters) throws Exception {
        StringBuilder typeList = new StringBuilder();
        for (int i = 0; i < typeFilters.length; i++) {
            if (i > 0) typeList.append(", ");
            typeList.append("'").append(typeFilters[i]).append("'");
        }
        String sql = "SELECT o.name, o.type, o.create_date, o.modify_date, s.name AS schema_name " +
                     "FROM sys.objects o " +
                     "JOIN sys.schemas s ON o.schema_id = s.schema_id " +
                     "WHERE s.name = '" + (schema != null ? schema : "dbo") + "' " +
                     "AND o.type IN (" + typeList + ") " +
                     "ORDER BY o.name";
        List<RoutineMeta> routines = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                RoutineMeta m = new RoutineMeta();
                m.setName(rs.getString("name"));
                m.setType(rs.getString("type"));
                m.setSchema(rs.getString("schema_name"));
                try { m.setCreated(rs.getTimestamp("create_date")); } catch (Exception ignored) {}
                try { m.setModified(rs.getTimestamp("modify_date")); } catch (Exception ignored) {}
                routines.add(m);
            }
        }
        return routines;
    }

    /**
     * H2: 通过 information_schema 读取别名（H2 没有存储过程概念，只有 FUNCTION 别名）
     */
    private static List<RoutineMeta> readH2Routines(Connection conn, String schema) throws Exception {
        String sql = "SELECT ALIAS_NAME FROM INFORMATION_SCHEMA.FUNCTION_ALIASES " +
                     "WHERE ALIAS_SCHEMA = 'PUBLIC' ORDER BY ALIAS_NAME";
        List<RoutineMeta> routines = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                RoutineMeta m = new RoutineMeta();
                m.setName(rs.getString("ALIAS_NAME"));
                m.setType("FUNCTION");
                m.setSchema("PUBLIC");
                routines.add(m);
            }
        }
        return routines;
    }

    /**
     * 比对两个数据库的函数/存储过程列表
     */
    public static RoutineCompareResult compareRoutines(
            List<RoutineMeta> sourceRoutines, List<RoutineMeta> targetRoutines, String routineType) {
        RoutineCompareResult result = new RoutineCompareResult();
        result.setRoutineType(routineType);
        result.setSourceCount(sourceRoutines.size());
        result.setTargetCount(targetRoutines.size());

        List<String> sourceNames = new ArrayList<>();
        List<String> targetNames = new ArrayList<>();
        for (RoutineMeta m : sourceRoutines) sourceNames.add(m.getName().toLowerCase());
        for (RoutineMeta m : targetRoutines) targetNames.add(m.getName().toLowerCase());

        List<String> onlyInSource = new ArrayList<>(sourceNames);
        onlyInSource.removeAll(targetNames);

        List<String> onlyInTarget = new ArrayList<>(targetNames);
        onlyInTarget.removeAll(sourceNames);

        sourceNames.retainAll(targetNames);

        result.setOnlyInSource(onlyInSource);
        result.setOnlyInTarget(onlyInTarget);
        result.setCommonCount(sourceNames.size());
        result.setConsistent(onlyInSource.isEmpty() && onlyInTarget.isEmpty());

        return result;
    }

    @Data
    public static class RoutineMeta {
        private String name;
        private String type;
        private String schema;
        private java.sql.Timestamp created;
        private java.sql.Timestamp modified;
    }

    @Data
    public static class RoutineCompareResult {
        private String routineType;
        private int sourceCount;
        private int targetCount;
        private int commonCount;
        private List<String> onlyInSource = new ArrayList<>();
        private List<String> onlyInTarget = new ArrayList<>();
        private boolean consistent;
    }
}

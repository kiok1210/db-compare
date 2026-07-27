package com.gcoll.dbcompare.diag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 本地 H2 库只读诊断：列出数据源与指定同步/比对任务配置。
 * <pre>
 * mvn -q -DskipTests exec:java -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.gcoll.dbcompare.diag.LocalStoreQueryMain
 * </pre>
 * 请在项目根目录执行（依赖 {@code ./data/dbcompare}）。
 */
public final class LocalStoreQueryMain {

    private LocalStoreQueryMain() {
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:./data/dbcompare;AUTO_SERVER=TRUE", "sa", "")) {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT ID,NAME,USERNAME FROM DBCOMPARE_DATASOURCE ORDER BY ID")) {
                while (rs.next()) {
                    System.out.println("DS " + rs.getLong(1) + " | " + rs.getString(2)
                            + " | user=" + rs.getString(3));
                }
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT ID,SOURCE_DATA_SOURCE_ID,TARGET_DATA_SOURCE_ID,SOURCE_SCHEMA,"
                                 + "TARGET_SCHEMA,SCHEMA_MAPPING FROM DBCOMPARE_SYNC_TASK WHERE ID=102")) {
                while (rs.next()) {
                    System.out.println("TASK102 srcDs=" + rs.getLong(2) + " tgtDs=" + rs.getLong(3)
                            + " srcSch=" + rs.getString(4) + " tgtSch=" + rs.getString(5)
                            + " map=" + rs.getString(6));
                }
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT ID,SOURCE_DATA_SOURCE_ID,TARGET_DATA_SOURCE_ID,SOURCE_SCHEMA,"
                                 + "TARGET_SCHEMA,SCHEMA_MAPPING FROM DBCOMPARE_TASK"
                                 + " WHERE TASK_NAME LIKE '%gcoll%' ORDER BY ID DESC")) {
                while (rs.next()) {
                    System.out.println("CMP " + rs.getLong(1) + " srcDs=" + rs.getLong(2)
                            + " tgtDs=" + rs.getLong(3) + " srcSch=" + rs.getString(4)
                            + " tgtSch=" + rs.getString(5) + " map=" + rs.getString(6));
                }
            }
        }
    }
}

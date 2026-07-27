package com.gcoll.dbcompare.loadtest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Locale;

/**
 * 生成压测大表（默认 6000 万行）。MySQL / GoldenDB（MySQL 协议）可用。
 * <pre>
 * mvn -q -DskipTests exec:java -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.gcoll.dbcompare.loadtest.BigTableLoadGenerator \
 *   -Dexec.args="jdbc:mysql://HOST:3306/loadtest user pass 60000000"
 * </pre>
 * 参数：jdbcUrl user password [rows=60000000] [batch=2000] [table=big_sync_60m]
 */
public final class BigTableLoadGenerator {

    private BigTableLoadGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: jdbcUrl user password [rows] [batch] [table]");
            System.err.println("示例: jdbc:mysql://127.0.0.1:3306/loadtest root pwd 60000000 2000 big_sync_60m");
            System.exit(2);
        }
        String url = args[0];
        String user = args[1];
        String pass = args[2];
        long targetRows = args.length > 3 ? Long.parseLong(args[3]) : 60_000_000L;
        int batch = args.length > 4 ? Integer.parseInt(args[4]) : 2000;
        String table = args.length > 5 ? args[5] : "big_sync_60m";
        batch = Math.max(100, Math.min(batch, 5000));

        Class.forName("com.mysql.cj.jdbc.Driver");
        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setAutoCommit(false);
            ensureTable(conn, table);
            long existing = countRows(conn, table);
            long nextId = existing + 1;
            System.out.printf(Locale.ROOT, "表 %s 已有 %,d 行，目标 %,d，batch=%d%n",
                    table, existing, targetRows, batch);
            if (existing >= targetRows) {
                System.out.println("已达目标行数，跳过写入。");
                return;
            }

            String sql = buildMultiInsert(table, batch);
            long written = existing;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                while (written < targetRows) {
                    int n = (int) Math.min(batch, targetRows - written);
                    if (n < batch) {
                        // 尾批用单行循环，避免改 SQL
                        insertTail(conn, table, nextId, n);
                        written += n;
                        nextId += n;
                        conn.commit();
                        break;
                    }
                    Timestamp now = new Timestamp(System.currentTimeMillis());
                    int p = 1;
                    for (int i = 0; i < batch; i++) {
                        long id = nextId + i;
                        ps.setLong(p++, id);
                        ps.setString(p++, "r" + id);
                        ps.setInt(p++, (int) (id % 1000));
                        ps.setDouble(p++, (id % 10000) / 100.0);
                        ps.setTimestamp(p++, now);
                    }
                    ps.executeUpdate();
                    conn.commit();
                    nextId += batch;
                    written += batch;
                    if (written % 1_000_000L < batch || written >= targetRows) {
                        long sec = Math.max(1, (System.currentTimeMillis() - start) / 1000);
                        System.out.printf(Locale.ROOT, "进度 %,d / %,d (%.1f%%) 用时 %ds 约 %,d 行/秒%n",
                                written, targetRows, written * 100.0 / targetRows, sec, written / sec);
                    }
                }
            }
            conn.commit();
            long cnt = countRows(conn, table);
            long sec = Math.max(1, (System.currentTimeMillis() - start) / 1000);
            System.out.printf(Locale.ROOT, "完成：COUNT=%d，耗时 %ds，约 %,d 行/秒%n",
                    cnt, sec, (cnt - existing) / sec);
        }
    }

    private static void ensureTable(Connection conn, String table) throws Exception {
        String ddl = "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "id BIGINT NOT NULL,"
                + "c1 VARCHAR(64) NOT NULL,"
                + "c2 INT NOT NULL,"
                + "c3 DECIMAL(18,2) NOT NULL,"
                + "created_at DATETIME NOT NULL,"
                + "PRIMARY KEY (id)"
                + ") ENGINE=InnoDB";
        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
        }
        conn.commit();
    }

    private static long countRows(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String buildMultiInsert(String table, int batch) {
        StringBuilder sb = new StringBuilder(64 + batch * 16);
        sb.append("INSERT INTO `").append(table).append("` (id,c1,c2,c3,created_at) VALUES ");
        for (int i = 0; i < batch; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("(?,?,?,?,?)");
        }
        return sb.toString();
    }

    private static void insertTail(Connection conn, String table, long startId, int n) throws Exception {
        String sql = "INSERT INTO `" + table + "` (id,c1,c2,c3,created_at) VALUES (?,?,?,?,?)";
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < n; i++) {
                long id = startId + i;
                ps.setLong(1, id);
                ps.setString(2, "r" + id);
                ps.setInt(3, (int) (id % 1000));
                ps.setDouble(4, (id % 10000) / 100.0);
                ps.setTimestamp(5, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}

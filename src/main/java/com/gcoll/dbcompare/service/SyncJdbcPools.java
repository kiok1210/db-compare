package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同步/比对任务级 JDBC 连接池（源、目标各一个 Hikari）。
 * <p>
 * 规则：
 * <ul>
 *   <li>一整次任务只建一对池，表与表之间借还复用，禁止每表 DriverManager 新建</li>
 *   <li>正常归还用 {@link #release}（{@code Connection.close()} → 回池）</li>
 *   <li>协议损坏才 {@link #evict}，避免把坏连接还回池</li>
 *   <li>任务结束（成功/失败/取消）必须 {@link #close()} 关掉两个池</li>
 * </ul>
 */
public final class SyncJdbcPools implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SyncJdbcPools.class);

    private final HikariDataSource sourcePool;
    private final HikariDataSource targetPool;
    private final DataSourceConfig sourceConfig;
    private final DataSourceConfig targetConfig;
    private final int poolSize;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    SyncJdbcPools(HikariDataSource sourcePool, HikariDataSource targetPool,
                  DataSourceConfig sourceConfig, DataSourceConfig targetConfig, int poolSize) {
        this.sourcePool = sourcePool;
        this.targetPool = targetPool;
        this.sourceConfig = sourceConfig;
        this.targetConfig = targetConfig;
        this.poolSize = poolSize;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public DataSourceConfig getSourceConfig() {
        return sourceConfig;
    }

    public DataSourceConfig getTargetConfig() {
        return targetConfig;
    }

    public DatabaseType getSourceDbType() {
        return sourceConfig.getDbType();
    }

    public DatabaseType getTargetDbType() {
        return targetConfig.getDbType();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 从源池借连接并设置 catalog / 超时会话参数（调用方用完必须 {@link #release} 归还）。
     */
    public Connection borrowSource(String schema, boolean multiCatalog) throws SQLException {
        ensureOpen();
        Connection conn = sourcePool.getConnection();
        prepareBorrowed(conn, schema, multiCatalog, sourceConfig.getDbType(), false);
        return conn;
    }

    /**
     * 从目标池借连接；targetHints=true 时开启清表/写入加速会话参数。
     */
    public Connection borrowTarget(String schema, boolean multiCatalog, boolean targetHints) throws SQLException {
        ensureOpen();
        Connection conn = targetPool.getConnection();
        prepareBorrowed(conn, schema, multiCatalog, targetConfig.getDbType(), targetHints);
        return conn;
    }

    private void ensureOpen() throws SQLException {
        if (closed.get() || sourcePool.isClosed() || targetPool.isClosed()) {
            throw new SQLException("同步连接池已关闭，无法再借连接");
        }
    }

    private void prepareBorrowed(Connection conn, String schema, boolean multiCatalog,
                                 DatabaseType dbType, boolean targetHints) throws SQLException {
        if (!multiCatalog && schema != null && !schema.trim().isEmpty()) {
            String cat = schema.trim().split(",")[0].trim();
            if (!cat.isEmpty()) {
                try {
                    conn.setCatalog(cat);
                } catch (SQLException e) {
                    log.debug("setCatalog({}) 失败，继续: {}", cat, e.getMessage());
                }
            }
        }
        extendSessionTimeouts(conn, dbType);
        if (targetHints) {
            applySpeedHints(conn, dbType);
        }
    }

    private static void extendSessionTimeouts(Connection conn, DatabaseType dbType) {
        if (dbType != DatabaseType.MYSQL && dbType != DatabaseType.GOLDENDB) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET SESSION wait_timeout=28800");
            stmt.execute("SET SESSION interactive_timeout=28800");
            stmt.execute("SET SESSION net_read_timeout=3600");
            stmt.execute("SET SESSION net_write_timeout=3600");
            try {
                stmt.execute("SET SESSION sql_select_limit = DEFAULT");
            } catch (SQLException e1) {
                try {
                    stmt.execute("SET SESSION sql_select_limit = 18446744073709551615");
                } catch (SQLException e2) {
                    log.warn("无法解除 sql_select_limit（可能导致大表同步被截断到 10 万行）: {}", e2.getMessage());
                }
            }
        } catch (SQLException e) {
            log.debug("延长会话超时失败(可忽略): {}", e.getMessage());
        }
    }

    private static void applySpeedHints(Connection conn, DatabaseType dbType) {
        if (dbType != DatabaseType.MYSQL && dbType != DatabaseType.GOLDENDB) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=0");
            stmt.execute("SET UNIQUE_CHECKS=0");
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT @@SESSION.FOREIGN_KEY_CHECKS")) {
                if (rs.next() && rs.getInt(1) != 0) {
                    log.warn("FOREIGN_KEY_CHECKS 未能关闭（当前={}），清表可能被外键阻塞", rs.getInt(1));
                }
            } catch (SQLException ignored) {
            }
        } catch (SQLException e) {
            String m = e.getMessage();
            if (m == null || m.isEmpty()
                    || m.toLowerCase().contains("closed")
                    || m.toLowerCase().contains("connection is closed")) {
                log.debug("会话加速参数跳过: {}", e.getClass().getSimpleName());
            } else {
                log.warn("会话加速参数设置失败: {}", m);
            }
        }
    }

    /** 归还连接到 Hikari 池（不要 abort，否则会销毁物理连接并迫使池新建）。 */
    public static void release(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            if (!conn.isClosed()) {
                conn.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 剔除源池坏连接（协议损坏时用）；正常结束请用 {@link #release}。 */
    public void evictSource(Connection conn) {
        evict(sourcePool, conn, "source");
    }

    /** 剔除目标池坏连接。 */
    public void evictTarget(Connection conn) {
        evict(targetPool, conn, "target");
    }

    private static void evict(HikariDataSource pool, Connection conn, String name) {
        if (conn == null) {
            return;
        }
        try {
            if (pool != null && !pool.isClosed()) {
                pool.evictConnection(conn);
                return;
            }
        } catch (Exception e) {
            log.debug("evict {} 失败，改为 abort/close: {}", name, e.getMessage());
        }
        try {
            conn.abort(Runnable::run);
        } catch (Throwable t) {
            release(conn);
        }
    }

    public String describeStats() {
        return "source[active=" + safeActive(sourcePool) + ",idle=" + safeIdle(sourcePool)
                + ",max=" + safeMax(sourcePool) + "] target[active=" + safeActive(targetPool)
                + ",idle=" + safeIdle(targetPool) + ",max=" + safeMax(targetPool) + "]";
    }

    private static int safeActive(HikariDataSource ds) {
        try {
            return ds == null || ds.isClosed() ? -1 : ds.getHikariPoolMXBean().getActiveConnections();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int safeIdle(HikariDataSource ds) {
        try {
            return ds == null || ds.isClosed() ? -1 : ds.getHikariPoolMXBean().getIdleConnections();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int safeMax(HikariDataSource ds) {
        try {
            return ds == null || ds.isClosed() ? -1 : ds.getMaximumPoolSize();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("关闭同步连接池（任务结束）: {}", describeStats());
        closePool(sourcePool, "source");
        closePool(targetPool, "target");
    }

    private void closePool(HikariDataSource ds, String name) {
        if (ds == null || ds.isClosed()) {
            return;
        }
        try {
            ds.close();
            log.info("同步连接池已关闭: {} (configuredMax≈{})", name, poolSize);
        } catch (Exception e) {
            log.warn("关闭同步连接池失败: {} — {}", name, e.getMessage());
        }
    }
}

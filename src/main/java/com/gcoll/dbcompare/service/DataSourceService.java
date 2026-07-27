package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.ds.DatasourceConfigRepository;
import com.gcoll.dbcompare.util.CredentialEncryptor;
import com.gcoll.dbcompare.util.JdbcConnectionHelper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/**
 * 数据源管理服务（持久化在独立库 ./data/datasource）。
 */
@Service
@Slf4j
@Transactional(transactionManager = "datasourceTransactionManager")
public class DataSourceService {

    @Autowired
    private DatasourceConfigRepository DatasourceConfigRepository;

    @Autowired
    private CredentialEncryptor credentialEncryptor;

    @Autowired
    private DataSourceScriptExporter dataSourceScriptExporter;

    public List<DataSourceConfig> getAllDataSources() {
        return DatasourceConfigRepository.findAll();
    }

    public DataSourceConfig getDataSourceById(Long id) {
        return DatasourceConfigRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("数据源不存在: " + id));
    }

    public DataSourceConfig addDataSource(DataSourceConfig config) {
        if (DatasourceConfigRepository.findByName(config.getName()) != null) {
            throw new RuntimeException("数据源名称已存在: " + config.getName());
        }
        prepareConfig(config);
        config.setPassword(credentialEncryptor.encrypt(config.getPassword()));
        DataSourceConfig saved = DatasourceConfigRepository.save(config);
        dataSourceScriptExporter.recordChange(DataSourceScriptExporter.ChangeOp.INSERT, saved, null, null);
        return saved;
    }

    public DataSourceConfig updateDataSource(Long id, DataSourceConfig config) {
        DataSourceConfig existing = getDataSourceById(id);
        existing.setName(config.getName());
        existing.setDbType(config.getDbType());
        existing.setUrl(JdbcConnectionHelper.normalizeJdbcUrl(config.getUrl(), config.getDbType()));
        existing.setUsername(config.getUsername());
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            existing.setPassword(credentialEncryptor.encrypt(config.getPassword()));
        }
        existing.setSchema(config.getSchema());
        existing.setDescription(config.getDescription());
        existing.setEnabled(config.getEnabled());
        if (existing.getDriverClass() == null || existing.getDriverClass().isEmpty()) {
            existing.setDriverClass(config.getDbType().getDriverClass());
        }
        DataSourceConfig saved = DatasourceConfigRepository.save(existing);
        dataSourceScriptExporter.recordChange(DataSourceScriptExporter.ChangeOp.UPDATE, saved, null, null);
        return saved;
    }

    public void deleteDataSource(Long id) {
        DataSourceConfig existing = getDataSourceById(id);
        String name = existing.getName();
        DatasourceConfigRepository.deleteById(id);
        dataSourceScriptExporter.recordChange(DataSourceScriptExporter.ChangeOp.DELETE, null, id, name);
        log.info("删除数据源: {}", id);
    }

    /** 导出全量 SQL（并刷新磁盘上的 full 快照） */
    public String exportDataSourceSql() throws java.io.IOException {
        return dataSourceScriptExporter.exportFullAndPersist();
    }

    public ConnectionTestResult testConnection(Long id) {
        return testConnection(getDataSourceById(id));
    }

    public ConnectionTestResult testConnection(DataSourceConfig config) {
        if (config.getDbType() == null) {
            return ConnectionTestResult.fail("请选择数据库类型");
        }
        if (config.getUrl() == null || config.getUrl().trim().isEmpty()) {
            return ConnectionTestResult.fail("请填写 JDBC URL");
        }
        if (config.getUsername() == null || config.getUsername().trim().isEmpty()) {
            return ConnectionTestResult.fail("请填写用户名");
        }

        String driverClass = resolveDriverClass(config);
        String jdbcUrl = JdbcConnectionHelper.resolveJdbcUrl(config.getUrl(), config.getSchema(), config.getDbType());
        String password = resolvePassword(config);

        Connection conn = null;
        try {
            Class.forName(driverClass);
            conn = DriverManager.getConnection(jdbcUrl, config.getUsername(), password);
            applyDefaultCatalog(conn, config);
            log.info("数据源连接成功: {} -> {}", config.getName() != null ? config.getName() : jdbcUrl, jdbcUrl);
            return ConnectionTestResult.ok();
        } catch (ClassNotFoundException e) {
            log.error("数据库驱动加载失败: {}", driverClass, e);
            return ConnectionTestResult.fail("驱动加载失败: " + driverClass);
        } catch (SQLException e) {
            log.error("数据源连接失败: url={}", jdbcUrl, e);
            return ConnectionTestResult.fail(JdbcConnectionHelper.toUserMessage(e));
        } catch (Exception e) {
            log.error("数据源连接失败", e);
            return ConnectionTestResult.fail(JdbcConnectionHelper.toUserMessage(e));
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.warn("关闭连接失败", e);
                }
            }
        }
    }

    public DatabaseType[] getSupportedDatabaseTypes() {
        return DatabaseType.values();
    }

    public Connection createConnection(DataSourceConfig config) throws SQLException {
        try {
            String password = resolvePassword(config);
            String jdbcUrl = JdbcConnectionHelper.resolveJdbcUrl(config.getUrl(), config.getSchema(), config.getDbType());
            Class.forName(config.getDbType().getDriverClass());
            Connection conn = DriverManager.getConnection(jdbcUrl, config.getUsername(), password);
            applyDefaultCatalog(conn, config);
            log.debug("数据库连接成功: {} ({})", config.getName(), config.getDbType().getDisplayName());
            return conn;
        } catch (ClassNotFoundException e) {
            log.error("数据库驱动加载失败: {}", config.getDbType().getDriverClass(), e);
            throw new SQLException("驱动加载失败: " + config.getDbType().getDriverClass(), e);
        }
    }

    /**
     * 为一次同步任务创建源/目标 Hikari 池。
     * maxPoolSize 建议等于表级并行度（每表同时最多占用源 1 + 目标 1）。
     * 建池前先用 DriverManager 探测，避免 Hikari 初始化失败时只抛出难懂的 pool 异常。
     */
    public SyncJdbcPools createSyncPools(DataSourceConfig source, DataSourceConfig target, int tableParallel)
            throws SQLException {
        return createSyncPools(source, target, tableParallel, false);
    }

    /**
     * @param needExtraTargetConn 数据同步等场景每表需额外一条目标读连接（归并比对）
     */
    public SyncJdbcPools createSyncPools(DataSourceConfig source, DataSourceConfig target, int tableParallel,
                                         boolean needExtraTargetConn)
            throws SQLException {
        probeForSync(source, "源");
        probeForSync(target, "目标");

        int size = Math.max(1, Math.min(SyncTask.MAX_PARALLEL_THREADS, tableParallel));
        // SQLite 写并发弱，目标池缩到 1，源可读并行适当放开
        // DATA_SYNC 等每表需额外目标读连接：目标池 ≈ 2×并行，上限 2×MAX
        int srcSize = size;
        int tgtSize = needExtraTargetConn
                ? Math.min(SyncTask.MAX_PARALLEL_THREADS * 2, Math.max(2, size * 2))
                : size;
        if (source.getDbType() == DatabaseType.SQLITE) {
            srcSize = Math.min(2, size);
        }
        if (target.getDbType() == DatabaseType.SQLITE) {
            tgtSize = 1;
        }
        HikariDataSource srcPool = null;
        HikariDataSource tgtPool = null;
        try {
            srcPool = buildHikariPool(source, srcSize, "sync-src-" + source.getId());
            tgtPool = buildHikariPool(target, tgtSize, "sync-tgt-" + target.getId());
        } catch (Exception e) {
            if (srcPool != null && !srcPool.isClosed()) {
                try { srcPool.close(); } catch (Exception ignored) { }
            }
            if (tgtPool != null && !tgtPool.isClosed()) {
                try { tgtPool.close(); } catch (Exception ignored) { }
            }
            throw new SQLException(wrapPoolInitError(e), e);
        }
        log.info("已创建同步连接池: source(max={}) target(max={}) parallel={} extraTgtRead={}",
                srcSize, tgtSize, size, needExtraTargetConn);
        return new SyncJdbcPools(srcPool, tgtPool, source, target, size);
    }

    /** 同步前探测：失败时带上数据源名称，便于进度页直接展示 */
    private void probeForSync(DataSourceConfig config, String role) throws SQLException {
        String label = config.getName() != null ? config.getName() : ("#" + config.getId());
        try (Connection ignored = createConnection(config)) {
            // ok
        } catch (SQLException e) {
            throw new SQLException(role + "数据源「" + label + "」连接失败: "
                    + JdbcConnectionHelper.toUserMessage(e), e);
        }
    }

    private static String wrapPoolInitError(Throwable e) {
        return "创建同步连接池失败: " + JdbcConnectionHelper.toUserMessage(e);
    }

    private HikariDataSource buildHikariPool(DataSourceConfig config, int maxPoolSize, String poolName)
            throws SQLException {
        try {
            Class.forName(resolveDriverClass(config));
        } catch (ClassNotFoundException e) {
            throw new SQLException("驱动加载失败: " + resolveDriverClass(config), e);
        }
        String jdbcUrl = JdbcConnectionHelper.resolveJdbcUrl(config.getUrl(), config.getSchema(), config.getDbType());
        // 同步写入必须开启 rewriteBatchedStatements，否则 addBatch 会逐条发送，吞吐常跌到数百行/秒。
        // GoldenDB 高并发协议异常仍由 SyncTaskRunner 丢弃坏连接 + 缩小批次重试兜底。
        if (config.getDbType() == DatabaseType.MYSQL || config.getDbType() == DatabaseType.GOLDENDB) {
            jdbcUrl = JdbcConnectionHelper.overrideUrlParam(jdbcUrl, "rewriteBatchedStatements", "true");
            jdbcUrl = JdbcConnectionHelper.overrideUrlParam(jdbcUrl, "useServerPrepStmts", "false");
        }
        HikariConfig hc = new HikariConfig();
        hc.setPoolName(poolName);
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(config.getUsername() != null ? config.getUsername() : "");
        hc.setPassword(resolvePassword(config));
        hc.setDriverClassName(resolveDriverClass(config));
        hc.setMaximumPoolSize(maxPoolSize);
        // 预热少量空闲连接，减少首批借出握手；仍远小于「每表新建」
        hc.setMinimumIdle(Math.min(2, Math.max(1, maxPoolSize)));
        hc.setInitializationFailTimeout(1);
        // 短超时 + 上层带心跳重试，避免等池 30s 无 touch 被看门狗误杀
        hc.setConnectionTimeout(10_000L);
        hc.setValidationTimeout(5_000L);
        hc.setIdleTimeout(600_000L);
        hc.setMaxLifetime(3_600_000L);
        hc.setAutoCommit(true);
        // 借出前校验，避免复用已被 wait_timeout 踢掉的死连接
        if (config.getDbType() == DatabaseType.MYSQL || config.getDbType() == DatabaseType.GOLDENDB
                || config.getDbType() == DatabaseType.H2 || config.getDbType() == DatabaseType.SQLITE) {
            hc.setConnectionTestQuery("SELECT 1");
        } else if (config.getDbType() == DatabaseType.ORACLE) {
            hc.setConnectionTestQuery("SELECT 1 FROM DUAL");
        } else {
            hc.setConnectionTestQuery("SELECT 1");
        }
        return new HikariDataSource(hc);
    }

    private void prepareConfig(DataSourceConfig config) {
        if (config.getDriverClass() == null || config.getDriverClass().isEmpty()) {
            config.setDriverClass(config.getDbType().getDriverClass());
        }
        config.setUrl(JdbcConnectionHelper.normalizeJdbcUrl(config.getUrl(), config.getDbType()));
    }

    private String resolveDriverClass(DataSourceConfig config) {
        if (config.getDriverClass() != null && !config.getDriverClass().isEmpty()) {
            return config.getDriverClass();
        }
        return config.getDbType().getDriverClass();
    }

    private String resolvePassword(DataSourceConfig config) {
        return credentialEncryptor.decrypt(config.getPassword());
    }

    /** 供 DataX 等外部进程使用：返回明文密码（勿写入日志） */
    public String resolvePasswordPublic(DataSourceConfig config) {
        return resolvePassword(config);
    }

    /** 单库模式：连接后设置默认 catalog（JumpServer URL 无库名时依赖 Schema 字段） */
    private void applyDefaultCatalog(Connection conn, DataSourceConfig config) throws SQLException {
        if (config.getSchema() == null || config.getSchema().trim().isEmpty()) {
            return;
        }
        if (config.getSchema().contains(",")) {
            return;
        }
        String catalog = config.getSchema().trim();
        if (config.getDbType() == DatabaseType.MYSQL || config.getDbType() == DatabaseType.GOLDENDB) {
            conn.setCatalog(catalog);
        }
    }
}

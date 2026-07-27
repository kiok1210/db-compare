package com.gcoll.dbcompare.config;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.ds.DatasourceConfigRepository;
import com.gcoll.dbcompare.entity.DatabaseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 首次启动：若独立库 datasource 为空，而任务库 dbcompare 仍有旧表 dbcompare_datasource，则按原 ID 迁入。
 * 保证比对/同步任务里的 sourceDataSourceId / targetDataSourceId 仍然有效。
 */
@Component
@Order(50)
public class DatasourceStoreMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasourceStoreMigrator.class);

    private final DataSource appDataSource;
    private final DataSource storeDataSource;
    private final DatasourceConfigRepository DatasourceConfigRepository;

    public DatasourceStoreMigrator(DataSource appDataSource,
                                   @Qualifier(DatasourceStoreJpaConfig.DS_BEAN) DataSource storeDataSource,
                                   DatasourceConfigRepository DatasourceConfigRepository) {
        this.appDataSource = appDataSource;
        this.storeDataSource = storeDataSource;
        this.DatasourceConfigRepository = DatasourceConfigRepository;
    }

    @Override
    @Transactional(transactionManager = DatasourceStoreJpaConfig.TM_BEAN)
    public void run(ApplicationArguments args) {
        try {
            if (DatasourceConfigRepository.count() > 0) {
                return;
            }
            String oldTable = resolveExistingTable(appDataSource);
            if (oldTable == null) {
                log.info("独立数据源库为空，任务库无旧表 dbcompare_datasource，跳过迁移");
                return;
            }
            List<DataSourceConfig> rows = loadFromAppDb(oldTable);
            if (rows.isEmpty()) {
                log.info("任务库 {} 无记录，跳过迁移", oldTable);
                return;
            }
            JdbcTemplate storeJdbc = new JdbcTemplate(storeDataSource);
            long maxId = 0;
            for (DataSourceConfig row : rows) {
                insertPreservingId(storeJdbc, row);
                if (row.getId() != null && row.getId() > maxId) {
                    maxId = row.getId();
                }
            }
            if (maxId > 0) {
                try {
                    storeJdbc.execute("ALTER TABLE dbcompare_datasource ALTER COLUMN id RESTART WITH " + (maxId + 1));
                } catch (Exception e) {
                    log.debug("重置 datasource 自增序列失败(可忽略): {}", e.getMessage());
                }
            }
            log.info("已从任务库迁移 {} 条数据源到独立库 ./data/datasource（保留原 ID）", rows.size());
        } catch (Exception e) {
            log.error("数据源库迁移失败（可手工从 scripts/datasource 导入）: {}", e.getMessage(), e);
        }
    }

    private List<DataSourceConfig> loadFromAppDb(String table) {
        JdbcTemplate jdbc = new JdbcTemplate(appDataSource);
        return jdbc.query("SELECT * FROM " + table, (rs, i) -> {
            Map<String, Object> cols = new HashMap<>();
            ResultSetMetaData meta = rs.getMetaData();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                String label = meta.getColumnLabel(c);
                if (label == null || label.isEmpty()) {
                    label = meta.getColumnName(c);
                }
                cols.put(label.toLowerCase(Locale.ROOT), rs.getObject(c));
            }
            DataSourceConfig ds = new DataSourceConfig();
            ds.setId(asLong(cols.get("id")));
            ds.setName(asString(cols.get("name")));
            ds.setDbType(DatabaseType.valueOf(asString(cols.get("db_type"))));
            ds.setUrl(asString(cols.get("url")));
            ds.setUsername(asString(cols.get("username")));
            ds.setPassword(asString(cols.get("password")));
            ds.setDriverClass(asString(cols.get("driver_class")));
            ds.setSchema(asString(cols.get("schema")));
            ds.setDescription(asString(cols.get("description")));
            Object en = cols.get("enabled");
            if (en instanceof Boolean) {
                ds.setEnabled((Boolean) en);
            } else if (en != null) {
                ds.setEnabled(!"0".equals(String.valueOf(en)) && !"false".equalsIgnoreCase(String.valueOf(en)));
            } else {
                ds.setEnabled(true);
            }
            ds.setCreateTime(asDateTime(cols.get("create_time")));
            ds.setUpdateTime(asDateTime(cols.get("update_time")));
            return ds;
        });
    }

    private void insertPreservingId(JdbcTemplate jdbc, DataSourceConfig ds) {
        jdbc.update(
                "INSERT INTO dbcompare_datasource "
                        + "(id, name, db_type, url, username, password, driver_class, schema, description, enabled, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                ds.getId(),
                ds.getName(),
                ds.getDbType() != null ? ds.getDbType().name() : null,
                ds.getUrl(),
                ds.getUsername(),
                ds.getPassword(),
                ds.getDriverClass(),
                ds.getSchema(),
                ds.getDescription(),
                ds.getEnabled() != null ? ds.getEnabled() : true,
                ds.getCreateTime() != null ? Timestamp.valueOf(ds.getCreateTime()) : Timestamp.valueOf(LocalDateTime.now()),
                ds.getUpdateTime() != null ? Timestamp.valueOf(ds.getUpdateTime()) : Timestamp.valueOf(LocalDateTime.now())
        );
    }

    private static String resolveExistingTable(DataSource ds) {
        if (tableExists(ds, "DBCOMPARE_DATASOURCE")) {
            return "DBCOMPARE_DATASOURCE";
        }
        if (tableExists(ds, "dbcompare_datasource")) {
            return "dbcompare_datasource";
        }
        return null;
    }

    private static boolean tableExists(DataSource ds, String tableName) {
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = meta.getTables(null, null, tableName.toUpperCase(Locale.ROOT), new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = meta.getTables(null, null, tableName.toLowerCase(Locale.ROOT), new String[]{"TABLE"})) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static LocalDateTime asDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp) {
            return ((Timestamp) v).toLocalDateTime();
        }
        if (v instanceof LocalDateTime) {
            return (LocalDateTime) v;
        }
        return null;
    }
}

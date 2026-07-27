package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncJdbcPoolsLifecycleTest {

    @Test
    void close_shutsDownBothPools_andIsIdempotent() throws Exception {
        HikariDataSource src = memPool("src");
        HikariDataSource tgt = memPool("tgt");
        DataSourceConfig sc = cfg("s");
        DataSourceConfig tc = cfg("t");
        SyncJdbcPools pools = new SyncJdbcPools(src, tgt, sc, tc, 2);
        try (Connection c = pools.borrowSource(null, true)) {
            assertFalse(c.isClosed());
        }
        pools.close();
        assertTrue(pools.isClosed());
        assertTrue(src.isClosed());
        assertTrue(tgt.isClosed());
        pools.close(); // idempotent
        assertTrue(pools.isClosed());
    }

    @Test
    void release_returnsConnectionToPool() throws Exception {
        HikariDataSource src = memPool("src2");
        HikariDataSource tgt = memPool("tgt2");
        SyncJdbcPools pools = new SyncJdbcPools(src, tgt, cfg("s"), cfg("t"), 1);
        Connection c1 = pools.borrowSource(null, true);
        SyncJdbcPools.release(c1);
        Connection c2 = pools.borrowSource(null, true);
        assertFalse(c2.isClosed());
        SyncJdbcPools.release(c2);
        pools.close();
    }

    private static HikariDataSource memPool(String name) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName(name);
        hc.setJdbcUrl("jdbc:h2:mem:pool_" + name + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        hc.setUsername("sa");
        hc.setPassword("");
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(1);
        return new HikariDataSource(hc);
    }

    private static DataSourceConfig cfg(String name) {
        DataSourceConfig c = new DataSourceConfig();
        c.setName(name);
        c.setDbType(DatabaseType.H2);
        c.setUrl("jdbc:h2:mem:x");
        c.setUsername("sa");
        c.setPassword("");
        return c;
    }
}

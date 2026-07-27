package com.gcoll.dbcompare.service;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncTaskRunnerInsertRetryTest {

    @Test
    void toSafeRetryInsertSql_convertsPlainInsert() {
        String sql = "INSERT INTO `t` (`a`) VALUES (?)";
        assertEquals("INSERT IGNORE INTO `t` (`a`) VALUES (?)",
                SyncTaskRunner.toSafeRetryInsertSql(sql));
    }

    @Test
    void toSafeRetryInsertSql_keepsIgnoreAndUpsert() {
        assertEquals("INSERT IGNORE INTO t VALUES (?)",
                SyncTaskRunner.toSafeRetryInsertSql("INSERT IGNORE INTO t VALUES (?)"));
        String upsert = "INSERT INTO t (a) VALUES (?) ON DUPLICATE KEY UPDATE a=VALUES(a)";
        assertEquals(upsert, SyncTaskRunner.toSafeRetryInsertSql(upsert));
    }

    @Test
    void isDuplicateKeyFailure_detectsMysql1062() {
        SQLException e = new SQLException(
                "Duplicate entry 'x' for key 'PRIMARY'", "23000", 1062);
        assertTrue(SyncTaskRunner.isDuplicateKeyFailure(e));
        assertFalse(SyncTaskRunner.isDuplicateKeyFailure(new SQLException("other", "42000", 1064)));
    }

    @Test
    void isPacketTooBigFailure_detectsMaxAllowedPacket() {
        assertTrue(SyncTaskRunner.isPacketTooBigFailure(
                new SQLException("Packet for query is too large", "08S01", 1153)));
        assertTrue(SyncTaskRunner.isPacketTooBigFailure(
                new SQLException("Got a packet bigger than 'max_allowed_packet' bytes")));
        assertFalse(SyncTaskRunner.isPacketTooBigFailure(new SQLException("Duplicate entry", "23000", 1062)));
    }

    @Test
    void isRetriableJdbcFailure_detectsProtocolNullAndSuppressedNpe() {
        SQLException primary = new SQLException((String) null);
        SQLException suppressed = new SQLException(
                "Error retrieving record: Unexpected Exception: java.lang.NullPointerException message given: null");
        suppressed.initCause(new NullPointerException());
        primary.addSuppressed(suppressed);
        assertTrue(SyncTaskRunner.isRetriableJdbcFailure(primary));
    }
}

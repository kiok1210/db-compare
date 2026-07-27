package com.gcoll.dbcompare.util;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConnectionHelperTest {

    @Test
    void toUserMessage_reconnectGivingUp_mapsToFriendly() {
        SQLException e = new SQLException(
                "Could not create connection to database server. Attempted reconnect 3 times. Giving up.");
        String msg = JdbcConnectionHelper.toUserMessage(e);
        assertTrue(msg.contains("无法连接到数据库服务器"), msg);
        assertTrue(msg.contains("测试连接"), msg);
    }

    @Test
    void toUserMessage_poolInit_mapsToFriendly() {
        RuntimeException e = new RuntimeException(
                "Failed to initialize pool: Could not create connection to database server");
        String msg = JdbcConnectionHelper.toUserMessage(e);
        assertTrue(msg.contains("无法连接到数据库服务器"), msg);
    }

    @Test
    void overrideUrlParam_replacesExisting() {
        String url = "jdbc:mysql://h:3306/db?useSSL=false&rewriteBatchedStatements=true&x=1";
        String out = JdbcConnectionHelper.overrideUrlParam(url, "rewriteBatchedStatements", "false");
        assertTrue(out.contains("rewriteBatchedStatements=false"), out);
        assertTrue(!out.contains("rewriteBatchedStatements=true"), out);
        assertTrue(out.contains("useSSL=false"), out);
    }

    @Test
    void overrideUrlParam_appendsWhenMissing() {
        String url = "jdbc:mysql://h:3306/db?useSSL=false";
        String out = JdbcConnectionHelper.overrideUrlParam(url, "rewriteBatchedStatements", "false");
        assertTrue(out.contains("rewriteBatchedStatements=false"), out);
    }

    @Test
    void overrideUrlParam_canForceRewriteTrue() {
        String url = "jdbc:mysql://h:3306/db?rewriteBatchedStatements=false";
        String out = JdbcConnectionHelper.overrideUrlParam(url, "rewriteBatchedStatements", "true");
        assertTrue(out.contains("rewriteBatchedStatements=true"), out);
        assertTrue(!out.contains("rewriteBatchedStatements=false"), out);
    }

    @Test
    void forceDatabaseInMysqlUrl_insertsBeforeQuery() {
        assertEquals("jdbc:mysql://h:3306/gcoll_aux?useSSL=false",
                JdbcConnectionHelper.forceDatabaseInMysqlUrl(
                        "jdbc:mysql://h:3306?useSSL=false", "gcoll_aux"));
        assertEquals("jdbc:mysql://h:3306/prd_gcoll_aux?x=1",
                JdbcConnectionHelper.forceDatabaseInMysqlUrl(
                        "jdbc:mysql://h:3306/other?x=1", "prd_gcoll_aux"));
    }
}

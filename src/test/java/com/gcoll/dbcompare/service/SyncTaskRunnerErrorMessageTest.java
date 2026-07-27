package com.gcoll.dbcompare.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncTaskRunnerErrorMessageTest {

    @Test
    void toFriendlySyncError_expandsSqlExceptionChain() throws Exception {
        SyncTaskRunner runner = new SyncTaskRunner(null, null, null, null, null, null);
        Method m = SyncTaskRunner.class.getDeclaredMethod("toFriendlySyncError", Throwable.class);
        m.setAccessible(true);

        SQLException root = new SQLException("Duplicate entry '1' for key 'PRIMARY'", "23000", 1062);
        SQLException wrap = new SQLException((String) null);
        wrap.setNextException(root);
        Exception outer = new Exception(wrap);

        String msg = (String) m.invoke(runner, outer);
        assertTrue(msg.contains("Duplicate entry") || msg.contains("主键"), msg);
        assertFalse("SQLException".equals(msg), msg);
    }

    @Test
    void toFriendlySyncError_protocolAioobe_mapsToHint() throws Exception {
        SyncTaskRunner runner = new SyncTaskRunner(null, null, null, null, null, null);
        Method m = SyncTaskRunner.class.getDeclaredMethod("toFriendlySyncError", Throwable.class);
        m.setAccessible(true);

        ArrayIndexOutOfBoundsException aioobe = new ArrayIndexOutOfBoundsException();
        SQLException sql = new SQLException((String) null, "S1000", aioobe);

        String msg = (String) m.invoke(runner, sql);
        assertTrue(msg.contains("协议") || msg.contains("GoldenDB") || msg.contains("并行"), msg);
    }

    @Test
    void isLockWaitTimeout_detectsError1205AndMessage() {
        SQLException byCode = new SQLException("Lock wait timeout exceeded; try restarting transaction",
                "40001", 1205);
        assertTrue(SyncTaskRunner.isLockWaitTimeout(byCode));
        assertTrue(SyncTaskRunner.isRetriableJdbcFailure(byCode)); // 40001 仍可重试

        SQLException byMsg = new SQLException("lock wait timeout exceeded", "HY000", 0);
        assertTrue(SyncTaskRunner.isLockWaitTimeout(byMsg));
        assertFalse(SyncTaskRunner.isLockWaitTimeout(new SQLException("other", "S1000", 0)));
    }

    @Test
    void toFriendlySyncError_lockWait_notMappedAsGoldenDbProtocol() throws Exception {
        SyncTaskRunner runner = new SyncTaskRunner(null, null, null, null, null, null);
        Method m = SyncTaskRunner.class.getDeclaredMethod("toFriendlySyncError", Throwable.class);
        m.setAccessible(true);

        SQLException sql = new SQLException(
                "Lock wait timeout exceeded; try restarting transaction [SQLState=40001] [err=1205]",
                "40001", 1205);
        String msg = (String) m.invoke(runner, sql);
        assertTrue(msg.contains("锁等待") || msg.contains("1205"), msg);
        assertTrue(msg.contains("批次") || msg.contains("事务") || msg.contains("PROCESSLIST"), msg);
        assertFalse(msg.contains("GoldenDB"), msg);
        assertFalse(msg.contains("协议/连接异常"), msg);
    }

    @Test
    void targetClearLockKey_sameTargetSharesKey() {
        assertEquals(
                SyncTaskRunner.targetClearLockKey("prd_gcoll_aux", "customer_payments"),
                SyncTaskRunner.targetClearLockKey("prd_gcoll_aux", "customer_payments"));
        assertFalse(SyncTaskRunner.targetClearLockKey("a", "t1")
                .equals(SyncTaskRunner.targetClearLockKey("a", "t2")));
    }
}

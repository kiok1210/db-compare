package com.gcoll.dbcompare.util;

import com.gcoll.dbcompare.entity.CompareTask;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableFilterHelperTest {

    @Test
    void filterTables_noRules_returnsAll() {
        CompareTask task = new CompareTask();
        List<String> tables = Arrays.asList("user_info", "order_info", "temp_log");

        List<String> result = TableFilterHelper.filterTables(tables, task);

        assertEquals(3, result.size());
    }

    @Test
    void filterTables_includeExactNames() {
        CompareTask task = new CompareTask();
        task.setIncludeTables("user_info, order_info");

        List<String> result = TableFilterHelper.filterTables(
            Arrays.asList("user_info", "order_info", "product_info"), task);

        assertEquals(Arrays.asList("user_info", "order_info"), result);
    }

    @Test
    void filterTables_includeWildcard() {
        CompareTask task = new CompareTask();
        task.setIncludeTables("user_%");

        List<String> result = TableFilterHelper.filterTables(
            Arrays.asList("user_info", "user_role", "order_info"), task);

        assertEquals(Arrays.asList("user_info", "user_role"), result);
    }

    @Test
    void filterTables_excludeWildcard() {
        CompareTask task = new CompareTask();
        task.setExcludeTables("temp_%, log_%");

        List<String> result = TableFilterHelper.filterTables(
            Arrays.asList("user_info", "temp_data", "log_audit"), task);

        assertEquals(Arrays.asList("user_info"), result);
    }

    @Test
    void splitPatterns_supportsMultipleDelimiters() {
        List<String> patterns = TableFilterHelper.splitPatterns("a, b;c  d");

        assertEquals(Arrays.asList("a", "b", "c", "d"), patterns);
    }

    @Test
    void matchesAny_caseInsensitive() {
        assertTrue(TableFilterHelper.matchesAny("User_Info", Arrays.asList("user_info")));
        assertFalse(TableFilterHelper.matchesAny("order", Arrays.asList("user_%")));
    }

    @Test
    void filterTables_questionMarkPattern_doesNotThrow() {
        // 历史 bug：Future 失败时表名被写成 "?"，再 include 会触发 PatternSyntaxException
        List<String> result = TableFilterHelper.filterTables(
                Arrays.asList("PUBLIC::USERS", "SHOP::DEVICE_TOKENS"),
                "?", null);
        assertTrue(result.isEmpty());
    }

    @Test
    void filterTables_schemaQualifiedExactWithUnderscore() {
        List<String> result = TableFilterHelper.filterTables(
                Arrays.asList("SHOP::DEVICE_TOKENS", "SHOP::PAYMENTS", "PUBLIC::USERS"),
                "SHOP::DEVICE_TOKENS,PUBLIC::USERS", null);
        assertEquals(Arrays.asList("SHOP::DEVICE_TOKENS", "PUBLIC::USERS"), result);
    }

    @Test
    void filterTables_schemaQualifiedInclude_matchesBareNameInSingleSchema() {
        // 一键重同步收窄为单库后，枚举返回裸表名，include 仍是 schema::table
        List<String> result = TableFilterHelper.filterTables(
                Arrays.asList("customer_payments", "gcoll_tasks_func", "other_tbl"),
                "gcoll_aux::customer_payments,gcoll_aux::gcoll_tasks_func", null);
        assertEquals(Arrays.asList("customer_payments", "gcoll_tasks_func"), result);
    }

    @Test
    void filterTables_dotQualifiedInclude_normalizesAndMatches() {
        List<String> result = TableFilterHelper.filterTables(
                Arrays.asList("gcoll_aux::customer_payments"),
                "gcoll_aux.customer_payments", null);
        assertEquals(Arrays.asList("gcoll_aux::customer_payments"), result);
    }

    @Test
    void matchesAny_schemaQualifiedAgainstBare() {
        assertTrue(TableFilterHelper.matchesAny("customer_payments",
                Arrays.asList("gcoll_aux::customer_payments")));
        assertTrue(TableFilterHelper.matchesAny("gcoll_aux::customer_payments",
                Arrays.asList("customer_payments")));
    }

    @Test
    void sqlWildcardToRegex_escapesMetaCharacters() {
        assertEquals("\\?", TableFilterHelper.sqlWildcardToRegex("?"));
        assertEquals("a.*b", TableFilterHelper.sqlWildcardToRegex("a%b"));
        assertEquals("a.b", TableFilterHelper.sqlWildcardToRegex("a_b"));
    }
}
